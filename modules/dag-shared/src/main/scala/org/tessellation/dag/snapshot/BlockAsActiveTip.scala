package org.tessellation.dag.snapshot

import cats.Order

import org.tessellation.dag.domain.block.{Block, CurrencyBlock, DAGBlock}
import org.tessellation.ext.cats.data.OrderBasedOrdering
import org.tessellation.schema._
import org.tessellation.security.signature.Signed

import derevo.cats.show
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.types.numeric.NonNegLong

@derive(show, encoder, decoder)
case class BlockAsActiveTip[A <: Block[_]](
  block: Signed[A],
  usageCount: NonNegLong
)

object BlockAsActiveTip {

  implicit val orderDAGBlock: Order[BlockAsActiveTip[DAGBlock]] = Order.whenEqual(
    Order.by[BlockAsActiveTip[DAGBlock], Signed[DAGBlock]](_.block),
    Order.by[BlockAsActiveTip[DAGBlock], NonNegLong](_.usageCount)
  )

  implicit val orderCurrencyBlock: Order[BlockAsActiveTip[CurrencyBlock]] = Order.whenEqual(
    Order.by[BlockAsActiveTip[CurrencyBlock], Signed[CurrencyBlock]](_.block),
    Order.by[BlockAsActiveTip[CurrencyBlock], NonNegLong](_.usageCount)
  )

  implicit object OrderingInstanceBSATForDAGBlock extends OrderBasedOrdering[BlockAsActiveTip[DAGBlock]]
  implicit object OrderingInstanceBSATForCurrencyBlock extends OrderBasedOrdering[BlockAsActiveTip[CurrencyBlock]]
}
