package org.tessellation.sdk.infrastructure

import org.tessellation.currency.schema.currency.{CurrencyBlock, CurrencyTransaction}
import org.tessellation.schema.currency.{CurrencySnapshotArtifact, CurrencySnapshotContext, CurrencySnapshotEvent}
import org.tessellation.schema.snapshot.Snapshot
import org.tessellation.schema.transaction.Transaction
import org.tessellation.schema.{Block, SnapshotOrdinal}
import org.tessellation.sdk.infrastructure.consensus.Consensus

package object snapshot {

  type SnapshotArtifact[T <: Transaction, B <: Block[T], S <: Snapshot[T, B]] = S

  type SnapshotConsensus[F[_], T <: Transaction, B <: Block[T], S <: Snapshot[T, B], Context, Event] =
    Consensus[F, Event, SnapshotOrdinal, SnapshotArtifact[T, B, S], Context]

  type CurrencySnapshotConsensus[F[_]] =
    SnapshotConsensus[F, CurrencyTransaction, CurrencyBlock, CurrencySnapshotArtifact, CurrencySnapshotContext, CurrencySnapshotEvent]

}
