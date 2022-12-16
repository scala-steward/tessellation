package org.tessellation.dag.l1.domain.snapshot.programs

import cats.effect.Async
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.{Applicative, MonadThrow}

import scala.util.control.NoStackTrace

import org.tessellation.dag.domain.block.{Block, BlockReference}
import org.tessellation.dag.l1.domain.block.BlockStorage.MajorityReconciliationData
import org.tessellation.dag.l1.domain.snapshot.programs.SnapshotProcessor.{SnapshotProcessingResult, TipsGotMisaligned}
import org.tessellation.dag.snapshot._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.transaction.{Transaction, TransactionReference}
import org.tessellation.sdk.domain.snapshot.Validator
import org.tessellation.security.hash.ProofsHash
import org.tessellation.security.{Hashed, SecurityProvider}

import derevo.cats.show
import derevo.derive
import eu.timepit.refined.types.numeric.NonNegLong

abstract class SnapshotProcessor[F[_]: Async: KryoSerializer: SecurityProvider, A <: Transaction, B <: Block[A], C <: Snapshot[B]] {
  def process(globalSnapshot: Hashed[GlobalSnapshot]): F[SnapshotProcessingResult]

  def extractMajorityTxRefs(
    acceptedInMajority: Map[ProofsHash, (Hashed[B], NonNegLong)],
    snapshot: C
  ): Map[Address, TransactionReference]

  def checkAlignmentA(
    snapshot: C,
    maybeLast: F[Option[Hashed[C]]],
    reconciliationData: F[MajorityReconciliationData]
  ): F[Alignment] =
    for {
      acceptedInMajority <- snapshot.blocks.toList.traverse {
        case BlockAsActiveTip(block, usageCount) =>
          block.toHashedWithSignatureCheck.flatMap(_.liftTo[F]).map(b => b.proofsHash -> (b, usageCount))
      }.map(_.toMap)

      GlobalSnapshotTips(gsDeprecatedTips, gsRemainedActive) = snapshot.tips

      result <- maybeLast.flatMap {
        case Some(last) =>
          Validator.compare[B, C](last, snapshot) match {
            case Validator.NextSubHeight =>
              // blockStorage.getBlocksForMajorityReconciliation(last.height, globalSnapshot.height)
              reconciliationData.flatMap {
                case MajorityReconciliationData(deprecatedTips, activeTips, _, _, acceptedAbove) =>
                  val onlyInMajority = acceptedInMajority -- acceptedAbove
                  val toMarkMajority = acceptedInMajority.view.filterKeys(acceptedAbove.contains).mapValues(_._2)
                  lazy val toAdd = onlyInMajority.values.toSet
                  lazy val toReset = acceptedAbove -- toMarkMajority.keySet
                  val tipsToRemove = deprecatedTips -- gsDeprecatedTips.map(_.block.hash)
                  val deprecatedTipsToAdd = gsDeprecatedTips.map(_.block.hash) -- deprecatedTips
                  val tipsToDeprecate = activeTips -- gsRemainedActive.map(_.block.hash)
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
              // blockStorage.getBlocksForMajorityReconciliation(last.height, globalSnapshot.height)
              reconciliationData.flatMap {
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
                  val tipsToRemove = deprecatedTips -- gsDeprecatedTips.map(_.block.hash)
                  val deprecatedTipsToAdd = gsDeprecatedTips.map(_.block.hash) -- deprecatedTips
                  val tipsToDeprecate = activeTips -- gsRemainedActive.map(_.block.hash)
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
          // blockStorage.getBlocksForMajorityReconciliation(Height.MinValue, globalSnapshot.height)
          reconciliationData.flatMap {
            case MajorityReconciliationData(_, _, waitingInRange, _, _) =>
              val obsoleteToRemove = waitingInRange -- acceptedInMajority.keySet -- gsRemainedActive
                .map(_.block.hash) -- gsDeprecatedTips.map(_.block.hash)

              Applicative[F].pure[Alignment](
                DownloadNeeded(
                  acceptedInMajority.values.toSet,
                  obsoleteToRemove,
                  gsRemainedActive,
                  gsDeprecatedTips.map(_.block)
                )
              )
          }
      }
    } yield result

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

  sealed trait SnapshotProcessingError extends NoStackTrace
  case class TipsGotMisaligned(deprecatedToAdd: Set[ProofsHash], activeToDeprecate: Set[ProofsHash]) extends SnapshotProcessingError {
    override def getMessage: String =
      s"Tips got misaligned! Check the implementation! deprecatedToAdd -> $deprecatedToAdd not equal activeToDeprecate -> $activeToDeprecate"
  }
}
