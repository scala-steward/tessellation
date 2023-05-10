package org.tessellation.schema

import cats.effect.Async
import cats.syntax.functor._

import org.tessellation.security.SecureRandom

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.estatico.newtype.macros.newtype

object salt {

  @derive(decoder, encoder, order, show)
  @newtype
  case class Salt(value: Long)

  object Salt {
    def generate[F[_]: Async]: F[Salt] =
      SecureRandom
        .get[F]
        .map(_.nextLong())
        .map(Salt.apply)
  }
}
