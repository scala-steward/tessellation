package org.tessellation.dag.l1.domain.consensus.block.storage

import cats.effect.{Ref, Sync}
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.dag.domain.block.Block
import org.tessellation.dag.l1.domain.consensus.block.RoundData
import org.tessellation.dag.l1.domain.consensus.round.RoundId
import org.tessellation.schema.transaction.Transaction

import io.chrisdavenport.mapref.MapRef

class ConsensusStorage[F[_], A <: Transaction, B <: Block[A]](
  val ownConsensus: Ref[F, Option[RoundData[A, B]]],
  val peerConsensuses: MapRef[F, RoundId, Option[RoundData[A, B]]]
)

object ConsensusStorage {

  def make[F[_]: Sync, A <: Transaction, B <: Block[A]]: F[ConsensusStorage[F, A, B]] =
    for {
      peerConsensuses <- MapRef.ofConcurrentHashMap[F, RoundId, RoundData[A, B]]()
      ownConsensus <- Ref.of[F, Option[RoundData[A, B]]](None)
    } yield new ConsensusStorage(ownConsensus, peerConsensuses)
}
