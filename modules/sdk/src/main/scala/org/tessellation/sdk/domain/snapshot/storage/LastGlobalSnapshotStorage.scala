package org.tessellation.sdk.domain.snapshot.storage

import org.tessellation.dag.domain.block.{Block, CurrencyBlock, DAGBlock}
import org.tessellation.dag.snapshot._
import org.tessellation.schema.height.Height
import org.tessellation.schema.transaction.{CurrencyTransaction, DAGTransaction, Transaction}
import org.tessellation.security.Hashed

sealed trait LastSnapshotStorage[F[_], A <: Transaction, B <: Block[A], C <: Snapshot[B]] {
  def set(snapshot: Hashed[C]): F[Unit]
  def setInitial(snapshot: Hashed[C]): F[Unit]
  def get: F[Option[Hashed[C]]]
  def getOrdinal: F[Option[SnapshotOrdinal]]
  def getHeight: F[Option[Height]]
}

trait LastGlobalSnapshotStorage[F[_]] extends LastSnapshotStorage[F, DAGTransaction, DAGBlock, GlobalSnapshot]

trait LastCurrencySnapshotStorage[F[_]] extends LastSnapshotStorage[F, CurrencyTransaction, CurrencyBlock, CurrencySnapshot]
