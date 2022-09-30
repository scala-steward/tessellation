package org.tessellation.dag.l1.domain.rosetta.server

import org.tessellation.dag.l1.rosetta.SnapshotInfo
import org.tessellation.kryo.KryoSerializer
import org.tessellation.rosetta.server.model.NetworkStatusResponse
import org.tessellation.rosetta.server.model.dag.schema.ConstructionPayloadsRequestMetadata
import org.tessellation.schema.address
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.signature.Signed

trait L1Client[F[_]] {
  def queryCurrentSnapshotHashAndHeight(): F[Either[String, SnapshotInfo]]
  def queryGenesisSnapshotHash(): F[Either[String, String]]
  def queryPeerIds(): F[Either[String, List[String]]]
  def queryNetworkStatus(): F[Either[String, NetworkStatusResponse]]
  def queryVersion(): F[Either[String, String]]
  def submitTransaction[Q[_]: KryoSerializer](stx: Signed[Transaction]): F[Either[String, Unit]]
  def requestSuggestedFee(): F[Either[String, Option[Long]]]

  def requestLastTransactionMetadataAndFee(
    addressActual: address.Address
  ): F[Either[String, Option[ConstructionPayloadsRequestMetadata]]]
  def queryMempool(): F[List[String]]
  def queryMempoolTransaction(hash: String): F[Option[Signed[Transaction]]]
}
