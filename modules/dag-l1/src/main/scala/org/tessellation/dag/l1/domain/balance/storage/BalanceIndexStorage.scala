package org.tessellation.dag.l1.domain.balance.storage

import org.tessellation.schema.address.Address
import org.tessellation.schema.height.Height
import org.tessellation.security.hash.Hash

trait BalanceIndexStorage[F[_]] {
  def updateStoredValues(values: Map[(Address, Hash), (Height, Int)]): F[Unit]
  def getStoredValue(address: Address, hash: Option[Hash], height: Option[Long]): F[Int]
}
