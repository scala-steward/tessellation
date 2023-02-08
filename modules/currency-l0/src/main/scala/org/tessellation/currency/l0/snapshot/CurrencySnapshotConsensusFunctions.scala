package org.tessellation.currency.l0.snapshot

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.option._
import cats.syntax.order._

import org.tessellation.currency.l0.snapshot.services.StateChannelSnapshotService
import org.tessellation.currency.schema.currency._
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.sdk.domain.block.processing._
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.sdk.infrastructure.snapshot.SnapshotConsensusFunctions
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import org.typelevel.log4cats.slf4j.Slf4jLogger

abstract class CurrencySnapshotConsensusFunctions[F[_]: Async: SecurityProvider]
    extends SnapshotConsensusFunctions[
      F,
      CurrencyTransaction,
      CurrencyBlock,
      CurrencySnapshotEvent,
      CurrencySnapshotArtifact,
      CurrencySnapshotContext,
      ConsensusTrigger
    ] {}

object CurrencySnapshotConsensusFunctions {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    stateChannelSnapshotService: StateChannelSnapshotService[F],
    blockAcceptanceManager: BlockAcceptanceManager[F, CurrencyTransaction, CurrencyBlock],
    collateral: Amount
  ): CurrencySnapshotConsensusFunctions[F] = new CurrencySnapshotConsensusFunctions[F] {

    def createContext(
      lastSnapshotContext: CurrencySnapshotInfo,
      lastSnapshot: CurrencySnapshotArtifact,
      snapshot: Signed[CurrencySnapshotArtifact]
    ): F[CurrencySnapshotInfo] = ???

    private val logger = Slf4jLogger.getLoggerFromClass(CurrencySnapshotConsensusFunctions.getClass)

    def getRequiredCollateral: Amount = collateral

    def consumeSignedMajorityArtifact(signedArtifact: Signed[CurrencyIncrementalSnapshot], context: CurrencySnapshotContext): F[Unit] =
      stateChannelSnapshotService.consume(signedArtifact, context)

    override def createProposalArtifact(
      lastKey: SnapshotOrdinal,
      lastSignedArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      trigger: ConsensusTrigger,
      events: Set[CurrencySnapshotEvent]
    ): F[(CurrencySnapshotArtifact, Set[CurrencySnapshotEvent])] = {

      val blocksForAcceptance = events
        .filter(_.height > lastSignedArtifact.height)
        .toList

      for {
        lastArtifactHash <- lastSignedArtifact.value.hashF
        currentOrdinal = lastSignedArtifact.ordinal.next
        lastActiveTips <- lastSignedArtifact.activeTips
        lastDeprecatedTips = lastSignedArtifact.tips.deprecated

        tipUsages = getTipsUsages(lastActiveTips, lastDeprecatedTips)
        context = BlockAcceptanceContext.fromStaticData(
          lastContext.balances,
          lastContext.lastTxRefs,
          tipUsages,
          collateral
        )
        acceptanceResult <- blockAcceptanceManager.acceptBlocksIteratively(blocksForAcceptance, context)

        (deprecated, remainedActive, accepted) = getUpdatedTips(
          lastActiveTips,
          lastDeprecatedTips,
          acceptanceResult,
          currentOrdinal
        )

        (height, subHeight) <- getHeightAndSubHeight(lastSignedArtifact, deprecated, remainedActive, accepted)

        updatedLastTxRefs = lastContext.lastTxRefs ++ acceptanceResult.contextUpdate.lastTxRefs
        balances = lastContext.balances ++ acceptanceResult.contextUpdate.balances
        positiveBalances = balances.filter { case (_, balance) => balance =!= Balance.empty }

        returnedEvents = getReturnedEvents(acceptanceResult)

        artifact = CurrencyIncrementalSnapshot(
          currentOrdinal,
          height,
          subHeight,
          lastArtifactHash,
          accepted,
          SnapshotTips(
            deprecated = deprecated,
            remainedActive = remainedActive
          ),
          lastArtifact.stateProof // TODO: incremental snapshots - new state proof
        )
      } yield (artifact, returnedEvents)
    }

    private def getReturnedEvents(
      acceptanceResult: BlockAcceptanceResult[CurrencyBlock]
    ): Set[CurrencySnapshotEvent] =
      acceptanceResult.notAccepted.mapFilter {
        case (signedBlock, _: BlockAwaitReason) => signedBlock.some
        case _                                  => none
      }.toSet
  }
}
