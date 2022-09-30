package org.tessellation.dag.l1.domain.block.storage

import org.tessellation.security.hash.Hash

case class StoredBlockIndex(hash: Hash, indexValue: Long, snapshotBytes: Array[Byte])
