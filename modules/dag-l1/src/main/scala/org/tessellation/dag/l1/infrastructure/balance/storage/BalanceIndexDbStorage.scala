package org.tessellation.dag.l1.infrastructure.balance.storage

import cats.effect.MonadCancelThrow
import cats.implicits.{toFunctorOps, toTraverseOps}

import org.tessellation.dag.l1.domain.balance.storage.{BalanceIndexStorage, StoredBalanceIndex}
import org.tessellation.dag.l1.infrastructure.db.Database
import org.tessellation.schema.address.Address
import org.tessellation.schema.height.Height
import org.tessellation.security.hash.Hash

import doobie.implicits._
import doobie.quill.DoobieContext
import io.getquill.context.Context
import io.getquill.{Literal, SqliteDialect}

object BalanceIndexDbStorage {
  type Ctx = DoobieContext.SQLite[Literal] with Context[SqliteDialect, Literal]

  def make[F[_]: MonadCancelThrow: Database]: BalanceIndexStorage[F] =
    make[F](new DoobieContext.SQLite[Literal](Literal))

  def make[F[_]: MonadCancelThrow](ctx: Ctx)(implicit db: Database[F]): BalanceIndexStorage[F] =
    new BalanceIndexStorage[F] {
      val xa = db.xa

      import ctx._

      val getStoredBalances = quote {
        querySchema[StoredBalanceIndex]("BalanceIndex")
      }

      val getStoredBalance = quote { (address: Address, hash: Hash) =>
        getStoredBalances.filter(x => x.address == address && x.hash == hash).take(1)
      }

      val getStoredBalanceTest = quote { (address: Address, hash: Option[Hash], height: Option[Long]) =>
        getStoredBalances.filter(
          x => x.address == address && (height.forall(_ <= x.height) || hash.forall(_ == x.hash))
        )
      }

      val getStoredBalanceSum = quote { (address: Address, hash: Option[Hash], height: Option[Long]) =>
        getStoredBalances
          .filter(x => x.address == address && (height.forall(_ <= x.height) || hash.forall(_ == x.hash)))
          .map(_.balance)
      }

      val insertStoredBalance = quote { (address: Address, hash: Hash, height: Long, balance: Int) =>
        getStoredBalances.insert(
          _.address -> address,
          _.height -> height,
          _.hash -> hash,
          _.balance -> balance
        )
      }

      val updateStoredBalance = quote { (address: Address, hash: Hash, height: Long, balance: Int) =>
        getStoredBalances.update(
          _.address -> address,
          _.height -> height,
          _.hash -> hash,
          _.balance -> balance
        )
      }

      def updateStoredValues(values: Map[(Address, Hash), (Height, Int)]) =
        values.toList.traverse {
          case (key, value) =>
            val address = key._1
            val hash = key._2
            val height = value._1.value.value
            val balance = value._2

            run(getStoredBalance(lift(address), lift(hash))).map(_.headOption).flatMap {
              case Some(_) =>
                run(
                  updateStoredBalance(
                    lift(address),
                    lift(hash),
                    lift(height),
                    lift(balance)
                  )
                )
              case None =>
                run(
                  insertStoredBalance(
                    lift(address),
                    lift(hash),
                    lift(height),
                    lift(balance)
                  )
                )
            }
        }.transact(xa).as(())

      def getStoredValue(address: Address, hash: Option[Hash], height: Option[Long]) =
        run(getStoredBalanceSum(lift(address), lift(hash), lift(height))).transact(xa).map(x => x.sum)
    }
}
