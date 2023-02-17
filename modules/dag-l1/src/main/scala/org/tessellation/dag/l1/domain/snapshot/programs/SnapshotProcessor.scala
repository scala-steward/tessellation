package org.tessellation.dag.l1.domain.snapshot.programs

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.{Applicative, MonadThrow}

import scala.util.control.NoStackTrace

import org.tessellation.dag.domain.block.{Block, BlockReference}
import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.block.BlockStorage.MajorityReconciliationData
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.dag.snapshot._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.transaction.{Transaction, TransactionReference}
import org.tessellation.sdk.domain.snapshot.Validator
import org.tessellation.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.security.hash.ProofsHash
import org.tessellation.security.{Hashed, SecurityProvider}

import derevo.cats.show
import derevo.derive
import eu.timepit.refined.types.numeric.NonNegLong
import org.typelevel.log4cats.slf4j.Slf4jLogger

abstract class SnapshotProcessor[F[_]: Async: KryoSerializer: SecurityProvider, T <: Transaction, B <: Block[T], S <: Snapshot[B]] {
  import SnapshotProcessor._
  def process(globalSnapshot: Hashed[GlobalSnapshot]): F[SnapshotProcessingResult]

  def extractMajorityTxRefs(
    acceptedInMajority: Map[ProofsHash, (Hashed[B], NonNegLong)],
    snapshot: S
  ): Map[Address, TransactionReference]

  def checkAlignment(
    snapshot: S,
    blockStorage: BlockStorage[F, T, B],
    lastSnapshotStorage: LastSnapshotStorage[F, S]
  ): F[Alignment] =
    for {
      acceptedInMajority <- snapshot.blocks.toList.traverse {
        case BlockAsActiveTip(block, usageCount) =>
          block.toHashedWithSignatureCheck.flatMap(_.liftTo[F]).map(b => b.proofsHash -> (b, usageCount))
      }.map(_.toMap)

      GlobalSnapshotTips(snapshotDeprecatedTips, snapshotRemainedActive) = snapshot.tips

      result <- lastSnapshotStorage.get.flatMap {
        case Some(last) =>
          Validator.compare[S](last, snapshot) match {
            case Validator.NextSubHeight =>
              blockStorage.getBlocksForMajorityReconciliation(last.height, snapshot.height).flatMap {
                case MajorityReconciliationData(deprecatedTips, activeTips, _, _, acceptedAbove) =>
                  val onlyInMajority = acceptedInMajority -- acceptedAbove
                  val toMarkMajority = acceptedInMajority.view.filterKeys(acceptedAbove.contains).mapValues(_._2)
                  lazy val toAdd = onlyInMajority.values.toSet
                  lazy val toReset = acceptedAbove -- toMarkMajority.keySet
                  val tipsToRemove = deprecatedTips -- snapshotDeprecatedTips.map(_.block.hash)
                  val deprecatedTipsToAdd = snapshotDeprecatedTips.map(_.block.hash) -- deprecatedTips
                  val tipsToDeprecate = activeTips -- snapshotRemainedActive.map(_.block.hash)
                  val areTipsAligned = deprecatedTipsToAdd == tipsToDeprecate
                  lazy val txRefsToMarkMajority = extractMajorityTxRefs(acceptedInMajority, snapshot)

                  if (!areTipsAligned)
                    MonadThrow[F].raiseError[Alignment](TipsGotMisaligned(deprecatedTipsToAdd, tipsToDeprecate))
                  else if (onlyInMajority.isEmpty)
                    Applicative[F].pure[Alignment](
                      AlignedAtNewOrdinal(toMarkMajority.toSet, tipsToDeprecate, tipsToRemove, txRefsToMarkMajority)
                    )
                  else
                    Applicative[F]
                      .pure[Alignment](
                        RedownloadNeeded(
                          toAdd,
                          toMarkMajority.toSet,
                          Set.empty,
                          Set.empty,
                          toReset,
                          tipsToDeprecate,
                          tipsToRemove
                        )
                      )
              }
            case Validator.NextHeight =>
              blockStorage.getBlocksForMajorityReconciliation(last.height, snapshot.height).flatMap {
                case MajorityReconciliationData(
                      deprecatedTips,
                      activeTips,
                      waitingInRange,
                      acceptedInRange,
                      acceptedAbove
                    ) =>
                  val acceptedLocally = acceptedInRange ++ acceptedAbove
                  val onlyInMajority = acceptedInMajority -- acceptedLocally
                  val toMarkMajority = acceptedInMajority.view.filterKeys(acceptedLocally.contains).mapValues(_._2)
                  val acceptedToRemove = acceptedInRange -- acceptedInMajority.keySet
                  lazy val toAdd = onlyInMajority.values.toSet
                  lazy val toReset = acceptedLocally -- toMarkMajority.keySet -- acceptedToRemove
                  val obsoleteToRemove = waitingInRange -- onlyInMajority.keySet
                  val tipsToRemove = deprecatedTips -- snapshotDeprecatedTips.map(_.block.hash)
                  val deprecatedTipsToAdd = snapshotDeprecatedTips.map(_.block.hash) -- deprecatedTips
                  val tipsToDeprecate = activeTips -- snapshotRemainedActive.map(_.block.hash)
                  val areTipsAligned = deprecatedTipsToAdd == tipsToDeprecate
                  lazy val txRefsToMarkMajority = extractMajorityTxRefs(acceptedInMajority, snapshot)

                  if (!areTipsAligned)
                    MonadThrow[F].raiseError[Alignment](TipsGotMisaligned(deprecatedTipsToAdd, tipsToDeprecate))
                  else if (onlyInMajority.isEmpty && acceptedToRemove.isEmpty)
                    Applicative[F].pure[Alignment](
                      AlignedAtNewHeight(
                        toMarkMajority.toSet,
                        obsoleteToRemove,
                        tipsToDeprecate,
                        tipsToRemove,
                        txRefsToMarkMajority
                      )
                    )
                  else
                    Applicative[F].pure[Alignment](
                      RedownloadNeeded(
                        toAdd,
                        toMarkMajority.toSet,
                        acceptedToRemove,
                        obsoleteToRemove,
                        toReset,
                        tipsToDeprecate,
                        tipsToRemove
                      )
                    )
              }

            case Validator.NotNext =>
              Applicative[F].pure[Alignment](
                Ignore(last.height, last.subHeight, last.ordinal, snapshot.height, snapshot.subHeight, snapshot.ordinal)
              )
          }

        case None =>
          blockStorage.getBlocksForMajorityReconciliation(Height.MinValue, snapshot.height).flatMap {
            case MajorityReconciliationData(_, _, waitingInRange, _, _) =>
              val obsoleteToRemove = waitingInRange -- acceptedInMajority.keySet -- snapshotRemainedActive
                .map(_.block.hash) -- snapshotDeprecatedTips.map(_.block.hash)

              Applicative[F].pure[Alignment](
                DownloadNeeded(
                  acceptedInMajority.values.toSet,
                  obsoleteToRemove,
                  snapshotRemainedActive,
                  snapshotDeprecatedTips.map(_.block)
                )
              )
          }
      }
    } yield result

  def processAlignment(
    snapshot: Hashed[S],
    alignment: Alignment,
    blockStorage: BlockStorage[F, T, B],
    transactionStorage: TransactionStorage[F, T],
    lastSnapshotStorage: LastSnapshotStorage[F, S],
    addressStorage: AddressStorage[F]
  ): F[SnapshotProcessingResult] =
    alignment match {
      case AlignedAtNewOrdinal(toMarkMajority, tipsToDeprecate, tipsToRemove, txRefsToMarkMajority) =>
        val adjustToMajority: F[Unit] =
          blockStorage
            .adjustToMajority(
              toMarkMajority = toMarkMajority,
              tipsToDeprecate = tipsToDeprecate,
              tipsToRemove = tipsToRemove
            )

        val markTxRefsAsMajority: F[Unit] =
          transactionStorage.markMajority(txRefsToMarkMajority)

        val setSnapshot: F[Unit] =
          lastSnapshotStorage.set(snapshot)

        adjustToMajority >>
          markTxRefsAsMajority >>
          setSnapshot.as[SnapshotProcessingResult] {
            Aligned(
              GlobalSnapshotReference.fromHashedGlobalSnapshot(snapshot),
              Set.empty
            )
          }

      case AlignedAtNewHeight(toMarkMajority, obsoleteToRemove, tipsToDeprecate, tipsToRemove, txRefsToMarkMajority) =>
        val adjustToMajority: F[Unit] =
          blockStorage
            .adjustToMajority(
              toMarkMajority = toMarkMajority,
              obsoleteToRemove = obsoleteToRemove,
              tipsToDeprecate = tipsToDeprecate,
              tipsToRemove = tipsToRemove
            )

        val markTxRefsAsMajority: F[Unit] =
          transactionStorage.markMajority(txRefsToMarkMajority)

        val setSnapshot: F[Unit] =
          lastSnapshotStorage.set(snapshot)

        adjustToMajority >>
          markTxRefsAsMajority >>
          setSnapshot.as[SnapshotProcessingResult] {
            Aligned(
              GlobalSnapshotReference.fromHashedGlobalSnapshot(snapshot),
              obsoleteToRemove
            )
          }

      case DownloadNeeded(toAdd, obsoleteToRemove, activeTips, deprecatedTips) =>
        val adjustToMajority: F[Unit] =
          blockStorage.adjustToMajority(
            toAdd = toAdd,
            obsoleteToRemove = obsoleteToRemove,
            activeTipsToAdd = activeTips,
            deprecatedTipsToAdd = deprecatedTips
          )

        val setBalances: F[Unit] =
          addressStorage.clean >>
            addressStorage.updateBalances(snapshot.getBalances)

        val setTransactionRefs: F[Unit] =
          transactionStorage.setLastAccepted(snapshot.getLastTxRefs)

        val setInitialSnapshot: F[Unit] =
          lastSnapshotStorage.setInitial(snapshot)

        adjustToMajority >>
          setBalances >>
          setTransactionRefs >>
          setInitialSnapshot.as[SnapshotProcessingResult] {
            DownloadPerformed(
              GlobalSnapshotReference.fromHashedGlobalSnapshot(snapshot),
              toAdd.map(_._1.proofsHash),
              obsoleteToRemove
            )
          }

      case RedownloadNeeded(
            toAdd,
            toMarkMajority,
            acceptedToRemove,
            obsoleteToRemove,
            toReset,
            tipsToDeprecate,
            tipsToRemove
          ) =>
        val adjustToMajority: F[Unit] =
          blockStorage.adjustToMajority(
            toAdd = toAdd,
            toMarkMajority = toMarkMajority,
            acceptedToRemove = acceptedToRemove,
            obsoleteToRemove = obsoleteToRemove,
            toReset = toReset,
            tipsToDeprecate = tipsToDeprecate,
            tipsToRemove = tipsToRemove
          )

        val setBalances: F[Unit] =
          addressStorage.clean >>
            addressStorage.updateBalances(snapshot.getBalances)

        val setTransactionRefs: F[Unit] =
          transactionStorage.setLastAccepted(snapshot.getLastTxRefs)

        val setSnapshot: F[Unit] =
          lastSnapshotStorage.set(snapshot)

        adjustToMajority >>
          setBalances >>
          setTransactionRefs >>
          setSnapshot.as[SnapshotProcessingResult] {
            RedownloadPerformed(
              GlobalSnapshotReference.fromHashedGlobalSnapshot(snapshot),
              toAdd.map(_._1.proofsHash),
              acceptedToRemove,
              obsoleteToRemove
            )
          }

      case Ignore(lastHeight, lastSubHeight, lastOrdinal, processingHeight, processingSubHeight, processingOrdinal) =>
        Slf4jLogger
          .getLogger[F]
          .warn(
            s"Unexpected case during global snapshot processing - ignoring snapshot! Last: (height: $lastHeight, subHeight: $lastSubHeight, ordinal: $lastOrdinal) processing: (height: $processingHeight, subHeight:$processingSubHeight, ordinal: $processingOrdinal)."
          )
          .as(SnapshotIgnored(GlobalSnapshotReference.fromHashedGlobalSnapshot(snapshot)))
    }

  sealed trait Alignment
  case class AlignedAtNewOrdinal(
    toMarkMajority: Set[(ProofsHash, NonNegLong)],
    tipsToDeprecate: Set[ProofsHash],
    tipsToRemove: Set[ProofsHash],
    txRefsToMarkMajority: Map[Address, TransactionReference]
  ) extends Alignment
  case class AlignedAtNewHeight(
    toMarkMajority: Set[(ProofsHash, NonNegLong)],
    obsoleteToRemove: Set[ProofsHash],
    tipsToDeprecate: Set[ProofsHash],
    tipsToRemove: Set[ProofsHash],
    txRefsToMarkMajority: Map[Address, TransactionReference]
  ) extends Alignment
  case class DownloadNeeded(
    toAdd: Set[(Hashed[B], NonNegLong)],
    obsoleteToRemove: Set[ProofsHash],
    activeTips: Set[ActiveTip],
    deprecatedTips: Set[BlockReference]
  ) extends Alignment
  case class RedownloadNeeded(
    toAdd: Set[(Hashed[B], NonNegLong)],
    toMarkMajority: Set[(ProofsHash, NonNegLong)],
    acceptedToRemove: Set[ProofsHash],
    obsoleteToRemove: Set[ProofsHash],
    toReset: Set[ProofsHash],
    tipsToDeprecate: Set[ProofsHash],
    tipsToRemove: Set[ProofsHash]
  ) extends Alignment
  case class Ignore(
    lastHeight: Height,
    lastSubHeight: SubHeight,
    lastOrdinal: SnapshotOrdinal,
    processingHeight: Height,
    processingSubHeight: SubHeight,
    processingOrdinal: SnapshotOrdinal
  ) extends Alignment
}

object SnapshotProcessor {
  @derive(show)
  sealed trait SnapshotProcessingResult
  case class Aligned(
    reference: GlobalSnapshotReference,
    removedObsoleteBlocks: Set[ProofsHash]
  ) extends SnapshotProcessingResult
  case class DownloadPerformed(
    reference: GlobalSnapshotReference,
    addedBlock: Set[ProofsHash],
    removedObsoleteBlocks: Set[ProofsHash]
  ) extends SnapshotProcessingResult
  case class RedownloadPerformed(
    reference: GlobalSnapshotReference,
    addedBlocks: Set[ProofsHash],
    removedBlocks: Set[ProofsHash],
    removedObsoleteBlocks: Set[ProofsHash]
  ) extends SnapshotProcessingResult
  case class SnapshotIgnored(
    reference: GlobalSnapshotReference
  ) extends SnapshotProcessingResult

  case class BatchResult(globalSnapshotResult: SnapshotProcessingResult, results: NonEmptyList[SnapshotProcessingResult])
      extends SnapshotProcessingResult

  sealed trait SnapshotProcessingError extends NoStackTrace
  case class TipsGotMisaligned(deprecatedToAdd: Set[ProofsHash], activeToDeprecate: Set[ProofsHash]) extends SnapshotProcessingError {
    override def getMessage: String =
      s"Tips got misaligned! Check the implementation! deprecatedToAdd -> $deprecatedToAdd not equal activeToDeprecate -> $activeToDeprecate"
  }
}
