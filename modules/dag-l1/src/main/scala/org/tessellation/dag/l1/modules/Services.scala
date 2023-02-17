package org.tessellation.dag.l1.modules

import cats.Eq
import cats.effect.kernel.Async

import org.tessellation.dag.block.processing.{BlockAcceptanceManager, BlockAcceptanceState}
import org.tessellation.dag.domain.block.Block
import org.tessellation.dag.l1.config.types.AppConfig
import org.tessellation.dag.l1.domain.block.BlockService
import org.tessellation.dag.l1.domain.transaction.TransactionService
import org.tessellation.dag.l1.http.p2p.P2PClient
import org.tessellation.dag.snapshot.{GlobalSnapshot, Snapshot}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.transaction.Transaction
import org.tessellation.sdk.domain.cluster.services.{Cluster, Session}
import org.tessellation.sdk.domain.collateral.Collateral
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.healthcheck.LocalHealthcheck
import org.tessellation.sdk.domain.snapshot.services.L0Service
import org.tessellation.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.sdk.infrastructure.Collateral
import org.tessellation.sdk.infrastructure.cluster.storage.L0ClusterStorage.GlobalL0ClusterStorage
import org.tessellation.sdk.modules.SdkServices
import org.tessellation.security.SecurityProvider

object Services {

  def make[F[_]: Async: SecurityProvider: KryoSerializer, A <: Transaction: Eq, B <: Block[A]: Ordering: Eq, S <: Snapshot[B]](
    storages: Storages[F, A, B, S],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalSnapshot],
    globalL0Cluster: GlobalL0ClusterStorage[F],
    validators: Validators[F, A, B],
    sdkServices: SdkServices[F],
    p2PClient: P2PClient[F, A, B],
    cfg: AppConfig
  )(implicit foo: Eq[BlockAcceptanceState[A, B]]): Services[F, A, B] =
    new Services[F, A, B](
      localHealthcheck = sdkServices.localHealthcheck,
      block = BlockService.make[F, A, B](
        BlockAcceptanceManager.make[F, A, B](validators.block),
        storages.address,
        storages.block,
        storages.transaction,
        cfg.collateral.amount
      ),
      cluster = sdkServices.cluster,
      gossip = sdkServices.gossip,
      l0 = L0Service
        .make[F](p2PClient.l0GlobalSnapshotClient, globalL0Cluster, lastGlobalSnapshotStorage, None),
      session = sdkServices.session,
      transaction = TransactionService.make[F, A](storages.transaction, validators.transactionContextual),
      collateral = Collateral.make[F](cfg.collateral, storages.lastSnapshot)
    ) {}
}

sealed abstract class Services[F[_], A <: Transaction, B <: Block[A]] private (
  val localHealthcheck: LocalHealthcheck[F],
  val block: BlockService[F, A, B],
  val cluster: Cluster[F],
  val gossip: Gossip[F],
  val l0: L0Service[F],
  val session: Session[F],
  val transaction: TransactionService[F, A],
  val collateral: Collateral[F]
)
