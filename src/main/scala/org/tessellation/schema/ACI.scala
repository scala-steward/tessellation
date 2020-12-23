package org.tessellation.schema

import cats.free.Free
import cats.free.Free.liftF
import cats.{Functor, Id, ~>}
import cats.syntax.all._
import cats.effect.IO
import higherkindness.droste.{Algebra, Coalgebra}
import org.tessellation.schema.L1Consensus.{L1Consensus, Peer, Response, compiler}


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

sealed trait AciF[A]
case class Consensus[A, B](consensus: L1Consensus[B]) extends AciF[A]
case class Return[A](output: Int) extends AciF[A]

object AciF {
  implicit val functor: Functor[AciF] = new Functor[AciF] {
    override def map[A, B](fa: AciF[A])(f: A => B): AciF[B] = fa match {
      case Return(output) => Return(output)
      case Consensus(c) => Consensus(c)
    }
  }

  val l1ConsensusAlgebra: Algebra[AciF, Option[Int]] = Algebra {
    case Consensus(consensus) => consensus.foldMap(compiler).asInstanceOf[Option[Int]]
    case Return(output) => Some(output)
  }

  val l1ConsensusCoalgebra: Coalgebra[AciF, Int] = Coalgebra {
    n => if (n <= 0) Return(n) else Consensus(L1Consensus.consensus(Set("aa", "bb", "cc")))
  }
}