package org.tessellation.schema

import java.security.PublicKey

import cats.effect.Async
import cats.syntax.functor._

import org.tessellation.schema.address.Address
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hex.Hex
import org.tessellation.security.key.ops._

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.estatico.newtype.macros.newtype

object ID {

  @derive(decoder, encoder, eqv, show, order)
  @newtype
  case class Id(hex: Hex) {
    def toPublicKey[F[_]: Async: SecurityProvider]: F[PublicKey] = hex.toPublicKey

    def toAddress[F[_]: Async: SecurityProvider]: F[Address] = toPublicKey.map(_.toAddress)
  }
}
