package org.tessellation.infrastructure.rewards

import cats.data.NonEmptyMap

import scala.concurrent.duration.FiniteDuration

import org.tessellation.config.types.DTMConfig
import org.tessellation.domain.rewards._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric._

object DTM {

  def make(config: DTMConfig, timeTriggerInterval: FiniteDuration): DTM = new DTM {

    def getAddress: Address = config.address

    def weight(
      ignore: Set[Address]
    )(distribution: NonEmptyMap[Address, Amount]): Either[RewardsError, NonEmptyMap[Address, Amount]] =
      for {
        withoutIgnored <- NonEmptyMap
          .fromMap(ignore.foldLeft(distribution.toSortedMap) {
            case (acc, addressToIgnore) => acc - addressToIgnore
          })
          .toRight[RewardsError](IgnoredAllAddressesDuringWeighting)

        ignored <- NonEmptyMap
          .fromMap(withoutIgnored.keys.foldLeft(distribution.toSortedMap) {
            case (acc, nonIgnoredAddress) => acc - nonIgnoredAddress
          })
          .toRight[RewardsError](IgnoredAllAddressesDuringWeighting)

        total = withoutIgnored.map(_.value.toLong).reduceLeft(_ + _)
        perSnapshot = Math.floorDiv(Math.floorDiv(config.monthly.value, 30 * 24 * 60), timeTriggerInterval.toMinutes)
        reduced = total - perSnapshot

        perNode <- NonNegLong
          .from(
            if (reduced >= 0) Math.floorDiv(reduced, distribution.length.toLong) else 0L
          )
          .left
          .map(NumberRefinementPredicatedFailure)

        weighted = withoutIgnored.transform { case (_, _) => Amount(perNode) } ++ ignored
      } yield weighted.add(getAddress -> Amount(NonNegLong.unsafeFrom(perSnapshot)))
  }
}
