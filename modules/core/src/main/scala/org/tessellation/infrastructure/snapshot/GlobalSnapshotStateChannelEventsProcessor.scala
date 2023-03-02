package org.tessellation.infrastructure.snapshot

import cats.Eval
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.list._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.traverse._

import scala.Console.{RESET, YELLOW}
import scala.collection.immutable.SortedMap

import org.tessellation.domain.statechannel.StateChannelValidator
import org.tessellation.ext.cats.syntax.validated._
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.GlobalSnapshotInfo
import org.tessellation.schema.address.Address
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.StateChannelSnapshotBinary
import org.tessellation.syntax.sortedCollection._

import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GlobalSnapshotStateChannelEventsProcessor[F[_]] {
  def process(
    lastGlobalSnapshotInfo: GlobalSnapshotInfo,
    events: List[StateChannelEvent]
  ): F[(SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]], Set[GlobalSnapshotEvent])]
}

object GlobalSnapshotStateChannelEventsProcessor {
  def log[F[_]: Async](str: String): F[Unit] = Slf4jLogger.getLogger.info(s"${YELLOW}${str}${RESET}")

  def make[F[_]: Async: KryoSerializer](stateChannelValidator: StateChannelValidator[F]) =
    new GlobalSnapshotStateChannelEventsProcessor[F] {
      private val logger = Slf4jLogger.getLoggerFromClass[F](GlobalSnapshotStateChannelEventsProcessor.getClass)
      def process(
        lastGlobalSnapshotInfo: GlobalSnapshotInfo,
        events: List[StateChannelEvent]
      ): F[(SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]], Set[GlobalSnapshotEvent])] =
        events
          .traverse(event => stateChannelValidator.validate(event).map(_.errorMap(error => (event.address, error))))
          .map(_.partitionMap(_.toEither))
          .flatTap {
            case (invalid, _) => logger.warn(s"Invalid state channels events: ${invalid}").whenA(invalid.nonEmpty)
          }
          .flatTap {
            case (_, valid) => log(s"Valid state channel events: ${valid}")
          }
          .map { case (_, validatedEvents) => processStateChannelEvents(lastGlobalSnapshotInfo, validatedEvents) }

      private def processStateChannelEvents(
        lastGlobalSnapshotInfo: GlobalSnapshotInfo,
        events: List[StateChannelEvent]
      ): (SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]], Set[GlobalSnapshotEvent]) = {

        val lshToSnapshot: Map[(Address, Hash), StateChannelEvent] = events.map { e =>
          (e.address, e.snapshotBinary.value.lastSnapshotHash) -> e
        }.foldLeft(Map.empty[(Address, Hash), StateChannelEvent]) { (acc, entry) =>
          entry match {
            case (k, newEvent) =>
              acc.updatedWith(k) { maybeEvent =>
                maybeEvent
                  .filter(event => Hash.fromBytes(event.snapshotBinary.content) < Hash.fromBytes(newEvent.snapshotBinary.content))
                  .orElse(newEvent.some)
              }
          }
        }

        println(s"${YELLOW}lshToSnapshot: ${lshToSnapshot}${RESET}")

        val result = events
          .map(_.address)
          .distinct
          .map { address =>
            val x = lastGlobalSnapshotInfo.lastStateChannelSnapshotHashes
              .get(address)
              .map(hash => address -> hash)
              .getOrElse(address -> Hash.empty)
            println(s"${YELLOW}\nlastStateChannelSnapshotHashes: ${x}${RESET}")
            x
          }
          .mapFilter {
            case (address, initLsh) =>
              def unfold(lsh: Hash): Eval[List[StateChannelEvent]] =
                lshToSnapshot
                  .get((address, lsh))
                  .map { go =>
                    for {
                      head <- Eval.now(go)
                      tail <- go.snapshotBinary.hash.fold(_ => Eval.now(List.empty), unfold)
                    } yield head :: tail
                  }
                  .getOrElse(Eval.now(List.empty))

              unfold(initLsh).value.toNel.map(address -> _.map(_.snapshotBinary).reverse) // NonEmptyList(Signed[GenesisBinary]])
          }
          .toSortedMap

        println(s"RESULT: ${result}")

        (result, Set.empty[GlobalSnapshotEvent])
      }
    }

}
