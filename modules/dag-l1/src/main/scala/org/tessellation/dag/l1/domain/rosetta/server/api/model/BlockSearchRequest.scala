package org.tessellation.dag.l1.domain.rosetta.server.api.model

case class BlockSearchRequest(
  isOr: Boolean,
  isAnd: Boolean,
  addressOpt: Option[String],
  networkStatus: Option[String],
  limit: Option[Long],
  offset: Option[Long],
  transactionHash: Option[String],
  maxBlock: Option[Long]
)
