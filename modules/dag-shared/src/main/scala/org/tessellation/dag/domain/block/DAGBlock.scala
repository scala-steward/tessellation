package org.tessellation.dag.domain.block

import cats.Show
import cats.data.NonEmptySet._
import cats.data.{NonEmptyList, NonEmptySet}
import cats.syntax.reducible._

import org.tessellation.ext.cats.data.OrderBasedOrdering
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.codecs.NonEmptySetCodec
import org.tessellation.schema.Fiber
import org.tessellation.schema.height.Height
import org.tessellation.schema.transaction.{CurrencyTransaction, DAGTransaction, Transaction}
import org.tessellation.security.signature.Signed

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe.Decoder

case class BaseBlockReference(parents: NonEmptyList[BlockReference])
case class BaseBlockData[A <: Transaction](transactions: NonEmptySet[Signed[A]])

sealed abstract class Block[A <: Transaction] extends Fiber[BaseBlockReference, BaseBlockData[A]] {
  val parent: NonEmptyList[BlockReference]
  val transactions: NonEmptySet[Signed[A]]

  // TODO: fix and also consider if it shouldn't be per DAGBlock CurrencyBlock and passed as typeclass in type params
  implicit def show: Show[Block[_]] = Show.show[Block[_]](_ => "block")

  val height: Height = parent.maximum.height.next

  def reference = BaseBlockReference(parent)
  def data = BaseBlockData(transactions)
}

@derive(encoder, decoder, order, show)
case class CurrencyBlock(
  parent: NonEmptyList[BlockReference],
  transactions: NonEmptySet[Signed[CurrencyTransaction]]
) extends Block[CurrencyTransaction]

object CurrencyBlock {
  implicit object OrderingInstance extends OrderBasedOrdering[CurrencyBlock]

  implicit val transactionsDecoder: Decoder[NonEmptySet[Signed[CurrencyTransaction]]] =
    NonEmptySetCodec.decoder[Signed[CurrencyTransaction]]
}

@derive(encoder, decoder, order, show)
case class DAGBlock(
  parent: NonEmptyList[BlockReference],
  transactions: NonEmptySet[Signed[DAGTransaction]]
) extends Block[DAGTransaction]

object DAGBlock {
  implicit object OrderingInstance extends OrderBasedOrdering[DAGBlock]

  implicit val transactionsDecoder: Decoder[NonEmptySet[Signed[DAGTransaction]]] =
    NonEmptySetCodec.decoder[Signed[DAGTransaction]]
}
