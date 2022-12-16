package org.tessellation.dag.l1.domain.consensus.block

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput.{BlockSignatureProposal, CancelledBlockCreationRound, Proposal}
import org.tessellation.schema.transaction.Transaction

sealed trait CoalgebraCommand

object CoalgebraCommand {
  case object StartOwnRound extends CoalgebraCommand
  case object InspectConsensuses extends CoalgebraCommand
  case class ProcessProposal[A <: Transaction: TypeTag](proposal: Proposal[A]) extends TypeTagged[ProcessProposal[A]] with CoalgebraCommand
  object ProcessProposal {
    def extractor[A <: Transaction: TypeTag] = new TypeTaggedExtractor[ProcessProposal[A]]
  }
  case class ProcessBlockSignatureProposal(blockSignatureProposal: BlockSignatureProposal) extends CoalgebraCommand
  case class ProcessCancellation(cancellation: CancelledBlockCreationRound) extends CoalgebraCommand

  case object DoNothing extends CoalgebraCommand
}
