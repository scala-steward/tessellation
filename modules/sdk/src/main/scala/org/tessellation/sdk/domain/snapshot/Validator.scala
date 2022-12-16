package org.tessellation.sdk.domain.snapshot

import cats.syntax.order._

import org.tessellation.dag.domain.block.{Block, DAGBlock}
import org.tessellation.dag.snapshot.{GlobalSnapshot, Snapshot}
import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.schema.height.SubHeight
import org.tessellation.security.Hashed

object Validator {

  def isNextSnapshot(previous: Hashed[GlobalSnapshot], next: Hashed[GlobalSnapshot]): Boolean =
    compare[DAGBlock, GlobalSnapshot](previous, next.signed.value).isInstanceOf[Next]

  def isNextSnapshot(previous: Hashed[GlobalSnapshot], next: GlobalSnapshot): Boolean =
    compare[DAGBlock, GlobalSnapshot](previous, next).isInstanceOf[Next]

  def compare[A <: Block[_], B <: Snapshot[A]](previous: Hashed[B], next: B): ComparisonResult = {
    val isLastSnapshotHashCorrect = previous.hash === next.lastSnapshotHash
    lazy val isNextOrdinal = previous.ordinal.next === next.ordinal
    lazy val isNextHeight = previous.height < next.height && next.subHeight === SubHeight.MinValue
    lazy val isNextSubHeight = previous.height === next.height && previous.subHeight.next === next.subHeight

    if (isLastSnapshotHashCorrect && isNextOrdinal && isNextHeight)
      NextHeight
    else if (isLastSnapshotHashCorrect && isNextOrdinal && isNextSubHeight)
      NextSubHeight
    else
      NotNext
  }

  sealed trait ComparisonResult
  sealed trait Next extends ComparisonResult
  case object NotNext extends ComparisonResult

  case object NextHeight extends Next
  case object NextSubHeight extends Next
}
