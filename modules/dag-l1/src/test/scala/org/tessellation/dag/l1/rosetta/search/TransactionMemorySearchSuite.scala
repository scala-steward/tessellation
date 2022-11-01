package org.tessellation.dag.l1.rosetta.search

import java.security.KeyPair

import cats.data.NonEmptySet
import cats.effect.{IO, Resource}

import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage.LastTransactionReferenceState
import org.tessellation.dag.l1.{Main, TransactionGenerator}
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.keytool.KeyPairGenerator
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction._
import org.tessellation.sdk.sdkKryoRegistrar
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.{Hashed, SecurityProvider}

import eu.timepit.refined.auto._
import io.chrisdavenport.mapref.MapRef
import weaver.SimpleIOSuite

object TransactionMemorySearchSuite extends SimpleIOSuite with TransactionGenerator {

  type TestResources = (
    TransactionStorage[IO],
    MapRef[IO, Address, Option[LastTransactionReferenceState]],
    MapRef[IO, Address, Option[NonEmptySet[Hashed[Transaction]]]],
    KeyPair,
    Address,
    KeyPair,
    Address,
    SecurityProvider[IO],
    KryoSerializer[IO]
  )

  def testResources: Resource[IO, TestResources] =
    SecurityProvider.forAsync[IO].flatMap { implicit sp =>
      KryoSerializer.forAsync[IO](Main.kryoRegistrar ++ sdkKryoRegistrar).flatMap { implicit kp =>
        for {
          lastAccepted <- MapRef.ofConcurrentHashMap[IO, Address, LastTransactionReferenceState]().asResource
          waitingTransactions <- MapRef.ofConcurrentHashMap[IO, Address, NonEmptySet[Hashed[Transaction]]]().asResource
          transactionStorage = new TransactionStorage[IO](lastAccepted, waitingTransactions)
          key1 <- KeyPairGenerator.makeKeyPair.asResource
          address1 = key1.getPublic.toAddress
          key2 <- KeyPairGenerator.makeKeyPair.asResource
          address2 = key2.getPublic.toAddress
        } yield (transactionStorage, lastAccepted, waitingTransactions, key1, address1, key2, address2, sp, kp)
      }
    }

  test("Get all transactions") {
    testResources.use {
      case (transactionStorage, _, _, key1, address1, key2, address2, sp, kp) =>
        implicit val securityProvider = sp
        implicit val kryoPool = kp

        val memoryStorage = for {
          txsA <- generateTransactions(address1, key1, address2, 2, TransactionFee(3L))
          txsB <- generateTransactions(address2, key2, address1, 2, TransactionFee(2L))
          txsA2 <- generateTransactions(
            address1,
            key1,
            address2,
            2,
            TransactionFee(1L),
            Some(TransactionReference(txsA.last.ordinal, txsA.last.hash))
          )
          txsA3 <- generateTransactions(
            address1,
            key1,
            address2,
            2,
            TransactionFee.zero,
            Some(TransactionReference(txsA2.last.ordinal, txsA2.last.hash))
          )
          _ <- transactionStorage.put((txsA.toList ::: txsA2.toList ::: txsA3.toList ::: txsB.toList).toSet)
        } yield (transactionStorage, txsA, txsB, txsA2)

        memoryStorage.flatMap {
          case (store, txsA, txsB, txsA2) =>
            TransactionMemorySearch
              .make(store)
              .getAllTransactions()
              .map(_.map(transactions => expect.same(transactions, txsA ::: txsB ::: txsA2)).getOrElse(expect(false)))
        }
    }
  }

  test("Get a transaction") {
    testResources.use {
      case (transactionStorage, _, _, key1, address1, key2, address2, sp, kp) =>
        implicit val securityProvider = sp
        implicit val kryoPool = kp

        val memoryStorage = for {
          txsA <- generateTransactions(address1, key1, address2, 2, TransactionFee(3L))
          txsB <- generateTransactions(address2, key2, address1, 2, TransactionFee(2L))
          txsA2 <- generateTransactions(
            address1,
            key1,
            address2,
            2,
            TransactionFee(1L),
            Some(TransactionReference(txsA.last.ordinal, txsA.last.hash))
          )
          txsA3 <- generateTransactions(
            address1,
            key1,
            address2,
            2,
            TransactionFee.zero,
            Some(TransactionReference(txsA2.last.ordinal, txsA2.last.hash))
          )
          _ <- transactionStorage.put((txsA.toList ::: txsA2.toList ::: txsA3.toList ::: txsB.toList).toSet)
        } yield (transactionStorage, txsA)

        memoryStorage.flatMap {
          case (store, transactions) =>
            TransactionMemorySearch
              .make(store)
              .getTransaction(transactions.head.hash)
              .map(_.map { hashedTransaction =>
                expect.same(hashedTransaction, transactions.head)
              }.getOrElse(expect(false)))
        }
    }
  }
}
