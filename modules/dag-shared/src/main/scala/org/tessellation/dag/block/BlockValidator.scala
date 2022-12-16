package org.tessellation.dag.block

import cats.data.{NonEmptyList, ValidatedNec}
import cats.effect.Async
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.list._
import cats.syntax.option._
import cats.syntax.validated._
import cats.{Functor, Order}

import org.tessellation.dag.block.BlockValidator.{BlockValidationErrorOr, BlockValidationParams}
import org.tessellation.dag.domain.block.{Block, BlockReference}
import org.tessellation.dag.transaction.TransactionChainValidator.{TransactionChainBroken, TransactionNel}
import org.tessellation.dag.transaction.TransactionValidator.TransactionValidationError
import org.tessellation.dag.transaction.{TransactionChainValidator, TransactionValidator}
import org.tessellation.ext.cats.syntax.validated._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction.{Transaction, TransactionReference}
import org.tessellation.security.signature.SignedValidator.SignedValidationError
import org.tessellation.security.signature.{Signed, SignedValidator}

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt

trait BlockValidator[F[_], A <: Transaction, B <: Block[A]] {

  def validate(
    signedBlock: Signed[B],
    params: BlockValidationParams = BlockValidationParams.default
  ): F[BlockValidationErrorOr[(Signed[B], Map[Address, TransactionNel[A]])]]

  def validateGetBlock(
    signedBlock: Signed[B],
    params: BlockValidationParams = BlockValidationParams.default
  )(implicit ev: Functor[F]): F[BlockValidationErrorOr[Signed[B]]] =
    validate(signedBlock, params).map(_.map(_._1))

  def validateGetTxChains(
    signedBlock: Signed[B],
    params: BlockValidationParams = BlockValidationParams.default
  )(implicit ev: Functor[F]): F[BlockValidationErrorOr[Map[Address, TransactionNel[A]]]] =
    validate(signedBlock, params).map(_.map(_._2))

}

object BlockValidator {

  def make[F[_]: Async: KryoSerializer, A <: Transaction, B <: Block[A]](
    signedValidator: SignedValidator[F],
    transactionChainValidator: TransactionChainValidator[F, A],
    transactionValidator: TransactionValidator[F, A]
  ): BlockValidator[F, A, B] =
    new BlockValidator[F, A, B] {

      def validate(
        signedBlock: Signed[B],
        params: BlockValidationParams
      ): F[BlockValidationErrorOr[(Signed[B], Map[Address, TransactionNel[A]])]] =
        for {
          signedV <- validateSigned(signedBlock, params)
          transactionsV <- validateTransactions(signedBlock)
          propertiesV = validateProperties(signedBlock, params)
          transactionChainV <- validateTransactionChain(signedBlock)
        } yield
          signedV
            .productR(transactionsV)
            .productR(propertiesV)
            .product(transactionChainV)

      private def validateSigned(
        signedBlock: Signed[B],
        params: BlockValidationParams
      ): F[BlockValidationErrorOr[Signed[B]]] =
        signedValidator
          .validateSignatures(signedBlock)
          .map { signaturesV =>
            signaturesV
              .productR(signedValidator.validateUniqueSigners(signedBlock))
              .productR(signedValidator.validateMinSignatureCount(signedBlock, params.minSignatureCount))
          }
          .map(_.errorMap[BlockValidationError](InvalidSigned))

      private def validateTransactions(
        signedBlock: Signed[B]
      ): F[BlockValidationErrorOr[Signed[B]]] =
        signedBlock.value.transactions.toNonEmptyList.traverse { signedTransaction =>
          for {
            txRef <- TransactionReference.of(signedTransaction)
            txV <- transactionValidator.validate(signedTransaction)
          } yield txV.errorMap(InvalidTransaction(txRef, _))
        }.map { vs =>
          vs.foldLeft(signedBlock.validNec[BlockValidationError]) { (acc, v) =>
            acc.productL(v)
          }
        }

      private def validateTransactionChain(
        signedBlock: Signed[B]
      ): F[BlockValidationErrorOr[Map[Address, TransactionNel[A]]]] =
        transactionChainValidator
          .validate(signedBlock.transactions)
          .map(_.errorMap[BlockValidationError](InvalidTransactionChain))

      private def validateProperties(
        signedBlock: Signed[B],
        params: BlockValidationParams
      ): BlockValidationErrorOr[Signed[B]] =
        validateParentCount(signedBlock, params)
          .productR(validateUniqueParents(signedBlock))

      private def validateParentCount(
        signedBlock: Signed[B],
        params: BlockValidationParams
      ): BlockValidationErrorOr[Signed[B]] =
        if (signedBlock.parent.size >= params.minParentCount)
          signedBlock.validNec
        else
          NotEnoughParents(signedBlock.parent.size, params.minParentCount).invalidNec

      private def validateUniqueParents(
        signedBlock: Signed[B]
      ): BlockValidationErrorOr[Signed[B]] =
        duplicatedValues[BlockReference](signedBlock.parent).toNel
          .map(NonUniqueParents)
          .toInvalidNec(signedBlock)

      private def duplicatedValues[T: Order](values: NonEmptyList[T]): List[T] =
        values.groupBy(identity).toList.mapFilter {
          case (value, occurrences) =>
            if (occurrences.tail.nonEmpty)
              value.some
            else
              none
        }
    }

  case class BlockValidationParams(minSignatureCount: PosInt, minParentCount: PosInt)

  object BlockValidationParams {
    val default: BlockValidationParams = BlockValidationParams(minSignatureCount = 3, minParentCount = 2)
  }

  @derive(eqv, show)
  sealed trait BlockValidationError
  case class InvalidTransactionChain(error: TransactionChainBroken) extends BlockValidationError
  case class InvalidTransaction(transactionReference: TransactionReference, error: TransactionValidationError) extends BlockValidationError
  case class InvalidSigned(error: SignedValidationError) extends BlockValidationError
  case class NotEnoughParents(parentCount: Int, minParentCount: Int) extends BlockValidationError
  case class NonUniqueParents(duplicatedParents: NonEmptyList[BlockReference]) extends BlockValidationError

  type BlockValidationErrorOr[A] = ValidatedNec[BlockValidationError, A]
}
