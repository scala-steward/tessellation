package org.tessellation.dag.l1.domain.snapshot.programs

import cats.effect.Async
import cats.syntax.flatMap._

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.dag.snapshot._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction.{DAGTransaction, TransactionReference}
import org.tessellation.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.security.hash.ProofsHash
import org.tessellation.security.{Hashed, SecurityProvider}

import eu.timepit.refined.types.numeric.NonNegLong

object DAGSnapshotProcessor {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    addressStorage: AddressStorage[F],
    blockStorage: BlockStorage[F, DAGTransaction, DAGBlock],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalSnapshot],
    transactionStorage: TransactionStorage[F, DAGTransaction]
  ): SnapshotProcessor[F, DAGTransaction, DAGBlock, GlobalSnapshot] =
    new SnapshotProcessor[F, DAGTransaction, DAGBlock, GlobalSnapshot] {

      import SnapshotProcessor._

      def process(globalSnapshot: Hashed[GlobalSnapshot]): F[SnapshotProcessingResult] =
        checkAlignment(globalSnapshot, blockStorage, lastGlobalSnapshotStorage)
          .flatMap(processAlignment(globalSnapshot, _, blockStorage, transactionStorage, lastGlobalSnapshotStorage, addressStorage))

      def extractMajorityTxRefs(
        acceptedInMajority: Map[ProofsHash, (Hashed[DAGBlock], NonNegLong)],
        globalSnapshot: GlobalSnapshot
      ): Map[Address, TransactionReference] = {
        val sourceAddresses =
          acceptedInMajority.values
            .flatMap(_._1.transactions.toSortedSet)
            .map(_.source)
            .toSet

        globalSnapshot.info.lastTxRefs.view.filterKeys(sourceAddresses.contains).toMap
      }
    }
}
