package org.tessellation.currency.l0.snapshot

import cats.effect.Async
import cats.syntax.all._

import org.tessellation.currency.dataApplication.DataApplicationBlock
import org.tessellation.currency.l0.snapshot.services.StateChannelSnapshotService
import org.tessellation.currency.schema.currency._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.currency.consensus.{CurrencySnapshotArtifact, CurrencySnapshotContext, CurrencySnapshotEvent}
import org.tessellation.sdk.domain.consensus.ConsensusFunctions.InvalidArtifact
import org.tessellation.sdk.domain.rewards.Rewards
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.sdk.infrastructure.snapshot._
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

abstract class CurrencySnapshotConsensusFunctions[F[_]: Async: SecurityProvider]
    extends SnapshotConsensusFunctions[
      F,
      CurrencyTransaction,
      CurrencyBlock,
      CurrencySnapshotStateProof,
      CurrencySnapshotEvent,
      CurrencySnapshotArtifact,
      CurrencySnapshotContext,
      ConsensusTrigger
    ] {}

object CurrencySnapshotConsensusFunctions {

  def make[F[_]: Async: KryoSerializer: SecurityProvider: Metrics](
    stateChannelSnapshotService: StateChannelSnapshotService[F],
    collateral: Amount,
    rewards: Rewards[F, CurrencyTransaction, CurrencyBlock, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot],
    currencySnapshotCreator: CurrencySnapshotCreator[F],
    currencySnapshotValidator: CurrencySnapshotValidator[F]
  ): CurrencySnapshotConsensusFunctions[F] = new CurrencySnapshotConsensusFunctions[F] {

    def getRequiredCollateral: Amount = collateral

    def consumeSignedMajorityArtifact(signedArtifact: Signed[CurrencyIncrementalSnapshot], context: CurrencySnapshotInfo): F[Unit] =
      stateChannelSnapshotService.consume(signedArtifact, context)

    override def validateArtifact(
      lastArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      trigger: ConsensusTrigger,
      artifact: CurrencySnapshotArtifact
    ): F[Either[InvalidArtifact, (CurrencySnapshotArtifact, CurrencySnapshotContext)]] =
      currencySnapshotValidator
        .validateSnapshot(lastArtifact, lastContext, artifact)
        .map(_.leftMap(_ => ArtifactMismatch).toEither)

    override def createProposalArtifact(
      lastKey: SnapshotOrdinal,
      lastArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      trigger: ConsensusTrigger,
      events: Set[CurrencySnapshotEvent]
    ): F[(CurrencySnapshotArtifact, CurrencySnapshotContext, Set[CurrencySnapshotEvent])] = {
      val blocksForAcceptance: Set[CurrencySnapshotEvent] = events.filter {
        case Left(currencyBlock) => currencyBlock.height > lastArtifact.height
        case Right(_)            => true
      }

      currencySnapshotCreator
        .createProposalArtifact(lastKey, lastArtifact, lastContext, trigger, blocksForAcceptance, rewards)
        .map(created => (created.artifact, created.context, created.awaitingBlocks.map(_.asLeft[Signed[DataApplicationBlock]])))
    }
  }
}
