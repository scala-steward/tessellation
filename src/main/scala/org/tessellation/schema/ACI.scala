package org.tessellation.schema

import cats.free.Free
import cats.free.Free.liftF
import cats.~>
import cats.syntax.all._
import cats.Id
import cats.effect.IO
import org.tessellation.schema.L1Consensus.{Peer, Response}


sealed trait L1ConsensusA[A]
case class SelectFacilitators(peers: Set[Peer]) extends L1ConsensusA[Set[Peer]]
case class CollectResponses[T](facilitators: Set[Peer]) extends L1ConsensusA[List[Response[T]]]
case class ValidateResponses[T](responses: List[Response[T]]) extends L1ConsensusA[Option[T]]

object L1Consensus {
  type Peer = String
  type Facilitators = Set[Peer]
  case class Response[O](output: O)


  type L1Consensus[A] = Free[L1ConsensusA, A]

  def selectFacilitators(peers: Set[Peer]): L1Consensus[Set[Peer]] =
    liftF[L1ConsensusA, Set[Peer]](SelectFacilitators(peers))

  def collectResponses[T](facilitators: Set[Peer]): L1Consensus[List[Response[T]]] =
    liftF[L1ConsensusA, List[Response[T]]](CollectResponses(facilitators: Set[Peer]))

  def validateResponses[T](responses: List[Response[T]]): L1Consensus[Option[T]] =
    liftF[L1ConsensusA, Option[T]](ValidateResponses(responses))

  def consensus(peers: Set[Peer]): L1Consensus[Option[Int]] =
    for {
      facilitators <- selectFacilitators(peers)
      responses <- collectResponses[Int](facilitators)
      output <- validateResponses[Int](responses)
    } yield output

  def compiler: L1ConsensusA ~> Id = new (L1ConsensusA ~> Id) {
    override def apply[A](fa: L1ConsensusA[A]): Id[A] = fa match {
      case SelectFacilitators(peers) => {
        peers.take(2).asInstanceOf[A]
      }
      case CollectResponses(facilitators) => {
        facilitators.toList.map(peer => {
          val proposal = 2
          println(s"Peer=${peer} proposal is ${proposal}")
          proposal
        }).asInstanceOf[A] // mocked api call
      }
      case ValidateResponses(responses) => {
        (if (responses.toSet.size == 1) Some(responses.head) else None).asInstanceOf[A]
      }
    }
  }

  def ioCompiler: L1ConsensusA ~> IO = new (L1ConsensusA ~> IO) {
    def apiCall: Peer => IO[Int] = peer => {
      val proposal = 2
      println(s"Peer=${peer} proposal is ${proposal}")
      proposal.pure[IO]
    }

    override def apply[A](fa: L1ConsensusA[A]): IO[A] = fa match {
      case SelectFacilitators(peers) => {
        peers.take(2).asInstanceOf[A].pure[IO]
      }
      case CollectResponses(facilitators) => {
        facilitators.toList.traverse(apiCall).map(_.asInstanceOf[A]) // mocked api call
      }
      case ValidateResponses(responses) => {
        (if (responses.toSet.size == 1) Some(responses.head) else None).asInstanceOf[A].pure[IO]
      }
    }
  }
}
