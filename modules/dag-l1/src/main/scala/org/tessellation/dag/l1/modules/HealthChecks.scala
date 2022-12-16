package org.tessellation.dag.l1.modules

import cats.effect.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.functor._

import org.tessellation.dag.domain.block.Block
import org.tessellation.dag.l1.http.p2p.P2PClient
import org.tessellation.dag.snapshot.Snapshot
import org.tessellation.effects.GenUUID
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.Transaction
import org.tessellation.sdk.config.types.HealthCheckConfig
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.infrastructure.healthcheck.ping.{PingHealthCheckConsensus, PingHealthCheckConsensusDriver}

import org.http4s.client.Client

object HealthChecks {

  def make[F[_]: Async: KryoSerializer: GenUUID: Random: Supervisor, A <: Transaction, B <: Block[A], C <: Snapshot[B]](
    storages: Storages[F, A, B],
    services: Services[F, A, B],
    programs: Programs[F, A, B, C],
    p2pClient: P2PClient[F, A, B],
    client: Client[F],
    session: Session[F],
    config: HealthCheckConfig,
    selfId: PeerId
  ): F[HealthChecks[F]] = {
    def ping = PingHealthCheckConsensus.make(
      storages.cluster,
      programs.joining,
      selfId,
      new PingHealthCheckConsensusDriver(),
      config,
      services.gossip,
      p2pClient.node,
      client,
      session
    )

    ping.map {
      new HealthChecks(_) {}
    }
  }
}

sealed abstract class HealthChecks[F[_]] private (
  val ping: PingHealthCheckConsensus[F]
)
