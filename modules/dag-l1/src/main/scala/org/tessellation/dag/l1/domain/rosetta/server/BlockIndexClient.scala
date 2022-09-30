package org.tessellation.dag.l1.domain.rosetta.server

import org.tessellation.dag.l1.domain.rosetta.server.api.model.BlockSearchRequest
import org.tessellation.dag.l1.rosetta.{AccountBlockResponse, BlockSearchResponse}
import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.rosetta.server.model.dag.schema.ConstructionPayloadsRequestMetadata
import org.tessellation.rosetta.server.model.{BlockIdentifier, PartialBlockIdentifier}
import org.tessellation.schema.address
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.signature.Signed

trait BlockIndexClient[F[_]] {
  def searchBlocks(blockSearchRequest: BlockSearchRequest): F[Either[String, BlockSearchResponse]]
  def queryBlockEvents(limit: Option[Long], offset: Option[Long]): F[Either[String, List[GlobalSnapshot]]]

  def requestLastTransactionMetadata(
    addressActual: address.Address
  ): F[Either[String, Option[ConstructionPayloadsRequestMetadata]]]
  def queryBlockTransaction(blockIdentifier: BlockIdentifier): F[Either[String, Option[Signed[Transaction]]]]
  def queryBlock(blockIdentifier: PartialBlockIdentifier): F[Either[String, Option[GlobalSnapshot]]]
  def findBlock(pbi: PartialBlockIdentifier): F[Option[(GlobalSnapshot, Long)]]

  def queryAccountBalance(
    address: String,
    blockIndex: Option[PartialBlockIdentifier]
  ): F[Either[String, AccountBlockResponse]]
}
