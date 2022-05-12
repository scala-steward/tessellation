package org.tessellation.rosetta.server
import cats.{Applicative, MonadThrow}
import cats.data.NonEmptyList
import cats.effect.unsafe.implicits.global
import cats.effect.{Async, IO}
import cats.implicits.toSemigroupKOps
import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.rosetta.server.model._
import org.tessellation.rosetta.server.model.dag.schema.{ChainObjectStatus, ConstructionMetadataResponseMetadata, ErrorDetailKeyValue, ErrorDetails}
import org.tessellation.schema.address.{Address, DAGAddress, DAGAddressRefined}
import org.tessellation.schema.transaction.{TransactionAmount, TransactionFee, TransactionReference, Transaction => DAGTransaction}
import org.tessellation.sdk.config.types.HttpServerConfig
import org.tessellation.sdk.resources.MkHttpServer
import org.tessellation.sdk.resources.MkHttpServer.ServerName
import org.tessellation.security.hex.Hex
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.SignatureProof
import org.tessellation.security.{Hashable, SecurityProvider}
import org.tessellation.shared.sharedKryoRegistrar
import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.types.all.PosLong
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.Decoder
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpApp, HttpRoutes, Response, _}
import org.tessellation.schema.address
import org.tessellation.security.hash.Hash
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.util.Try


case class LastTransactionResponse(
                                    constructionMetadataResponseMetadata: Option[ConstructionMetadataResponseMetadata],
                                    suggestedFee: Option[Long]
                                  )

// Mockup of real client
class DagL1APIClient(val endpoint: String) {

  def requestSuggestedFee(): Either[String, Option[Long]] = {
    Right(Some(123))
  }

  def requestLastTransactionMetadataAndFee(addressActual: address.Address):
  Either[String, LastTransactionResponse] = {
    Either.cond(addressActual.value.value == examples.address,
      LastTransactionResponse(
        Some(ConstructionMetadataResponseMetadata(examples.sampleHash, 123)), Some(456)
      ), "exception from l1 client query"
    )
  }

  def queryMempool(): List[String] =
    List(examples.sampleHash)

  def queryMempoolTransaction(hash: String): Option[Signed[DAGTransaction]] =
    if (hash == examples.sampleHash) Some(examples.transaction)
    else None

}

case class AccountBlockResponse(
                                 amount: Long,
                                 snapshotHash: String,
                                 height: Long
                               )

// Mockup of real client
class BlockIndexClient(val endpoint: String) {


  // This also has to request from L1 in case the indexer doesn't have it
  def requestLastTransactionMetadata(addressActual: address.Address):
  Either[String, Option[ConstructionMetadataResponseMetadata]] = {
    Either.cond(addressActual.value.value == examples.address,
      Some(ConstructionMetadataResponseMetadata(examples.sampleHash, 123)),
      "exception from l1 client query")
  }

  def queryBlockTransaction(blockIdentifier: BlockIdentifier): Either[String, Option[Signed[DAGTransaction]]] =
    Either.cond(
      test = blockIdentifier.index == 0 ||
        blockIdentifier.hash.contains(examples.sampleHash),
      Some(examples.transaction),
      "err"
    )

  def queryBlock(blockIdentifier: PartialBlockIdentifier): Either[String, Option[GlobalSnapshot]] =
    Either.cond(
      test =
        blockIdentifier.index.contains(0) ||
          blockIdentifier.hash.contains(examples.sampleHash),
      Some(examples.snapshot),
      "err"
    )

  def queryAccountBalance(
                           address: String,
                           blockIndex: Option[PartialBlockIdentifier]
                         ): Either[String, Option[AccountBlockResponse]] =
    Either.cond(
      test = address == examples.address,
      Some(AccountBlockResponse(123457890, examples.sampleHash, 1)),
      "some error"
    )

}
import cats.syntax.flatMap._
import cats.syntax.functor._

//import io.circe.generic.extras.Configuration
//import io.circe.generic.extras.auto._
import org.tessellation.rosetta.server.model.dag.decoders._

import java.security.{PublicKey => JPublicKey}

object Rosetta {


  import cats.effect._


  def convertSignature[F[_]: KryoSerializer: SecurityProvider: Async](
                                                                       signature: List[Signature]
                                                                     ): F[Either[Error, NonEmptyList[SignatureProof]]] = {
    val value = signature.map { s =>
      // TODO: Handle hex validation here
      val value1 = Hex(s.publicKey.hexBytes).toPublicKey(Async[F], SecurityProvider[F])
      value1.map { pk =>
        s.signatureType match {
          case "ecdsa" =>
            Right({
              // TODO, validate curve type.
              SignatureProof(pk.toId, org.tessellation.security.signature.signature.Signature(Hex(s.hexBytes)))
            })
          case x => Left(makeErrorCodeMsg(10, s"${x} not supported"))
        }
      }
    }
    val zero: Either[Error, List[SignatureProof]] = Right[Error, List[SignatureProof]](List())
    value
      .foldLeft(Async[F].delay(zero)) {
        case (agga, nextFEither) =>
          val inner2 = agga.flatMap { agg =>
            val inner = nextFEither.map { eitherErrSigProof =>
              val res = eitherErrSigProof.map { sp =>
                agg.map(ls => ls ++ List(sp))
              }
              var rett: Either[Error, List[SignatureProof]] = null
              // TODO: Better syntax
              if (res.isRight) {
                val value2 = res.toOption.get
                val zz = value2.getOrElse(List())
                if (zz.nonEmpty) {
                  rett = Right(zz)
                } else {
                  rett = Left(value2.swap.toOption.get)
                }
              } else {
                rett = Left(res.swap.toOption.get)
              }
              rett
            }
            inner
          }
          inner2
      }
      .map(_.map(q => NonEmptyList(q.head, q.tail)))
  }

  // TODO: From config / and/or arg
  val endpoints = Map(
    "mainnet" -> "localhost:8080",
    "testnet" -> "localhost:8080"
  )
  val signatureTypeEcdsa = "ecdsa"


  val errorCodes = Map(
    0 -> ("Unknown internal error", ""),
    1 -> ("Unsupported network", "Unable to route request to specified network or network not yet supported"),
    2 -> ("Unknown hash", "Unable to find reference to hash"),
    3 -> ("Invalid request", "Unable to decode request"),
    4 -> ("Malformed address", "Address is invalidly formatted or otherwise un-parseable"),
    5 -> ("Block service failure", "Request to block service failed"),
    6 -> ("Unknown address", "No known reference to address"),
    7 -> ("Unknown transaction", "No known reference to transaction"),
    8 -> ("Unsupported operation", "Operation not supported"),
    9 -> ("Malformed transaction", "Unable to decode transaction"),
    10 -> ("Unsupported signature type", "Cannot translate signature"),
    11 -> ("Hex decode failure", "Unable to parse hex to bytes"),
    12 -> ("Unsupported curve type", "Curve type not available for use"),
    13 -> ("L1 service failure", "Request to L1 service failed"),
    14 -> ("Deserialization failure", "Unable to deserialize class to kryo type"),
    15 -> ("Malformed request", "Missing required information or necessary fields")
  )

  def makeErrorCode(code: Int, retriable: Boolean = true, details: Option[ErrorDetails] = None): Error = {
    val (message, description) = errorCodes(code)
    Error(
      code,
      message,
      Some(description),
      retriable,
      details
    )
  }

  def makeErrorCodeMsg(code: Int, message: String, retriable: Boolean = true): Error =
    makeErrorCode(code, retriable, Some(ErrorDetails(List(ErrorDetailKeyValue("exception", message)))))

  implicit class RefinedRosettaRequestDecoder[F[_]: JsonDecoder: MonadThrow](req: Request[F]) extends Http4sDsl[F] {
    import cats.syntax.applicativeError._
    import cats.syntax.flatMap._

    def decodeRosetta[A: Decoder](f: A => F[Response[F]]): F[Response[F]] =
      req.asJsonDecode[A].attempt.flatMap {
        case Left(e) =>
          Option(e.getCause) match {
            case Some(c) =>
              InternalServerError(
                makeErrorCode(
                  3,
                  retriable = false,
                  Some(ErrorDetails(List(ErrorDetailKeyValue("exception", c.getMessage))))
                )
              )
            case _ => InternalServerError(makeErrorCode(3, retriable = false))
          }
        case Right(a) => f(a)
      }
  }

  // TODO Confirm 1e8 is correct multiplier
  val DagCurrency: Currency = Currency("DAG", 1e8.toInt, None)

  def reverseTranslateTransaction(t: DAGTransaction): Unit = {}

  val dagCurrencyType = "CURRENCY"

  def operationsToDAGTransaction(
                                  src: String,
                                  fee: Long,
                                  operations: List[Operation],
                                  parentHash: String,
                                  parentOrdinal: Long,
                                  salt: Option[Long]
                                ) = {
    // TODO: Same question here as below, one or two operations?
    val operationPositive = operations.head
    val amount = operationPositive.amount.get.value.toLong
    val destination = operationPositive.account.get.address
    import eu.timepit.refined.auto._
    val srcA = Address(src)
    // TODO: Validate at higher level
    //import DAGAddressRefined._
    //val srcA = addressCorrectValidate.validate(src).fold(identity, _ => Address(examples.transaction.)

    DAGTransaction(
      srcA,
      Address(destination),
      TransactionAmount(PosLong(amount)),
      TransactionFee(NonNegLong(fee)),
      TransactionReference(Hash(""), TransactionOrdinal())


    )
  }

  def translateDAGTransactionToOperations(tx: DAGTransaction, status: String): List[Operation] = {
    // TODO: Huge question, do we need to represent another operation for the
    // negative side of this transaction?
    // if so remove list type.
    val operation = Operation(
      OperationIdentifier(0, None),
      None,
      dagCurrencyType, // TODO: Replace with enum
      Some(status), // TODO: Replace with enum
      Some(AccountIdentifier(tx.destination.value.value, None, None)),
      Some(
        Amount(
          tx.amount.value.toString(),
          DagCurrency,
          None
        )
      ),
      None,
      None
    )
    List(operation)
  }

  def translateTransaction[F[_]: KryoSerializer](
                                                  dagTransaction: Signed[DAGTransaction],
                                                  status: String = ChainObjectStatus.Accepted.toString
                                                ): Either[Error, Transaction] = {
    // Is this the correct hash reference here? Are we segregating the signature data here?
    import org.tessellation.ext.crypto._

    val tx = dagTransaction.value
    tx.hash.left.map(_ => makeErrorCode(0)).map { hash =>
      model.Transaction(
        TransactionIdentifier(hash.value),
        translateDAGTransactionToOperations(tx, status),
        None,
        None
      )
    }
  }
}

import org.tessellation.rosetta.server.Rosetta._

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
final case class RosettaRoutes[F[_]: Async: KryoSerializer: SecurityProvider]() extends Http4sDsl[F] {

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

  // TODO: Change signature to pass network identifier directly
  def validateNetwork[T](t: T, NRA: T => NetworkIdentifier, f: (String) => F[Response[F]]): F[Response[F]] = {
    val networkIdentifier = NRA(t)
    networkIdentifier.blockchain match {
      case "dag" =>
        networkIdentifier.network match {
          case x if endpoints.contains(x) =>
            f(endpoints(x))
          case _ =>
            InternalServerError(makeErrorCode(1))
        }
      case _ => InternalServerError(makeErrorCode(1))
      // TODO: Error for invalid subnetwork unsupported.
    }
  }

  def validateAddress[T](request: T, requestToAddress: T => String, f: (String) => F[Response[F]]): F[Response[F]] = {
    val address = requestToAddress(request)
    if (DAGAddressRefined.addressCorrectValidate.isValid(address)) {
      f(address)
    } else errorMsg(4, address)
  }

  def validateCurveType(curveType: String): Either[F[Response[F]], Unit] = {
    curveType match {
      case "secp256k1" => Right(())
      case _ => Left(errorMsg(12, curveType, retriable=false))
    }
  }

  def validateHex(hexInput: String): Either[F[Response[F]], Hex] = {
    Try{Hex(hexInput).toBytes}.toEither.left.map( e =>
      errorMsg(11, s"hexInput: ${hexInput}, error: ${e.getMessage}",
        retriable=false)
    ).map(_ => Hex(hexInput))
  }

  def convertRosettaPublicKeyToJPublicKey(rosettaPublicKey: PublicKey):
  Either[F[Response[F]], F[JPublicKey]] = {
    validateCurveType(rosettaPublicKey.curveType) match {
      case Left(e) => Left(e)
      case Right(_) => validateHex(rosettaPublicKey.hexBytes) match {
        case Left(e) => Left(e)
        case Right(v) => Right(v.toPublicKey(Async[F], SecurityProvider[F]))
      }
    }
  }

  private val routes: HttpRoutes[F] = HttpRoutes.of[F] {

    case req @ POST -> Root / "account" / "balance" => {
      req.decodeRosetta[AccountBalanceRequest] { r =>
        validateNetwork[AccountBalanceRequest](
          r,
          _.networkIdentifier, { x =>
            validateAddress[AccountBalanceRequest](
              r, // TODO: Error on accountIdentifier subaccount not supported.
              _.accountIdentifier.address, { address =>
                new BlockIndexClient(x)
                  .queryAccountBalance(address, r.blockIdentifier)
                  .left
                  .map(e => errorMsg(5, e))
                  .map(
                    o =>
                      o.map(
                        a =>
                          Ok(
                            AccountBalanceResponse(
                              BlockIdentifier(a.height, a.snapshotHash),
                              // TODO: Enum
                              List(Amount(a.amount.toString, DagCurrency, None)),
                              None
                            )
                          )
                      )
                        .getOrElse(errorMsg(6, address))
                  )
                  .fold(identity, identity)
              }
            )
          }
        )
      }
    }

    case req @ POST -> Root / "account" / "coins" => {
      errorMsg(0, "UTXO endpoints not implemented")
    }

    case req @ POST -> Root / "block" => {
      req.decodeRosetta[BlockRequest] { br =>
        validateNetwork[BlockRequest](
          br,
          _.networkIdentifier, { (x) =>
            val value = new BlockIndexClient(x).queryBlock(br.blockIdentifier).map { ogs =>
              val inner = ogs.map {
                gs =>
                  gs.hash.left
                    .map(t => errorMsg(0, "Hash calculation on snapshot failure: " + t.getMessage))
                    .map { gsHash =>
                      val translatedTransactions = gs.blocks
                        .map(_.block.value.transactions.head)
                        .map(t => translateTransaction(t))
                        .toList
                      if (translatedTransactions.exists(_.isLeft)) {
                        errorMsg(0, "Internal transaction translation failure")
                      } else {
                        val txs = translatedTransactions.map(_.toOption.get)
                        Ok(
                          BlockResponse(
                            Some(
                              Block(
                                BlockIdentifier(gs.height.value, gsHash.value),
                                BlockIdentifier(gs.height.value - 1, gs.lastSnapshotHash.value),
                                // TODO: Timestamp??
                                0L,
                                txs,
                                None
                              )
                            ),
                            None
                          )
                        )
                      }
                    }
                    .merge
              }
              inner.getOrElse(Ok(BlockResponse(None, None)))
            }
            value.left.map(e => errorMsg(5, e)).merge
          }
        )
      }
    }

    case req @ POST -> Root / "block" / "transaction" => {
      req.decodeRosetta[BlockTransactionRequest] { br =>
        validateNetwork[BlockTransactionRequest](
          br,
          _.networkIdentifier, { (x) =>
            val value = new BlockIndexClient(x).queryBlockTransaction(br.blockIdentifier)
            value.left
              .map(errorMsg(5, _))
              .map(
                t =>
                  t.map(tt => Ok(BlockTransactionResponse(translateTransaction(tt).toOption.get))).getOrElse(error(7))
              )
              .merge
          }
        )
      }
    }

    case req @ POST -> Root / "call" => {
      req.decodeRosetta[CallRequest] { br =>
        validateNetwork[CallRequest](
          br,
          _.networkIdentifier, { (x) =>
            // TODO: is any implementation here required yet?
            // this doesn't need to be required yet unless we have custom logic
            // Ok(CallResponse(CallResponseActual(), idempotent = true))
            error(8)
          }
        )
      }
    }

    case req @ POST -> Root / "construction" / "combine" => {
      req.decodeRosetta[ConstructionCombineRequest] { br =>
        validateNetwork[ConstructionCombineRequest](
          br,
          _.networkIdentifier, { (x) =>
            // TODO combine multiple signatures yet supported?
            if (br.signatures.size > 1) {
              error(8)
            } else if (br.signatures.isEmpty) {
              errorMsg(3, "No signatures found")
            } else {
              val res = KryoSerializer[F]
                .deserialize[DAGTransaction](
                  Hex(br.unsignedTransaction).toBytes // TODO: Handle error here for invalid hex
                )
                .left
                .map(_ => error(9))
                .map { t: DAGTransaction =>
                  val value = Rosetta.convertSignature(br.signatures)
                  value.flatMap { v =>
                    v.left
                      .map(InternalServerError(_))
                      .map { prf =>
                        val ser = KryoSerializer[F]
                          .serialize(Signed[DAGTransaction](t, prf))
                          .left
                          .map(e => errorMsg(0, "Serialize transaction failure: " + e.getMessage))
                          .map(s => Ok(ConstructionCombineResponse(Hex.fromBytes(s).value)))
                        ser.merge
                      }
                      .merge
                  }
                }
              res.merge
            }
          }
        )
      }
    }

    case req @ POST -> Root / "construction" / "derive" => {
      req.decodeRosetta[ConstructionDeriveRequest] { br =>
        validateNetwork[ConstructionDeriveRequest](
          br,
          _.networkIdentifier, { (x) =>
            convertRosettaPublicKeyToJPublicKey(br.publicKey).map{ inner =>
              inner.flatMap { pk =>
                val value = pk.toAddress.value.value
                Ok(ConstructionDeriveResponse(Some(value), Some(AccountIdentifier(value, None, None)), None))
              }
            }.merge
          }
        )
      }
    }

    case req @ POST -> Root / "construction" / "hash" => {
      req.decodeRosetta[ConstructionHashRequest] { br =>
        validateNetwork[ConstructionHashRequest](
          br,
          _.networkIdentifier, { (x) =>
            val ser = KryoSerializer[F]
              .deserialize[Signed[DAGTransaction]](Hex(br.signedTransaction).toBytes)
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
            ser
          }
        )
      }
    }
    // TODO:

    case req @ POST -> Root / "construction" / "metadata" => {
      req.decodeRosetta[ConstructionMetadataRequest] { br =>
        validateNetwork[ConstructionMetadataRequest](br, _.networkIdentifier, { endpoint =>
          if (br.options.isDefined) {
            errorMsg(8, "Custom options not supported")
          }
          br.publicKeys match {
            case x if  x.isEmpty => errorMsg(8, "Must provide public key")
            case x if x.size > 1 => errorMsg(8, "Multiple public keys not supported")
            case Some(x) => {
              val key = x.head
              convertRosettaPublicKeyToJPublicKey(key).map { inner =>
                val res = inner.flatMap { pk =>
                  val address = pk.toAddress
                  val resp = new DagL1APIClient(endpoint)
                    .requestLastTransactionMetadataAndFee(address)
                    .left.map(e => errorMsg(13, e))
                  val withFallback = resp.map { l =>
                    val done = l.constructionMetadataResponseMetadata match {
                      case Some(_) => Right(l)
                      case None => new BlockIndexClient(endpoint)
                        .requestLastTransactionMetadata(address)
                        .left.map(e => errorMsg(5, e))
                        .map(cmrm => l.copy(constructionMetadataResponseMetadata = cmrm))
                    }
                    done.map { r =>
                      r.constructionMetadataResponseMetadata.map( cmrm =>
                        Ok(ConstructionMetadataResponse(cmrm, r.suggestedFee.map(f => List(Amount(f.toString, DagCurrency, None)))))
                      ).getOrElse(errorMsg(6, "Unable to find reference to prior transaction in L1 or block index"))
                    }.merge
                  }.merge
                  withFallback
                }
                res
              }
            }.merge
            case None => errorMsg(8, "No public keys provided, required for construction")
          }
        })
      }
    }

    case req @ POST -> Root / "construction" / "parse" => {
      req.decodeRosetta[ConstructionParseRequest] { br =>
        validateNetwork[ConstructionParseRequest](br, _.networkIdentifier, { _ =>
          validateHex(br.transaction).map{ h =>
            val bytes = h.toBytes
            if (br.signed) {
              KryoSerializer[F].deserialize[Signed[DAGTransaction]](bytes)
                .left.map(t => errorMsg(14, t.getMessage))
                .map{ stx =>
                  val res = stx.proofs.map(s => s.id.hex.toPublicKey.map(_.toAddress.value.value)).toList
                  val folded = res.foldLeft(Async[F].delay(List[String]())) {
                    case (agg, next) =>
                      next.flatMap(n => agg.map(ls => ls.appended(n)))
                  }
                  folded.map { addresses =>
                    Ok(ConstructionParseResponse(
                      // TODO: Verify what this status needs to be, because we don't know it at this point
                      // Do we need an API call here?
                      translateDAGTransactionToOperations(stx.value, ChainObjectStatus.Unknown.toString),
                      Some(addresses),
                      Some(addresses.map{a => AccountIdentifier(a, None, None)}),
                      None
                    ))
                  }.flatten
                }.merge
            } else {
              KryoSerializer[F].deserialize[DAGTransaction](bytes)
                .left.map(t => errorMsg(14, t.getMessage))
                .map{ tx =>
                  Ok(ConstructionParseResponse(
                    // TODO: Verify what this status needs to be, because we don't know it at this point
                    // Do we need an API call here?
                    translateDAGTransactionToOperations(tx, ChainObjectStatus.Unknown.toString),
                    None,
                    None,
                    None
                  ))
                }.merge
            }
          }.merge
        })
      }
    }

    case req @ POST -> Root / "construction" / "payloads" => {
      req.decodeRosetta[ConstructionPayloadsRequest] { br =>
        validateNetwork[ConstructionPayloadsRequest](br, _.networkIdentifier, { (x) =>
          br.metadata match {
            case None =>
              errorMsg(15, "Missing metadata containing last transaction parent reference", retriable = false)
            case Some(meta) => {
              val tx = Rosetta.operationsToDAGTransaction(
                meta.srcAddress, meta.fee, br.operations, meta.lastTransactionHashReference,
                meta.lastTransactionOrdinalReference, meta.salt
              )
              KryoSerializer[F].serialize(tx)
                .left.map(t => errorMsg(0,
                "Kryo serialization failure of unsigned transaction: " + t.getMessage))
                .map(b => Hex.fromBytes(b))
                .map{hex =>
                  val payloads = List(
                    SigningPayload(
                      Some(meta.srcAddress),
                      Some(AccountIdentifier(meta.srcAddress, None, None)),
                      tx.toEncode,
                      Some(signatureTypeEcdsa)
                    )
                  )
                  Ok(ConstructionPayloadsResponse(hex.value, payloads))
                }.merge
            }
          }
        })
      }
    }

//
//        case req @ POST -> Root / "construction" / "preprocess" => {
//          req.decodeRosetta[ConstructionPreprocessRequest] { br =>
//            validateNetwork[ConstructionPreprocessRequest](br, _.networkIdentifier, { (x) =>
//              ConstructionPreprocessResponse()
//            }
//          }
//        }

    //    case req @ POST -> Root / "construction" / "submit" => {
    //      req.decodeRosetta[ConstructionMetadataRequest] { br =>
    //        validateNetwork[ConstructionMetadataRequest](br, _.networkIdentifier, { (x) =>
    //          ConstructionMetadataResponse()
    //        }
    //      }
    //    }

    //    case req @ POST -> Root / "events" / "blocks" => {
    //      req.decodeRosetta[ConstructionMetadataRequest] { br =>
    //        validateNetwork[ConstructionMetadataRequest](br, _.networkIdentifier, { (x) =>
    //          ConstructionMetadataResponse()
    //        }
    //      }
    //    }

    case req @ POST -> Root / "mempool" => {
      req.decodeRosetta[NetworkRequest] { NR =>
        validateNetwork[NetworkRequest](NR, _.networkIdentifier, { (x) =>
          val value = new DagL1APIClient(x)
            .queryMempool()
            .map(v => TransactionIdentifier(v))
          Ok(MempoolResponse(value))
        })
      }(NetworkRequestDecoder)
    }
    case req @ POST -> Root / "mempool" / "transaction" => {
      req.decodeRosetta[MempoolTransactionRequest] { NR =>
        validateNetwork[MempoolTransactionRequest](
          NR,
          _.networkIdentifier, { (x) =>
            val value = new DagL1APIClient(x).queryMempoolTransaction(NR.transactionIdentifier.hash)
            value.map { v =>
              // TODO: Enum
              val t: Either[Error, Transaction] =
                Rosetta.translateTransaction(v, status = ChainObjectStatus.Pending.toString)
              t.map { tt =>
                Ok(MempoolTransactionResponse(tt, None))
              }.getOrElse(error(0))
            }.getOrElse(error(2))
          }
        )
      }
    }
    case req @ POST -> Root / "network" / "list" => {
      req.decodeRosetta[MetadataRequest] { br =>
        Ok(
          NetworkListResponse(
            List(
              NetworkIdentifier("dag", "mainnet", None),
              NetworkIdentifier("dag", "testnet", None)
            )
          )
        )
      }
    }
    //    case req @ POST -> Root / "network" / "options" => {
    //      req.decodeRosetta[ConstructionMetadataRequest] { br =>
    //        validateNetwork[ConstructionMetadataRequest](br, _.networkIdentifier, { (x) =>
    //          ConstructionMetadataResponse()
    //        }
    //      }
    //    }
    //    case req @ POST -> Root / "network" / "status" => {
    //      req.decodeRosetta[ConstructionMetadataRequest] { br =>
    //        validateNetwork[ConstructionMetadataRequest](br, _.networkIdentifier, { (x) =>
    //          ConstructionMetadataResponse()
    //        }
    //      }
    //    }
    //
    //        case req @ POST -> Root / "search" / "transactions" => {
    //          req.decodeRosetta[SearchTransactionsRequest] { br =>
    //            validateNetwork[SearchTransactionsRequest](br, _.networkIdentifier, { (x) =>
    //              val client = new BlockIndexClient(x)
    //              // TODO: Enum
    //              val isOr = br.operator.contains("or")
    //              val isAnd = br.operator.contains("and")
    //              // TODO: Throw error on subaccountidentifier not supported.
    //              val account2 = br.accountIdentifier.map(_.address)
    //              val accountOpt = Seq(br.address, account2).filter(_.nonEmpty).head
    //              // TODO: Throw error on `type` not supported, coin, currency not supported
    //              br.transactionIdentifier
    //              val networkStatus = br.status
    //              br.success
    //              case class BlockSearchRequest(
    //                                             isOr: Boolean,
    //                                             isAnd: Boolean,
    //                                             addressOpt: Option[String],
    //                                             networkStatus: NetworkChainObjectStatus,
    //                                             limit: Option[Long],
    //                                             offset: Option[Long],
    //                                             transactionHash: Option[String],
    //                                             maxBlock: Option[String],
    //                                           )
    //
    //              ConstructionMetadataResponse()
    //            }
    //          }
    //        }

  }

  val allRoutes: HttpRoutes[F] = testRoutes <+> routes

}

// Temporary
object runner {

  def main(args: Array[String]): Unit = {

    implicit val logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]
    import org.tessellation.ext.kryo._
    val registrar = org.tessellation.dag.dagSharedKryoRegistrar.union(sharedKryoRegistrar)
    SecurityProvider
      .forAsync[IO]
      .flatMap { implicit sp =>
        KryoSerializer
          .forAsync[IO](registrar)
          .flatMap { implicit kryo =>
            println(
              "Example hex unsigned transaction: " + Hex
                .fromBytes(kryo.serialize(examples.transaction.value).toOption.get)
                .value
            )
            println(
              "Example hex signed transaction: " + Hex
                .fromBytes(kryo.serialize(examples.transaction).toOption.get)
                .value
            )
            val http = new RosettaRoutes[IO]()(Async[IO], kryo, sp)
            val publicApp: HttpApp[IO] = http.allRoutes.orNotFound
            //loggers(openRoutes.orNotFound)
            MkHttpServer[IO].newEmber(
              ServerName("public"),
              HttpServerConfig(Host.fromString("0.0.0.0").get, Port.fromInt(8080).get),
              publicApp
            )
          }
      }
      .useForever
      .unsafeRunSync()
  }
}
