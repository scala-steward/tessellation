package org.tessellation.dag.l1.rosetta.server

import org.tessellation.dag.l1.rosetta.server.CurrencyConstants.DAGCurrency
import org.tessellation.dag.l1.rosetta.server.enums.CurrencyTransferType.TRANSFER
import org.tessellation.rosetta.server.model._
import org.tessellation.schema.transaction.Transaction

object TransactionUtilities {

  def translateTransactionToOperations(
    tx: Transaction,
    status: String,
    includeNegative: Boolean = true,
    ignoreStatus: Boolean = false
  ): List[Operation] = {
    // TODO: Huge question, do we need to represent another operation for the
    // negative side of this transaction?
    // if so remove list type.
    val operation = Operation(
      OperationIdentifier(if (includeNegative) 1 else 0, None),
      None,
      TRANSFER.toString, // TODO: Replace with enum
      if (ignoreStatus) None else Some(status), // TODO: Replace with enum
      Some(AccountIdentifier(tx.destination.value.value, None, None)),
      Some(
        Amount(
          tx.amount.value.value.toString,
          DAGCurrency,
          None
        )
      ),
      None,
      None
    )

    val operationNegative = Operation(
      OperationIdentifier(0, None),
      None,
      TRANSFER.toString, // TODO: Replace with enum
      if (ignoreStatus) None else Some(status), // TODO: Replace with enum
      Some(AccountIdentifier(tx.source.value.value, None, None)),
      Some(
        Amount(
          (-1L * tx.amount.value.value).toString,
          DAGCurrency,
          None
        )
      ),
      None,
      None
    )

    if (includeNegative) List(operationNegative, operation) else List(operation)
  }
}
