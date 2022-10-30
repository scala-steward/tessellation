package org.tessellation.dag.l1.rosetta.server

import cats.data.NonEmptyList
import cats.effect.Async
import cats.implicits.{toFlatMapOps, toFunctorOps}
import org.tessellation.dag.l1.rosetta.server.Error.makeErrorCodeMsg
import org.tessellation.kryo.KryoSerializer
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.signature.SignatureProof
import org.tessellation.rosetta.server.model.{Error => RosettaError, Signature => RosettaSignature}
import org.tessellation.security.hex.Hex
import org.tessellation.dag.l1.rosetta.Util
import org.tessellation.security.key.ops.PublicKeyOps

object RosettaSignatureUtilities {

  def convertSignature[F[_]: KryoSerializer: SecurityProvider: Async](
                                                                                                signature: List[RosettaSignature]
                                                                                              ): F[Either[RosettaError, NonEmptyList[SignatureProof]]] = {
  val value = signature.map { s =>
    // TODO: Handle hex validation here
    val value1 = Async[F].delay(Util.getPublicKeyFromBytes(Hex(s.publicKey.hexBytes).toBytes))
    // TODO: TRy here .asInstanceOf[JPublicKey])}.toEither
    //      .left.map(e => makeErrorCodeMsg(0, e.getMessage))
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
  val zero: Either[RosettaError, List[SignatureProof]] = Right[RosettaError, List[SignatureProof]](List())
  value
    .foldLeft(Async[F].delay(zero)) {
      case (agga, nextFEither) =>
        val inner2 = agga.flatMap { agg =>
          val inner = nextFEither.map { eitherErrSigProof =>
            val res = eitherErrSigProof.map { sp =>
              agg.map(ls => ls ++ List(sp))
            }
            var rett: Either[RosettaError, List[SignatureProof]] = null
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

}
