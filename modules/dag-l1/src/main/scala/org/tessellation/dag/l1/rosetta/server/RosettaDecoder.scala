package org.tessellation.dag.l1.rosetta.server

import cats.MonadThrow
import cats.implicits.toFunctorOps
import io.circe.Decoder
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.{Request, Response}
import org.http4s.circe.{JsonDecoder, toMessageSyntax}
import org.http4s.dsl.Http4sDsl
import org.tessellation.dag.l1.rosetta.server.Error.{makeErrorCode, makeErrorCodeMsg}
import org.tessellation.rosetta.server.model.dag.schema.{ErrorDetailKeyValue, ErrorDetails}
import org.tessellation.rosetta.server.model.dag.decoders._

import scala.util.Try

object RosettaDecoder {

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
        case Right(a) => {
          println("Incoming rosetta request " + a)
          // TODO: Wrap either left map in a generic error handle implicit.
          val res = Try { f(a) }.toEither.left
            .map(t => InternalServerError(makeErrorCodeMsg(0, t.getMessage, retriable = true)))
            .merge
            .handleErrorWith(t => {
              InternalServerError(makeErrorCodeMsg(0, t.getMessage, retriable = true))
            })
          res.flatMap { r =>
            r.asJson.map(_ => r)
          }
        }
      }
  }

}
