package org.tessellation.sdk.domain

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.functor._

import scala.concurrent.duration.FiniteDuration

import fs2.Stream

trait Daemon[F[_]] {
  def start: F[Unit]
}

object Daemon {

  def spawn[F[_]: Async](thunk: F[Unit])(implicit S: Supervisor[F]): Daemon[F] = new Daemon[F] {
    def start: F[Unit] = S.supervise(thunk).void
  }

  def periodic[F[_]: Async](thunk: F[Unit], sleepTime: FiniteDuration)(implicit S: Supervisor[F]): Daemon[F] =
    new Daemon[F] {
      def start: F[Unit] =
        S.supervise(
          Stream
            .awakeEvery(sleepTime)
            .evalMap(_ => thunk)
            .compile
            .drain
        ).void
    }

}
