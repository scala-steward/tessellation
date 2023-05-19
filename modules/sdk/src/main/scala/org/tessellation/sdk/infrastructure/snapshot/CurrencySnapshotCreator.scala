package org.tessellation.sdk.infrastructure.snapshot

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.Async
import cats.implicits.catsSyntaxEitherId
import cats.syntax.applicativeError._
import cats.syntax.applicative._
import cats.syntax.bifunctor._
import cats.syntax.contravariantSemigroupal._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.traverse._
import cats.syntax.option._
import cats.syntax.order._

import scala.collection.immutable.SortedSet
import org.tessellation.currency.schema.currency._
import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.currency.{CurrencySnapshotArtifact, CurrencySnapshotContext, CurrencySnapshotEvent}
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.sdk.domain.block.processing.{BlockAcceptanceResult, deprecationThreshold, _}
import org.tessellation.sdk.domain.rewards.Rewards
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed
import org.tessellation.syntax.sortedCollection.sortedSetSyntax
import eu.timepit.refined.auto._
import org.tessellation.currency.{BaseDataApplicationL0Service, DataState}
import org.tessellation.currency.dataApplication.DataApplicationBlock
import org.typelevel.log4cats.slf4j.Slf4jLogger

case class CurrencySnapshotCreationResult(
  artifact: CurrencySnapshotArtifact,
  context: CurrencySnapshotContext,
  awaitingBlocks: Set[Signed[CurrencyBlock]],
  rejectedBlocks: Set[Signed[CurrencyBlock]]
)

trait CurrencySnapshotCreator[F[_]] {
  def createProposalArtifact(
    lastKey: SnapshotOrdinal,
    lastArtifact: Signed[CurrencySnapshotArtifact],
    lastContext: CurrencySnapshotContext,
    trigger: ConsensusTrigger,
    events: Set[CurrencySnapshotEvent]
  ): F[CurrencySnapshotCreationResult]
}

object CurrencySnapshotCreator {

  def make[F[_]: Async: SecurityProvider: KryoSerializer](
    currencySnapshotAcceptanceManager: CurrencySnapshotAcceptanceManager[F],
    rewards: Rewards[F, CurrencyTransaction, CurrencyBlock, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot],
    maybeDataApplicationService: Option[BaseDataApplicationL0Service[F]]
  ): CurrencySnapshotCreator[F] = new CurrencySnapshotCreator[F] {
    private val logger = Slf4jLogger.getLoggerFromClass(CurrencySnapshotCreator.getClass)

    override def createProposalArtifact(
      lastKey: SnapshotOrdinal,
      lastArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      trigger: ConsensusTrigger,
      events: Set[CurrencySnapshotEvent]
    ): F[CurrencySnapshotCreationResult] = {

      val (blocks: List[Signed[CurrencyBlock]], dataBlocks: List[Signed[DataApplicationBlock]]) = events.collect {
        case event @ Left(_)                                           => event
        case event @ Right(_) if maybeDataApplicationService.isDefined => event
      }.toList.partitionMap(identity)

      val blocksForAcceptance = blocks.toList

      for {
        lastArtifactHash <- lastArtifact.value.hashF
        currentOrdinal = lastArtifact.ordinal.next
        lastActiveTips <- lastArtifact.activeTips
        lastDeprecatedTips = lastArtifact.tips.deprecated

        maybeLastDataApplication = lastArtifact.data

        maybeLastDataState <- (maybeLastDataApplication, maybeDataApplicationService).mapN {
          case ((lastDataApplication, service)) =>
            service
              .deserializeState(lastDataApplication)
              .flatTap {
                case Left(err) => logger.warn(err)(s"Cannot deserialize custom state")
                case Right(a)  => logger.info(s"Deserialized state: ${a}") >> Applicative[F].unit
              }
              .map(_.toOption)
              .handleErrorWith(err =>
                logger.error(err)(s"Unhandled exception during deserialization data application, fallback to empty state") >>
                  none[DataState].pure[F]
              )
        }.flatSequence

        maybeNewDataState <- (maybeDataApplicationService, maybeLastDataState).mapN {
          case ((service, lastState)) =>
            NonEmptyList
              .fromList(dataBlocks.flatMap(_.updates))
              .map { updates =>
                service
                  .validateData(lastState, updates)
                  .map(_.isValid)
                  .handleErrorWith(err =>
                    logger.error(err)(s"Unhandled exception during validating data application, assumed as invalid") >> false.pure[F]
                  )
                  .ifM(
                    service
                      .combine(lastState, updates)
                      .flatMap { state =>
                        service.serializeState(state)
                      }
                      .map(_.some)
                      .handleErrorWith(err =>
                        logger.error(err)(
                          s"Unhandled exception during combine and serialize data application, fallback to last data application"
                        ) >> maybeLastDataApplication.pure[F]
                      ),
                    logger.warn("Data application is not valid") >> maybeLastDataApplication.pure[F]
                  )
              }
              .getOrElse(maybeLastDataApplication.pure[F])
        }.flatSequence

        (acceptanceResult, acceptedRewardTxs, snapshotInfo, stateProof) <- currencySnapshotAcceptanceManager.accept(
          blocksForAcceptance,
          lastContext,
          lastActiveTips,
          lastDeprecatedTips,
          rewards.distribute(lastArtifact, lastContext.balances, _, trigger)
        )

        (awaitingBlocks, rejectedBlocks) = acceptanceResult.notAccepted.partitionMap {
          case (b, _: BlockAwaitReason)     => b.asRight
          case (b, _: BlockRejectionReason) => b.asLeft
        }

        (deprecated, remainedActive, accepted) = getUpdatedTips(
          lastActiveTips,
          lastDeprecatedTips,
          acceptanceResult,
          currentOrdinal
        )

        (height, subHeight) <- getHeightAndSubHeight(lastArtifact, deprecated, remainedActive, accepted)

        artifact = CurrencyIncrementalSnapshot(
          currentOrdinal,
          height,
          subHeight,
          lastArtifactHash,
          accepted,
          acceptedRewardTxs,
          SnapshotTips(
            deprecated = deprecated,
            remainedActive = remainedActive
          ),
          stateProof
        )
        context = CurrencySnapshotInfo(
          lastTxRefs = lastContext.lastTxRefs ++ acceptanceResult.contextUpdate.lastTxRefs,
          balances = lastContext.balances ++ acceptanceResult.contextUpdate.balances
        )
      } yield CurrencySnapshotCreationResult(artifact, context, awaitingBlocks.toSet, rejectedBlocks.toSet)
    }

    protected def getHeightAndSubHeight(
      lastGS: CurrencySnapshotArtifact,
      deprecated: Set[DeprecatedTip],
      remainedActive: Set[ActiveTip],
      accepted: Set[BlockAsActiveTip[CurrencyBlock]]
    ): F[(Height, SubHeight)] = {
      val tipHeights = (deprecated.map(_.block.height) ++ remainedActive.map(_.block.height) ++ accepted
        .map(_.block.height)).toList

      for {
        height <- tipHeights.minimumOption.liftTo[F](NoTipsRemaining)

        _ <-
          if (height < lastGS.height)
            InvalidHeight(lastGS.height, height).raiseError
          else
            Applicative[F].unit

        subHeight = if (height === lastGS.height) lastGS.subHeight.next else SubHeight.MinValue
      } yield (height, subHeight)
    }

    protected def getUpdatedTips(
      lastActive: SortedSet[ActiveTip],
      lastDeprecated: SortedSet[DeprecatedTip],
      acceptanceResult: BlockAcceptanceResult[CurrencyBlock],
      currentOrdinal: SnapshotOrdinal
    ): (SortedSet[DeprecatedTip], SortedSet[ActiveTip], SortedSet[BlockAsActiveTip[CurrencyBlock]]) = {
      val usagesUpdate = acceptanceResult.contextUpdate.parentUsages
      val accepted =
        acceptanceResult.accepted.map { case (block, usages) => BlockAsActiveTip(block, usages) }.toSortedSet
      val (remainedActive, newlyDeprecated) = lastActive.partitionMap { at =>
        val maybeUpdatedUsage = usagesUpdate.get(at.block)
        Either.cond(
          maybeUpdatedUsage.exists(_ >= deprecationThreshold),
          DeprecatedTip(at.block, currentOrdinal),
          maybeUpdatedUsage.map(uc => at.copy(usageCount = uc)).getOrElse(at)
        )
      }.bimap(_.toSortedSet, _.toSortedSet)
      val lowestActiveIntroducedAt = remainedActive.toList.map(_.introducedAt).minimumOption.getOrElse(currentOrdinal)
      val remainedDeprecated = lastDeprecated.filter(_.deprecatedAt > lowestActiveIntroducedAt)

      (remainedDeprecated | newlyDeprecated, remainedActive, accepted)
    }

  }
}
