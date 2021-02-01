package org.tessellation.schema

import cats.Functor
import cats.syntax.all._
import cats.effect.IO
import higherkindness.droste.{Algebra, Coalgebra, RAlgebra, RCoalgebra}

import scala.util.Random

object L1Consensus {

  trait Data[A]
  case class Transactions(txs: Set[Int]) extends Data[Transactions]
  case class Block(txs: Transactions) extends Data[Block]
  case class Node(addr: String) extends Data[Node]

  final case class Response[A](a: A)

  trait L1Consensus[A]
  final case class Step1[A](a: A) extends L1Consensus[A]
  final case class Step2[A](a: A) extends L1Consensus[A]
  final case class Step3[A](a: A) extends L1Consensus[A]
  final case class Step4[A](a: A) extends L1Consensus[A]
  final case class ReturnValue[A](a: A) extends L1Consensus[A]

  // Test methods / Test data
  val nodes = Set(Node("A"), Node("B"), Node("C"), Node("D"))
  val getFacilitators = (nodes: Set[Node]) => Random.shuffle(nodes).take(3)
  val validateProposals = (proposals: List[Block]) => Option(proposals).filter(_.distinct.size == 1).map(_.head)
  val createProposal = (txs: Transactions) => Block(txs)
  val getProposal = (facilitator: Node) => Block(Transactions(Set(1)))


  // Data(Set(Transaction(1), Transaction(2))) ->
  val coalgebra: Coalgebra[L1Consensus, Data] = Coalgebra {
    //
  }

  val algebra: Algebra[L1Consensus, Data] = Algebra {
    //
  }


}

//
//object L1Consensus {
//  type Peer = String
//  type Facilitators = Set[Peer]
//  case class Response[O](output: O)
//
//  def apiCall: Peer => IO[Int] = peer => IO {
//    val proposal = Random.nextInt(2)
//    println(s"Peer=${peer} proposal is ${proposal}")
//    proposal
//  }
//
//  def consensus(peers: Set[Peer]): IO[Option[Int]] =
//    for {
//      facilitators <- peers.take(2).pure[IO] // step 1 - select facilitators
//      responses <- facilitators.toList.traverse(apiCall) // step 2 - collect responses
//      output = (if (responses.toSet.size == 1) Some(responses.head) else None) // step 3 - validate responses
//    } yield output
//
//  val coalgebra: Coalgebra[AciF, Int] = Coalgebra {
//    i => Suspend(consensus(Set("aa", "bb", "cc", "dd"))).asInstanceOf[AciF[Int]]
//  }
//  val algebra: Algebra[AciF, Option[Int]] = Algebra {
//    case Suspend(io) => io.asInstanceOf[IO[Option[Int]]].unsafeRunSync()
//  }
//}
//
//object L0Consensus {
//  def createSnapshot(block: Int): IO[String] = IO { s"${block}-${block}" }
//
//  val coalgebra: Coalgebra[AciF, Int] = Coalgebra {
//    i => Suspend[Int, String](createSnapshot(i)).asInstanceOf[AciF[Int]]
//  }
//  val algebra: Algebra[AciF, String] = Algebra {
//    case Suspend(io) => io.unsafeRunSync().asInstanceOf[String]
//  }
//}

sealed trait AciF[A]
case class Suspend[A, B](io: IO[B]) extends AciF[A]
case class Return[A](output: Int) extends AciF[A]

object AciF {
  implicit val functor: Functor[AciF] = new Functor[AciF] {
    override def map[A, B](fa: AciF[A])(f: A => B): AciF[B] = fa match {
      case Return(output) => Return(output)
      case Suspend(io) => Suspend(io)
    }
  }
}