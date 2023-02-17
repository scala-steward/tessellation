package org.tessellation.dag.l1.domain.consensus.block

import java.security.KeyPair

import cats.data.{NonEmptyList, NonEmptySet}

import org.tessellation.dag.block.BlockValidator
import org.tessellation.dag.domain.block.{Block, BlockReference}
import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.consensus.block.config.ConsensusConfig
import org.tessellation.dag.l1.domain.consensus.block.http.p2p.clients.BlockConsensusClient
import org.tessellation.dag.l1.domain.consensus.block.storage.ConsensusStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.dag.transaction.TransactionValidator
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.Transaction
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.security.signature.Signed

case class BlockConsensusContext[F[_], A <: Transaction, B <: Block[A]](
  blockConsensusClient: BlockConsensusClient[F, A],
  blockStorage: BlockStorage[F, A, B],
  blockValidator: BlockValidator[F, A, B],
  clusterStorage: ClusterStorage[F],
  consensusConfig: ConsensusConfig,
  consensusStorage: ConsensusStorage[F, A, B],
  keyPair: KeyPair,
  selfId: PeerId,
  transactionStorage: TransactionStorage[F, A],
  transactionValidator: TransactionValidator[F, A],
  createBlock: (NonEmptyList[BlockReference], NonEmptySet[Signed[A]]) => Option[B]
)
