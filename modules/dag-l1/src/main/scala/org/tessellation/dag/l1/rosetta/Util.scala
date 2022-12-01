package org.tessellation.dag.l1.rosetta

import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPublicKeySpec

import org.tessellation.dag.l1.rosetta.examples.proofs
import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction._
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.all.PosLong
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECNamedCurveSpec
import org.bouncycastle.jce.{ECNamedCurveTable, ECPointUtil}

object Util {

  // This works from:
  //  https://stackoverflow.com/questions/26159149/how-can-i-get-a-publickey-object-from-ec-public-key-bytes
  def getPublicKeyFromBytes(pubKey: Array[Byte]) = {
    val spec = ECNamedCurveTable.getParameterSpec("secp256k1")
    val kf = KeyFactory.getInstance("ECDSA", new BouncyCastleProvider)
    val params = new ECNamedCurveSpec("secp256k1", spec.getCurve, spec.getG, spec.getN)
    val point = ECPointUtil.decodePoint(params.getCurve, pubKey)
    val pubKeySpec = new ECPublicKeySpec(point, params)
    val pk = kf.generatePublic(pubKeySpec).asInstanceOf[ECPublicKey]
    pk
  }

  def reduceListEither[L, R](eitherList: List[Either[L, List[R]]]): Either[L, List[R]] =
    eitherList.reduce { (l: Either[L, List[R]], r: Either[L, List[R]]) =>
      l match {
        case x @ Left(_) => x
        case Right(y) =>
          r match {
            case xx @ Left(_) => xx
            case Right(yy)    => Right(y ++ yy)
          }
      }
    }

  def extractTransactions[F[_]](snapshot: GlobalSnapshot)(
    implicit kryoSerializer: KryoSerializer[F]
  ): (List[Signed[Transaction]], List[Signed[Transaction]], List[Signed[Transaction]]) = {
    val genesisTxs = if (snapshot.ordinal.value.value == 0L) {
      snapshot.info.balances.toList.map {
        case (balanceAddress, balance) =>
          // TODO: Empty transaction translator
          Signed(
            Transaction(
              Address("DAG2EUdecqFwEGcgAcH1ac2wrsg8acrgGwrQabcd"),
              balanceAddress,
              TransactionAmount(PosLong.from(balance.value).toOption.get),
              TransactionFee(0L),
              TransactionReference(
                TransactionOrdinal(0L),
                Hash(examples.sampleHash)
              ),
              TransactionSalt(0L)
            ),
            proofs
          )
      }
    } else List()

    val blockTransactions = snapshot.blocks.toList
      .flatMap(x => x.block.value.transactions.toNonEmptyList.toList)

    val rewardTransactions = snapshot.rewards.toList.map { rw =>
      Signed(
        Transaction(
          Address("DAG2EUdecqFwEGcgAcH1ac2wrsg8acrgGwrQabcd"),
          rw.destination,
          rw.amount,
          TransactionFee(0L),
          TransactionReference(
            TransactionOrdinal(0L),
            Hash(examples.sampleHash)
          ),
          TransactionSalt(0L)
        ),
        proofs
      )
    }

    (blockTransactions, rewardTransactions, genesisTxs)
  }
}
