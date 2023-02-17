package org.tessellation.dag.snapshot

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.dag.domain.block._
import org.tessellation.dag.snapshot.epoch.EpochProgress
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.{DAGTransaction, RewardTransaction, TransactionReference}
import org.tessellation.security.hash.{Hash, ProofsHash}
import org.tessellation.security.hex.Hex
import org.tessellation.syntax.sortedCollection._

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt

sealed trait Snapshot[A <: Block[_]] {
  val ordinal: SnapshotOrdinal
  val height: Height
  val subHeight: SubHeight
  val lastSnapshotHash: Hash
  val blocks: SortedSet[BlockAsActiveTip[A]]
  val tips: GlobalSnapshotTips

  def getBalances: Map[Address, Balance]
  def getLastTxRefs: Map[Address, TransactionReference]
}

case class CurrencySnapshot(
  ordinal: SnapshotOrdinal,
  height: Height,
  subHeight: SubHeight,
  lastSnapshotHash: Hash,
  blocks: SortedSet[BlockAsActiveTip[CurrencyBlock]],
  lastTxRefs: SortedMap[Address, TransactionReference],
  balances: SortedMap[Address, Balance],
  tips: GlobalSnapshotTips
) extends Snapshot[CurrencyBlock] {

  override def getBalances: Map[Address, Balance] = balances

  override def getLastTxRefs: Map[Address, TransactionReference] = lastTxRefs
}

@derive(eqv, show, encoder, decoder)
case class GlobalSnapshot(
  ordinal: SnapshotOrdinal,
  height: Height,
  subHeight: SubHeight,
  lastSnapshotHash: Hash,
  blocks: SortedSet[BlockAsActiveTip[DAGBlock]],
  stateChannelSnapshots: SortedMap[Address, NonEmptyList[StateChannelSnapshotBinary]],
  rewards: SortedSet[RewardTransaction],
  epochProgress: EpochProgress,
  nextFacilitators: NonEmptyList[PeerId],
  info: GlobalSnapshotInfo,
  tips: GlobalSnapshotTips
) extends Snapshot[DAGBlock] {

  override def getBalances: Map[Address, Balance] = info.balances

  override def getLastTxRefs: Map[Address, TransactionReference] = info.lastTxRefs

  def activeTips[F[_]: Async: KryoSerializer]: F[SortedSet[ActiveTip]] =
    blocks.toList.traverse { blockAsActiveTip =>
      BlockReference
        .of[F, DAGTransaction, DAGBlock](blockAsActiveTip.block)
        .map(blockRef => ActiveTip(blockRef, blockAsActiveTip.usageCount, ordinal))
    }.map(_.toSortedSet.union(tips.remainedActive))

}

object GlobalSnapshot {

  def mkGenesis(balances: Map[Address, Balance], startingEpochProgress: EpochProgress): GlobalSnapshot =
    GlobalSnapshot(
      SnapshotOrdinal.MinValue,
      Height.MinValue,
      SubHeight.MinValue,
      Coinbase.hash,
      SortedSet.empty[BlockAsActiveTip[DAGBlock]],
      SortedMap.empty,
      SortedSet.empty,
      startingEpochProgress,
      nextFacilitators,
      GlobalSnapshotInfo(SortedMap.empty, SortedMap.empty, SortedMap.from(balances)),
      GlobalSnapshotTips(
        SortedSet.empty[DeprecatedTip],
        mkActiveTips(8)
      )
    )

  val nextFacilitators: NonEmptyList[PeerId] =
    NonEmptyList
      .of(
        "e0c1ee6ec43510f0e16d2969a7a7c074a5c8cdb477c074fe9c32a9aad8cbc8ff1dff60bb81923e0db437d2686a9b65b86c403e6a21fa32b6acc4e61be4d70925"
      )
      .map(s => PeerId(Hex(s)))

  private def mkActiveTips(n: PosInt): SortedSet[ActiveTip] =
    List
      .range(0, n.value)
      .map { i =>
        ActiveTip(BlockReference(Height.MinValue, ProofsHash(s"%064d".format(i))), 0L, SnapshotOrdinal.MinValue)
      }
      .toSortedSet

}
