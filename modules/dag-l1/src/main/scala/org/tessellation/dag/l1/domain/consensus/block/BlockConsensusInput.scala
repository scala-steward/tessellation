package org.tessellation.dag.l1.domain.consensus.block

import cats.Show
import cats.syntax.functor._

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.dag.domain.block.Tips
import org.tessellation.dag.l1.domain.consensus.round.RoundId
import org.tessellation.kernel.Ω
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.Signature

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.syntax._
import io.circe.{Decoder, Encoder}

sealed trait BlockConsensusInput extends Ω

object BlockConsensusInput {
  sealed trait OwnerBlockConsensusInput extends BlockConsensusInput
  case object OwnRoundTrigger extends OwnerBlockConsensusInput
  case object InspectionTrigger extends OwnerBlockConsensusInput

  sealed trait PeerBlockConsensusInput[+A <: Transaction] extends BlockConsensusInput {
    val senderId: PeerId
    val owner: PeerId
  }

  object PeerBlockConsensusInput {
    implicit def encoder[A <: Transaction: Encoder: TypeTag]: Encoder[PeerBlockConsensusInput[A]] =
      Encoder.instance[PeerBlockConsensusInput[A]] {
        case foo: Proposal[A]                                                    => foo.asJson(Proposal.encoder[A])
        case foo @ BlockSignatureProposal(roundId, senderId, owner, signature)   => foo.asJson
        case foo @ CancelledBlockCreationRound(roundId, senderId, owner, reason) => foo.asJson
      }
    implicit def decoder[A <: Transaction: Decoder: TypeTag]: Decoder[PeerBlockConsensusInput[A]] =
      List[Decoder[PeerBlockConsensusInput[A]]](
        Decoder[Proposal[A]].widen,
        Decoder[BlockSignatureProposal].widen,
        Decoder[CancelledBlockCreationRound].widen
      ).reduceLeft(_ or _)
  }

  case class Proposal[A <: Transaction: TypeTag](
    roundId: RoundId,
    senderId: PeerId,
    owner: PeerId,
    facilitators: Set[PeerId],
    transactions: Set[Signed[A]],
    tips: Tips
  ) extends TypeTagged[Proposal[A]]
      with PeerBlockConsensusInput[A]
  object Proposal {
    def extractor[A <: Transaction: TypeTag] = new TypeTaggedExtractor[Proposal[A]]
    implicit def encoder[A <: Transaction: Encoder: TypeTag]: Encoder[Proposal[A]] = deriveEncoder
    implicit def decoder[A <: Transaction: Decoder: TypeTag]: Decoder[Proposal[A]] = deriveDecoder
  }
  case class BlockSignatureProposal(roundId: RoundId, senderId: PeerId, owner: PeerId, signature: Signature)
      extends PeerBlockConsensusInput[Nothing]

  object BlockSignatureProposal {
    implicit val encoder: Encoder[BlockSignatureProposal] = deriveEncoder
    implicit val decoder: Decoder[BlockSignatureProposal] = deriveDecoder
  }
  case class CancelledBlockCreationRound(roundId: RoundId, senderId: PeerId, owner: PeerId, reason: CancellationReason)
      extends PeerBlockConsensusInput[Nothing]

  object CancelledBlockCreationRound {
    implicit val encoder: Encoder[CancelledBlockCreationRound] = deriveEncoder
    implicit val decoder: Decoder[CancelledBlockCreationRound] = deriveDecoder
  }

  implicit val showBlockConsensusInput: Show[BlockConsensusInput] = {
    case OwnRoundTrigger   => "OwnRoundTrigger"
    case InspectionTrigger => "InspectionTrigger"
    case Proposal(roundId, senderId, _, _, txs, _) =>
      s"Proposal(roundId=${roundId.value.toString.take(8)}, senderId=${senderId.value.value.take(8)} txsCount=${txs.size})"
    case BlockSignatureProposal(roundId, senderId, _, _) =>
      s"BlockSignatureProposal(roundId=${roundId.value.toString.take(8)}, senderId=${senderId.value.value.take(8)})"
    case CancelledBlockCreationRound(roundId, senderId, _, reason) =>
      s"CancelledBlockCreationRound(roundId=${roundId.value.toString.take(8)}, senderId=${senderId.value.value.take(8)}, reason=$reason)"
  }
}
