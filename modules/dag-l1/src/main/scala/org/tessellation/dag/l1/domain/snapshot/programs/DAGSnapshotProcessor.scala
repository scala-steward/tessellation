package org.tessellation.dag.l1.domain.snapshot.programs

import cats.effect.Async
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.{Applicative, MonadThrow}

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.block.BlockStorage.MajorityReconciliationData
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.dag.snapshot._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.height.Height
import org.tessellation.schema.transaction.{DAGTransaction, TransactionReference}
import org.tessellation.sdk.domain.snapshot.Validator
import org.tessellation.sdk.domain.snapshot.storage.LastGlobalSnapshotStorage
import org.tessellation.security.hash.ProofsHash
import org.tessellation.security.{Hashed, SecurityProvider}

import eu.timepit.refined.types.numeric.NonNegLong
import org.typelevel.log4cats.slf4j.Slf4jLogger

object DAGSnapshotProcessor {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    addressStorage: AddressStorage[F],
    blockStorage: BlockStorage[F, DAGTransaction, DAGBlock],
    lastGlobalSnapshotStorage: LastGlobalSnapshotStorage[F],
    transactionStorage: TransactionStorage[F, DAGTransaction]
  ): SnapshotProcessor[F, DAGTransaction, DAGBlock, GlobalSnapshot] =
    new SnapshotProcessor[F, DAGTransaction, DAGBlock, GlobalSnapshot] {

      import SnapshotProcessor._

      def logger = Slf4jLogger.getLogger[F]

      def process(globalSnapshot: Hashed[GlobalSnapshot]): F[SnapshotProcessingResult] =
        checkAlignment(globalSnapshot).flatMap {
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
              lastGlobalSnapshotStorage.set(globalSnapshot)

            adjustToMajority >>
              markTxRefsAsMajority >>
              setSnapshot.map { _ =>
                Aligned(
                  GlobalSnapshotReference.fromHashedGlobalSnapshot(globalSnapshot),
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
              lastGlobalSnapshotStorage.set(globalSnapshot)

            adjustToMajority >>
              markTxRefsAsMajority >>
              setSnapshot.map { _ =>
                Aligned(
                  GlobalSnapshotReference.fromHashedGlobalSnapshot(globalSnapshot),
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
                addressStorage.updateBalances(globalSnapshot.info.balances)

            val setTransactionRefs: F[Unit] =
              transactionStorage.setLastAccepted(globalSnapshot.info.lastTxRefs)

            val setInitialSnapshot: F[Unit] =
              lastGlobalSnapshotStorage.setInitial(globalSnapshot)

            adjustToMajority >>
              setBalances >>
              setTransactionRefs >>
              setInitialSnapshot.map { _ =>
                DownloadPerformed(
                  GlobalSnapshotReference.fromHashedGlobalSnapshot(globalSnapshot),
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
                addressStorage.updateBalances(globalSnapshot.info.balances)

            val setTransactionRefs: F[Unit] =
              transactionStorage.setLastAccepted(globalSnapshot.info.lastTxRefs)

            val setSnapshot: F[Unit] =
              lastGlobalSnapshotStorage.set(globalSnapshot)

            adjustToMajority >>
              setBalances >>
              setTransactionRefs >>
              setSnapshot.map { _ =>
                RedownloadPerformed(
                  GlobalSnapshotReference.fromHashedGlobalSnapshot(globalSnapshot),
                  toAdd.map(_._1.proofsHash),
                  acceptedToRemove,
                  obsoleteToRemove
                )
              }

          case Ignore(lastHeight, lastSubHeight, lastOrdinal, processingHeight, processingSubHeight, processingOrdinal) =>
            logger
              .warn(
                s"Unexpected case during global snapshot processing - ignoring snapshot! Last: (height: $lastHeight, subHeight: $lastSubHeight, ordinal: $lastOrdinal) processing: (height: $processingHeight, subHeight:$processingSubHeight, ordinal: $processingOrdinal)."
              )
              .as(SnapshotIgnored(GlobalSnapshotReference.fromHashedGlobalSnapshot(globalSnapshot)))
        }

      private def checkAlignment(globalSnapshot: GlobalSnapshot): F[Alignment] =
        for {
          acceptedInMajority <- globalSnapshot.blocks.toList.traverse {
            case BlockAsActiveTip(block, usageCount) =>
              block.toHashedWithSignatureCheck.flatMap(_.liftTo[F]).map(b => b.proofsHash -> (b, usageCount))
          }.map(_.toMap)

          GlobalSnapshotTips(gsDeprecatedTips, gsRemainedActive) = globalSnapshot.tips

          result <- lastGlobalSnapshotStorage.get.flatMap {
            case Some(last) =>
              Validator.compare[DAGBlock, GlobalSnapshot](last, globalSnapshot) match {
                case Validator.NextSubHeight =>
                  blockStorage.getBlocksForMajorityReconciliation(last.height, globalSnapshot.height).flatMap {
                    case MajorityReconciliationData(deprecatedTips, activeTips, _, _, acceptedAbove) =>
                      val onlyInMajority = acceptedInMajority -- acceptedAbove
                      val toMarkMajority = acceptedInMajority.view.filterKeys(acceptedAbove.contains).mapValues(_._2)
                      lazy val toAdd = onlyInMajority.values.toSet
                      lazy val toReset = acceptedAbove -- toMarkMajority.keySet
                      val tipsToRemove = deprecatedTips -- gsDeprecatedTips.map(_.block.hash)
                      val deprecatedTipsToAdd = gsDeprecatedTips.map(_.block.hash) -- deprecatedTips
                      val tipsToDeprecate = activeTips -- gsRemainedActive.map(_.block.hash)
                      val areTipsAligned = deprecatedTipsToAdd == tipsToDeprecate
                      lazy val txRefsToMarkMajority = extractMajorityTxRefs(acceptedInMajority, globalSnapshot)

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
                  blockStorage.getBlocksForMajorityReconciliation(last.height, globalSnapshot.height).flatMap {
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
                      lazy val txRefsToMarkMajority = extractMajorityTxRefs(acceptedInMajority, globalSnapshot)

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
                    Ignore(
                      last.height,
                      last.subHeight,
                      last.ordinal,
                      globalSnapshot.height,
                      globalSnapshot.subHeight,
                      globalSnapshot.ordinal
                    )
                  )
              }

            case None =>
              blockStorage.getBlocksForMajorityReconciliation(Height.MinValue, globalSnapshot.height).flatMap {
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

      def extractMajorityTxRefs(
        acceptedInMajority: Map[ProofsHash, (Hashed[DAGBlock], NonNegLong)],
        globalSnapshot: GlobalSnapshot
      ): Map[Address, TransactionReference] = {
        val sourceAddresses =
          acceptedInMajority.values
            .flatMap(_._1.transactions.toSortedSet)
            .map(_.source)
            .toSet

        globalSnapshot.info.lastTxRefs.view.filterKeys(sourceAddresses.contains).toMap
      }
    }
}
