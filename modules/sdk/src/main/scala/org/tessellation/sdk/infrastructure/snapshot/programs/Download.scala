package org.tessellation.sdk.infrastructure.snapshot.programs

import cats.Monad
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import scala.concurrent.duration.{Duration, DurationInt}

import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.Peer
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.infrastructure.snapshot.SnapshotConsensus

object Download {

  def make[F[_]: Async](
    nodeStorage: NodeStorage[F],
    consensus: SnapshotConsensus[F, _, _, _, _],
    getPeers: () => F[Set[Peer]]
  ): Download[F] =
    new Download[F](
      nodeStorage,
      consensus,
      getPeers
    ) {}
}

sealed abstract class Download[F[_]: Async] private (
  nodeStorage: NodeStorage[F],
  consensus: SnapshotConsensus[F, _, _, _, _],
  getPeers: () => F[Set[Peer]]
) {

  private val timeout: Duration = 15.seconds // set to reasonable value (from config?)
  private val minKnownPeers = 50 // get this from config?

  private def waitUntilMinPeersFound: F[Unit] = {
    val notEnoughPeers = getPeers().map(_.size < minKnownPeers)
    Async[F].timeoutTo(
      Monad[F].whileM_(notEnoughPeers)(Async[F].sleep(1.second)),
      timeout,
      Async[F].unit
    )
  }

  def download(): F[Unit] =
    nodeStorage.tryModifyState(NodeState.WaitingForDownload, NodeState.WaitingForObserving) >>
      waitUntilMinPeersFound >>
      consensus.manager.startObserving
}
