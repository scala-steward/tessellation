package org.tessellation.dag.l1.rosetta

import java.security.{PublicKey => JPublicKey}

import cats.Order
import cats.data.NonEmptySet
import cats.effect.Async
import cats.implicits.{toFlatMapOps, toFunctorOps, toSemigroupKOps}
import cats.syntax.flatMap._

import scala.collection.immutable.SortedSet
import scala.util.Try

import org.tessellation.dag.l1.domain.rosetta.server.api.model.BlockSearchRequest
import org.tessellation.dag.l1.domain.rosetta.server.{BlockIndexClient, L1Client}
import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.rosetta.server.model._
import org.tessellation.rosetta.server.model.dag.decoders._
import org.tessellation.rosetta.server.model.dag.schema._
import org.tessellation.schema.address.{Address, DAGAddressRefined}
import org.tessellation.schema.transaction.{Transaction => DAGTransaction}
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.SignatureProof
import org.tessellation.security.{Hashable, SecurityProvider}

import eu.timepit.refined.types.all.PosLong
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpRoutes, Response}
import org.typelevel.log4cats.slf4j.Slf4jLogger

import Rosetta._
import MockData.mockup
import examples.proofs
import SignatureProof._
import Util.{getPublicKeyFromBytes, reduceListEither}

/**
  * The data model for these routes was code-genned according to openapi spec using
  * the scala-scalatra generator. This generates the least amount of additional
  * model artifacts with minimal dependencies compared to the other scala code
  * generators.
  *
  * They are consistent with  rosetta "version":"1.4.12",
  *
  * Enum types do not generate properly (in any of the Scala code generators.)
  * circe json decoders were manually generated separately
  */
final case class RosettaRoutes[F[_]: Async: KryoSerializer: SecurityProvider](
  val networkId: String = "mainnet",
  val blockIndexClient: BlockIndexClient[F],
  val l1Client: L1Client[F]
) extends Http4sDsl[F] {

  implicit val logger = Slf4jLogger.getLogger[F]

  private val testRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "test" =>
      Ok("test")
  }

  def error(code: Int, retriable: Boolean = true, details: Option[ErrorDetails] = None): F[Response[F]] =
    InternalServerError(makeErrorCode(code, retriable, details))

  def errorMsg(code: Int, message: String, retriable: Boolean = true): F[Response[F]] =
    InternalServerError(
      makeErrorCode(code, retriable, Some(ErrorDetails(List(ErrorDetailKeyValue("exception", message)))))
    )

  // TODO: Error for invalid subnetwork unsupported.
  def validateNetwork(networkIdentifier: NetworkIdentifier): Either[F[Response[F]], Unit] = {
    if (networkIdentifier.subNetworkIdentifier.isDefined) {
      return Left(errorMsg(1, "Subnetworks not supported"))
    }
    networkIdentifier.blockchain match {
      case constants.dagBlockchainId =>
        networkIdentifier.network match {
          case x if x == networkId =>
            Right(())
          case _ =>
            Left(errorMsg(1, "Invalid network id"))
        }
      case _ => Left(errorMsg(1, "Invalid blockchain id"))
    }
  }

  def validateAddress(address: String): Either[F[Response[F]], Unit] =
    if (DAGAddressRefined.addressCorrectValidate.isValid(address)) {
      Right(())
    } else Left(errorMsg(4, address))

  def validateCurveType(curveType: String): Either[F[Response[F]], Unit] =
    curveType match {
      case "secp256k1" => Right(())
      case _           => Left(errorMsg(12, curveType, retriable = false))
    }

  def validateHex(hexInput: String): Either[F[Response[F]], Hex] =
    Try { Hex(hexInput).toBytes }.toEither.left
      .map(e => errorMsg(11, s"hexInput: ${hexInput}, error: ${e.getMessage}", retriable = false))
      .map(_ => Hex(hexInput))

  def convertRosettaPublicKeyToJPublicKey(rosettaPublicKey: PublicKey): Either[F[Response[F]], F[JPublicKey]] =
    validateCurveType(rosettaPublicKey.curveType) match {
      case Left(e) => Left(e)
      case Right(_) =>
        validateHex(rosettaPublicKey.hexBytes) match {
          case Left(e) => Left(e)
          case Right(v) =>
            Right(
              Async[F].delay(getPublicKeyFromBytes(v.toBytes).asInstanceOf[JPublicKey])
            )
        }
    }

  private val routes: HttpRoutes[F] = HttpRoutes.of[F] {

    case req @ POST -> Root / "account" / "balance" =>
      req.decodeRosetta[AccountBalanceRequest] { r =>
        val endpointResponse = for {
          _ <- validateNetwork(r.networkIdentifier)
          _ <- validateAddress(r.accountIdentifier.address)
          address = r.accountIdentifier.address
        } yield {
          blockIndexClient
            .queryAccountBalance(address, r.blockIdentifier)
            .flatMap(
              x =>
                x.left
                  .map(e => errorMsg(5, e))
                  .map(
                    y =>
                      Ok(
                        AccountBalanceResponse(
                          BlockIdentifier(y.height, y.snapshotHash),
                          List(Amount(y.amount.toString, DagCurrency, None)),
                          None
                        )
                      )
                  )
                  .getOrElse(errorMsg(6, address))
            )
        }
        endpointResponse.merge
      }

    case _ @POST -> Root / "account" / "coins" => errorMsg(0, "UTXO endpoints not implemented")

    case req @ POST -> Root / "block" =>
      req.decodeRosetta[BlockRequest] { blockRequest =>
        val endpointResponse = for {
          _ <- validateNetwork(blockRequest.networkIdentifier)
        } yield {
          blockIndexClient
            .queryBlock(blockRequest.blockIdentifier)
            .map { x =>
              x.map { y =>
                y.map { snapshot =>
                  snapshot.hash.left
                    .map(e => errorMsg(0, f"Hash calculation on snapshot failure: ${e.getMessage}"))
                    .map { hash =>
                      val extractedTransactions = extractTransactions(snapshot)

                      if (extractedTransactions.exists(_.isLeft))
                        errorMsg(0, "Internal transaction translation failure")
                      else
                        Ok(
                          BlockResponse(
                            Some(
                              Block(
                                BlockIdentifier(snapshot.height.value.value, hash.value),
                                BlockIdentifier(
                                  Math.max(snapshot.height.value.value - 1, 0),
                                  if (snapshot.height.value.value > 0)
                                    snapshot.lastSnapshotHash.value
                                  else
                                    hash.value
                                ),
                                mockup.timestamps(snapshot.height.value.value),
                                extractedTransactions.map(_.toOption.get),
                                None
                              )
                            ),
                            None
                          )
                        )
                    }
                    .merge
                }.getOrElse(Ok(BlockResponse(None, None)))
              }.left.map(e => errorMsg(5, e))
            }
            .flatMap(_.merge)
        }
        endpointResponse.merge
      }

    case req @ POST -> Root / "block" / "transaction" =>
      req.decodeRosetta[BlockTransactionRequest] { transactionRequest =>
        val endpointResponse = for {
          _ <- validateNetwork(transactionRequest.networkIdentifier)
        } yield {
          blockIndexClient.queryBlockTransaction(transactionRequest.blockIdentifier).flatMap { transactionEither =>
            transactionEither.left
              .map(errorMsg(5, _))
              .map(_.map { signedTransaction =>
                Ok(
                  BlockTransactionResponse(
                    translateTransaction(signedTransaction).toOption.get
                  )
                )
              }.getOrElse(error(7)))
              .merge
          }
        }

        endpointResponse.merge
      }

    case req @ POST -> Root / "call" =>
      req.decodeRosetta[CallRequest] { callRequest =>
        val endpointResponse = for {
          _ <- validateNetwork(callRequest.networkIdentifier)
        } yield {
          // TODO: is any implementation here required yet?
          error(8)
        }

        endpointResponse.merge
      }

    case req @ POST -> Root / "construction" / "combine" =>
      req.decodeRosetta[ConstructionCombineRequest] { constructionCombineRequest =>
        val endpointResponse = for {
          _ <- validateNetwork(constructionCombineRequest.networkIdentifier)
        } yield {
          // TODO combine multiple signatures yet supported?
          if (constructionCombineRequest.signatures.size > 1) {
            error(8)
          } else if (constructionCombineRequest.signatures.isEmpty) {
            errorMsg(3, "No signatures found")
          } else {
            KryoSerializer[F]
              .deserialize[Signed[DAGTransaction]](
                Hex(constructionCombineRequest.unsignedTransaction).toBytes // TODO: Handle error here for invalid hex
              )
              .left
              .map(_ => error(9))
              .map { t: Signed[DAGTransaction] =>
                val test = Rosetta.convertSignature(constructionCombineRequest.signatures).flatMap {
                  _.left
                    .map(InternalServerError(_))
                    .map { prf =>
                      val serializedTransaction = KryoSerializer[F]
                        .serialize(
                          Signed[DAGTransaction](
                            t.value,
                            NonEmptySet(prf.head, SortedSet(prf.tail: _*))(
                              Order.fromOrdering(SignatureProof.OrderingInstance)
                            )
                          )
                        )
                        .left
                        .map(e => errorMsg(0, "Serialize transaction failure: " + e.getMessage))
                        .map(s => Ok(ConstructionCombineResponse(Hex.fromBytes(s).value)))

                      serializedTransaction.merge
                    }
                    .merge
                }

                test
              }
              .merge
          }
        }

        endpointResponse.merge
      }

    case req @ POST -> Root / "construction" / "derive" =>
      req.decodeRosetta[ConstructionDeriveRequest] { constructionDeriveRequest =>
        val endpointResponse = for {
          _ <- validateNetwork(constructionDeriveRequest.networkIdentifier)
        } yield {
          val test = convertRosettaPublicKeyToJPublicKey(constructionDeriveRequest.publicKey).map { inner =>
            inner.flatMap { pk =>
              val value = pk.toAddress.value.value
              Ok(ConstructionDeriveResponse(Some(value), Some(AccountIdentifier(value, None, None)), None))
            }
          }

          val mergedTest = test.merge
          mergedTest
        }

        endpointResponse.merge
      }

    case req @ POST -> Root / "construction" / "hash" =>
      req.decodeRosetta[ConstructionHashRequest] { constructionHashRequest =>
        val endpointResponse = for {
          _ <- validateNetwork(constructionHashRequest.networkIdentifier)
        } yield {
          KryoSerializer[F]
            .deserialize[Signed[DAGTransaction]](Hex(constructionHashRequest.signedTransaction).toBytes)
            .left
            .map(_ => error(9))
            .map(
              t =>
                Hashable
                  .forKryo[F]
                  .hash(t)
                  .left
                  .map(_ => error(0)) // TODO: error code
                  .map(_.value)
                  .map(s => Ok(TransactionIdentifierResponse(TransactionIdentifier(s), None)))
                  .merge
            )
            .merge
        }

        endpointResponse.merge
      }

    case req @ POST -> Root / "construction" / "metadata" =>
      req.decodeRosetta[ConstructionMetadataRequest] { constructionMetadataRequest =>
        val endpointResponse = for {
          _ <- validateNetwork(constructionMetadataRequest.networkIdentifier)
        } yield {
          if (constructionMetadataRequest.options.isDefined)
            errorMsg(8, "Custom options not supported")

          constructionMetadataRequest.publicKeys match {
            case x if x.isEmpty  => errorMsg(8, "Must provide public key")
            case x if x.size > 1 => errorMsg(8, "Multiple public keys not supported")
            case None            => errorMsg(8, "No public keys provided, required for construction")
            case Some(keyList) =>
              convertRosettaPublicKeyToJPublicKey(keyList.head).map { y =>
                y.flatMap { publicKey =>
                  val response = l1Client.requestLastTransactionMetadataAndFee(publicKey.toAddress).map {
                    lastTransaction =>
                      lastTransaction.left.map(errorMsg(13, _))
                  }

                  response
                    .flatMap(_.map { maybeMetadata =>
                      val done = maybeMetadata match {
                        case Some(_) =>
                          Async[F].pure(
                            Right(maybeMetadata): Either[F[Response[F]], Option[
                              ConstructionPayloadsRequestMetadata
                            ]]
                          )
                        case None =>
                          blockIndexClient.requestLastTransactionMetadata(publicKey.toAddress).map { z =>
                            z.left
                              .map(errorMsg(5, _))
                              .map(maybePayloadMetadata => maybeMetadata.orElse(maybePayloadMetadata))
                          }
                      }

                      done.flatMap(
                        _.map(
                          _.map(
                            metadata =>
                              Ok(
                                ConstructionMetadataResponse(
                                  metadata,
                                  Some(List(Amount(metadata.fee.toString, DagCurrency, None)))
                                )
                              )
                          ).getOrElse(
                            errorMsg(6, "Unable to find reference to prior transaction in L1 or block index")
                          )
                        ).merge
                      )
                    }.merge)
                }
              }.merge
          }
        }

        endpointResponse.merge
      }

    case req @ POST -> Root / "construction" / "parse" =>
      req.decodeRosetta[ConstructionParseRequest] { constructionParseRequest =>
        val endpointResponse = for {
          _ <- validateNetwork(constructionParseRequest.networkIdentifier)
        } yield {
          validateHex(constructionParseRequest.transaction).map { h =>
            val bytes = h.toBytes
            if (constructionParseRequest.signed) {
              KryoSerializer[F]
                .deserialize[Signed[DAGTransaction]](bytes)
                .left
                .map(t => errorMsg(14, t.getMessage))
                .map { stx =>
                  val res =
                    stx.proofs.toNonEmptyList.toList.map(s => s.id.hex.toPublicKey.map(_.toAddress.value.value))
                  val folded = res.foldLeft(Async[F].delay(List[String]())) {
                    case (agg, next) =>
                      next.flatMap(n => agg.map(ls => ls.appended(n)))
                  }
                  folded.map { addresses =>
                    Ok(
                      ConstructionParseResponse(
                        // TODO: Verify what this status needs to be, because we don't know it at this point
                        translateDAGTransactionToOperations(
                          stx.value,
                          ChainObjectStatus.Unknown.toString,
                          ignoreStatus = true
                        ),
                        Some(addresses),
                        Some(addresses.map { a =>
                          AccountIdentifier(a, None, None)
                        }),
                        None
                      )
                    )
                  }.flatten
                }
                .merge
            } else {
              KryoSerializer[F]
                .deserialize[DAGTransaction](bytes)
                .left
                .map(t => errorMsg(14, t.getMessage))
                .map { tx =>
                  Ok(
                    ConstructionParseResponse(
                      // TODO: Verify what this status needs to be, because we don't know it at this point
                      translateDAGTransactionToOperations(
                        tx,
                        ChainObjectStatus.Unknown.toString,
                        ignoreStatus = true
                      ),
                      None,
                      None,
                      None
                    )
                  )
                }
                .merge
            }
          }.merge
        }

        endpointResponse.merge
      }

    case req @ POST -> Root / "construction" / "payloads" =>
      req.decodeRosetta[ConstructionPayloadsRequest] { constructionPayloadsRequest =>
        val endpointResponse = for {
          _ <- validateNetwork(constructionPayloadsRequest.networkIdentifier)
        } yield {
          constructionPayloadsRequest.metadata match {
            case None =>
              errorMsg(15, "Missing metadata containing last transaction parent reference", retriable = false)
            case Some(meta) => {
              for {
                unsignedTx <- Rosetta
                  .operationsToDAGTransaction(
                    meta.srcAddress,
                    meta.fee,
                    constructionPayloadsRequest.operations,
                    meta.lastTransactionHashReference,
                    meta.lastTransactionOrdinalReference,
                    meta.salt
                  )
                  .left
                  .map(e => errorMsg(0, e))
                serializedTx <- KryoSerializer[F]
                  .serialize(unsignedTx)
                  .left
                  .map(t => errorMsg(0, "Kryo serialization failure of unsigned transaction: " + t.getMessage))
                serializedTxHex = Hex.fromBytes(serializedTx)
                unsignedTxHash <- unsignedTx.hash.left.map(e => errorMsg(0, e.getMessage))
                toSignBytes = unsignedTxHash.value
                payloads = List(
                  SigningPayload(
                    Some(meta.srcAddress),
                    Some(AccountIdentifier(meta.srcAddress, None, None)),
                    toSignBytes,
                    Some(signatureTypeEcdsa)
                  )
                )
              } yield {
                Ok(ConstructionPayloadsResponse(serializedTxHex.value, payloads))
              }
            }.merge
          }
        }

        endpointResponse.merge
      }

    case req @ POST -> Root / "construction" / "preprocess" =>
      req.decodeRosetta[ConstructionPreprocessRequest] { constructionPreprocessRequest =>
        val endpointResponse = for {
          _ <- validateNetwork(constructionPreprocessRequest.networkIdentifier)
        } yield {
          // TODO: Multisig for multi-accounts here potentially.
          val sourceOperations = constructionPreprocessRequest.operations.filter(_.amount.exists(_.value.toLong < 0))
          if (sourceOperations.isEmpty) {
            errorMsg(0, "Missing source operation with outgoing transaction amount")
          } else {
            val requiredPublicKeys = Some(sourceOperations.flatMap { _.account }).filter(_.nonEmpty)
            // TODO: Right now the /metadata endpoint is actually grabbing the options directly
            // that logic should be moved here, but it doesn't matter much initially.
            val options = None
            Ok(ConstructionPreprocessResponse(options, requiredPublicKeys))
          }
        }

        endpointResponse.merge
      }

    case req @ POST -> Root / "construction" / "submit" =>
      req.decodeRosetta[ConstructionSubmitRequest] { constructionSubmitRequest =>
        val endpointResponse = for {
          _ <- validateNetwork(constructionSubmitRequest.networkIdentifier)
        } yield {
          validateHex(constructionSubmitRequest.signedTransaction).map { hex =>
            KryoSerializer[F]
              .deserialize[Signed[DAGTransaction]](hex.toBytes)
              .left
              .map(throwable => errorMsg(14, throwable.getMessage))
              .map { signedTransaction =>
                signedTransaction.hash.left
                  .map(err => errorMsg(0, f"Hash calculation failure: ${err.getMessage}"))
                  .map { hash =>
                    l1Client.submitTransaction(signedTransaction).flatMap { x =>
                      x.left
                        .map(err => errorMsg(0, err))
                        .map(_ => Ok(TransactionIdentifierResponse(TransactionIdentifier(hash.value), None)))
                        .merge
                    }
                  }
                  .merge
              }
              .merge
          }.merge
        }

        endpointResponse.merge
      }

    case req @ POST -> Root / "events" / "blocks" =>
      req.decodeRosetta[EventsBlocksRequest] { eventsBlocksRequest =>
        val endpointResponse = for {
          _ <- validateNetwork(eventsBlocksRequest.networkIdentifier)
        } yield {
          blockIndexClient
            .queryBlockEvents(eventsBlocksRequest.limit, eventsBlocksRequest.offset)
            .flatMap {
              // TODO: error code and abstract
              _.left
                .map(err => errorMsg(0, f"blockevents query failure: ${err}"))
                .map { snapshot =>
                  // TODO: handle block_removed
                  Rosetta
                    .convertSnapshotsToBlockEvents(snapshot, "block_added")
                    .left
                    .map(errorMsg(0, _))
                    .map { blockEvents =>
                      val maxSeq = blockEvents.map(_.sequence).max
                      Ok(EventsBlocksResponse(maxSeq, blockEvents))
                    }
                    .merge
                }
                .merge
            }
        }

        endpointResponse.merge
      }

    case req @ POST -> Root / "mempool" =>
      req.decodeRosetta[NetworkRequest] { networkRequest =>
        println(f"Mempool request ${networkRequest}")

        val endpointResponse = for {
          _ <- validateNetwork(networkRequest.networkIdentifier)
        } yield {
          l1Client
            .queryMempool()
            .flatMap { y =>
              val z = y.map(elem => TransactionIdentifier(elem))
              Ok(MempoolResponse(z))
            }
        }

        endpointResponse.merge
      }

    case req @ POST -> Root / "mempool" / "transaction" =>
      req.decodeRosetta[MempoolTransactionRequest] { mempoolTransactionRequest =>
        val endpointResponse = for {
          _ <- validateNetwork(mempoolTransactionRequest.networkIdentifier)
        } yield {
          val mempoolTransaction =
            l1Client.queryMempoolTransaction(mempoolTransactionRequest.transactionIdentifier.hash)

          mempoolTransaction.flatMap {
            // TODO: Enum
            _.map { signedTransaction =>
              val t = Rosetta.translateTransaction(signedTransaction, status = ChainObjectStatus.Pending.toString)

              t.map(transaction => Ok(MempoolTransactionResponse(transaction, None))).getOrElse(error(0))
            }.getOrElse(error(2))
          }
        }

        endpointResponse.merge
      }

    case req @ POST -> Root / "network" / "list" =>
      req.decodeRosetta[MetadataRequest] { metadataRequest =>
        println(f"network list request ${metadataRequest}")

        Ok(
          NetworkListResponse(
            List(
              NetworkIdentifier("dag", "mainnet", None),
              NetworkIdentifier("dag", "testnet", None)
            )
          )
        )
      }

    case req @ POST -> Root / "network" / "options" =>
      req.decodeRosetta[NetworkRequest] { networkRequest =>
        val endpointResponse = for {
          _ <- validateNetwork(networkRequest.networkIdentifier)
        } yield {
          l1Client
            .queryVersion()
            .flatMap(
              _.left
                .map(_ => errorMsg(0, "err"))
                .map(
                  version =>
                    Ok(
                      NetworkOptionsResponse(
                        Version("1.4.12", version, None, None),
                        Allow(
                          List(
                            OperationStatus(ChainObjectStatus.Pending.toString, successful = false),
                            OperationStatus(ChainObjectStatus.Unknown.toString, successful = false),
                            OperationStatus(ChainObjectStatus.Accepted.toString, successful = true)
                          ),
                          List(dagCurrencyTransferType),
                          errorCodes.map {
                            case (code, (description, extended)) =>
                              Error(code, description, Some(extended), retriable = true, None)
                          }.toList,
                          historicalBalanceLookup = false,
                          None,
                          List(),
                          // TODO: Verify no balance exemptions
                          List(),
                          mempoolCoins = false,
                          Some("lower_case"),
                          Some("lower_case")
                        )
                      )
                    )
                )
                .merge
            )
        }

        endpointResponse.merge
      }

    case req @ POST -> Root / "network" / "status" =>
      req.decodeRosetta[NetworkRequest] { networkRequest =>
        println(f"network status request ${networkRequest}")

        l1Client
          .queryNetworkStatus()
          .flatMap { statusF =>
            val response = for {
              _ <- validateNetwork(networkRequest.networkIdentifier)
              status <- statusF.left
                .map(err => errorMsg(0, err))
            } yield {
              Ok(status)
            }
            response.merge
          }
      }

    case req @ POST -> Root / "search" / "transactions" =>
      req.decodeRosetta[SearchTransactionsRequest] { searchTransactionsRequest =>
        val endpointResponse = for {
          _ <- validateNetwork(searchTransactionsRequest.networkIdentifier)
        } yield {
          // TODO: Enum
          val isOr = searchTransactionsRequest.operator.contains("or")
          val isAnd = searchTransactionsRequest.operator.contains("and")

          // TODO: Throw error on subaccountidentifier not supported.
          val account2 = searchTransactionsRequest.accountIdentifier.map(_.address)
          val accountOpt = Seq(searchTransactionsRequest.address, account2).filter(_.nonEmpty).head

          // TODO: Throw error on `type` not supported, coin, currency not supported
          val networkStatus = searchTransactionsRequest.status

          blockIndexClient
            .searchBlocks(
              BlockSearchRequest(
                isOr,
                isAnd,
                accountOpt,
                networkStatus,
                searchTransactionsRequest.limit,
                searchTransactionsRequest.offset,
                searchTransactionsRequest.transactionIdentifier.map(_.hash),
                searchTransactionsRequest.maxBlock
              )
            )
            .flatMap(
              _.left
                .map(errorMsg(0, _))
                .map { res =>
                  val value = res.transactions.map { tx =>
                    translateTransaction(tx.transaction).left
                      .map(InternalServerError(_))
                      .map(txR => List(BlockTransaction(BlockIdentifier(tx.blockIndex, tx.blockHash), txR)))
                  }
                  reduceListEither(value).map { bt =>
                    Ok(SearchTransactionsResponse(bt, res.total, res.nextOffset))
                  }.merge
                }
                .merge
            )
        }

        endpointResponse.merge
      }
  }

  def extractTransactions(snapshot: GlobalSnapshot) = {
    import eu.timepit.refined.auto._
    import org.tessellation.schema.transaction._

    val genesisTxs = if (snapshot.height.value.value == 0L) {
      snapshot.info.balances.toList.map {
        case (balanceAddress, balance) =>
          // TODO: Empty transaction translator
          Signed(
            DAGTransaction(
              Address("DAG2EUdecqFwEGcgAcH1ac2wrsg8acrgGwrQabcd"),
              balanceAddress,
              TransactionAmount(PosLong.from(balance.value).toOption.get),
              TransactionFee(0L),
              TransactionReference(
                TransactionOrdinal(0L),
                Hash(examples.sampleHash)
              ),
              TransactionSalt(0L)
            ),
            proofs
          )
      }
    } else List()

    val blockTransactions = snapshot.blocks.toList
      .flatMap(x => x.block.value.transactions.toNonEmptyList.toList)

    val rewardTransactions = snapshot.rewards.toList.map { rw =>
      Signed(
        DAGTransaction(
          Address("DAG2EUdecqFwEGcgAcH1ac2wrsg8acrgGwrQabcd"),
          rw.destination,
          rw.amount,
          TransactionFee(0L),
          TransactionReference(
            TransactionOrdinal(0L),
            Hash(examples.sampleHash)
          ),
          TransactionSalt(0L)
        ),
        proofs
      )
    }

    blockTransactions.map(translateTransaction(_)) ++ (rewardTransactions ++ genesisTxs).map(
      translateTransaction(_, includeNegative = false)
    )
  }

  val allRoutes: HttpRoutes[F] = testRoutes <+> routes

}
