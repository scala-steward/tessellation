package org.tessellation.dag.l1.domain.block.storage

import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

trait BlockIndexStorage[F[_]] {
  def updateStoredBlockIndexValues(values: Map[Hash, (Long, Array[Byte])]): F[Unit]
  def indexGlobalSnapshot(signedGlobalSnapshot: Signed[GlobalSnapshot]): F[Unit]
  def getStoredBlockIndexValue(hash: Option[Hash], index: Option[Long]): F[Option[BlockIndexEntry]]
}
