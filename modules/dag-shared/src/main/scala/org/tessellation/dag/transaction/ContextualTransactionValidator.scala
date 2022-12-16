package org.tessellation.dag.transaction

import cats.data.ValidatedNec
import cats.effect.Async
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.order._
import cats.syntax.validated._

import org.tessellation.dag.transaction.TransactionValidator.TransactionValidationError
import org.tessellation.ext.cats.syntax.validated._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction.{Transaction, TransactionOrdinal, TransactionReference}
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._

trait ContextualTransactionValidator[F[_], A <: Transaction] {

  import ContextualTransactionValidator._

  def validate(
    signedTransaction: Signed[A]
  ): F[ContextualTransactionValidationErrorOr[Signed[A]]]

}

object ContextualTransactionValidator {

  trait TransactionValidationContext[F[_]] {

    def getLastTransactionRef(address: Address): F[TransactionReference]

  }

  def make[F[_]: Async: KryoSerializer, A <: Transaction](
    nonContextualValidator: TransactionValidator[F, A],
    context: TransactionValidationContext[F]
  ): ContextualTransactionValidator[F, A] =
    new ContextualTransactionValidator[F, A] {

      def validate(
        signedTransaction: Signed[A]
      ): F[ContextualTransactionValidationErrorOr[Signed[A]]] =
        for {
          nonContextuallyV <- validateNonContextually(signedTransaction)
          lastTxRefV <- validateLastTransactionRef(signedTransaction)
        } yield
          nonContextuallyV
            .productR(lastTxRefV)

      private def validateNonContextually(
        signedTx: Signed[A]
      ): F[ContextualTransactionValidationErrorOr[Signed[A]]] =
        nonContextualValidator
          .validate(signedTx)
          .map(_.errorMap(NonContextualValidationError))

      private def validateLastTransactionRef(
        signedTx: Signed[A]
      ): F[ContextualTransactionValidationErrorOr[Signed[A]]] =
        context.getLastTransactionRef(signedTx.source).map { lastTxRef =>
          if (signedTx.parent.ordinal > lastTxRef.ordinal)
            signedTx.validNec[ContextualTransactionValidationError]
          else if (signedTx.parent.ordinal < lastTxRef.ordinal)
            ParentOrdinalLowerThenLastTxOrdinal(signedTx.parent.ordinal, lastTxRef.ordinal).invalidNec
          else {
            if (signedTx.parent.hash =!= lastTxRef.hash)
              ParentHashDifferentThanLastTxHash(signedTx.parent.hash, lastTxRef.hash).invalidNec
            else
              signedTx.validNec[ContextualTransactionValidationError]
          }
        }
    }

  @derive(eqv, show)
  sealed trait ContextualTransactionValidationError
  case class ParentOrdinalLowerThenLastTxOrdinal(parentOrdinal: TransactionOrdinal, lastTxOrdinal: TransactionOrdinal)
      extends ContextualTransactionValidationError
  case class ParentHashDifferentThanLastTxHash(parentHash: Hash, lastTxHash: Hash) extends ContextualTransactionValidationError
  case class NonContextualValidationError(error: TransactionValidationError) extends ContextualTransactionValidationError

  type ContextualTransactionValidationErrorOr[A] = ValidatedNec[ContextualTransactionValidationError, A]
}
