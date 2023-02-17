package org.tessellation.currency.l1

import cats.Applicative
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.semigroupk._

import scala.concurrent.duration._

import org.tessellation.BuildInfo
import org.tessellation.currency.l1.modules.{CurrencyPrograms, CurrencyStorages}
import org.tessellation.dag.domain.block.CurrencyBlock
import org.tessellation.dag.l1._
import org.tessellation.dag.l1.cli.method.{Run, RunInitialValidator, RunValidator}
import org.tessellation.dag.l1.domain.snapshot.programs.CurrencySnapshotProcessor
import org.tessellation.dag.l1.http.p2p.P2PClient
import org.tessellation.dag.l1.infrastructure.block.rumor.handler.blockRumorHandler
import org.tessellation.dag.l1.modules._
import org.tessellation.dag.snapshot.CurrencySnapshot
import org.tessellation.dag.{DagSharedKryoRegistrationIdRange, dagSharedKryoRegistrar}
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.kryo.{KryoRegistrationId, MapRegistrationId}
import org.tessellation.schema.address.Address
import org.tessellation.schema.cluster.ClusterId
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.node.NodeState.SessionStarted
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.CurrencyTransaction
import org.tessellation.sdk.app.{SDK, TessellationIOApp}
import org.tessellation.sdk.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import org.tessellation.sdk.resources.MkHttpServer
import org.tessellation.sdk.resources.MkHttpServer.ServerName

import com.monovore.decline.Opts
import eu.timepit.refined.boolean.Or
import fs2.Stream

abstract class L1CurrencyIOApp(symbol: String, clusterId: ClusterId, identifier: Address)
    extends TessellationIOApp[Run](
      s"$symbol-currency-l1",
      s"$symbol L1 node",
      clusterId,
      version = BuildInfo.version
    ) {

  val opts: Opts[Run] = cli.method.opts

  type KryoRegistrationIdRange = DagL1KryoRegistrationIdRange Or DagSharedKryoRegistrationIdRange Or CurrencyL1KryoRegistrationIdRange

  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]] =
    dagL1KryoRegistrar.union(dagSharedKryoRegistrar).union(currencyL1KryoRegistrar)

  def run(method: Run, sdk: SDK[IO]): Resource[IO, Unit] = {
    import sdk._

    val cfg = method.appConfig

    for {
      queues <- Queues.make[IO, CurrencyTransaction, CurrencyBlock](sdkQueues).asResource
      currencyStorages <- CurrencyStorages
        .make[IO, CurrencyTransaction, CurrencyBlock, CurrencySnapshot](sdkStorages, method.l0Peer, method.l0Peer)
        .asResource // TODO: 3rd param needs to be global L0 peer
      validators = Validators.make[IO, CurrencyTransaction, CurrencyBlock, CurrencySnapshot](currencyStorages, seedlist)
      p2pClient = P2PClient.make[IO, CurrencyTransaction, CurrencyBlock](sdkP2PClient, sdkResources.client)
      services = Services
        .make[IO, CurrencyTransaction, CurrencyBlock, CurrencySnapshot](
          currencyStorages,
          currencyStorages.lastGlobalSnapshot,
          currencyStorages.globalL0Cluster,
          validators,
          sdkServices,
          p2pClient,
          cfg
        )
      snapshotProcessor = CurrencySnapshotProcessor.make(
        identifier,
        currencyStorages.address,
        currencyStorages.block,
        currencyStorages.lastGlobalSnapshot,
        currencyStorages.lastSnapshot,
        currencyStorages.transaction
      )
      programs = CurrencyPrograms.make(sdkPrograms, p2pClient, currencyStorages, snapshotProcessor)
      healthChecks <- HealthChecks
        .make[IO, CurrencyTransaction, CurrencyBlock, CurrencySnapshot](
          currencyStorages,
          services,
          programs,
          p2pClient,
          sdkResources.client,
          sdkServices.session,
          cfg.healthCheck,
          sdk.nodeId
        )
        .asResource

      rumorHandler = RumorHandlers.make[IO](currencyStorages.cluster, healthChecks.ping, services.localHealthcheck).handlers <+>
        blockRumorHandler[IO, CurrencyBlock](queues.peerBlock)

      _ <- Daemons
        .start(currencyStorages, services, healthChecks)
        .asResource

      api = HttpApi
        .make[IO, CurrencyTransaction, CurrencyBlock, CurrencySnapshot](
          currencyStorages,
          queues,
          keyPair.getPrivate,
          services,
          programs,
          healthChecks,
          sdk.nodeId,
          BuildInfo.version,
          cfg.http
        )
      _ <- MkHttpServer[IO].newEmber(ServerName("public"), cfg.http.publicHttp, api.publicApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), cfg.http.p2pHttp, api.p2pApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("cli"), cfg.http.cliHttp, api.cliApp)

      stateChannel <- StateChannel
        .make[IO, CurrencyTransaction, CurrencyBlock, CurrencySnapshot](
          cfg,
          keyPair,
          p2pClient,
          programs,
          queues,
          nodeId,
          services,
          currencyStorages,
          validators,
          (tips, txs) => Some(CurrencyBlock(tips, txs))
        )
        .asResource

      gossipDaemon = GossipDaemon.make[IO](
        currencyStorages.rumor,
        queues.rumor,
        currencyStorages.cluster,
        p2pClient.gossip,
        rumorHandler,
        validators.rumorValidator,
        services.localHealthcheck,
        nodeId,
        generation,
        cfg.gossip.daemon,
        services.collateral
      )
      _ <- {
        method match {
          case cfg: RunInitialValidator =>
            gossipDaemon.startAsInitialValidator >>
              programs.l0PeerDiscovery.discoverFrom(cfg.l0Peer) >>
              programs.globalL0PeerDiscovery.discoverFrom(cfg.l0Peer) >> // TODO: should be globalL0Peer
              currencyStorages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin) >>
              services.cluster.createSession >>
              services.session.createSession >>
              currencyStorages.node.tryModifyState(SessionStarted, NodeState.Ready)

          case cfg: RunValidator =>
            gossipDaemon.startAsRegularValidator >>
              programs.l0PeerDiscovery.discoverFrom(cfg.l0Peer) >>
              programs.globalL0PeerDiscovery.discoverFrom(cfg.l0Peer) >> // TODO: should be globalL0Peer
              currencyStorages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin)
        }
      }.asResource
      globalL0PeerDiscovery = Stream
        .awakeEvery[IO](10.seconds)
        .evalMap { _ =>
          currencyStorages.lastGlobalSnapshot.get.flatMap {
            _.fold(Applicative[IO].unit) { latestSnapshot =>
              programs.globalL0PeerDiscovery.discover(latestSnapshot.signed.proofs.map(_.id).map(PeerId._Id.reverseGet))
            }
          }
        }
      _ <- stateChannel.runtime.merge(globalL0PeerDiscovery).compile.drain.asResource
    } yield ()
  }
}
