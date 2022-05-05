package org.tessellation.rosetta.server

import cats.data.NonEmptyList

import org.tessellation.dag.domain.block.{BlockReference, DAGBlock}
import org.tessellation.dag.snapshot.{BlockAsActiveTip, GlobalSnapshot, SnapshotOrdinal}
import org.tessellation.schema.ID.Id
import org.tessellation.schema.address.Address
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction._
import org.tessellation.security.hash
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.{Signature, SignatureProof}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

/*
key 0:
public hex: bb2c6883916fc5fd703a69dbb8685cb3db1f6cbef131afe9e8fbd72faf7f6129e068f1ea48f2e4dd9f1297a5ce0acfe7b570b7a22a3452ef182f2533188270e6
private hex: d27337b4cf6b2badacead599ed7e4fa0a6436e0254631c91df0b28b69e9e4996
 */

object examples {

  private val proofs: NonEmptyList[SignatureProof] =
    NonEmptyList(SignatureProof(Id(Hex("a")), Signature(Hex("a"))), List())

  val address = "DAG2EUdecqFwEGcgAcH1ac2wrsg8acrgGwrQefgh"

  val transaction = Signed(
    Transaction(
      Address("DAG2EUdecqFwEGcgAcH1ac2wrsg8acrgGwrQabcd"),
      Address("DAG2EUdecqFwEGcgAcH1ac2wrsg8acrgGwrQefgh"),
      TransactionAmount(10L),
      TransactionFee(3L),
      TransactionReference(
        Hash("someHash"),
        TransactionOrdinal(2L)
      ),
      TransactionSalt(1234L)
    ),
    proofs
  )

  val genesis = GlobalSnapshot.mkGenesis(Map.empty)

  val sampleHash = "f0e4c2f76c58916ec258f246851bea091d14d4247a2fc3e18694461b1816e13b"

  val snapshot = GlobalSnapshot(
    SnapshotOrdinal(NonNegLong(0L)),
    Height.MinValue,
    SubHeight.MinValue,
    Hash(""),
    Set(
      BlockAsActiveTip(
        Signed(
          DAGBlock(Set(transaction), NonEmptyList(BlockReference(hash.ProofsHash(""), Height(0L)), List())),
          proofs
        ),
        NonNegLong(0)
      )
    ),
    Map.empty,
    Set.empty,
    NonEmptyList.of(PeerId(Hex("peer1"))),
    genesis.info,
    genesis.tips
  )

}
