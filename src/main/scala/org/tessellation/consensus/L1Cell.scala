package org.tessellation.consensus

import cats.effect.IO
import io.chrisdavenport.fuuid.FUUID
import org.tessellation.Node
import org.tessellation.consensus.L1ConsensusStep.L1ConsensusMetadata
import org.tessellation.schema.{Cell, CellError, StackF, Ω}


class L1Cell(edge: L1Edge) extends Cell[IO, StackF, L1Edge, Either[CellError, Ω], (L1ConsensusMetadata, Ω)](edge, L1Consensus.hyloM) {}

object L1Cell {
  def apply(edge: L1Edge): L1Cell = new L1Cell(edge)
}

case class L1StartConsensusCell(edge: L1Edge, metadata: L1ConsensusMetadata) extends L1Cell(edge) {
  override def run(): IO[Either[CellError, Ω]] = hylo(metadata, StartOwnRound(edge))
}

object L1StartConsensusCell {
  def fromCell[M[_], F[_]](cell: Cell[M, F, L1Edge, Either[CellError, Ω],(L1ConsensusMetadata, Ω)])(metadata: L1ConsensusMetadata): L1StartConsensusCell =
    L1StartConsensusCell(cell.data, metadata)
}

case class L1ParticipateInConsensusCell(edge: L1Edge, metadata: L1ConsensusMetadata, roundId: FUUID, proposalNode: Node, receivedEdge: L1Edge) extends L1Cell(edge) {
  override def run(): IO[Either[CellError, Ω]] = hylo(metadata, ReceiveProposal(roundId, proposalNode, receivedEdge, edge))
}

object L1ParticipateInConsensusCell {
  def fromCell[M[_], F[_]](cell: Cell[M, F, L1Edge, Either[CellError, Ω],(L1ConsensusMetadata, Ω)])(metadata: L1ConsensusMetadata, roundId: FUUID, proposalNode: Node, receivedEdge: L1Edge): L1ParticipateInConsensusCell =
    L1ParticipateInConsensusCell(cell.data, metadata, roundId, proposalNode, receivedEdge)
}