package org.tessellation.infrastructure.rewards

import cats.data._
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.list._
import cats.syntax.semigroup._

import scala.collection.immutable.SortedSet
import scala.concurrent.duration.FiniteDuration
import scala.math.Integral.Implicits._

import org.tessellation.config.types.RewardsConfig
import org.tessellation.dag.snapshot.epoch.EpochProgress
import org.tessellation.domain.rewards._
import org.tessellation.schema.ID.Id
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.transaction.{RewardTransaction, TransactionAmount}
import org.tessellation.security.SecurityProvider

import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric._
import io.estatico.newtype.ops.toCoercibleIdOps
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Rewards {

  def make[F[_]: Async: SecurityProvider](
    config: RewardsConfig,
    timeTriggerInterval: FiniteDuration,
    softStaking: SoftStaking,
    dtm: DTM,
    stardust: StardustCollective
  ): Rewards[F] =
    new Rewards[F] {

      implicit val logger = Slf4jLogger.getLogger[F]

      def calculateRewards(
        epochProgress: EpochProgress,
        facilitators: NonEmptySet[Id]
      ): F[SortedSet[RewardTransaction]] =
        EitherT
          .fromEither(getRewardsPerSnapshot(epochProgress, timeTriggerInterval))
          .flatMap { snapshotReward =>
            EitherT.liftF {
              facilitators.toNonEmptyList
                .traverse(_.toAddress)
                .map(_.toNes)
            }.flatMap(createSharedDistribution(epochProgress, snapshotReward, _))
          }
          // Note: Weights need to be validated
          .flatMap { distribution =>
            EitherT.fromEither(weightByStardust(distribution))
//            EitherT.fromEither {
//              Kleisli(weightBySoftStaking(epochProgress))
//                .compose(weightByDTM)
//                .compose(weightByStardust)
//                .apply(distribution)
//            }
          }
          .flatMap { distribution =>
            EitherT.fromEither {
              distribution.toNel.traverse {
                case (address, reward) =>
                  PosLong
                    .from(reward.value)
                    .map(reward => RewardTransaction(address, TransactionAmount(reward)))
                    .left
                    .map[RewardsError](NumberRefinementPredicatedFailure)
              }.map(_.toNes.toSortedSet)
            }
          }
          .flatTap(txs => EitherT.liftF(logger.info(s"Minted reward transactions: $txs")))
          .valueOrF { error =>
            val logError = error match {
              case LastEpochExceeded => logger.warn("Last epoch exceeded. Won't mint anymore")
              case err @ NumberRefinementPredicatedFailure(_) =>
                logger.error(err)("Unhandled refinement predicate failure")
              case IgnoredAllAddressesDuringWeighting =>
                logger.warn("Ignored all the addresses from distribution so weights won't be applied")
            }

            logError.as(SortedSet.empty[RewardTransaction])
          }

      private[rewards] def getRewardsPerSnapshot(
        epochProgress: EpochProgress,
        timeTriggerInterval: FiniteDuration
      ): Either[RewardsError, Amount] =
        for {
          snapshots <- getSnapshotsCountPerEpoch(timeTriggerInterval)
          epoch <- getEpochNumber(epochProgress, snapshots)
          reward <- getTotalRewardsPerEpoch(epoch)
          // Note: We need rounding there to match validator rewards sheet rounding
          rewardPerSnapshot <- NonNegLong
            .from(Math.round(reward.value.toDouble / snapshots))
            .left
            .map(NumberRefinementPredicatedFailure)
        } yield Amount(rewardPerSnapshot)

      private[rewards] def getSnapshotsCountPerEpoch(
        timeTriggerInterval: FiniteDuration
      ): Either[RewardsError, PosLong] =
        PosLong
          .from((config.epochDurationInYears * 12 * 30 * 24 * 60).toLong / timeTriggerInterval.toMinutes)
          .left
          .map(NumberRefinementPredicatedFailure)

      private[rewards] def getTotalRewardsPerEpoch(epochNumber: PosInt): Either[RewardsError, Amount] =
        NonNegLong
          .from(
            (config.baseEpochReward.value / Math.pow(2, epochNumber.toDouble - 1.0)).toLong
          )
          .map(Amount(_))
          .left
          .map(NumberRefinementPredicatedFailure)

      private[rewards] def getEpochNumber(
        epochProgress: EpochProgress,
        snapshotsPerEpoch: PosLong
      ): Either[RewardsError, PosInt] =
        epochProgress match {
          case ordinal if ordinal == EpochProgress.MinValue => Right(1)
          case ordinal =>
            for (epoch <- 1 to config.epochs)
              if (ordinal.value > (epoch - 1) * snapshotsPerEpoch && ordinal.value <= epoch * snapshotsPerEpoch)
                return PosInt.from(epoch).left.map(NumberRefinementPredicatedFailure)
            Left(LastEpochExceeded)
        }

      private[rewards] def createSharedDistribution(
        epochProgress: EpochProgress,
        snapshotReward: Amount,
        addresses: NonEmptySet[Address]
      ): EitherT[F, RewardsError, NonEmptyMap[Address, Amount]] = {

        val (quotient, remainder) = snapshotReward.coerce.toLong /% addresses.length.toLong

        val quotientDistribution = addresses.toNonEmptyList.map(_ -> NonNegLong.unsafeFrom(quotient)).toNem

        EitherT.liftF {
          Random.scalaUtilRandomSeedLong(epochProgress.coerce).flatMap { random =>
            random
              .shuffleList(addresses.toNonEmptyList.toList)
              .map { shuffled =>
                shuffled.take(remainder.toInt)
              }
              .map { top =>
                top.toNel
                  .fold(quotientDistribution) { topNel =>
                    topNel.map(_ -> NonNegLong(1L)).toNem |+| quotientDistribution
                  }
                  .mapBoth { case (addr, reward) => (addr, Amount(reward)) }
              }
          }
        }
      }

      private[rewards] def weightBySoftStaking(
        epochProgress: EpochProgress
      ): NonEmptyMap[Address, Amount] => Either[RewardsError, NonEmptyMap[Address, Amount]] =
        epochProgress match {
          case ordinal if ordinal.value >= config.softStaking.startingOrdinal.value =>
            softStaking.weight(config.softStaking.nodes)(Set(stardust.getAddress, dtm.getAddress))
          case _ => distribution => Either.right(distribution)
        }

      private[rewards] def weightByStardust
        : NonEmptyMap[Address, Amount] => Either[RewardsError, NonEmptyMap[Address, Amount]] =
        stardust.weight(Set.empty)

      private[rewards] def weightByDTM
        : NonEmptyMap[Address, Amount] => Either[RewardsError, NonEmptyMap[Address, Amount]] =
        dtm.weight(Set(stardust.getAddress))

    }

}
