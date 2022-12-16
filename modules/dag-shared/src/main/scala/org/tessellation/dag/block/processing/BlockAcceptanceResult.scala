package org.tessellation.dag.block.processing

import org.tessellation.dag.domain.block.Block
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong

@derive(eqv, show)
case class BlockAcceptanceResult[A <: Transaction, B <: Block[A]](
  contextUpdate: BlockAcceptanceContextUpdate,
  accepted: List[(Signed[B], NonNegLong)],
  notAccepted: List[(Signed[B], BlockNotAcceptedReason)]
)
