package org.tessellation.dag.l1.domain.block.storage

import org.tessellation.security.hash.Hash

trait BlockIndexStorage[F[_]] {
  def updateStoredBlockIndexValues(values: Map[Hash, (Long, Array[Byte])]): F[Unit]
  def getStoredBlockIndexValue(hash: Option[Hash], index: Option[Long]): F[Option[BlockIndexEntry]]
}
