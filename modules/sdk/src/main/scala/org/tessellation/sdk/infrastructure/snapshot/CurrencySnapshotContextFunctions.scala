package org.tessellation.sdk.infrastructure.snapshot

import cats.data.{NonEmptyChain, Validated}
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import scala.util.control.NoStackTrace

import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.domain.snapshot.SnapshotContextFunctions
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._

abstract class CurrencySnapshotContextFunctions[F[_]] extends SnapshotContextFunctions[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo]

object CurrencySnapshotContextFunctions {
  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    validator: CurrencySnapshotValidator[F],
    snapshotAcceptanceManager: CurrencySnapshotAcceptanceManager[F]
  ) =
    new CurrencySnapshotContextFunctions[F] {
      def createContext(
        context: CurrencySnapshotInfo,
        lastArtifact: Signed[CurrencyIncrementalSnapshot],
        signedArtifact: Signed[CurrencyIncrementalSnapshot]
      ): F[CurrencySnapshotInfo] = for {

        validatedS <- validator.validateSnapshot(lastArtifact, context, signedArtifact)
        validatedContext <- validatedS match {
          case Validated.Valid((_, validatedContext)) => validatedContext.pure[F]
          case Validated.Invalid(e)                   => CannotCreateContext(e).raiseError[F, CurrencySnapshotInfo]
        }
      } yield validatedContext
    }
  @derive(eqv, show)
  case class CannotCreateContext(reasons: NonEmptyChain[CurrencySnapshotValidationError]) extends NoStackTrace {

    override def getMessage: String =
      s"Cannot build currency snapshot ${reasons.show}"
  }
}
