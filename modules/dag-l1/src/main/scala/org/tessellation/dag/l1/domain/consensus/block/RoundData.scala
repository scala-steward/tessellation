package org.tessellation.dag.l1.domain.consensus.block

import cats.Order
import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.traverse._

import scala.concurrent.duration.FiniteDuration

import org.tessellation.dag.domain.block.{Block, BlockReference, Tips}
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput.{BlockSignatureProposal, CancelledBlockCreationRound, Proposal}
import org.tessellation.dag.l1.domain.consensus.round.RoundId
import org.tessellation.dag.transaction.TransactionValidator
import org.tessellation.dag.transaction.filter.Consecutive
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.SignatureProof
import org.tessellation.syntax.sortedCollection._

import monocle.macros.syntax.lens._
import org.typelevel.log4cats.slf4j.Slf4jLogger

case class RoundData[A <: Transaction /*: Order: Ordering*/, B <: Block[A]](
  roundId: RoundId,
  startedAt: FiniteDuration,
  peers: Set[Peer],
  owner: PeerId,
  ownProposal: Proposal[A],
  ownBlock: Option[Signed[B]] = None,
  ownCancellation: Option[CancellationReason] = None,
  peerProposals: Map[PeerId, Proposal[A]] = Map.empty[PeerId, Proposal[A]],
  peerBlockSignatures: Map[PeerId, SignatureProof] = Map.empty,
  peerCancellations: Map[PeerId, CancellationReason] = Map.empty,
  tips: Tips,
  createBlock: (NonEmptyList[BlockReference], NonEmptySet[Signed[A]]) => Option[B]
)(implicit O: Order[A], D: Ordering[A]) {

  private def logger[F[_]: Async] = Slf4jLogger.getLogger

  def addPeerProposal(proposal: Proposal[A]): RoundData[A, B] =
    this.focus(_.peerProposals).modify(_ + (proposal.senderId -> proposal))

  def setOwnBlock(block: Signed[B]): RoundData[A, B] = this.focus(_.ownBlock).replace(block.some)

  def addPeerBlockSignature(blockSignatureProposal: BlockSignatureProposal): RoundData[A, B] = {
    val proof = SignatureProof(PeerId._Id.get(blockSignatureProposal.senderId), blockSignatureProposal.signature)
    this.focus(_.peerBlockSignatures).modify(_ + (blockSignatureProposal.senderId -> proof))
  }

  def setOwnCancellation(reason: CancellationReason): RoundData[A, B] = this.focus(_.ownCancellation).replace(reason.some)

  def addPeerCancellation(cancellation: CancelledBlockCreationRound): RoundData[A, B] =
    this.focus(_.peerCancellations).modify(_ + (cancellation.senderId -> cancellation.reason))

  def formBlock[F[_]: Async: KryoSerializer](validator: TransactionValidator[F, A]): F[Option[B]] =
    (ownProposal.transactions ++ peerProposals.values.flatMap(_.transactions)).toList
      .traverse(validator.validate)
      .flatMap { validatedTxs =>
        val (invalid, valid) = validatedTxs.partitionMap(_.toEither)

        invalid.traverse { errors =>
          logger.warn(s"Discarded invalid transaction during L1 consensus with roundId=$roundId. Reasons: ${errors.show}")
        } >>
          valid.pure[F]
      }
      .flatMap {
        _.groupBy(_.source).values.toList
          .traverse(txs => Consecutive.take(txs))
          .map(listOfTxs => NonEmptySet.fromSet(listOfTxs.flatten.toSortedSet))
          .map(_.flatMap(createBlock(tips.value, _)))
      }
}
