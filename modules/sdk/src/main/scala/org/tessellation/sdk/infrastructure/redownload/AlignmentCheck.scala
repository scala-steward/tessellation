package org.tessellation.sdk.infrastructure.redownload

import cats.MonadThrow
import cats.data.NonEmptySet
import cats.effect.kernel.Temporal
import cats.effect.std.Supervisor
import cats.effect.syntax.concurrent._
import cats.effect.{Async, Ref}
import cats.syntax.applicative._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.set._

import scala.collection.immutable.SortedSet
import scala.concurrent.duration.{DurationInt, FiniteDuration}

import org.tessellation.ext.collection.IterableUtils.pickMajority
import org.tessellation.schema.peer.{L0Peer, Peer, PeerInfo}
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import org.tessellation.sdk.domain.Daemon
import org.tessellation.sdk.domain.cluster.storage.{ClusterStorage, L0ClusterStorage}
import org.tessellation.sdk.domain.seedlist.SeedlistEntry
import org.tessellation.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.sdk.http.p2p.clients.L0GlobalSnapshotClient

trait AlignmentCheck[F[_]] {
  def check(): F[Unit]
}

object AlignmentCheck {
  private val maxConcurrentPeerInquiries = 10
  def make[F[_]: Async: Temporal](
    priorityPeers: NonEmptySet[SeedlistEntry],
    isAlignedR: Ref[F, Boolean],
    clusterStorage: ClusterStorage[F],
    l0ClusterStorage: L0ClusterStorage[F],
    lastSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    l0GlobalSnapshot: L0GlobalSnapshotClient[F],
    waitForPeers: FiniteDuration = 1.minute
  ): AlignmentCheck[F] = () => {
    // TODO Are these the correct peers to use to determine if we're aligned?
    //  At first I thought to use the priority seed list but I'm not sure that's correct
    def getPeers: F[List[Peer]] =
      priorityPeers.toNonEmptyList
        .traverse(se => clusterStorage.getPeer(se.peerId))
        .map(_.toList.flatten)

    def toL0Peers(peers: List[Peer]): List[L0Peer] =
      peers.map(p => L0Peer.fromPeerInfo(PeerInfo.fromPeer(p)))

    for {
      (ordinal, hash) <- lastSnapshotStorage.get
        .flatMap(MonadThrow[F].fromOption(_, new Exception("TODO raise error or exit early?")))
        .map(hs => (hs.ordinal, hs.hash))

      // TODO add comment explaining why we're sleeping
      _ <- Temporal[F].sleep(waitForPeers)

      // TODO filter on Responsive?
      // TODO what to do if empty?
      //   do nothing and try again next iteration?
      //   will that ever happen?
      peers <- getPeers

      majorityHash <- peers
        .parTraverseN(maxConcurrentPeerInquiries)(p => l0GlobalSnapshot.getHash(ordinal).run(p))
        .map(_.flatten)
//        .flatMap(hs => MonadThrow[F].fromOption(hs.toNel, new Exception("No peers have our ordinal")))
        .map(pickMajority)

      // TODO if we are not aligned, can we pick any peer from seedlist and use that as new global0Peer ?
      //  We can store it in the Ref, with the initial value being the either cfg or method globalL0Peer
      isAligned = majorityHash.fold(false)(hash === _)

      // TODO alternatively, we can reset our l0 peers using priority seed list?
      //   if so, is this the place to do it?
      maybeL0Peers = SortedSet.from(toL0Peers(peers)).toNes
      _ <- maybeL0Peers.traverse_(l0ClusterStorage.setPeers).unlessA(isAligned)

      _ <- isAlignedR.set(isAligned)

    } yield ()
  }

  def daemon[F[_]: Async: Supervisor](ac: AlignmentCheck[F]): Daemon[F] =
    Daemon.periodic(ac.check(), 1.minute)
}
