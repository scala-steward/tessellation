package org.tessellation.dag.l1.domain.block.storage

import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

case class BlockIndexEntry(hash: Hash, height: Long, signedSnapshot: Signed[GlobalSnapshot])
