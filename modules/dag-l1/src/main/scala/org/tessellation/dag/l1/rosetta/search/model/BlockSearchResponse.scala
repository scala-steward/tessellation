package org.tessellation.dag.l1.rosetta.search.model

import org.tessellation.dag.l1.rosetta.server.api.model.TransactionWithBlockHash

case class BlockSearchResponse(
  transactions: List[TransactionWithBlockHash],
  total: Long,
  nextOffset: Option[Long]
)
