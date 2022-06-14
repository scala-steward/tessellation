package org.tessellation.dag.snapshot

import cats.Order
import cats.kernel._
import cats.syntax.contravariant._
import cats.syntax.semigroup._

import org.tessellation.schema._

import derevo.cats.show
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong
import io.estatico.newtype.macros.newtype

object epoch {

  @derive(encoder, decoder, show)
  @newtype
  case class EpochProgress(value: NonNegLong)

  object EpochProgress {
    val MinValue: EpochProgress = EpochProgress(NonNegLong.MinValue)

    implicit val eqv: Eq[EpochProgress] = Eq[NonNegLong].contramap(_.value)
    implicit val order: Order[EpochProgress] = Order[NonNegLong].contramap(_.value)
    implicit val ordering: Ordering[EpochProgress] = order.toOrdering

    implicit val next: Next[EpochProgress] = new Next[EpochProgress] {
      def next(a: EpochProgress): EpochProgress = EpochProgress(a.value |+| NonNegLong(1L))
      def partialOrder: PartialOrder[EpochProgress] = order
    }
  }

}
