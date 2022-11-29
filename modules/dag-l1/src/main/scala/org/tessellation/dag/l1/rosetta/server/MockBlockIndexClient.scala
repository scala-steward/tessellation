package org.tessellation.dag.l1.rosetta.server

import cats.effect.Async
import cats.implicits.toFunctorOps

import org.tessellation.dag.l1.domain.rosetta.server.BlockIndexClient
import org.tessellation.dag.l1.rosetta.MockData.mockup
import org.tessellation.dag.l1.rosetta._
import org.tessellation.dag.l1.rosetta.search.model.{AccountBlockResponse, BlockSearchRequest, BlockSearchResponse}
import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.rosetta.server.model.dag.schema.ConstructionPayloadsRequestMetadata
import org.tessellation.rosetta.server.model.{BlockIdentifier, PartialBlockIdentifier, TransactionIdentifier}
import org.tessellation.schema.address

object MockBlockIndexClient {

  def make[F[_]: Async]: BlockIndexClient[F] =
    new BlockIndexClient[F] {

      def searchBlocks(blockSearchRequest: BlockSearchRequest) =
        Async[F].pure {
          Right(
            BlockSearchResponse(
              List(api.model.TransactionWithBlockHash(examples.transaction, examples.sampleHash, 0)),
              1,
              None
            )
          )
        }

      def queryBlockEvents(limit: Option[Long], offset: Option[Long]) =
        Async[F].pure {
          Either.cond(offset.contains(0L), List(examples.snapshot), "err from block service")
        }

      def requestLastTransactionMetadata(addressActual: address.Address) =
        Async[F].pure {
          Right(
            Some(
              ConstructionPayloadsRequestMetadata(
                addressActual.value.value,
                "emptylasttxhashref",
                0L,
                0L,
                None
              )
            )
          )
        }

      def queryBlockTransaction(blockIdentifier: BlockIdentifier, transactionIdentifier: TransactionIdentifier) =
        Async[F].pure {
          Either.cond(
            test = blockIdentifier.index == 0 ||
              blockIdentifier.hash.contains(examples.sampleHash),
            Some(examples.transaction),
            "err"
          )
        }

      def queryBlock(blockIdentifier: PartialBlockIdentifier): F[Either[String, Option[GlobalSnapshot]]] =
        findBlock(blockIdentifier).map(_.map(_._1)).map {
          case Some(value) => Right(Some(value))
          case None        => Left("Error")
        }

      def findBlock(pbi: PartialBlockIdentifier): F[Option[(GlobalSnapshot, Long)]] =
        Async[F].pure(mockup.allBlocks.zipWithIndex.find {
          case (snapshot, i) =>
            val hash = mockup.blockToHash.get(snapshot)
            pbi.index.contains(i) || pbi.hash.exists(x => hash.exists(_.value == x))
        }.map { case (snapshot, i) => snapshot -> i.toLong })

      def queryAccountBalance(address: String, blockIndex: Option[PartialBlockIdentifier]) =
        blockIndex.map { id =>
          val blockResponse = findBlock(id).map(
            x =>
              x.flatMap { x =>
                val snapshot = x._1
                val heightValue = x._2
                val hash = mockup.blockToHash(snapshot)

                val response = snapshot.info.balances
                  .map(y => y._1.value.value -> y._2)
                  .get(address)
                  .map(z => AccountBlockResponse(z.value.value, hash.value, heightValue))

                response
              }
          )

          blockResponse.map {
            case Some(value) => Right(value)
            case None        => Left("Error getting the block response.")
          }
        }.getOrElse(
          Async[F]
            .pure({
              mockup.balances.get(address).map { v =>
                Right(
                  AccountBlockResponse(v, mockup.currentBlockHash.value, mockup.currentBlock.height.value.value)
                ): Either[String, AccountBlockResponse]
              } match {
                case Some(value) => value
                case None        => Left("Error getting the block response.")
              }
            })
        )
    }
}
