package org.tessellation.dag.l1.modules

import cats.Order
import cats.effect.kernel.Async
import cats.effect.std.Random
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.dag.domain.block.Block
import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.consensus.block.storage.ConsensusStorage
import org.tessellation.dag.l1.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.dag.l1.infrastructure.address.storage.AddressStorage
import org.tessellation.dag.snapshot.Snapshot
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.L0Peer
import org.tessellation.schema.transaction.Transaction
import org.tessellation.sdk.domain.cluster.storage.{ClusterStorage, L0ClusterStorage, SessionStorage}
import org.tessellation.sdk.domain.collateral.LatestBalances
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.sdk.infrastructure.cluster.storage.L0ClusterStorage
import org.tessellation.sdk.infrastructure.gossip.RumorStorage
import org.tessellation.sdk.modules.SdkStorages

object Storages {

  def make[F[_]: Async: Random: KryoSerializer, A <: Transaction: Order: Ordering, B <: Block[A], S <: Snapshot[B]](
    sdkStorages: SdkStorages[F],
    l0Peer: L0Peer
  ): F[Storages[F, A, B, S]] =
    for {
      blockStorage <- BlockStorage.make[F, A, B]
      consensusStorage <- ConsensusStorage.make[F, A, B]
      l0ClusterStorage <- L0ClusterStorage.make[F](l0Peer)
      lastSnapshotStorage <- LastSnapshotStorage.make[F, S]
      transactionStorage <- TransactionStorage.make[F, A]
      addressStorage <- AddressStorage.make[F]
    } yield
      new Storages[F, A, B, S] {
        val address = addressStorage
        val block = blockStorage
        val consensus = consensusStorage
        val cluster = sdkStorages.cluster
        val l0Cluster = l0ClusterStorage
        val lastSnapshot = lastSnapshotStorage
        val node = sdkStorages.node
        val session = sdkStorages.session
        val rumor = sdkStorages.rumor
        val transaction = transactionStorage
      }
}

trait Storages[F[_], A <: Transaction, B <: Block[A], S <: Snapshot[B]] {
  val address: AddressStorage[F]
  val block: BlockStorage[F, A, B]
  val consensus: ConsensusStorage[F, A, B]
  val cluster: ClusterStorage[F]
  val l0Cluster: L0ClusterStorage[F]
  val lastSnapshot: LastSnapshotStorage[F, S] with LatestBalances[F]
  val node: NodeStorage[F]
  val session: SessionStorage[F]
  val rumor: RumorStorage[F]
  val transaction: TransactionStorage[F, A]
}
