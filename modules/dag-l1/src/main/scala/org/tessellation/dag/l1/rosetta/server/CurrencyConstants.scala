package org.tessellation.dag.l1.rosetta.server

import org.tessellation.rosetta.server.model.Currency

object CurrencyConstants {
  // TODO Confirm 1e8 is correct multiplier
  val DAGCurrency: Currency = Currency("DAG", 8, None)

}
