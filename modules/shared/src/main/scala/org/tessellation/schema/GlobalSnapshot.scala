package org.tessellation.schema

import cats.MonadThrow
import cats.data.NonEmptyList
import cats.syntax.functor._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.semver.SnapshotVersion
import org.tessellation.schema.snapshot.Snapshot
import org.tessellation.schema.transaction.RewardTransaction
import org.tessellation.security.Hashed
import org.tessellation.security.hash.{Hash, ProofsHash}
import org.tessellation.security.hex.Hex
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.StateChannelSnapshotBinary
import org.tessellation.syntax.sortedCollection._

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt

@derive(eqv, show, encoder, decoder)
case class GlobalIncrementalSnapshot(
  ordinal: SnapshotOrdinal,
  lastSnapshotHash: Hash,
  currencyData: CurrencyData,
  stateChannelSnapshots: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
  nextFacilitators: NonEmptyList[PeerId],
  stateProof: GlobalSnapshotStateProof,
  version: SnapshotVersion = SnapshotVersion("0.0.1")
) extends Snapshot

object GlobalIncrementalSnapshot {
  def fromGlobalSnapshot[F[_]: MonadThrow: KryoSerializer](snapshot: GlobalSnapshot): F[GlobalIncrementalSnapshot] =
    snapshot.info.stateProof.map { stateProof =>
      GlobalIncrementalSnapshot(
        snapshot.ordinal,
        snapshot.lastSnapshotHash,
        CurrencyData(
          snapshot.height,
          snapshot.subHeight,
          snapshot.blocks,
          snapshot.rewards,
          snapshot.tips,
          snapshot.epochProgress
        ),
        snapshot.stateChannelSnapshots,
        snapshot.nextFacilitators,
        stateProof
      )
    }
}

@derive(eqv, show, encoder, decoder)
case class GlobalSnapshot(
  ordinal: SnapshotOrdinal,
  height: Height,
  subHeight: SubHeight,
  lastSnapshotHash: Hash,
  blocks: SortedSet[BlockAsActiveTip],
  stateChannelSnapshots: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
  rewards: SortedSet[RewardTransaction],
  epochProgress: EpochProgress,
  nextFacilitators: NonEmptyList[PeerId],
  info: GlobalSnapshotInfoV1,
  tips: SnapshotTips
) extends Snapshot {
  def currencyData: CurrencyData = CurrencyData(height, subHeight, blocks, rewards, tips, epochProgress)
}

object GlobalSnapshot {

  def mkGenesis(balances: Map[Address, Balance], startingEpochProgress: EpochProgress): GlobalSnapshot =
    GlobalSnapshot(
      SnapshotOrdinal.MinValue,
      Height.MinValue,
      SubHeight.MinValue,
      Coinbase.hash,
      SortedSet.empty[BlockAsActiveTip],
      SortedMap.empty,
      SortedSet.empty,
      startingEpochProgress,
      nextFacilitators,
      GlobalSnapshotInfoV1(SortedMap.empty, SortedMap.empty, SortedMap.from(balances)),
      SnapshotTips(
        SortedSet.empty[DeprecatedTip],
        mkActiveTips(8)
      )
    )

  def mkFirstIncrementalSnapshot[F[_]: MonadThrow: KryoSerializer](genesis: Hashed[GlobalSnapshot]): F[GlobalIncrementalSnapshot] =
    genesis.info.stateProof.map { stateProof =>
      GlobalIncrementalSnapshot(
        genesis.ordinal.next,
        genesis.hash,
        CurrencyData(
          genesis.height,
          genesis.subHeight.next,
          SortedSet.empty,
          SortedSet.empty,
          genesis.tips,
          genesis.epochProgress
        ),
        SortedMap.empty,
        nextFacilitators,
        stateProof
      )
    }

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
