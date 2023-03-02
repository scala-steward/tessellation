package org.tessellation.currency.l0

import java.security.KeyPair

import cats.effect.{Async, IO, Resource}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.semigroupk._

import scala.Console.{RESET, YELLOW}

import org.tessellation.currency.cli.method
import org.tessellation.currency.cli.method.{Run, RunGenesis, RunValidator}
import org.tessellation.currency.http.P2PClient
import org.tessellation.currency.modules._
import org.tessellation.currency.schema.currency.{CurrencySnapshot, TokenSymbol}
import org.tessellation.currency.{BuildInfo, CurrencyKryoRegistrationIdRange, currencyKryoRegistrar}
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.crypto._
import org.tessellation.ext.kryo._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.cluster.ClusterId
import org.tessellation.schema.node.NodeState
import org.tessellation.sdk.app.{SDK, TessellationIOApp}
import org.tessellation.sdk.cli.CliMethod
import org.tessellation.sdk.domain.collateral.OwnCollateralNotSatisfied
import org.tessellation.sdk.infrastructure.genesis.{Loader => GenesisLoader}
import org.tessellation.sdk.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import org.tessellation.sdk.resources.MkHttpServer
import org.tessellation.sdk.resources.MkHttpServer.ServerName
import org.tessellation.sdk.{SdkOrSharedOrKernelRegistrationIdRange, sdkKryoRegistrar}
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.StateChannelSnapshotBinary

import com.monovore.decline.Opts
import eu.timepit.refined.boolean.Or
import org.typelevel.log4cats.slf4j.Slf4jLogger

abstract class CurrencyApp[A <: CliMethod](
  symbol: TokenSymbol,
  clusterId: ClusterId,
  identifier: Address
) extends TessellationIOApp[A](
      s"$symbol-currency-l0",
      s"$symbol L0 node",
      clusterId,
      version = BuildInfo.version
    ) {
  type KryoRegistrationIdRange = CurrencyKryoRegistrationIdRange Or SdkOrSharedOrKernelRegistrationIdRange

  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]] =
    currencyKryoRegistrar.union(sdkKryoRegistrar)

  def run(method: A, sdk: SDK[IO]): Resource[IO, Unit]
}

abstract class L0CurrencyIOApp(
  symbol: TokenSymbol,
  clusterId: ClusterId,
  identifier: Address
) extends CurrencyApp[Run](symbol, clusterId, identifier) {

  val opts: Opts[Run] = method.opts

  private def genesisBinary[F[_]: Async: KryoSerializer: SecurityProvider](
    keyPair: KeyPair,
    genesis: Signed[CurrencySnapshot],
    storage: LastSignedBinaryHashStorage[F]
  ): F[Signed[StateChannelSnapshotBinary]] =
    for {
      artifactHash <- genesis.hashF
      genesisBytes <- genesis.toBinaryF
      genesisBinary <- StateChannelSnapshotBinary(genesis.lastSnapshotHash, genesisBytes).sign(keyPair)
      binaryHash <- genesisBinary.hashF
      _ <- Slf4jLogger.getLogger.info(s"${YELLOW}Genesis binary hash: ${binaryHash}${RESET}")
      _ <- storage.set(binaryHash)
    } yield genesisBinary

  def run(method: Run, sdk: SDK[IO]): Resource[IO, Unit] = {
    import sdk._

    val cfg = method.appConfig

    for {
      _ <- Resource.unit
      p2pClient = P2PClient.make[IO](sdkP2PClient, sdkResources.client, keyPair, identifier)
      queues <- Queues.make[IO](sdkQueues).asResource
      storages <- Storages.make[IO](sdkStorages, cfg.snapshot, method.l0Peer).asResource
      validators = Validators.make[IO](seedlist)
      services <- Services
        .make[IO](
          sdkServices,
          storages,
          validators,
          sdkResources.client,
          sdkServices.session,
          sdk.seedlist,
          sdk.nodeId,
          keyPair,
          cfg,
          identifier
        )
        .asResource
      programs = Programs.make[IO](sdkPrograms, storages, services, p2pClient)
      healthChecks <- HealthChecks
        .make[IO](
          storages,
          services,
          programs,
          p2pClient,
          sdkResources.client,
          sdkServices.session,
          cfg.healthCheck,
          sdk.nodeId
        )
        .asResource
      rumorHandler = RumorHandlers.make[IO](storages.cluster, healthChecks.ping, services.localHealthcheck).handlers <+>
        services.consensus.handler
      _ <- Daemons
        .start(storages, services, programs, queues, healthChecks)
        .asResource
      api = HttpApi
        .make[IO](
          storages,
          queues,
          services,
          programs,
          healthChecks,
          keyPair.getPrivate,
          cfg.environment,
          sdk.nodeId,
          BuildInfo.version,
          cfg.http
        )
      _ <- MkHttpServer[IO].newEmber(ServerName("public"), cfg.http.publicHttp, api.publicApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), cfg.http.p2pHttp, api.p2pApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("cli"), cfg.http.cliHttp, api.cliApp)

      gossipDaemon = GossipDaemon.make[IO](
        storages.rumor,
        queues.rumor,
        storages.cluster,
        p2pClient.gossip,
        rumorHandler,
        validators.rumorValidator,
        services.localHealthcheck,
        nodeId,
        generation,
        cfg.gossip.daemon,
        services.collateral
      )

      _ <- (method match {
        case _: RunValidator =>
          gossipDaemon.startAsRegularValidator >>
            programs.globalL0PeerDiscovery.discoverFrom(cfg.l0Peer) >>
            storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin)
        case m: RunGenesis =>
          storages.node.tryModifyState(
            NodeState.Initial,
            NodeState.LoadingGenesis,
            NodeState.GenesisReady
          ) {
            GenesisLoader.make[IO].load(m.genesisPath).flatMap { accounts =>
              val genesis = CurrencySnapshot.mkGenesis(
                accounts.map(a => (a.address, a.balance)).toMap
              )

              Signed.forAsyncKryo[IO, CurrencySnapshot](genesis, keyPair).flatMap { signedGenesis =>
                storages.snapshot.prepend(signedGenesis) >>
                  genesisBinary[IO](keyPair, signedGenesis, storages.lastSignedBinaryHash).flatMap { bin =>
                    p2pClient.stateChannelSnapshotClient
                      .sendStateChannelSnapshot(bin)(method.l0Peer)
                      .ifM(
                        logger.info("Genesis sent"),
                        logger.error("Genesis not sent")
                      )
                  } >>
                  services.collateral
                    .hasCollateral(sdk.nodeId)
                    .flatMap(OwnCollateralNotSatisfied.raiseError[IO, Unit].unlessA) >>
                  services.consensus.manager.startFacilitatingAfter(genesis.ordinal, signedGenesis)
              }
            }
          } >>
            gossipDaemon.startAsInitialValidator >>
            services.cluster.createSession >>
            services.session.createSession >>
            programs.globalL0PeerDiscovery.discoverFrom(cfg.l0Peer) >>
            storages.node.setNodeState(NodeState.Ready)
      }).asResource
    } yield ()
  }
}
