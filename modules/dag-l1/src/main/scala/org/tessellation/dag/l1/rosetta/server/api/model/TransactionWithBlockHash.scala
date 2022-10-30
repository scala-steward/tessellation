package org.tessellation.dag.l1.rosetta.server.api.model

import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.signature.Signed

case class TransactionWithBlockHash(
  transaction: Signed[Transaction],
  blockHash: String,
  blockIndex: Long
)
