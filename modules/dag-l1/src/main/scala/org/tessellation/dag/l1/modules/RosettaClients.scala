package org.tessellation.dag.l1.modules

import cats.effect.{Async, MonadCancelThrow}

import org.tessellation.dag.l1.domain.rosetta.server.{BlockIndexClient, L1Client}
import org.tessellation.dag.l1.infrastructure.block.storage.BlockIndexDbStorage
import org.tessellation.dag.l1.infrastructure.db.Database
import org.tessellation.dag.l1.infrastructure.transaction.storage.TransactionIndexDbStorage
import org.tessellation.dag.l1.rosetta.search.{SnapshotMemoryService, TransactionIndexSearch, TransactionMemoryService}
import org.tessellation.dag.l1.rosetta.server.{RosettaBlockIndexClient, RosettaL1Client}
import org.tessellation.kryo.KryoSerializer

object RosettaClients {

  def make[F[_]: Async: MonadCancelThrow: Database: KryoSerializer](services: Services[F], storages: Storages[F]) = {
    val rosettaBlockIndexClient = RosettaBlockIndexClient.make[F](
      TransactionIndexSearch.make[F](TransactionIndexDbStorage.make[F]),
      storages.transaction,
      BlockIndexDbStorage.make[F]
    )

    val rosettaL1Client = RosettaL1Client.make(
      TransactionMemoryService.make(
        storages.transaction,
        services.transaction
      ),
      SnapshotMemoryService.make(storages.lastGlobalSnapshotStorage)
    )

    new RosettaClients[F](
      rosettaBlockIndexClient,
      rosettaL1Client
    ) {}
  }

}

sealed abstract class RosettaClients[F[_]] private (
  val blockIndexClient: BlockIndexClient[F],
  val l1Client: L1Client[F]
)
