package org.tessellation.dag.l1.domain.consensus.block

import cats.effect.std.Random
import cats.effect.{Async, Clock}
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._
import cats.syntax.traverse._
import cats.{Applicative, ApplicativeError, Order}

import scala.concurrent.duration.FiniteDuration
import scala.reflect.runtime.universe.TypeTag

import org.tessellation.dag.block.BlockValidator.BlockValidationParams
import org.tessellation.dag.domain.block.Block
import org.tessellation.dag.l1.domain.consensus.block.AlgebraCommand._
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusCell.{Algebra, Coalgebra}
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput._
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusOutput.{CleanedConsensuses, FinalBlock}
import org.tessellation.dag.l1.domain.consensus.block.CancellationReason._
import org.tessellation.dag.l1.domain.consensus.block.CoalgebraCommand._
import org.tessellation.dag.l1.domain.consensus.block.Validator.isReadyForBlockConsensus
import org.tessellation.dag.l1.domain.consensus.block.http.p2p.clients.BlockConsensusClient
import org.tessellation.dag.l1.domain.consensus.round.RoundId
import org.tessellation.dag.l1.domain.transaction.{TransactionStorage, transactionLoggerName}
import org.tessellation.effects.GenUUID
import org.tessellation.ext.collection.MapRefUtils.MapRefOps
import org.tessellation.kernel.Cell.NullTerminal
import org.tessellation.kernel._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.Signed._
import org.tessellation.security.{Hashed, SecurityProvider}

import eu.timepit.refined.auto.{autoRefineV, autoUnwrap}
import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}
import io.circe.Encoder
import org.typelevel.log4cats.slf4j.Slf4jLogger

class BlockConsensusCell[
  F[_]: Async: SecurityProvider: KryoSerializer: Random,
  A <: Transaction: TypeTag: Order: Ordering: Encoder,
  B <: Block[A]: TypeTag
](
  data: BlockConsensusInput,
  ctx: BlockConsensusContext[F, A, B],
  extractAlgebraCommand: TypeTaggedExtractor[AlgebraCommand[A, B]], // = AlgebraCommand.extractor[A, B]
  extractProcessProposal: TypeTaggedExtractor[ProcessProposal[A]],
  extractProposal: TypeTaggedExtractor[Proposal[A]]
) extends Cell[F, StackF, BlockConsensusInput, Either[CellError, Ω], CoalgebraCommand](
      data,
      scheme.hyloM(
        AlgebraM[F, StackF, Either[CellError, Ω]] {
          case More(a) => a.pure[F]
          case Done(Right(extractAlgebraCommand(cmd))) =>
            cmd match {
              case foo: PersistInitialOwnRoundData[A, B] =>
                Algebra.persistInitialOwnRoundData[F, A, B](foo.roundData, ctx)

              case foo: PersistInitialPeerRoundData[A, B] =>
                Algebra.persistInitialPeerRoundData[F, A, B](foo.roundData, foo.peerProposal, ctx)

              case foo: PersistProposal[A, B] =>
                Algebra.persistProposal[F, A, B](foo.proposal, ctx)

              case foo: PersistBlockSignatureProposal[A, B] =>
                Algebra.persistBlockSignatureProposal(foo.blockSignatureProposal, ctx)

              case foo: InformAboutInabilityToParticipate[A, B] =>
                Algebra.informAboutInabilityToParticipate[F, A, B](foo.proposal, foo.reason, ctx)

              case foo: PersistCancellationResult[A, B] =>
                Algebra.processCancellation(foo.cancellation, ctx)

              case foo: InformAboutRoundStartFailure[A, B] =>
                CellError(foo.message).asLeft[Ω].pure[F]

              case foo: CancelTimedOutRounds[A, B] =>
                Algebra.cancelTimedOutRounds[F, A, B](foo.toCancel, ctx)

              case _: NoAction[A, B] =>
                NullTerminal.asRight[CellError].widen[Ω].pure[F]

              case _ =>
                NullTerminal.asRight[CellError].widen[Ω].pure[F]
            }

          case Done(other) => other.pure[F]
        },
        CoalgebraM[F, StackF, CoalgebraCommand] {
          case StartOwnRound =>
            Coalgebra.startOwnRound[F, A, B](ctx)

          case InspectConsensuses =>
            Coalgebra.inspectConsensuses[F, A, B](ctx)

          case extractProcessProposal(foo) =>
            Coalgebra.processProposal[F, A, B](foo.proposal, ctx)

          case ProcessBlockSignatureProposal(blockSignatureProposal) =>
            Applicative[F].pure(Done(PersistBlockSignatureProposal(blockSignatureProposal).asRight[CellError]))

          case ProcessCancellation(cancellation) =>
            Applicative[F].pure(Done(PersistCancellationResult(cancellation).asRight[CellError]))

          case _ =>
            ApplicativeError[F, Throwable].raiseError(new Throwable("Coalgebra match not exhaustive"))
        }
      ),
      {
        case OwnRoundTrigger                                => StartOwnRound
        case InspectionTrigger                              => InspectConsensuses
        case extractProposal(proposal)                      => ProcessProposal(proposal)
        case blockSignatureProposal: BlockSignatureProposal => ProcessBlockSignatureProposal(blockSignatureProposal)
        case cancellation: CancelledBlockCreationRound      => ProcessCancellation(cancellation)
        case _                                              => DoNothing
      }
    )

object BlockConsensusCell {

  private def logger[F[_]: Async] = Slf4jLogger.getLogger

  private def getTransactionLogger[F[_]: Async] = Slf4jLogger.getLoggerFromName(transactionLoggerName)

  private def getTime[F[_]: Clock](): F[FiniteDuration] = Clock[F].monotonic

  private def deriveConsensusPeerIds[A <: Transaction](proposal: Proposal[A], selfId: PeerId): Set[PeerId] =
    proposal.facilitators + proposal.senderId + proposal.owner - selfId

  private def returnTransactions[F[_]: Async: KryoSerializer, A <: Transaction](
    ownProposal: Proposal[A],
    transactionStorage: TransactionStorage[F, A]
  ): F[Unit] =
    ownProposal.transactions.toList
      .traverse(_.toHashed[F])
      .map(_.toSet)
      .flatTap { txs =>
        getTransactionLogger[F]
          .info(s"Returned transactions for round: ${ownProposal.roundId} are: ${txs.size}, ${txs.map(_.hash).show}")
      }
      .flatMap {
        transactionStorage.put
      }

  private def cleanUpRoundData[F[_]: Async, A <: Transaction, B <: Block[A]](
    ownProposal: Proposal[A],
    ctx: BlockConsensusContext[F, A, B]
  ): F[Unit] = {
    def clean: Option[RoundData[A, B]] => (Option[RoundData[A, B]], Unit) = {
      case Some(roundData: RoundData[A, B]) if roundData.roundId == ownProposal.roundId => (none[RoundData[A, B]], ())
      case current                                                                      => (current, ())
    }

    (ownProposal.owner == ctx.selfId)
      .pure[F]
      .ifM(
        ctx.consensusStorage.ownConsensus.modify(clean),
        ctx.consensusStorage.peerConsensuses(ownProposal.roundId).modify(clean)
      )
  }

  private def cancelRound[F[_]: Async: KryoSerializer, A <: Transaction, B <: Block[A]](
    ownProposal: Proposal[A],
    ctx: BlockConsensusContext[F, A, B]
  ): F[Unit] =
    for {
      _ <- returnTransactions(ownProposal, ctx.transactionStorage)
      _ <- cleanUpRoundData(ownProposal, ctx)
    } yield ()

  private def broadcast[F[_]: Async, A <: Transaction: Encoder](
    data: Signed[PeerBlockConsensusInput[A]],
    peers: Set[Peer],
    blockConsensusClient: BlockConsensusClient[F, A]
  ): F[Unit] =
    peers.toList
      .traverse(blockConsensusClient.sendConsensusData(data)(_))
      .void

  object Algebra {

    private def tryPersistRoundData[A <: Transaction, B <: Block[A]](
      roundData: RoundData[A, B]
    ): Option[RoundData[A, B]] => (Option[RoundData[A, B]], Option[RoundData[A, B]]) = {
      case existing @ Some(_) => (existing, None)
      case None               => (roundData.some, roundData.some)
    }

    private def sendOwnProposal[F[_]: Async: SecurityProvider: KryoSerializer, A <: Transaction: Encoder, B <: Block[A]](
      ownProposal: Proposal[A],
      peers: Set[Peer],
      context: BlockConsensusContext[F, A, B]
    ): F[Unit] =
      for {
        signedProposal <- Signed.forAsyncKryo[F, PeerBlockConsensusInput[A]](ownProposal, context.keyPair)
        _ <- broadcast(signedProposal, peers, context.blockConsensusClient)
      } yield ()

    def persistInitialOwnRoundData[F[_]: Async: SecurityProvider: KryoSerializer, A <: Transaction: Encoder, B <: Block[A]](
      roundData: RoundData[A, B],
      ctx: BlockConsensusContext[F, A, B]
    ): F[Either[CellError, Ω]] =
      ctx.consensusStorage.ownConsensus
        .modify(tryPersistRoundData(roundData))
        .flatMap {
          case Some(RoundData(_, _, peers, _, ownProposal, _, _, _, _, _, _, _)) =>
            sendOwnProposal(ownProposal, peers, ctx)
              .map(_ => NullTerminal.asRight[CellError].widen[Ω])
          case None =>
            returnTransactions(roundData.ownProposal, ctx.transactionStorage)
              .map(_ => CellError("Another own round already in progress! Transactions returned.").asLeft[Ω])
        }

    def persistInitialPeerRoundData[F[_]: Async: SecurityProvider: KryoSerializer, A <: Transaction: Encoder, B <: Block[A]](
      roundData: RoundData[A, B],
      peerProposal: Proposal[A],
      ctx: BlockConsensusContext[F, A, B]
    ): F[Either[CellError, Ω]] =
      ctx.consensusStorage
        .peerConsensuses(roundData.roundId)
        .modify(tryPersistRoundData(roundData))
        .flatMap {
          case Some(RoundData(_, _, peers, _, ownProposal, _, _, _, _, _, _, _)) =>
            sendOwnProposal(ownProposal, peers, ctx)
          case None =>
            for {
              _ <- logger
                .debug(
                  s"Round with roundId=${roundData.roundId} already exists! Returning transactions and processing proposal!"
                )
              _ <- returnTransactions(roundData.ownProposal, ctx.transactionStorage)
            } yield ()
        } >> persistProposal(peerProposal, ctx)

    private def canPersistProposal[A <: Transaction, B <: Block[A]](roundData: RoundData[A, B], proposal: Proposal[A]): Boolean = {
      val sameRoundId = roundData.roundId == proposal.roundId
      lazy val peerExists = roundData.peers.exists(_.id == proposal.senderId)
      lazy val noProposalYet = !roundData.peerProposals.contains(proposal.senderId)

      sameRoundId && peerExists && noProposalYet
    }

    private def tryPersistProposal[A <: Transaction, B <: Block[A]](
      proposal: Proposal[A]
    ): Option[RoundData[A, B]] => (Option[RoundData[A, B]], Option[RoundData[A, B]]) = {
      case Some(roundData) if canPersistProposal(roundData, proposal) =>
        val updated = roundData.addPeerProposal(proposal)
        (updated.some, updated.some)
      case other => (other, None)
    }

    private def canPersistOwnBlock[A <: Transaction, B <: Block[A]](roundData: RoundData[A, B], proposal: Proposal[A]): Boolean =
      roundData.ownBlock.isEmpty && roundData.roundId == proposal.roundId

    private def tryPersistOwnBlock[A <: Transaction, B <: Block[A]](
      signedBlock: Signed[B],
      proposal: Proposal[A]
    ): Option[RoundData[A, B]] => (Option[RoundData[A, B]], Option[RoundData[A, B]]) = {
      case Some(roundData) if canPersistOwnBlock(roundData, proposal) =>
        val updated = roundData.setOwnBlock(signedBlock)
        (updated.some, updated.some)
      case other => (other, None)
    }

    private def sendBlockProposal[F[_]: Async: SecurityProvider: KryoSerializer, A <: Transaction: Encoder, B <: Block[A]](
      signedBlock: Signed[B],
      roundData: RoundData[A, B],
      ctx: BlockConsensusContext[F, A, B]
    ): F[Either[CellError, Ω]] =
      for {
        _ <- Applicative[F].unit
        blockSignatureProposal: PeerBlockConsensusInput[A] = BlockSignatureProposal(
          roundData.roundId,
          ctx.selfId,
          roundData.owner,
          signedBlock.proofs.head.signature
        )
        signedBlockSignatureProposal <- Signed
          .forAsyncKryo[F, PeerBlockConsensusInput[A]](blockSignatureProposal, ctx.keyPair)
        _ <- broadcast(signedBlockSignatureProposal, roundData.peers, ctx.blockConsensusClient)
      } yield NullTerminal.asRight[CellError].widen[Ω]

    private def processValidBlock[F[_]: Async: SecurityProvider: KryoSerializer, A <: Transaction: Encoder, B <: Block[A]](
      proposal: Proposal[A],
      signedBlock: Signed[B],
      ctx: BlockConsensusContext[F, A, B]
    ): F[Either[CellError, Ω]] =
      (proposal.owner == ctx.selfId)
        .pure[F]
        .ifM(
          ctx.consensusStorage.ownConsensus.modify(tryPersistOwnBlock(signedBlock, proposal)),
          ctx.consensusStorage
            .peerConsensuses(proposal.roundId)
            .modify(tryPersistOwnBlock(signedBlock, proposal))
        )
        .flatMap {
          case Some(roundData) =>
            sendBlockProposal(signedBlock, roundData, ctx)
          case None =>
            CellError("Tried to persist own signed block but the update failed!").asLeft[Ω].pure[F]
        }

    private def gotAllProposals[A <: Transaction, B <: Block[A]](roundData: RoundData[A, B]): Boolean =
      roundData.peers.map(_.id) == roundData.peerProposals.keySet

    private val validationParams: BlockValidationParams = BlockValidationParams.default.copy(minSignatureCount = 1)

    def persistProposal[F[_]: Async: SecurityProvider: KryoSerializer, A <: Transaction: Encoder, B <: Block[A]](
      proposal: Proposal[A],
      ctx: BlockConsensusContext[F, A, B]
    ): F[Either[CellError, Ω]] = (proposal.owner == ctx.selfId)
      .pure[F]
      .ifM(
        ctx.consensusStorage.ownConsensus.modify(tryPersistProposal(proposal)),
        ctx.consensusStorage.peerConsensuses(proposal.roundId).modify(tryPersistProposal(proposal))
      )
      .flatMap {
        case Some(roundData) if gotAllProposals(roundData) =>
          roundData.formBlock(ctx.transactionValidator).flatMap {
            case Some(block) =>
              Signed.forAsyncKryo(block, ctx.keyPair).flatMap { signedBlock =>
                ctx.blockValidator
                  .validate(signedBlock, validationParams)
                  .flatTap { validationResult =>
                    Applicative[F].whenA(validationResult.isInvalid) {
                      logger.debug(s"Created block is invalid: $validationResult")
                    }
                  }
                  .map(_.isValid)
                  .ifM(
                    processValidBlock(proposal, signedBlock, ctx), {
                      val cancellation = CancelledBlockCreationRound(
                        roundData.roundId,
                        senderId = ctx.selfId,
                        owner = roundData.owner,
                        CreatedInvalidBlock
                      )
                      processCancellation(cancellation, ctx)
                    }
                  )
              }
            case None =>
              val cancellation = CancelledBlockCreationRound(
                roundData.roundId,
                senderId = ctx.selfId,
                owner = roundData.owner,
                CreatedBlockWithNoTransactions
              )
              processCancellation(cancellation, ctx)
          }
        case _ => NullTerminal.asRight[CellError].widen[Ω].pure[F]

      }

    private def canPersistBlockSignatureProposal[A <: Transaction, B <: Block[A]](
      roundData: RoundData[A, B],
      blockSignatureProposal: BlockSignatureProposal
    ): Boolean = {
      val sameRoundId = roundData.roundId == blockSignatureProposal.roundId
      lazy val peerExists = roundData.peers.exists(_.id == blockSignatureProposal.senderId)
      lazy val noBlockYet = !roundData.peerBlockSignatures.contains(blockSignatureProposal.senderId)

      sameRoundId && peerExists && noBlockYet
    }

    private def gotAllSignatures[A <: Transaction, B <: Block[A]](roundData: RoundData[A, B]): Boolean =
      roundData.peers.map(_.id) == roundData.peerBlockSignatures.keySet

    private def tryPersistBlockSignatureProposal[A <: Transaction, B <: Block[A]](
      blockSignatureProposal: BlockSignatureProposal
    ): Option[RoundData[A, B]] => (Option[RoundData[A, B]], Option[RoundData[A, B]]) = {
      case Some(roundData) if canPersistBlockSignatureProposal(roundData, blockSignatureProposal) =>
        val updated = roundData.addPeerBlockSignature(blockSignatureProposal)
        (updated.some, updated.some)
      case other => (other, None)
    }

    def persistBlockSignatureProposal[F[_]: Async: SecurityProvider: KryoSerializer, A <: Transaction: TypeTag, B <: Block[A]: TypeTag](
      blockSignatureProposal: BlockSignatureProposal,
      ctx: BlockConsensusContext[F, A, B]
    ): F[Either[CellError, Ω]] =
      (blockSignatureProposal.owner == ctx.selfId)
        .pure[F]
        .ifM(
          ctx.consensusStorage.ownConsensus.modify(tryPersistBlockSignatureProposal(blockSignatureProposal)),
          ctx.consensusStorage
            .peerConsensuses(blockSignatureProposal.roundId)
            .modify(tryPersistBlockSignatureProposal(blockSignatureProposal))
        )
        .flatMap {
          case Some(roundData @ RoundData(_, _, _, _, _, Some(ownBlock), _, _, _, _, _, _)) if gotAllSignatures(roundData) =>
            for {
              _ <- Applicative[F].unit
              finalBlock = roundData.peerBlockSignatures.values.foldLeft(ownBlock) {
                case (agg, proof) => agg.addProof(proof)
              }
              result <- finalBlock.toHashedWithSignatureCheck.flatMap {
                case Left(_) =>
                  val foo: F[Either[CellError, Ω]] =
                    cancelRound(roundData.ownProposal, ctx)
                      .map(_ => CellError("Round cancelled after final block turned out to be invalid!").asLeft[Ω])
                  foo

                case Right(hashedBlock) =>
                  val foo: F[Either[CellError, Ω]] = cleanUpRoundData(roundData.ownProposal, ctx)
                    .map(_ => FinalBlock[A, B](hashedBlock).asRight[CellError].widen[Ω])
                  foo
              }
            } yield result
          case _ =>
            NullTerminal.asRight[CellError].widen[Ω].pure[F]
        }

    def informAboutInabilityToParticipate[F[_]: Async: SecurityProvider: KryoSerializer, A <: Transaction: Encoder, B <: Block[A]](
      proposal: Proposal[A],
      reason: CancellationReason,
      ctx: BlockConsensusContext[F, A, B]
    ): F[Either[CellError, Ω]] =
      for {
        peersToInform <- ctx.clusterStorage.getResponsivePeers
          .map(_.filter(peer => deriveConsensusPeerIds(proposal, ctx.selfId).contains(peer.id)))
        cancellation: PeerBlockConsensusInput[A] = CancelledBlockCreationRound(
          proposal.roundId,
          senderId = ctx.selfId,
          owner = proposal.owner,
          reason
        )
        signedCancellationMessage <- Signed
          .forAsyncKryo[F, PeerBlockConsensusInput[A]](cancellation, ctx.keyPair)
        _ <- broadcast(signedCancellationMessage, peersToInform, ctx.blockConsensusClient)
      } yield NullTerminal.asRight[CellError].widen[Ω]

    private def canPersistCancellation[A <: Transaction, B <: Block[A]](
      roundData: RoundData[A, B],
      cancellation: CancelledBlockCreationRound,
      selfId: PeerId
    ): Boolean = {
      val sameRoundId = roundData.roundId == cancellation.roundId
      lazy val peerExists = roundData.peers.exists(_.id == cancellation.senderId)
      lazy val myCancellation = cancellation.senderId == selfId

      sameRoundId && (peerExists || myCancellation)
    }

    private def persistCancellationMessage[A <: Transaction, B <: Block[A]](
      cancellation: CancelledBlockCreationRound,
      selfId: PeerId
    ): Option[RoundData[A, B]] => (Option[RoundData[A, B]], Option[(RoundData[A, B], Option[CancelledBlockCreationRound])]) = {
      case Some(roundData) if canPersistCancellation(roundData, cancellation, selfId) =>
        (roundData, cancellation.senderId) match {
          case (roundData @ RoundData(_, _, _, _, _, _, Some(_), _, _, _, _, _), `selfId`) =>
            (roundData.some, None)

          case (roundData @ RoundData(_, _, _, _, _, _, None, _, _, _, _, _), `selfId`) =>
            val updated = roundData.setOwnCancellation(cancellation.reason)
            (updated.some, (updated, cancellation.some).some)

          case (roundData @ RoundData(_, _, _, _, _, _, Some(_), _, _, _, _, _), _) =>
            val updated = roundData.addPeerCancellation(cancellation)
            (updated.some, (updated, None).some)

          case (roundData @ RoundData(_, _, _, _, _, _, None, _, _, _, _, _), _) =>
            val myCancellation = CancelledBlockCreationRound(roundData.roundId, selfId, roundData.owner, PeerCancelled)
            val updated = roundData.setOwnCancellation(myCancellation.reason).addPeerCancellation(cancellation)
            (updated.some, (updated, myCancellation.some).some)
        }
      case other => (other, None)
    }

    private def canFinalizeRoundCancellation[A <: Transaction, B <: Block[A]](roundData: RoundData[A, B]): Boolean = {
      val ownCancellationPresent = roundData.ownCancellation.nonEmpty
      lazy val peerCancellationsPresent = roundData.peers.map(_.id) == roundData.peerCancellations.keySet

      ownCancellationPresent && peerCancellationsPresent
    }

    def processCancellation[F[_]: Async: SecurityProvider: KryoSerializer, A <: Transaction: Encoder, B <: Block[A]](
      cancellation: CancelledBlockCreationRound,
      ctx: BlockConsensusContext[F, A, B]
    ): F[Either[CellError, Ω]] =
      for {
        maybeUpdated <- (cancellation.owner == ctx.selfId)
          .pure[F]
          .ifM(
            ctx.consensusStorage.ownConsensus.modify(persistCancellationMessage(cancellation, ctx.selfId)),
            ctx.consensusStorage
              .peerConsensuses(cancellation.roundId)
              .modify(persistCancellationMessage(cancellation, ctx.selfId))
          )
        _ <- maybeUpdated match {
          case Some((roundData, Some(myCancellation))) =>
            for {
              signedCancellationMessage <- Signed // TODO: NO NIE TAK
                .forAsyncKryo[F, PeerBlockConsensusInput[A]](myCancellation.asInstanceOf[PeerBlockConsensusInput[A]], ctx.keyPair)
              _ <- broadcast(signedCancellationMessage, roundData.peers, ctx.blockConsensusClient)
            } yield ()
          case _ => Applicative[F].unit
        }
        result <- maybeUpdated match {
          case Some((roundData, _)) if canFinalizeRoundCancellation(roundData) =>
            cancelRound(roundData.ownProposal, ctx)
              .map(_ => CellError("Round cancelled after all peers agreed!").asLeft[Ω])
          case Some((_, Some(myCancellation))) =>
            CellError(
              s"Round is being cancelled! Own round cancellation request got processed. Reason: ${myCancellation.reason}"
            ).asLeft[Ω].pure[F]
          case Some(_) =>
            CellError(s"Round is being cancelled! Round cancellation request got processed.").asLeft[Ω].pure[F]
          case None => NullTerminal.asRight[CellError].widen[Ω].pure[F]
        }
      } yield result

    def cancelTimedOutRounds[F[_]: Async: KryoSerializer, A <: Transaction, B <: Block[A]](
      toCancel: Set[Proposal[A]],
      ctx: BlockConsensusContext[F, A, B]
    ): F[Either[CellError, Ω]] =
      toCancel.toList
        .traverse(cancelRound(_, ctx))
        .map(_ => CleanedConsensuses(toCancel.map(_.roundId)).asRight[CellError])
  }

  object Coalgebra {

    private def pullNewConsensusPeers[F[_]: Async: Random, A <: Transaction, B <: Block[A]](
      ctx: BlockConsensusContext[F, A, B]
    ): F[Option[Set[Peer]]] =
      ctx.clusterStorage.getResponsivePeers
        .map(_.filter(p => isReadyForBlockConsensus(p.state)))
        .flatMap(peers => Random[F].shuffleList(peers.toList))
        .map(_.take(ctx.consensusConfig.peersCount).toSet match {
          case peers if peers.size == ctx.consensusConfig.peersCount.value => peers.some
          case _                                                           => None
        })

    private def pullTransactions[F[_]: Async, A <: Transaction, B <: Block[A]](
      ctx: BlockConsensusContext[F, A, B]
    ): F[Set[Hashed[A]]] =
      ctx.transactionStorage
        .pull(ctx.consensusConfig.pullTxsCount)
        .map(_.map(_.toList.toSet).getOrElse(Set.empty[Hashed[A]]))

    def startOwnRound[F[_]: Async: Random, A <: Transaction: Ordering: Order: TypeTag, B <: Block[A]: TypeTag](
      ctx: BlockConsensusContext[F, A, B]
    ): F[StackF[CoalgebraCommand]] =
      for {
        roundId <- GenUUID.forSync[F].make.map(RoundId(_))
        maybePeers <- pullNewConsensusPeers(ctx)
        maybeTips <- ctx.blockStorage.getTips(ctx.consensusConfig.tipsCount)
        algebraCommand <- (maybePeers, maybeTips) match {
          case (Some(peers), Some(tips)) =>
            for {
              transactions <- pullTransactions[F, A, B](ctx)
              startedAt <- getTime()
              proposal = Proposal[A](
                roundId,
                senderId = ctx.selfId,
                owner = ctx.selfId,
                peers.map(_.id),
                transactions.map(_.signed),
                tips
              )
              roundData = RoundData[A, B](roundId, startedAt, peers, ctx.selfId, proposal, tips = tips, createBlock = (_, _) => None)
            } yield PersistInitialOwnRoundData(roundData)

          case (Some(_), None) => InformAboutRoundStartFailure("Missing tips!").pure[F]
          case (None, Some(_)) => InformAboutRoundStartFailure("Missing peers!").pure[F]
          case (None, None)    => InformAboutRoundStartFailure("Missing both peers and tips!").pure[F]
        }
      } yield Done(algebraCommand.asRight[CellError])

    def inspectConsensuses[F[_]: Async, A <: Transaction: TypeTag, B <: Block[A]: TypeTag](
      ctx: BlockConsensusContext[F, A, B]
    ): F[StackF[CoalgebraCommand]] =
      for {
        ownConsensus <- ctx.consensusStorage.ownConsensus.get.map(_.toSet)
        peerConsensuses <- ctx.consensusStorage.peerConsensuses.toMap.map(_.values.toSet)
        current <- getTime()
        toCancel = (peerConsensuses ++ ownConsensus)
          .filter(_.startedAt + ctx.consensusConfig.timeout < current)
          .map(_.ownProposal)
        algebraCommand = if (toCancel.nonEmpty) CancelTimedOutRounds(toCancel) else NoAction[A, B]()
      } yield Done(algebraCommand.asRight[CellError])

    private def fetchConsensusPeers[F[_]: Async, A <: Transaction, B <: Block[A]](
      proposal: Proposal[A],
      ctx: BlockConsensusContext[F, A, B]
    ): F[Option[Set[Peer]]] =
      for {
        knownPeers <- ctx.clusterStorage.getResponsivePeers
        peerIds = deriveConsensusPeerIds(proposal, ctx.selfId)
        peers = peerIds
          .map(id => id -> knownPeers.find(_.id == id))
          .collect { case (id, Some(peer)) => id -> peer }
          .toMap
        result <-
          if (peers.keySet == peerIds && peerIds.size == ctx.consensusConfig.peersCount.value)
            peers.values.toSet.some.pure[F]
          else
            none[Set[Peer]].pure[F]
      } yield result

    def processProposal[F[_]: Async, A <: Transaction: Order: Ordering: TypeTag, B <: Block[A]: TypeTag](
      proposal: Proposal[A],
      ctx: BlockConsensusContext[F, A, B]
    ): F[StackF[CoalgebraCommand]] =
      for {
        maybeRoundData <- ctx.consensusStorage.ownConsensus.get.flatMap {
          case Some(ownRoundData) if ownRoundData.roundId == proposal.roundId => Option(ownRoundData).pure[F]
          case _                                                              => ctx.consensusStorage.peerConsensuses(proposal.roundId).get
        }
        maybePeers <- fetchConsensusPeers(proposal, ctx)
        algebraCommand <- (maybeRoundData, maybePeers) match {
          case (Some(_), _) => PersistProposal(proposal).pure[F]
          case (None, _) if proposal.owner == ctx.selfId =>
            InformAboutInabilityToParticipate(proposal, ReceivedProposalForNonExistentOwnRound).pure[F]
          case (None, Some(peers)) =>
            for {
              transactions <- pullTransactions[F, A, B](ctx)
              startedAt <- getTime()
              ownProposal = Proposal(
                proposal.roundId,
                senderId = ctx.selfId,
                owner = proposal.owner,
                peers.map(_.id),
                transactions.map(_.signed),
                proposal.tips
              )
              roundData = RoundData[A, B](
                proposal.roundId,
                startedAt,
                peers,
                owner = proposal.owner,
                ownProposal,
                tips = proposal.tips,
                createBlock = (a, b) => None
              )
            } yield PersistInitialPeerRoundData(roundData, proposal)
          case (None, None) => InformAboutInabilityToParticipate(proposal, MissingRoundPeers).pure[F]
        }
      } yield Done(algebraCommand.asRight[CellError])
  }
}
