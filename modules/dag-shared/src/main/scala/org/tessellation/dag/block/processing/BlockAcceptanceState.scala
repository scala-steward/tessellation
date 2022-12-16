package org.tessellation.dag.block.processing

import cats.Eq

import org.tessellation.dag.domain.block.{Block, DAGBlock}
import org.tessellation.schema.transaction.{DAGTransaction, Transaction}
import org.tessellation.security.signature.Signed

import derevo.cats.show
import derevo.derive
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong

@derive(show)
case class BlockAcceptanceState[A <: Transaction, B <: Block[A]](
  contextUpdate: BlockAcceptanceContextUpdate,
  accepted: List[(Signed[B], NonNegLong)],
  rejected: List[(Signed[B], BlockRejectionReason)],
  awaiting: List[((Signed[B], TxChains[A]), BlockAwaitReason)]
) {

  def toBlockAcceptanceResult: BlockAcceptanceResult[A, B] =
    BlockAcceptanceResult(
      contextUpdate,
      accepted,
      awaiting.map { case ((block, _), reason) => (block, reason) } ++ rejected
    )
}

object BlockAcceptanceState {

  // TODO: what is the correct Equals method here
  implicit val orderDAGBlock: Eq[BlockAcceptanceState[DAGTransaction, DAGBlock]] =
    Eq.fromUniversalEquals[BlockAcceptanceState[DAGTransaction, DAGBlock]]
//    Order.whenEqual(
//      Order.by[BlockAcceptanceState[DAGTransaction, DAGBlock], BlockAcceptanceContextUpdate](_.contextUpdate),
//      Order.whenEqual(
//        Order.by[BlockAcceptanceState[DAGTransaction, DAGBlock], List[(Signed[DAGBlock], NonNegLong)]](_.accepted),
//        Order.whenEqual(
//          Order.by[BlockAcceptanceState[DAGTransaction, DAGBlock], List[(Signed[DAGBlock], BlockRejectionReason)]](_.rejected),
//          Order.by[BlockAcceptanceState[DAGTransaction, DAGBlock], List[((Signed[DAGBlock], TxChains[DAGTransaction]), BlockAwaitReason)]](
//            _.awaiting
//          )
//        )
//      )
//    )

  def withRejectedBlocks[A <: Transaction: Eq, B <: Block[A]: Eq](
    rejected: List[(Signed[B], BlockRejectionReason)]
  ): BlockAcceptanceState[A, B] =
    BlockAcceptanceState[A, B](
      contextUpdate = BlockAcceptanceContextUpdate.empty,
      accepted = List.empty,
      rejected = rejected,
      awaiting = List.empty
    )
}
