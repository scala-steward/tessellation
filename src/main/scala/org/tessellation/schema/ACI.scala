package org.tessellation.schema

import cats.free.Free
import cats.free.Free.liftF
import cats.~>
import higherkindness.droste.{Algebra, Coalgebra}
import cats.Id
import org.tessellation.schema.L1Consensus.Peer

trait AciF[A]

case class Return[A](x: Int) extends AciF[A]
case class Execute[A](command: Command[A, Int, Int]) extends AciF[A]

trait Command[A, I, O] {
  def run(): O
}

case class ApiCall[A](request: Request[Int]) extends Command[A, Int, Int] {
  def run() = request.input // todo: make ApiCall a trait
}
case class Request[I](input: I)
case class Response[O](output: O)

object AciF {

  val algebra: Algebra[AciF, Int] = Algebra {
    case Return(x) => x
    case Execute(cmd@ApiCall(_)) => cmd.run()
  }

  val coalgebra: Coalgebra[AciF, Int] = Coalgebra {
    n => if (n <= 0) Return(n) else Execute(ApiCall(Request(n)))
  }

}


sealed trait L1ConsensusA[A]
case class SelectFacilitators(peers: Set[Peer]) extends L1ConsensusA[Set[Peer]]
case class CollectResponses[T](facilitators: Set[Peer]) extends L1ConsensusA[List[Response[T]]]
case class ValidateResponses[T](responses: List[Response[T]]) extends L1ConsensusA[Option[T]]

object L1Consensus {
  type Peer = String
  type Facilitators = Set[Peer]


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
        facilitators.toList.map(peer => 2).asInstanceOf[A] // mocked api call
      }
      case ValidateResponses(responses) => {
        (if (responses.toSet.size == 1) Some(responses.head) else None).asInstanceOf[A]
      }
    }
  }
}
