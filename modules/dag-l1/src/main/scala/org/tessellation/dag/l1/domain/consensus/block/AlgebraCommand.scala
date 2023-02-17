package org.tessellation.dag.l1.domain.consensus.block

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.dag.domain.block.Block
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput.{BlockSignatureProposal, CancelledBlockCreationRound, Proposal}
import org.tessellation.kernel.Ω
import org.tessellation.schema.transaction.Transaction

abstract class AlgebraCommand[A <: Transaction: TypeTag, B <: Block[A]: TypeTag] extends TypeTagged[AlgebraCommand[A, B]] with Ω

object AlgebraCommand {
  def extractor[A <: Transaction: TypeTag, B <: Block[A]: TypeTag] = new TypeTaggedExtractor[AlgebraCommand[A, B]]

  case class PersistInitialOwnRoundData[A <: Transaction: TypeTag, B <: Block[A]: TypeTag](roundData: RoundData[A, B])
      extends AlgebraCommand[A, B]
  case class PersistInitialPeerRoundData[A <: Transaction: TypeTag, B <: Block[A]: TypeTag](
    roundData: RoundData[A, B],
    peerProposal: Proposal[A]
  ) extends AlgebraCommand[A, B]
  case class PersistProposal[A <: Transaction: TypeTag, B <: Block[A]: TypeTag](proposal: Proposal[A]) extends AlgebraCommand[A, B]
  case class PersistBlockSignatureProposal[A <: Transaction: TypeTag, B <: Block[A]: TypeTag](
    blockSignatureProposal: BlockSignatureProposal
  ) extends AlgebraCommand[A, B]
  case class InformAboutInabilityToParticipate[A <: Transaction: TypeTag, B <: Block[A]: TypeTag](
    proposal: Proposal[A],
    reason: CancellationReason
  ) extends AlgebraCommand[A, B]
  case class PersistCancellationResult[A <: Transaction: TypeTag, B <: Block[A]: TypeTag](cancellation: CancelledBlockCreationRound)
      extends AlgebraCommand[A, B]
  case class InformAboutRoundStartFailure[A <: Transaction: TypeTag, B <: Block[A]: TypeTag](message: String) extends AlgebraCommand[A, B]
  case class CancelTimedOutRounds[A <: Transaction: TypeTag, B <: Block[A]: TypeTag](toCancel: Set[Proposal[A]])
      extends AlgebraCommand[A, B]
  case class NoAction[A <: Transaction: TypeTag, B <: Block[A]: TypeTag]() extends AlgebraCommand[A, B]
}
