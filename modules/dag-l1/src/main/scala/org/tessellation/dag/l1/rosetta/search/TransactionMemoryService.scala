package org.tessellation.dag.l1.rosetta.search

import cats.data.NonEmptyList
import cats.effect.Async

import org.tessellation.dag.l1.domain.transaction.{TransactionService, TransactionStorage}
import org.tessellation.dag.transaction.ContextualTransactionValidator.ContextualTransactionValidationError
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction.{Transaction, TransactionReference}
import org.tessellation.security.Hashed
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

object TransactionMemoryService {

  def make[F[_]: Async](
    seeker: TransactionStorage[F],
    service: TransactionService[F]
  ): TransactionMemoryService[F] =
    new TransactionMemoryService[F](search = seeker, service = service) {
      override def getTransaction(hash: Hash): F[Option[Hashed[Transaction]]] =
        search.find(hash)

      override def getAllTransactions: F[List[Hashed[Transaction]]] =
        search.getAllWaitingTransactions()

      override def submitTransaction(
        transaction: Signed[Transaction]
      ): F[Either[NonEmptyList[ContextualTransactionValidationError], Hash]] =
        service.offer(transaction)

      override def getLastAcceptedTransactionReference(address: Address): F[TransactionReference] =
        search.getLastAcceptedReference(address)
    }
}

sealed abstract class TransactionMemoryService[F[_]] private (
  val search: TransactionStorage[F],
  val service: TransactionService[F]
) {
  def getTransaction(hash: Hash): F[Option[Hashed[Transaction]]]
  def getAllTransactions: F[List[Hashed[Transaction]]]

  def submitTransaction(
    transaction: Signed[Transaction]
  ): F[Either[NonEmptyList[ContextualTransactionValidationError], Hash]]
  def getLastAcceptedTransactionReference(address: Address): F[TransactionReference]
}
