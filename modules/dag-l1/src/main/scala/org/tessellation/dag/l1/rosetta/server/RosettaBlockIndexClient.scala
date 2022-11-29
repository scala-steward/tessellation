package org.tessellation.dag.l1.rosetta.server

import cats.Applicative
import cats.effect.Async
import cats.implicits.toFunctorOps

import scala.util.Random

import org.tessellation.dag.l1.domain.block.storage.BlockIndexStorage
import org.tessellation.dag.l1.domain.rosetta.server.BlockIndexClient
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.dag.l1.rosetta.search.TransactionIndexSearch
import org.tessellation.dag.l1.rosetta.search.model.{AccountBlockResponse, BlockSearchRequest, BlockSearchResponse}
import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.rosetta.server.model.dag.schema.ConstructionPayloadsRequestMetadata
import org.tessellation.rosetta.server.model.{BlockIdentifier, PartialBlockIdentifier, TransactionIdentifier}
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

object RosettaBlockIndexClient {

  def make[F[_]: Async: KryoSerializer](
    transactionIndexSearch: TransactionIndexSearch[F],
    transactionMemoryStorage: TransactionStorage[F],
    blockIndexStorage: BlockIndexStorage[F]
  ): BlockIndexClient[F] =
    new BlockIndexClient[F] {
      val transactionStorage = transactionMemoryStorage
      val blockStorage = blockIndexStorage

      override def searchBlocks(blockSearchRequest: BlockSearchRequest): F[Either[String, BlockSearchResponse]] = ???

      override def queryBlockEvents(
        limit: Option[Long],
        offset: Option[Long]
      ): F[Either[String, List[GlobalSnapshot]]] = ???

      override def requestLastTransactionMetadata(
        addressActual: Address
      ): F[Either[String, Option[ConstructionPayloadsRequestMetadata]]] =
        transactionStorage.getLastAcceptedReference(addressActual).map { transactionReference =>
          Right(
            Some(
              ConstructionPayloadsRequestMetadata(
                addressActual.value.value,
                transactionReference.hash.value,
                transactionReference.ordinal.value.value,
                0L,
                Some(new Random().nextLong())
              )
            )
          )
        }

      override def queryBlockTransaction(
        blockIdentifier: BlockIdentifier,
        transactionIdentifier: TransactionIdentifier
      ): F[Either[String, Option[Signed[Transaction]]]] =
        blockStorage.getStoredBlockIndexValue(Some(Hash(blockIdentifier.hash)), Some(blockIdentifier.index)).map {
          maybeBlockIndexEntry =>
            maybeBlockIndexEntry.headOption.flatMap { blockEntry =>
              blockEntry.signedSnapshot.blocks.toList.flatMap {
                _.block.transactions.filter { x =>
                  x.hash.map(y => y.value == transactionIdentifier.hash).toOption.getOrElse(false)
                }.toList.headOption
              }.headOption
            } match {
              case Some(value) => Right(Some(value): Option[Signed[Transaction]])
              case None        => Left("Unable to find the block transaction.")
            }
        }

      override def queryBlock(blockIdentifier: PartialBlockIdentifier): F[Either[String, Option[GlobalSnapshot]]] = {
        val storageQueryResult =
          blockStorage.getStoredBlockIndexValue(blockIdentifier.hash.map(Hash(_)), blockIdentifier.index)

        storageQueryResult.map(_ match {
          case Some(value) => Right(Some(value.signedSnapshot.value))
          case None        => Left("Error: Unable to retrieve the block.")
        })
      }

      override def queryAccountBalance(
        address: String,
        blockIndex: Option[PartialBlockIdentifier]
      ): F[Either[String, AccountBlockResponse]] = {
        val response = blockIndex.map { blockIdentifier =>
          val storageQueryResult =
            blockStorage.getStoredBlockIndexValue(blockIdentifier.hash.map(Hash(_)), blockIdentifier.index)

          storageQueryResult.map(_.flatMap { blockEntry =>
            blockEntry.signedSnapshot.info.balances.map {
              case (balanceAddress, balanceValue) =>
                balanceAddress.value.value -> balanceValue
            }.get(address)
              .map(
                retrievedBalance =>
                  AccountBlockResponse(retrievedBalance.value.value, blockEntry.hash.value, blockEntry.height)
              )
          })
        } match {
          case Some(value) => value
          case None        => Applicative[F].pure(None: Option[AccountBlockResponse])
        }

        response.map(_ match {
          case Some(value) => Right(value)
          case None        => Left("Error: Unable to get the account balance.")
        })
      }
    }
}
