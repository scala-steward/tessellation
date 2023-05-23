package org.tessellation.sdk.infrastructure.snapshot

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.traverse._

import scala.collection.immutable.SortedMap

import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshot, CurrencySnapshotInfo}
import org.tessellation.ext.cats.syntax.validated._
import org.tessellation.json.JsonBinarySerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import org.tessellation.sdk.domain.statechannel.StateChannelValidator
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GlobalSnapshotStateChannelEventsProcessor[F[_]] {
  def process(
    snapshotOrdinal: SnapshotOrdinal,
    lastGlobalSnapshotInfo: GlobalSnapshotInfo,
    events: List[StateChannelOutput]
  ): F[
    (
      SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
      SortedMap[Address, (Option[Signed[CurrencyIncrementalSnapshot]], CurrencySnapshotInfo)],
      Set[StateChannelOutput]
    )
  ]
}

object GlobalSnapshotStateChannelEventsProcessor {
  def make[F[_]: Async: KryoSerializer](
    stateChannelValidator: StateChannelValidator[F],
    stateChannelManager: GlobalSnapshotStateChannelAcceptanceManager[F],
    currencySnapshotContextFns: CurrencySnapshotContextFunctions[F]
  ) =
    new GlobalSnapshotStateChannelEventsProcessor[F] {
      private val logger = Slf4jLogger.getLoggerFromClass[F](GlobalSnapshotStateChannelEventsProcessor.getClass)
      def process(
        snapshotOrdinal: SnapshotOrdinal,
        lastGlobalSnapshotInfo: GlobalSnapshotInfo,
        events: List[StateChannelOutput]
      ): F[
        (
          SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
          SortedMap[Address, (Option[Signed[CurrencyIncrementalSnapshot]], CurrencySnapshotInfo)],
          Set[StateChannelOutput]
        )
      ] =
        events
          .traverse(event => stateChannelValidator.validate(event).map(_.errorMap(error => (event.address, error))))
          .map(_.partitionMap(_.toEither))
          .flatTap {
            case (invalid, _) => logger.warn(s"Invalid state channels events: ${invalid}").whenA(invalid.nonEmpty)
          }
          .flatMap { case (_, validatedEvents) => processStateChannelEvents(snapshotOrdinal, lastGlobalSnapshotInfo, validatedEvents) }
          .flatMap {
            case (scSnapshots, returnedSCEvents) =>
              processCurrencySnapshots(lastGlobalSnapshotInfo, scSnapshots).map((scSnapshots, _, returnedSCEvents))
          }

      private def applyCurrencySnapshot(
        lastState: CurrencySnapshotInfo,
        lastSnapshot: Signed[CurrencyIncrementalSnapshot],
        snapshot: Signed[CurrencyIncrementalSnapshot]
      ): F[CurrencySnapshotInfo] = currencySnapshotContextFns.createContext(lastState, lastSnapshot, snapshot)

      private def processCurrencySnapshots(
        lastGlobalSnapshotInfo: GlobalSnapshotInfo,
        events: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]]
      ): F[SortedMap[Address, (Option[Signed[CurrencyIncrementalSnapshot]], CurrencySnapshotInfo)]] =
        events.toList
          .foldLeftM(SortedMap.empty[Address, (Option[Signed[CurrencyIncrementalSnapshot]], CurrencySnapshotInfo)]) {
            case (agg, (address, binaries)) =>
              type Success = (Option[Signed[CurrencyIncrementalSnapshot]], CurrencySnapshotInfo)
              type Agg =
                (Option[(Option[Signed[CurrencyIncrementalSnapshot]], CurrencySnapshotInfo)], List[Signed[StateChannelSnapshotBinary]])
              type Result = Option[Success]

              (lastGlobalSnapshotInfo.lastCurrencySnapshots.get(address), binaries.toList.reverse)
                .tailRecM[F, Result] {
                  case (state, Nil) => state.asRight[Agg].pure[F]

                  case (None, head :: tail) =>
                    JsonBinarySerializer
                      .deserialize[Signed[CurrencySnapshot]](head.value.content)
                      .toOption
                      .map { snapshot =>
                        ((none[Signed[CurrencyIncrementalSnapshot]], snapshot.info).some, tail).asLeft[Result]
                      }
                      .getOrElse((none[Success], tail).asLeft[Result])
                      .pure[F]

                  case (Some((maybeLastSnapshot, lastState)), head :: tail) =>
                    JsonBinarySerializer
                      .deserialize[Signed[CurrencyIncrementalSnapshot]](head.value.content)
                      .toOption
                      .map { snapshot =>
                        maybeLastSnapshot
                          .map(applyCurrencySnapshot(lastState, _, snapshot))
                          .getOrElse(lastState.pure[F])
                          .map { state =>
                            ((snapshot.some, state).some, tail).asLeft[Result]
                          }
                      }
                      .getOrElse(((maybeLastSnapshot, lastState).some, tail).asLeft[Result].pure[F])
                }
                .map {
                  _.map(updated => agg + (address -> updated)).getOrElse(agg)
                }

          }

      private def processStateChannelEvents(
        ordinal: SnapshotOrdinal,
        lastGlobalSnapshotInfo: GlobalSnapshotInfo,
        events: List[StateChannelOutput]
      ): F[(SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]], Set[StateChannelOutput])] =
        stateChannelManager.accept(ordinal, lastGlobalSnapshotInfo, events)

    }

}
