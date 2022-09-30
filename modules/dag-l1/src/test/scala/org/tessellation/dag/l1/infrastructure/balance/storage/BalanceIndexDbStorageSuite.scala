package org.tessellation.dag.l1.infrastructure.balance.storage

import cats.effect.{IO, Resource}

import org.tessellation.dag.l1.config.types.DBConfig
import org.tessellation.dag.l1.domain.balance.storage.BalanceIndexStorage
import org.tessellation.dag.l1.infrastructure.balance.storage.AddressGenerator.addressGen
import org.tessellation.dag.l1.infrastructure.db.Database
import org.tessellation.schema.height.Height
import org.tessellation.security.hash.Hash

import ciris.Secret
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.NonNegative
import eu.timepit.refined.scalacheck.numeric._
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen.{chooseNum, listOfN}
import weaver._
import weaver.scalacheck._

object BalanceIndexDbStorageSuite extends SimpleIOSuite with Checkers {

  val dataGenerator = for {
    address <- addressGen
    height <- arbitrary[Long Refined NonNegative].map(Height(_))
    hash <- arbitrary[Hash]
    balance <- chooseNum(0, 100)
  } yield (address, hash) -> (height, balance)

  val generatedData = listOfN(3, dataGenerator)

  val dbConfig = DBConfig("org.sqlite.JDBC", "jdbc:sqlite::memory:", "sa", Secret(""))

  def testResource: Resource[IO, BalanceIndexStorage[IO]] = Database.forAsync[IO](dbConfig).map { implicit db =>
    BalanceIndexDbStorage.make[IO]
  }

  test("Test writing balance index values to the database - height") {
    forall(generatedData) { data =>
      testResource.use { store =>
        val referenceAddress = data.head._1._1
        val referenceHeight = data.head._2._1
        val arguments = data.map(datum => (referenceAddress, datum._1._2) -> (referenceHeight, datum._2._2)).toMap
        val expectedSum = data.map(_._2._2).sum

        store.updateStoredValues(arguments) >>
          store
            .getStoredValue(referenceAddress, None, Some(referenceHeight.value.value))
            .map(expect.same(expectedSum, _))
      }
    }
  }

  test("Test writing balance index values to the database - hash") {
    forall(generatedData) { data =>
      testResource.use { store =>
        val referenceAddress = data.head._1._1
        val referenceHash = data.head._1._2
        val referenceBalance = data.head._2._2
        val arguments = data.map(datum => datum._1 -> (datum._2._1, datum._2._2)).toMap

        store.updateStoredValues(arguments) >>
          store.getStoredValue(referenceAddress, Some(referenceHash), None).map(expect.same(referenceBalance, _))
      }
    }
  }
}
