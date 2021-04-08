package org.tessellation.consensus

import cats.effect.IO
import cats.implicits._
import fs2.Stream
import io.chrisdavenport.fuuid.FUUID
import org.mockito.cats.IdiomaticMockitoCats
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito}
import org.scalatest.BeforeAndAfter
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.tessellation.Node
import org.tessellation.consensus.transaction.RandomTransactionGenerator

import scala.collection.immutable.Set

class L1Test
    extends AnyFreeSpec
    with IdiomaticMockito
    with IdiomaticMockitoCats
    with Matchers
    with ArgumentMatchersSugar
    with BeforeAndAfter {

  var nodeATxGenerator = mock[RandomTransactionGenerator]
  var nodeBTxGenerator = mock[RandomTransactionGenerator]
  var nodeCTxGenerator = mock[RandomTransactionGenerator]

  val nodeACellCache = mock[L1CellCache]
  val nodeBCellCache = mock[L1CellCache]
  val nodeCCellCache = mock[L1CellCache]

  val txA = L1Transaction(1, "A", "X", "", 0)
  val txB = L1Transaction(1, "B", "X", "", 0)
  val txC = L1Transaction(1, "C", "X", "", 0)

  before {
    nodeATxGenerator.generateRandomTransaction() shouldReturn IO.pure(txA)
    nodeBTxGenerator.generateRandomTransaction() shouldReturn IO.pure(txB)
    nodeCTxGenerator.generateRandomTransaction() shouldReturn IO.pure(txC)

    nodeACellCache.pullCell(*) shouldReturn IO.pure(L1Cell(L1Edge(Set(txA))))
    nodeBCellCache.pullCell(*) shouldReturn IO.pure(L1Cell(L1Edge(Set(txB))))
    nodeCCellCache.pullCell(*) shouldReturn IO.pure(L1Cell(L1Edge(Set(txC))))
  }

  "Own consensus round" - {
    "creates block" in {
      val scenario = for {
        nodeA <- Node.run("nodeA", "A").map(_.copy(txGenerator = nodeATxGenerator))
        nodeB <- Node.run("nodeB", "B").map(_.copy(txGenerator = nodeBTxGenerator))
        nodeC <- Node.run("nodeC", "C").map(_.copy(txGenerator = nodeCTxGenerator))

        _ <- nodeA.joinTo(Set(nodeB, nodeC))
        _ <- nodeB.joinTo(Set(nodeA, nodeC))
        _ <- nodeC.joinTo(Set(nodeA, nodeB))

        txA <- nodeA.txGenerator.generateRandomTransaction()
        edgeA = L1Edge(Set(txA))
        cell = L1Cell(edgeA)
        result <- nodeA.startL1Consensus(cell)
      } yield result

      val result = scenario.unsafeRunSync()

      result.isRight shouldBe true
    }

    "block contains proposals from all the nodes" in {
      val scenario = for {
        nodeA <- Node.run("nodeA", "A").map(_.copy(txGenerator = nodeATxGenerator, cellCache = nodeACellCache))
        nodeB <- Node.run("nodeB", "B").map(_.copy(txGenerator = nodeBTxGenerator, cellCache = nodeBCellCache))
        nodeC <- Node.run("nodeC", "C").map(_.copy(txGenerator = nodeCTxGenerator, cellCache = nodeCCellCache))

        _ <- nodeA.joinTo(Set(nodeB, nodeC))
        _ <- nodeB.joinTo(Set(nodeA, nodeC))
        _ <- nodeC.joinTo(Set(nodeA, nodeB))

        txA <- nodeA.txGenerator.generateRandomTransaction()
        edgeA = L1Edge(Set(txA))
        cell = L1Cell(edgeA)
        result <- nodeA.startL1Consensus(cell)
      } yield result

      val result = scenario.unsafeRunSync()

      result shouldBe Right(L1Block(Set(txA, txB, txC)))
    }
  }

  "Facilitator consensus round" - {
    "returns proposal" in {
      nodeBCellCache.pullCell(*) shouldReturn IO.pure(L1Cell(L1Edge(Set(txB))))

      val scenario = for {
        nodeA <- Node.run("nodeA", "A").map(_.copy(txGenerator = nodeATxGenerator))
        nodeB <- Node.run("nodeB", "B").map(_.copy(txGenerator = nodeBTxGenerator).copy(cellCache = nodeBCellCache))
        nodeC <- Node.run("nodeC", "C").map(_.copy(txGenerator = nodeCTxGenerator))

        _ <- nodeA.joinTo(Set(nodeB, nodeC))
        _ <- nodeB.joinTo(Set(nodeA, nodeC))
        _ <- nodeC.joinTo(Set(nodeA, nodeB))

        txA <- nodeA.txGenerator.generateRandomTransaction()
        roundIdA <- FUUID.randomFUUID[IO]
        // Consensus owner (nodeA) sends Edge as proposal
        edgeA = L1Edge(Set(txA))

        result <- nodeB.participateInL1Consensus(roundIdA, nodeA, nodeA, edgeA, Set(nodeB, nodeC))
      } yield result

      val result = scenario.unsafeRunSync()

      result.isRight shouldBe true
    }

    "exchanges proposal" in {
      nodeBCellCache.pullCell(*) shouldReturn IO.pure(L1Cell(L1Edge(Set(txB))))

      val scenario = for {
        nodeA <- Node.run("nodeA", "A").map(_.copy(txGenerator = nodeATxGenerator))
        nodeB <- Node.run("nodeB", "B").map(_.copy(txGenerator = nodeBTxGenerator, cellCache = nodeBCellCache))
        nodeC <- Node.run("nodeC", "C").map(_.copy(txGenerator = nodeCTxGenerator, cellCache = nodeCCellCache))

        _ <- nodeA.joinTo(Set(nodeB, nodeC))
        _ <- nodeB.joinTo(Set(nodeA, nodeC))
        _ <- nodeC.joinTo(Set(nodeA, nodeB))

        txA <- nodeA.txGenerator.generateRandomTransaction()
        roundIdA <- FUUID.randomFUUID[IO]
        // Consensus owner (nodeA) sends Edge as proposal
        edgeA = L1Edge(Set(txA))

        result <- nodeB.participateInL1Consensus(roundIdA, nodeA, nodeA, edgeA, Set(nodeB, nodeC))
      } yield result

      val result = scenario.unsafeRunSync()

      result.isRight shouldBe true
    }

    "executes same cell for same round" ignore {
      nodeBCellCache.pullCell(*) shouldReturn IO.pure(L1Cell(L1Edge(Set(txB))))

      val scenario = for {
        nodeA <- Node.run("nodeA", "A").map(_.copy(txGenerator = nodeATxGenerator))
        nodeB <- Node.run("nodeB", "B").map(_.copy(txGenerator = nodeBTxGenerator, cellCache = nodeBCellCache))
        nodeC <- Node.run("nodeC", "C").map(_.copy(txGenerator = nodeCTxGenerator, cellCache = nodeCCellCache))

        _ <- nodeA.joinTo(Set(nodeB, nodeC))
        _ <- nodeB.joinTo(Set(nodeA, nodeC))
        _ <- nodeC.joinTo(Set(nodeA, nodeB))

        txA <- nodeA.txGenerator.generateRandomTransaction()
        roundIdA <- FUUID.randomFUUID[IO]
        // Consensus owner (nodeA) sends Edge as proposal
        edgeA = L1Edge(Set(txA))
        edgeC = L1Edge(Set(txC))

        proposalA <- nodeB.participateInL1Consensus(roundIdA, nodeA, nodeA, edgeA, Set(nodeB, nodeC))
        proposalB <- nodeB.participateInL1Consensus(roundIdA, nodeA, nodeC, edgeC, Set(nodeB, nodeC))
      } yield (proposalA, proposalB)

      val (a, b) = scenario.unsafeRunSync()

      // TODO: FIX
      //      a shouldBe Right(L1Block(Set(txB, txC)))
      //      b shouldBe Right(L1Block(Set(txB, txC)))

    }
  }

}
