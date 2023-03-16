package org.tessellation.modules

import cats.effect.kernel.Async
import cats.effect.std.Supervisor
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.domain.trust.storage.TrustStorage
import org.tessellation.infrastructure.trust.storage.TrustStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import org.tessellation.sdk.config.types.SnapshotConfig
import org.tessellation.sdk.domain.cluster.storage.{ClusterStorage, SessionStorage}
import org.tessellation.sdk.domain.collateral.LatestBalances
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.domain.snapshot.storage.SnapshotStorage
import org.tessellation.sdk.infrastructure.gossip.RumorStorage
import org.tessellation.sdk.infrastructure.snapshot.storage.{SnapshotLocalFileSystemStorage, SnapshotStorage}
import org.tessellation.sdk.modules.SdkStorages

object Storages {

  def make[F[_]: Async: KryoSerializer: Supervisor](
    sdkStorages: SdkStorages[F],
    snapshotConfig: SnapshotConfig
  ): F[Storages[F]] =
    for {
      trustStorage <- TrustStorage.make[F]
      incrementalGlobalSnapshotLocalFileSystemStorage <- SnapshotLocalFileSystemStorage.make[F, GlobalIncrementalSnapshot](
        snapshotConfig.incrementalSnapshotPath
      )
      globalSnapshotStorage <- SnapshotStorage.make[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
        incrementalGlobalSnapshotLocalFileSystemStorage,
        snapshotConfig.inMemoryCapacity
      )
    } yield
      new Storages[F](
        cluster = sdkStorages.cluster,
        node = sdkStorages.node,
        session = sdkStorages.session,
        rumor = sdkStorages.rumor,
        trust = trustStorage,
        globalSnapshot = globalSnapshotStorage,
        incrementalGlobalSnapshotLocalFileSystemStorage = incrementalGlobalSnapshotLocalFileSystemStorage
      ) {}
}

sealed abstract class Storages[F[_]] private (
  val cluster: ClusterStorage[F],
  val node: NodeStorage[F],
  val session: SessionStorage[F],
  val rumor: RumorStorage[F],
  val trust: TrustStorage[F],
  val globalSnapshot: SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo] with LatestBalances[F],
  val incrementalGlobalSnapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot]
)
