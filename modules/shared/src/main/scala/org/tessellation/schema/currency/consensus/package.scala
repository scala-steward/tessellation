package org.tessellation.schema.currency

import org.tessellation.currency.dataApplication.DataApplicationBlock
import org.tessellation.currency.schema.currency.{CurrencyBlock, CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import org.tessellation.security.signature.Signed

package object consensus {
  type CurrencySnapshotEvent = Either[Signed[CurrencyBlock], Signed[DataApplicationBlock]]

  type CurrencySnapshotArtifact = CurrencyIncrementalSnapshot

  type CurrencySnapshotContext = CurrencySnapshotInfo
}
