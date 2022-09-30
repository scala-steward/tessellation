package org.tessellation.dag.l1.infrastructure.transaction.storage

import cats.data.NonEmptySet

import scala.collection.immutable.SortedSet

import org.tessellation.generators._
import org.tessellation.schema.ID.Id
import org.tessellation.schema.address.{Address, DAGAddressRefined}
import org.tessellation.schema.cluster.SessionToken
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.schema.transaction._
import org.tessellation.security.Base58
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.{Signature, SignatureProof}

import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.refineV
import eu.timepit.refined.scalacheck.numeric._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import org.scalacheck.{Arbitrary, Gen}

object SignedTransactionGenerator {

  private val base58CharGen: Gen[Char] = Gen.oneOf(Base58.alphabet)

  private val base58StringGen: Gen[String] =
    Gen.chooseNum(21, 40).flatMap { n =>
      Gen.buildableOfN[String, Char](n, base58CharGen)
    }

  private val peerIdGen: Gen[PeerId] =
    nesGen(str => PeerId(Hex(str)))

  private val idGen: Gen[Id] =
    nesGen(str => Id(Hex(str)))

  private val hostGen: Gen[Host] =
    for {
      a <- Gen.chooseNum(1, 255)
      b <- Gen.chooseNum(1, 255)
      c <- Gen.chooseNum(1, 255)
      d <- Gen.chooseNum(1, 255)
    } yield Host.fromString(s"$a.$b.$c.$d").get

  private val portGen: Gen[Port] =
    Gen.chooseNum(1, 65535).map(Port.fromInt(_).get)

  private val nodeStateGen: Gen[NodeState] =
    Gen.oneOf(NodeState.all)

  private val peerGen: Gen[Peer] =
    for {
      i <- peerIdGen
      h <- hostGen
      p <- portGen
      p2 <- portGen
      s <- Gen.uuid.map(SessionToken.apply)
      st <- nodeStateGen
    } yield Peer(i, h, p, p2, s, st)

  private def peersGen(n: Option[Int] = None): Gen[Set[Peer]] =
    n.map(Gen.const).getOrElse(Gen.chooseNum(1, 20)).flatMap { n =>
      Gen.sequence[Set[Peer], Peer](Array.tabulate(n)(_ => peerGen))
    }

  private val addressGen: Gen[Address] =
    for {
      end <- Gen.stringOfN(36, base58CharGen)
      par = end.filter(_.isDigit).map(_.toString.toInt).sum % 9
    } yield Address(refineV[DAGAddressRefined].unsafeFrom(s"DAG$par$end"))

  private val transactionAmountGen: Gen[TransactionAmount] = Arbitrary.arbitrary[PosLong].map(TransactionAmount(_))
  private val transactionFeeGen: Gen[TransactionFee] = Arbitrary.arbitrary[NonNegLong].map(TransactionFee(_))
  private val transactionOrdinalGen: Gen[TransactionOrdinal] =
    Arbitrary.arbitrary[NonNegLong].map(TransactionOrdinal(_))

  private val transactionReferenceGen: Gen[TransactionReference] =
    for {
      ordinal <- transactionOrdinalGen
      hash <- Arbitrary.arbitrary[Hash]
    } yield TransactionReference(ordinal, hash)

  private val transactionSaltGen: Gen[TransactionSalt] = Gen.long.map(TransactionSalt(_))

  private val transactionGen: Gen[Transaction] =
    for {
      src <- addressGen
      dst <- addressGen
      txnAmount <- transactionAmountGen
      txnFee <- transactionFeeGen
      txnReference <- transactionReferenceGen
      txnSalt <- transactionSaltGen
    } yield Transaction(src, dst, txnAmount, txnFee, txnReference, txnSalt)

  private val signatureGen: Gen[Signature] = nesGen(str => Signature(Hex(str)))

  private val signatureProofGen: Gen[SignatureProof] =
    for {
      id <- idGen
      signature <- signatureGen
    } yield SignatureProof(id, signature)

  private def signedOf[A](valueGen: Gen[A]): Gen[Signed[A]] =
    for {
      txn <- valueGen
      signatureProof <- Gen.listOfN(3, signatureProofGen).map(l => NonEmptySet.fromSetUnsafe(SortedSet.from(l)))
    } yield Signed(txn, signatureProof)

  val signedTransactionGen: Gen[Signed[Transaction]] = signedOf(transactionGen)

}
