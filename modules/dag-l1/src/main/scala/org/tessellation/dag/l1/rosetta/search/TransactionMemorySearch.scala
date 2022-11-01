package org.tessellation.dag.l1.rosetta.search

import cats.data.NonEmptyList
import cats.effect.Async

import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.Hashed
import org.tessellation.security.hash.Hash

import eu.timepit.refined.auto._

object TransactionMemorySearch {

  def make[F[_]: Async: KryoSerializer](seeker: TransactionStorage[F]): TransactionMemorySearch[F] =
    new TransactionMemorySearch[F](search = seeker) {
      override def getTransaction(hash: Hash): F[Option[Hashed[Transaction]]] =
        search.find(hash)

      override def getAllTransactions(): F[Option[NonEmptyList[Hashed[Transaction]]]] =
        search.pull(Long.MaxValue)
    }
}

sealed abstract class TransactionMemorySearch[F[_]] private (
  val search: TransactionStorage[F]
) {
  def getTransaction(hash: Hash): F[Option[Hashed[Transaction]]]
  def getAllTransactions(): F[Option[NonEmptyList[Hashed[Transaction]]]]
}
