package org.tessellation.dag.l1.domain.balance.storage

import org.tessellation.schema.address.Address
import org.tessellation.security.hash.Hash

case class StoredBalanceIndex(address: Address, height: Long, hash: Hash, balance: Int)
