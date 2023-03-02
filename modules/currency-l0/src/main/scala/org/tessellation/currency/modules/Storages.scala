package org.tessellation.currency.modules

import cats.Functor
import cats.effect.Ref
import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.currency.schema.currency.CurrencySnapshot
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.L0Peer
import org.tessellation.sdk.config.types.SnapshotConfig
import org.tessellation.sdk.domain.cluster.storage.{ClusterStorage, L0ClusterStorage, SessionStorage}
import org.tessellation.sdk.domain.collateral.LatestBalances
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.domain.snapshot.storage.SnapshotStorage
import org.tessellation.sdk.infrastructure.cluster.storage.L0ClusterStorage
import org.tessellation.sdk.infrastructure.gossip.RumorStorage
import org.tessellation.sdk.infrastructure.snapshot.storage.{SnapshotLocalFileSystemStorage, SnapshotStorage}
import org.tessellation.sdk.modules.SdkStorages
import org.tessellation.security.hash.Hash

object Storages {

  def make[F[_]: Async: KryoSerializer: Supervisor: Random](
    sdkStorages: SdkStorages[F],
    snapshotConfig: SnapshotConfig,
    globalL0Peer: L0Peer
  ): F[Storages[F]] =
    for {
      snapshotLocalFileSystemStorage <- SnapshotLocalFileSystemStorage.make[F, CurrencySnapshot](
        snapshotConfig.snapshotPath
      )
      snapshotStorage <- SnapshotStorage
        .make[F, CurrencySnapshot](snapshotLocalFileSystemStorage, snapshotConfig.inMemoryCapacity)

      globalL0ClusterStorage <- L0ClusterStorage.make[F](globalL0Peer)
      lastSignedBinaryHashStorage <- LastSignedBinaryHashStorage.make[F]
    } yield
      new Storages[F](
        globalL0Cluster = globalL0ClusterStorage,
        cluster = sdkStorages.cluster,
        node = sdkStorages.node,
        session = sdkStorages.session,
        rumor = sdkStorages.rumor,
        snapshot = snapshotStorage,
        lastSignedBinaryHash = lastSignedBinaryHashStorage
      ) {}
}

sealed abstract class Storages[F[_]] private (
  val globalL0Cluster: L0ClusterStorage[F],
  val cluster: ClusterStorage[F],
  val node: NodeStorage[F],
  val session: SessionStorage[F],
  val rumor: RumorStorage[F],
  val snapshot: SnapshotStorage[F, CurrencySnapshot] with LatestBalances[F],
  val lastSignedBinaryHash: LastSignedBinaryHashStorage[F]
)

trait LastSignedBinaryHashStorage[F[_]] {
  def set(hash: Hash): F[Unit]

  def get: F[Hash]
}

object LastSignedBinaryHashStorage {

  def make[F[_]: Ref.Make: Functor]: F[LastSignedBinaryHashStorage[F]] = Ref.of[F, Hash](Hash.empty).map { lastHashR =>
    new LastSignedBinaryHashStorage[F] {
      def set(hash: Hash): F[Unit] = lastHashR.set(hash)
      def get: F[Hash] = lastHashR.get
    }

  }
}
