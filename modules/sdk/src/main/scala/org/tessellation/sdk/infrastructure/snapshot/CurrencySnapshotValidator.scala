package org.tessellation.sdk.infrastructure.snapshot

import cats.data.ValidatedNec
import cats.effect.kernel.Async
import cats.syntax.all._

import org.tessellation.currency.schema.currency.{CurrencyBlock, CurrencyIncrementalSnapshot}
import org.tessellation.ext.cats.syntax.validated.validatedSyntax
import org.tessellation.schema.currency.{CurrencySnapshotArtifact, CurrencySnapshotContext}
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.security.signature.SignedValidator.SignedValidationError
import org.tessellation.security.signature.{Signed, SignedValidator}

import derevo.cats.{eqv, show}
import derevo.derive

trait CurrencySnapshotValidator[F[_]] {

  type CurrencySnapshotValidationErrorOr[A] = ValidatedNec[CurrencySnapshotValidationError, A]

  def validateSignedSnapshot(
    lastSignedArtifact: Signed[CurrencySnapshotArtifact],
    lastContext: CurrencySnapshotContext,
    trigger: ConsensusTrigger,
    artifact: CurrencySnapshotArtifact
  ): F[CurrencySnapshotValidationErrorOr[(Signed[CurrencyIncrementalSnapshot], CurrencySnapshotContext)]]

}

object CurrencySnapshotValidator {

  def make[F[_]: Async](
    currencySnapshotCreator: CurrencySnapshotCreator[F],
    signedValidator: SignedValidator[F]
  ): CurrencySnapshotValidator[F] = new CurrencySnapshotValidator[F] {

    def validateSignedSnapshot(
      lastSignedArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      trigger: ConsensusTrigger,
      artifact: CurrencySnapshotArtifact
    ): F[CurrencySnapshotValidationErrorOr[(Signed[CurrencyIncrementalSnapshot], CurrencySnapshotContext)]] =
      validateSigned(lastSignedArtifact).flatMap { signedV =>
        validateSnapshot(lastSignedArtifact, lastContext, trigger, artifact).map { snapshotV =>
          signedV.product(snapshotV.map { case (_, info) => info })
        }
      }

    private def validateSnapshot(
      lastSignedArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      trigger: ConsensusTrigger,
      artifact: CurrencySnapshotArtifact
    ): F[CurrencySnapshotValidationErrorOr[(CurrencyIncrementalSnapshot, CurrencySnapshotContext)]] = {
      val events = artifact.blocks.unsorted.map(_.block)

      currencySnapshotCreator.createProposalArtifact(lastSignedArtifact.ordinal, lastSignedArtifact, lastContext, trigger, events).map {
        creationResult =>
          validateContent(creationResult.artifact, artifact)
            .productL(validateNotAcceptedBlocks(creationResult))
            .map(snapshot => (snapshot, creationResult.context))
      }
    }

    private def validateSigned(
      signedSnapshot: Signed[CurrencyIncrementalSnapshot]
    ): F[CurrencySnapshotValidationErrorOr[Signed[CurrencyIncrementalSnapshot]]] =
      signedValidator.validateSignatures(signedSnapshot).map(_.errorMap(InvalidSigned))

    private def validateContent(
      actual: CurrencyIncrementalSnapshot,
      expected: CurrencyIncrementalSnapshot
    ): CurrencySnapshotValidationErrorOr[CurrencyIncrementalSnapshot] =
      if (actual =!= expected)
        SnapshotDifferentThanExpected(actual, expected).invalidNec
      else
        actual.validNec

    private def validateNotAcceptedBlocks(
      creationResult: CurrencySnapshotCreationResult
    ): CurrencySnapshotValidationErrorOr[Unit] =
      if (creationResult.awaitingBlocks.nonEmpty || creationResult.rejectedBlocks.nonEmpty)
        SomeBlocksWereNotAccepted(creationResult.awaitingBlocks, creationResult.rejectedBlocks).invalidNec
      else ().validNec
  }

}

@derive(eqv, show)
sealed trait CurrencySnapshotValidationError

case class SnapshotDifferentThanExpected(expected: CurrencyIncrementalSnapshot, actual: CurrencyIncrementalSnapshot)
    extends CurrencySnapshotValidationError

case class SomeBlocksWereNotAccepted(awaitingBlocks: Set[Signed[CurrencyBlock]], rejectedBlocks: Set[Signed[CurrencyBlock]])
    extends CurrencySnapshotValidationError

case class InvalidSigned(error: SignedValidationError) extends CurrencySnapshotValidationError
