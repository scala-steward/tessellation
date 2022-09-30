package org.tessellation.dag.l1.rosetta.search

import cats.effect.MonadCancelThrow

import org.tessellation.dag.l1.domain.balance.storage.BalanceIndexStorage
import org.tessellation.dag.l1.infrastructure.db.Database
import org.tessellation.schema.address.Address
import org.tessellation.schema.height.Height
import org.tessellation.security.hash.Hash

object BalanceSearch {

  def make[F[_]: MonadCancelThrow: Database](storage: BalanceIndexStorage[F]): BalanceSearch[F] =
    new BalanceSearch[F](store = storage) {

      def search(address: Address, hash: Option[Hash], height: Option[Height]) = {
        val heightValue = height.map(_.value.value)

        store.getStoredValue(address, hash, heightValue)
      }
    }
}

sealed abstract class BalanceSearch[F[_]] private (
  val store: BalanceIndexStorage[F]
) {
  def search(address: Address, hash: Option[Hash], height: Option[Height]): F[Int]
}
