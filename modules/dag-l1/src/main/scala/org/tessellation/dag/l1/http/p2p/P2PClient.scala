package org.tessellation.dag.l1.http.p2p

import cats.effect.Async

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.dag.domain.block.Block
import org.tessellation.dag.l1.domain.consensus.block.http.p2p.clients.BlockConsensusClient
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.transaction.Transaction
import org.tessellation.sdk.http.p2p.SdkP2PClient
import org.tessellation.sdk.http.p2p.clients._
import org.tessellation.sdk.infrastructure.gossip.p2p.GossipClient
import org.tessellation.security.SecurityProvider

import io.circe.Encoder
import org.http4s.client._

object P2PClient {

  def make[F[_]: Async: SecurityProvider: KryoSerializer, A <: Transaction: Encoder: TypeTag, B <: Block[A]: Encoder](
    sdkP2PClient: SdkP2PClient[F],
    client: Client[F]
  ): P2PClient[F, A, B] =
    new P2PClient[F, A, B](
      sdkP2PClient.sign,
      sdkP2PClient.node,
      sdkP2PClient.cluster,
      L0ClusterClient.make(client),
      L0DAGClusterClient.make(client),
      sdkP2PClient.gossip,
      BlockConsensusClient.make(client),
      L0GlobalSnapshotClient.make(client)
    ) {}
}

sealed abstract class P2PClient[F[_], A <: Transaction, B <: Block[A]] private (
  val sign: SignClient[F],
  val node: NodeClient[F],
  val cluster: ClusterClient[F],
  val l0Cluster: L0ClusterClient[F],
  val l0DAGCluster: L0DAGClusterClient[F, A, B],
  val gossip: GossipClient[F],
  val blockConsensus: BlockConsensusClient[F, A],
  val l0GlobalSnapshotClient: L0GlobalSnapshotClient[F]
)
