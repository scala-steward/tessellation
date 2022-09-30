package org.tessellation.dag.l1.rosetta.search

import cats.effect.MonadCancelThrow
import cats.implicits.toFunctorOps

import org.tessellation.dag.l1.domain.block.storage.{BlockIndexEntry, BlockIndexStorage}
import org.tessellation.dag.l1.infrastructure.db.Database
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

object BlockSearch {

  def make[F[_]: MonadCancelThrow: Database: KryoSerializer](storage: BlockIndexStorage[F]): BlockSearch[F] =
    new BlockSearch[F](store = storage) {
      override def search(hash: Option[Hash], index: Option[Long]) = store.getStoredBlockIndexValue(hash, index)

      override def searchByTransactionHash(hash: Option[Hash], index: Option[Long], transactionHash: Hash) =
        store
          .getStoredBlockIndexValue(hash, index)
          .map(
            _.flatMap(
              _.signedSnapshot.value.blocks.toList
                .flatMap(
                  _.block.value.transactions.toNonEmptyList.toList
                )
                .filter(
                  _.hash
                    .map(
                      _.value == transactionHash.value
                    )
                    .toOption
                    .getOrElse(false)
                )
                .headOption
            )
          )
    }
}

sealed abstract class BlockSearch[F[_]] private (
  val store: BlockIndexStorage[F]
) {
  def search(hash: Option[Hash], index: Option[Long]): F[Option[BlockIndexEntry]]

  def searchByTransactionHash(
    hash: Option[Hash],
    index: Option[Long],
    transactionHash: Hash
  ): F[Option[Signed[Transaction]]]
}
