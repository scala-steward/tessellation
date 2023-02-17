package org.tessellation.dag.l1

import java.security.KeyPair

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.Async
import cats.effect.std.{Random, Semaphore}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.order._
import cats.syntax.show._
import cats.syntax.traverse._
import cats.syntax.traverseFilter._
import cats.{Applicative, Order}

import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag

import org.tessellation.dag.domain.block.{Block, BlockReference}
import org.tessellation.dag.l1.config.types.AppConfig
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput._
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusOutput.{CleanedConsensuses, FinalBlock}
import org.tessellation.dag.l1.domain.consensus.block.CoalgebraCommand.ProcessProposal
import org.tessellation.dag.l1.domain.consensus.block.Validator.{canStartOwnConsensus, isPeerInputValid}
import org.tessellation.dag.l1.domain.consensus.block._
import org.tessellation.dag.l1.domain.snapshot.programs.SnapshotProcessor.SnapshotProcessingResult
import org.tessellation.dag.l1.http.p2p.P2PClient
import org.tessellation.dag.l1.modules._
import org.tessellation.dag.snapshot.{GlobalSnapshot, GlobalSnapshotReference, Snapshot}
import org.tessellation.ext.fs2.StreamOps
import org.tessellation.kernel.Cell.NullTerminal
import org.tessellation.kernel.{CellError, Ω}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.height.Height
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hashed, SecurityProvider}

import fs2.{Pipe, Stream}
import io.circe.Encoder
import org.typelevel.log4cats.slf4j.Slf4jLogger

class StateChannel[
  F[_]: Async: KryoSerializer: SecurityProvider: Random,
  A <: Transaction: Order: Ordering: TypeTag: Encoder,
  B <: Block[A]: TypeTag: Encoder,
  C <: Snapshot[B]
](
  appConfig: AppConfig,
  blockAcceptanceS: Semaphore[F],
  blockCreationS: Semaphore[F],
  blockStoringS: Semaphore[F],
  keyPair: KeyPair,
  p2PClient: P2PClient[F, A, B],
  programs: Programs[F, A, B, C],
  queues: Queues[F, A, B],
  selfId: PeerId,
  services: Services[F, A, B],
  storages: Storages[F, A, B, C],
  validators: Validators[F, A, B],
  createBlock: (NonEmptyList[BlockReference], NonEmptySet[Signed[A]]) => Option[B]
) {

  private implicit val logger = Slf4jLogger.getLogger[F]

  private val blockConsensusContext =
    BlockConsensusContext[F, A, B](
      p2PClient.blockConsensus,
      storages.block,
      validators.block,
      storages.cluster,
      appConfig.consensus,
      storages.consensus,
      keyPair,
      selfId,
      storages.transaction,
      validators.transaction,
      createBlock
    )

  private val inspectionTriggerInput: Stream[F, OwnerBlockConsensusInput] = Stream
    .awakeEvery(5.seconds)
    .as(InspectionTrigger)

  private val ownRoundTriggerInput: Stream[F, OwnerBlockConsensusInput] = Stream
    .awakeEvery(5.seconds)
    .evalMapLocked(blockCreationS) { _ =>
      canStartOwnConsensus(
        storages.consensus,
        storages.node,
        storages.cluster,
        storages.block,
        storages.transaction,
        appConfig.consensus.peersCount,
        appConfig.consensus.tipsCount
      ).handleErrorWith { e =>
        logger.warn(e)("Failure checking if own consensus can be kicked off!").map(_ => false)
      }
    }
    .filter(identity)
    .as(OwnRoundTrigger)

  private val l0PeerDiscovery: Stream[F, Unit] = Stream
    .awakeEvery(10.seconds)
    .evalMap { _ =>
      storages.lastSnapshot.get.flatMap {
        _.fold(Applicative[F].unit) { latestSnapshot =>
          programs.l0PeerDiscovery.discover(latestSnapshot.signed.proofs.map(_.id).map(PeerId._Id.reverseGet))
        }
      }
    }

  private val ownerBlockConsensusInputs: Stream[F, OwnerBlockConsensusInput] =
    inspectionTriggerInput.merge(ownRoundTriggerInput)

  private val peerBlockConsensusInputs: Stream[F, PeerBlockConsensusInput[A]] = Stream
    .fromQueueUnterminated(queues.peerBlockConsensusInput)
    .evalFilter(isPeerInputValid(_))
    .map(_.value)

  private val blockConsensusInputs: Stream[F, BlockConsensusInput] =
    ownerBlockConsensusInputs.merge(peerBlockConsensusInputs)

  private val runConsensus: Pipe[F, BlockConsensusInput, FinalBlock[A, B]] =
    _.evalTap(input => logger.debug(s"Received block consensus input to process: ${input.show}"))
      .evalMap(
        new BlockConsensusCell[F, A, B](
          _,
          blockConsensusContext,
          AlgebraCommand.extractor[A, B],
          ProcessProposal.extractor[A],
          Proposal.extractor[A]
        )
          .run()
          .handleErrorWith(e => CellError(e.getMessage).asLeft[Ω].pure[F])
      )
      .flatMap {
        case Left(ce) =>
          Stream.eval(logger.warn(ce)(s"Error occurred during some step of block consensus.")) >>
            Stream.empty
        case Right(ohm) =>
          val fbExt = FinalBlock.extractor[A, B]
          ohm match {
            case fbExt(fb) =>
              Stream
                .eval(logger.debug(s"Block created! Hash=${fb.hashedBlock.hash} ProofsHash=${fb.hashedBlock.proofsHash}"))
                .as(fb)
            case CleanedConsensuses(ids) =>
              Stream.eval(logger.warn(s"Cleaned following timed-out consensuses: $ids")) >>
                Stream.empty
            case NullTerminal => Stream.empty
            case other =>
              Stream.eval(logger.warn(s"Unexpected ohm in block consensus occurred: $other")) >>
                Stream.empty
          }
      }

  private val gossipBlock: Pipe[F, FinalBlock[A, B], FinalBlock[A, B]] =
    _.evalTap { fb =>
      services.gossip
        .spreadCommon(fb.hashedBlock.signed)
        .handleErrorWith(e => logger.warn(e)("Block gossip spread failed!"))
    }

  private val peerBlocks: Stream[F, FinalBlock[A, B]] = Stream
    .fromQueueUnterminated(queues.peerBlock)
    .evalMap(_.toHashedWithSignatureCheck)
    .evalTap {
      case Left(e)  => logger.warn(e)(s"Received an invalidly signed peer block!")
      case Right(_) => Async[F].unit
    }
    .collect {
      case Right(hashedBlock) => FinalBlock[A, B](hashedBlock)
    }

  private val storeBlock: Pipe[F, FinalBlock[A, B], Unit] =
    _.evalMapLocked(blockStoringS) { fb =>
      storages.lastSnapshot.getHeight.map(_.getOrElse(Height.MinValue)).flatMap { lastSnapshotHeight =>
        if (lastSnapshotHeight < fb.hashedBlock.height)
          storages.block.store(fb.hashedBlock).handleErrorWith(e => logger.debug(e)("Block storing failed."))
        else
          logger.debug(
            s"Block can't be stored! Block height not above last snapshot height! block:${fb.hashedBlock.height} <= snapshot: $lastSnapshotHeight"
          )
      }
    }

  // TODO: Send to state channel L0 not global L0
  private val sendBlockToL0: Pipe[F, FinalBlock[A, B], FinalBlock[A, B]] =
    _.evalTap { fb =>
      for {
        l0PeerOpt <- storages.l0Cluster.getPeers
          .map(_.toNonEmptyList.toList)
          .flatMap(_.filterA(p => services.collateral.hasCollateral(p.id)))
          .flatMap(peers => Random[F].shuffleList(peers))
          .map(peers => peers.headOption)

        _ <- l0PeerOpt.fold(logger.warn("No available L0 peer")) { l0Peer =>
          p2PClient.l0DAGCluster
            .sendL1Output(fb.hashedBlock.signed)(l0Peer)
            .ifM(Applicative[F].unit, logger.warn("Sending block to L0 failed."))
        }
      } yield ()
    }

  private val blockAcceptance: Stream[F, Unit] = Stream
    .awakeEvery(1.seconds)
    .evalMapLocked(blockAcceptanceS) { _ =>
      storages.block.getWaiting.flatTap { awaiting =>
        if (awaiting.nonEmpty) logger.debug(s"Pulled following blocks for acceptance ${awaiting.keySet}")
        else Async[F].unit
      }.flatMap(
        _.toList
          .sortBy(_._2.value.height)
          .traverse {
            case (hash, signedBlock) =>
              logger.debug(s"Acceptance of a block $hash starts!") >>
                services.block
                  .accept(signedBlock)
                  .handleErrorWith(logger.warn(_)(s"Failed acceptance of a block with ${hash.show}"))

          }
          .void
      )
    }

  // TODO: process global snapshot and state channel snapshot
  private val globalSnapshotProcessing: Stream[F, Unit] = Stream
    .awakeEvery(10.seconds)
    .evalMap(_ => services.l0.pullGlobalSnapshots)
    .evalTap {
      _.traverse { s =>
        logger.info(s"Pulled following global snapshot: ${GlobalSnapshotReference.fromHashedGlobalSnapshot(s).show}")
      }
    }
    .evalMapLocked(NonEmptyList.of(blockAcceptanceS, blockCreationS, blockStoringS)) { snapshots =>
      (snapshots, List.empty[SnapshotProcessingResult]).tailRecM {
        case (snapshot :: nextSnapshots, aggResults) =>
          programs.snapshotProcessor
            .process(snapshot)
            .map(result => (nextSnapshots, aggResults :+ result).asLeft[List[SnapshotProcessingResult]])

        case (Nil, aggResults) =>
          aggResults.asRight[(List[Hashed[GlobalSnapshot]], List[SnapshotProcessingResult])].pure[F]
      }
    }
    .evalMap {
      _.traverse(result => logger.info(s"Snapshot processing result: ${result.show}")).void
    }

  private val blockConsensus: Stream[F, Unit] =
    blockConsensusInputs
      .through(runConsensus)
      .through(gossipBlock)
      .through(sendBlockToL0)
      .merge(peerBlocks)
      .through(storeBlock)

  val runtime: Stream[F, Unit] =
    blockConsensus
      .merge(blockAcceptance)
      .merge(globalSnapshotProcessing)
      .merge(l0PeerDiscovery)

}

object StateChannel {

  def make[
    F[_]: Async: KryoSerializer: SecurityProvider: Random,
    A <: Transaction: Order: Ordering: TypeTag: ClassTag: Encoder,
    B <: Block[A]: TypeTag: Encoder,
    C <: Snapshot[B]
  ](
    appConfig: AppConfig,
    keyPair: KeyPair,
    p2PClient: P2PClient[F, A, B],
    programs: Programs[F, A, B, C],
    queues: Queues[F, A, B],
    selfId: PeerId,
    services: Services[F, A, B],
    storages: Storages[F, A, B, C],
    validators: Validators[F, A, B],
    createBlock: (NonEmptyList[BlockReference], NonEmptySet[Signed[A]]) => Option[B]
  ): F[StateChannel[F, A, B, C]] =
    for {
      blockAcceptanceS <- Semaphore(1)
      blockCreationS <- Semaphore(1)
      blockStoringS <- Semaphore(1)
    } yield
      new StateChannel[F, A, B, C](
        appConfig,
        blockAcceptanceS,
        blockCreationS,
        blockStoringS,
        keyPair,
        p2PClient,
        programs,
        queues,
        selfId,
        services,
        storages,
        validators,
        createBlock
      )
}
