package org.tessellation.dag.l1.domain.consensus.block

import java.util.UUID

import cats.data.NonEmptyList
import cats.effect.IO

import org.tessellation.dag.domain.block.{BlockReference, Tips}
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput.{PeerBlockConsensusInput, Proposal}
import org.tessellation.dag.l1.domain.consensus.round.RoundId
import org.tessellation.dag.l1.{Main, TransactionGenerator}
import org.tessellation.keytool.KeyPairGenerator
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.height.Height
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.{CurrencyTransaction, DAGTransaction}
import org.tessellation.sdk.sdkKryoRegistrar
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hash.ProofsHash
import org.tessellation.security.hex.Hex
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object ProposalSuite extends SimpleIOSuite with Checkers with TransactionGenerator {

  test("proposal should be serialized and deserialized without errors") {
    val kryo = KryoSerializer.forAsync[IO](Main.kryoRegistrar ++ sdkKryoRegistrar)
    val securityProvider = SecurityProvider.forAsync[IO]

    val proposalDAG = Proposal(
      RoundId(UUID.randomUUID()),
      PeerId(Hex("peer1")),
      PeerId(Hex("peer1")),
      Set(PeerId(Hex("peer2")), PeerId(Hex("peer3"))),
      Set.empty[Signed[DAGTransaction]],
      Tips(NonEmptyList.one(BlockReference(Height(10L), ProofsHash("someProofsHash"))))
    )

    val proposalCurrency = Proposal(
      RoundId(UUID.randomUUID()),
      PeerId(Hex("peer1")),
      PeerId(Hex("peer1")),
      Set(PeerId(Hex("peer2")), PeerId(Hex("peer3"))),
      Set.empty[Signed[CurrencyTransaction]],
      Tips(NonEmptyList.one(BlockReference(Height(10L), ProofsHash("someProofsHash"))))
    )

    println(proposalDAG.selfTypeTag)
    println(proposalCurrency.selfTypeTag)

    kryo.use { implicit kp =>
      securityProvider.use { implicit sp =>
        KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
          for {
            signedDAGProposal <- Signed.forAsyncKryo[F, PeerBlockConsensusInput[DAGTransaction]](proposalDAG, keyPair)
            singedCurrencyProposal <- Signed.forAsyncKryo[F, PeerBlockConsensusInput[CurrencyTransaction]](proposalCurrency, keyPair)
          } yield (signedDAGProposal, singedCurrencyProposal)
        }
      }
    }.map { case (signedDAG, signedCurrency) => expect.all(signedDAG != signedCurrency) }
  }
}
