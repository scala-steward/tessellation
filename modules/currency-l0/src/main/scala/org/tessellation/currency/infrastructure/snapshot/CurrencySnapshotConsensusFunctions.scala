package org.tessellation.currency.infrastructure.snapshot

import java.security.KeyPair

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.show._

import org.tessellation.currency.schema.currency._
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.crypto._
import org.tessellation.ext.kryo._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.sdk.domain.block.processing._
import org.tessellation.sdk.domain.cluster.storage.L0ClusterStorage
import org.tessellation.sdk.domain.snapshot.storage.SnapshotStorage
import org.tessellation.sdk.http.p2p.clients.StateChannelSnapshotClient
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.sdk.infrastructure.snapshot.SnapshotConsensusFunctions
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.StateChannelSnapshotBinary

import org.typelevel.log4cats.slf4j.Slf4jLogger

abstract class CurrencySnapshotConsensusFunctions[F[_]: Async: SecurityProvider]
    extends SnapshotConsensusFunctions[
      F,
      CurrencyTransaction,
      CurrencyBlock,
      CurrencySnapshotEvent,
      CurrencySnapshotArtifact,
      ConsensusTrigger
    ] {}

object CurrencySnapshotConsensusFunctions {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    keyPair: KeyPair,
    snapshotStorage: SnapshotStorage[F, CurrencySnapshot],
    blockAcceptanceManager: BlockAcceptanceManager[F, CurrencyTransaction, CurrencyBlock],
    collateral: Amount,
    l0ClusterStorage: L0ClusterStorage[F],
    stateChannelSnapshotClient: StateChannelSnapshotClient[F]
  ): CurrencySnapshotConsensusFunctions[F] = new CurrencySnapshotConsensusFunctions[F] {

    private val logger = Slf4jLogger.getLoggerFromClass(CurrencySnapshotConsensusFunctions.getClass)

    def getRequiredCollateral: Amount = collateral

    def consumeSignedMajorityArtifact(signedArtifact: Signed[CurrencySnapshot]): F[Unit] = {
      val store = snapshotStorage
        .prepend(signedArtifact)
        .ifM(
          Async[F].unit,
          logger.error("Cannot save CurrencySnapshot into the storage")
        )

      val sendToL0 = for {
        l0Peer <- l0ClusterStorage.getRandomPeer
        artifactHash <- signedArtifact.hashF
        artifactBytes <- signedArtifact.toBinaryF
        artifactBinary <- StateChannelSnapshotBinary(artifactHash, artifactBytes).sign(keyPair)
        _ <- stateChannelSnapshotClient
          .sendStateChannelSnapshot(artifactBinary)(l0Peer)
          .ifM(
            logger.info(s"Sent ${artifactHash.show} to Global L0"),
            logger.error(s"Cannot send ${artifactHash.show} to Global L0 peer ${l0Peer.show}")
          )
      } yield ()

      store >> sendToL0
    }

    override def createProposalArtifact(
      lastKey: SnapshotOrdinal,
      lastSignedArtifact: Signed[CurrencySnapshotArtifact],
      trigger: ConsensusTrigger,
      events: Set[Signed[CurrencyBlock]]
    ): F[(CurrencySnapshotArtifact, Set[Signed[CurrencyBlock]])] = {

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
          lastSignedArtifact.info.balances,
          lastSignedArtifact.info.lastTxRefs,
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

        updatedLastTxRefs = lastSignedArtifact.info.lastTxRefs ++ acceptanceResult.contextUpdate.lastTxRefs
        balances = lastSignedArtifact.info.balances ++ acceptanceResult.contextUpdate.balances
        positiveBalances = balances.filter { case (_, balance) => balance =!= Balance.empty }

        returnedEvents = getReturnedEvents(acceptanceResult)

        artifact = CurrencySnapshot(
          currentOrdinal,
          height,
          subHeight,
          lastArtifactHash,
          accepted,
          SnapshotTips(
            deprecated = deprecated,
            remainedActive = remainedActive
          ),
          CurrencySnapshotInfo(
            updatedLastTxRefs,
            positiveBalances
          )
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
