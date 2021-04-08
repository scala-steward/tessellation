package org.tessellation.consensus

import cats.effect.IO
import cats.effect.concurrent.Ref
import io.chrisdavenport.fuuid.FUUID

import scala.collection.immutable.Queue

class L1CellCache {
  private val freeCells: Ref[IO, Queue[L1Cell]] = Ref.unsafe(Queue.empty)
  private val usedCells: Ref[IO, Map[FUUID, L1Cell]] = Ref.unsafe(Map.empty)

  def cacheCell(cell: L1Cell): IO[Unit] = freeCells.modify { q =>
    (q.enqueue(cell), ())
  }

  def pullCell(roundId: FUUID): IO[L1Cell] =
    usedCells.get.flatMap { u =>
      u.get(roundId) match {
        case Some(cell) => IO.pure(cell)
        case None => for {
          freeCell <- freeCells.modifyMaybe { q => q.dequeueOption.map(_.swap) }
          pulledCell = freeCell.getOrElse(emptyCell())
          _ <- usedCells.modify { u =>
            (u.updated(roundId, pulledCell), ())
          }
        } yield pulledCell
      }
    }

  private def emptyCell(): L1Cell = L1Cell(L1Edge(Set.empty))
}

object L1CellCache {
  def apply(): L1CellCache = new L1CellCache
}