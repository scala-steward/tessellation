package org.tessellation.dag.l1.rosetta.search

import java.security.KeyPair

import cats.data.NonEmptySet
import cats.effect.{IO, Resource}

import org.tessellation.dag.l1.domain.transaction.TransactionStorage.LastTransactionReferenceState
import org.tessellation.dag.l1.domain.transaction.{TransactionService, TransactionStorage}
import org.tessellation.dag.l1.{Main, TransactionGenerator}
import org.tessellation.dag.transaction.{ContextualTransactionValidator, TransactionValidator}
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.keytool.KeyPairGenerator
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction._
import org.tessellation.sdk.sdkKryoRegistrar
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.SignedValidator
import org.tessellation.security.{Hashed, SecurityProvider}

import eu.timepit.refined.auto._
import io.chrisdavenport.mapref.MapRef
import weaver.SimpleIOSuite

object TransactionMemoryServiceSuite extends SimpleIOSuite with TransactionGenerator {

  type TestResources = (
    TransactionStorage[IO],
    TransactionService[IO],
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
          signedValidator = SignedValidator.make[IO]
          transactionValidator = TransactionValidator.make(signedValidator)
          contextualTransactionValidator = ContextualTransactionValidator.make(
            transactionValidator,
            (address: Address) => transactionStorage.getLastAcceptedReference(address)
          )
          transactionService = TransactionService.make(transactionStorage, contextualTransactionValidator)
        } yield
          (
            transactionStorage,
            transactionService,
            lastAccepted,
            waitingTransactions,
            key1,
            address1,
            key2,
            address2,
            sp,
            kp
          )
      }
    }

  test("Get all transactions") {
    testResources.use {
      case (transactionStorage, transactionService, _, _, key1, address1, key2, address2, sp, kp) =>
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
        } yield
          (
            transactionStorage,
            transactionService,
            txsA.toList ::: txsA2.toList ::: txsA3.toList ::: txsB.toList
          )

        memoryStorage.flatMap {
          case (store, service, expectedResults) =>
            TransactionMemoryService
              .make(store, service)
              .getAllTransactions
              .map(
                transactions =>
                  expect.all(transactions.forall(hashedTransactions => expectedResults.contains(hashedTransactions)))
              )
        }
    }
  }

  test("Get a transaction") {
    testResources.use {
      case (transactionStorage, transactionService, _, _, key1, address1, key2, address2, sp, kp) =>
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
        } yield (transactionStorage, transactionService, txsA.head)

        memoryStorage.flatMap {
          case (store, service, transaction) =>
            TransactionMemoryService
              .make(store, service)
              .getTransaction(transaction.hash)
              .map(_.map { hashedTransaction =>
                expect.same(hashedTransaction, transaction)
              }.getOrElse(expect(false)))
        }
    }
  }

  test("Submit a transaction") {
    testResources.use {
      case (transactionStorage, transactionService, _, _, key1, address1, _, address2, sp, kp) =>
        implicit val securityProvider = sp
        implicit val kryoPool = kp

        val memoryStorage = for {
          txsA <- generateTransactions(address1, key1, address2, 2, TransactionFee(3L))
        } yield (transactionStorage, transactionService, txsA.head)

        memoryStorage.flatMap {
          case (store, service, transaction) =>
            val memoryService = TransactionMemoryService
              .make(store, service)

            memoryService.submitTransaction(transaction.signed) >>
              memoryService
                .getTransaction(transaction.hash)
                .map(
                  _.map(hashedTransaction => expect.same(hashedTransaction.hash, transaction.hash))
                    .getOrElse(expect(false))
                )
        }
    }
  }

  test("Get the last accepted transaction reference") {
    testResources.use {
      case (transactionStorage, transactionService, _, _, key1, address1, _, address2, sp, kp) =>
        implicit val securityProvider = sp
        implicit val kryoPool = kp

        val memoryStorage = for {
          txsA <- generateTransactions(address1, key1, address2, 2, TransactionFee(3L))
        } yield (transactionStorage, transactionService, txsA.head)

        memoryStorage.flatMap {
          case (store, service, transaction) =>
            store.setLastAccepted(Map(transaction.source -> transaction.reference)).flatMap { _ =>
              val memoryService = TransactionMemoryService
                .make(store, service)

              memoryService.getLastAcceptedTransactionReference(transaction.source).map { transactionReference =>
                expect.same(transactionReference.hash.value, transaction.reference.hash.value)
              }
            }
        }
    }
  }
}
