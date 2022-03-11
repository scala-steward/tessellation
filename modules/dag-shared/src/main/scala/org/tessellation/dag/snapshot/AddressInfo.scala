package org.tessellation.dag.snapshot

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.transaction.TransactionReference

@derive(decoder, encoder, eqv, show)
case class AddressInfo(
  balance: Balance = Balance.empty,
  lastTransactionRef: TransactionReference = TransactionReference.empty
) {}

object AddressInfo {
  def empty = AddressInfo()
}
