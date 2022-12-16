package org.tessellation.dag.l1.domain.snapshot.storage

import cats.effect.Ref
import cats.effect.kernel.Async
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.{Applicative, MonadThrow}

import org.tessellation.dag.snapshot.{CurrencySnapshot, SnapshotOrdinal}
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.schema.height.Height
import org.tessellation.sdk.domain.snapshot.storage.LastCurrencySnapshotStorage
import org.tessellation.security.Hashed

import fs2.concurrent.SignallingRef

object LastCurrencySnapshotStorage {

  def make[F[_]: Async: Ref.Make]: F[LastCurrencySnapshotStorage[F]] =
    SignallingRef.of[F, Option[Hashed[CurrencySnapshot]]](None).map(make(_))

  def make[F[_]: MonadThrow](
    snapshotR: SignallingRef[F, Option[Hashed[CurrencySnapshot]]]
  ): LastCurrencySnapshotStorage[F] =
    new LastCurrencySnapshotStorage[F] {
      def set(snapshot: Hashed[CurrencySnapshot]): F[Unit] =
        snapshotR.modify {
          case Some(current) if current.hash === snapshot.lastSnapshotHash && current.ordinal.next === snapshot.ordinal =>
            (snapshot.some, Applicative[F].unit)
          case other =>
            (other, MonadThrow[F].raiseError[Unit](new Throwable("Failure during setting new currency snapshot!")))
        }.flatten

      def setInitial(snapshot: Hashed[CurrencySnapshot]): F[Unit] =
        snapshotR.modify {
          case None => (snapshot.some, Applicative[F].unit)
          case other =>
            (
              other,
              MonadThrow[F].raiseError[Unit](new Throwable(s"Failure setting initial currency snapshot! Encountered non empty "))
            )
        }.flatten

      def get: F[Option[Hashed[CurrencySnapshot]]] =
        snapshotR.get

      def getOrdinal: F[Option[SnapshotOrdinal]] =
        snapshotR.get.map(_.map(_.ordinal))

      def getHeight: F[Option[Height]] = snapshotR.get.map(_.map(_.height))
    }
}
