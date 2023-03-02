package org.tessellation.currency.schema

import cats.data.{NonEmptyList, NonEmptySet}

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.ext.cats.data.OrderBasedOrdering
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo}
import org.tessellation.schema.transaction._
import org.tessellation.security.hash.{Hash, ProofsHash}
import org.tessellation.security.signature.Signed
import org.tessellation.syntax.sortedCollection._

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.string.MatchesRegex
import eu.timepit.refined.types.numeric.PosInt
import io.estatico.newtype.macros.newtype

object currency {

  @newtype
  case class TokenSymbol(symbol: String Refined MatchesRegex["[A-Z]+"])

  @derive(decoder, encoder, order, show)
  case class CurrencyTransaction(
    source: Address,
    destination: Address,
    amount: TransactionAmount,
    fee: TransactionFee,
    parent: TransactionReference,
    salt: TransactionSalt
  ) extends Transaction

  object CurrencyTransaction {
    implicit object OrderingInstance extends OrderBasedOrdering[CurrencyTransaction]
  }

  @derive(show, eqv, encoder, decoder, order)
  case class CurrencyBlock(
    parent: NonEmptyList[BlockReference],
    transactions: NonEmptySet[Signed[CurrencyTransaction]]
  ) extends Block[CurrencyTransaction]

  object CurrencyBlock {
    implicit object OrderingInstance extends OrderBasedOrdering[CurrencyBlock]
    implicit object OrderingInstanceAsActiveTip extends OrderBasedOrdering[BlockAsActiveTip[CurrencyBlock]]
  }

  @derive(encoder, decoder, eqv, show)
  case class CurrencySnapshotInfo(
    lastTxRefs: SortedMap[Address, TransactionReference],
    balances: SortedMap[Address, Balance]
  ) extends SnapshotInfo {}

  @derive(eqv, show, encoder, decoder)
  case class CurrencySnapshot(
    ordinal: SnapshotOrdinal,
    height: Height,
    subHeight: SubHeight,
    lastSnapshotHash: Hash,
    blocks: SortedSet[BlockAsActiveTip[CurrencyBlock]],
    tips: SnapshotTips,
    info: CurrencySnapshotInfo
  ) extends Snapshot[CurrencyTransaction, CurrencyBlock] {}

  object CurrencySnapshot {
    def mkGenesis(balances: Map[Address, Balance]): CurrencySnapshot =
      CurrencySnapshot(
        SnapshotOrdinal.MinValue,
        Height.MinValue,
        SubHeight.MinValue,
        Hash.empty,
        SortedSet.empty,
        SnapshotTips(SortedSet.empty, mkActiveTips(8)),
        CurrencySnapshotInfo(SortedMap.empty, SortedMap.from(balances))
      )

    private def mkActiveTips(n: PosInt): SortedSet[ActiveTip] =
      List
        .range(0, n.value)
        .map { i =>
          ActiveTip(BlockReference(Height.MinValue, ProofsHash(s"%064d".format(i))), 0L, SnapshotOrdinal.MinValue)
        }
        .toSortedSet
  }
}
