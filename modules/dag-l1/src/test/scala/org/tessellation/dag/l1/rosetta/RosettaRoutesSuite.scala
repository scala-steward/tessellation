package org.tessellation.dag.l1.rosetta

import cats.effect.{Async, IO, Resource}
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.tessellation.dag.l1.rosetta.RosettaServer.runnerKryoRegistrar
import org.tessellation.dag.l1.rosetta.server.{MockBlockIndexClient, MockL1Client}
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.kryo.KryoSerializer
import org.tessellation.security.SecurityProvider
import org.tessellation.shared.sharedKryoRegistrar
import suite.ResourceSuite
import weaver.scalacheck.Checkers
import org.tessellation.ext.kryo._
import org.tessellation.keytool.KeyStoreUtils
import org.tessellation.rosetta.server.model.PublicKey
import org.tessellation.security.hex.Hex

object RosettaRoutesSuite extends ResourceSuite with Checkers {
  val registrar = org.tessellation.dag.dagSharedKryoRegistrar.union(sharedKryoRegistrar).union(runnerKryoRegistrar)
  override type Res = RosettaRoutes[IO]

  override def sharedResource: Resource[IO, Res] = {
    SecurityProvider
      .forAsync[IO]
      .flatMap { implicit sp =>
        KryoSerializer
          .forAsync[IO](registrar)
          .flatMap { implicit kryo =>
            IO.pure(new RosettaRoutes[IO]("mainnet", MockBlockIndexClient.make, MockL1Client.make)(Async[IO], kryo, sp)).asResource
          }
      }
  }

  test("Construction test") { resource: Res =>
    SecurityProvider
      .forAsync[IO]
      .flatMap { implicit sp =>
        KryoSerializer
          .forAsync[IO](registrar)
          .flatMap { implicit kryo =>
            val keyPair = KeyStoreUtils
              .readKeyPairFromStore(
                s"/Users/chuong/Documents/dev/constellationnetwork/key-0_v2.p12",
                "alias",
                "storepass".toCharArray,
                "storepass".toCharArray
              )(Async[IO], sp).map { keyPair =>
              val publicKeyInfo = SubjectPublicKeyInfo.getInstance(keyPair.getPublic.getEncoded)
              val encodedBytes = publicKeyInfo.getPublicKeyData.getBytes
              val keyBytes = new Array[Byte](encodedBytes.length - 1)
              System.arraycopy(encodedBytes, 1, keyBytes, 0, encodedBytes.length - 1)

              PublicKey(Hex.fromBytes(keyBytes).toString, "ecdsa")
            }

            keyPair.asResource
          }
      }.use { publicKey =>
      val test = resource.convertRosettaPublicKeyToJPublicKey(publicKey)
      println(f"Public key ${test.isRight}")
      IO.pure(expect(test.isRight))
    }
  }
/*
  package org.tessellation.keytool

  import java.math.BigInteger
  import java.security.interfaces.ECPublicKey
  import java.security.spec.ECPublicKeySpec
  import java.security.{KeyPair, PrivateKey, PublicKey}

  import cats.effect.unsafe.implicits.global
  import cats.effect.{Async, IO}

  import org.tessellation.security.SecurityProvider
  import org.tessellation.security.hex.Hex

  import org.bouncycastle.jce.spec.ECPrivateKeySpec

  object TempMain {

    import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo

    // doesn't work
    // Source: https://stackoverflow.com/questions/28172710/java-compact-representation-of-ecc-publickey
    //  def extractData(publicKey: JPublicKey): Array[Byte] = {
    //    val subjectPublicKeyInfo = SubjectPublicKeyInfo.getInstance(publicKey.getEncoded)
    //    val encodedBytes = subjectPublicKeyInfo.getPublicKeyData.getBytes
    //    val publicKeyData = new Array[Byte](encodedBytes.length - 1)
    //    System.arraycopy(encodedBytes, 1, publicKeyData, 0, encodedBytes.length - 1)
    //    publicKeyData
    //  }

    //
    //  def bytesToJPublicKey(bytes: Array[Byte]): Array[Byte] = {
    //    val subjectPublicKeyInfo = SubjectPublicKeyInfo.getInstance(publicKey.getEncoded)
    //    val encodedBytes = subjectPublicKeyInfo.getPublicKeyData.getBytes
    //    val publicKeyData = new Array[Byte](encodedBytes.length - 1)
    //    System.arraycopy(encodedBytes, 1, publicKeyData, 0, encodedBytes.length - 1)
    //    publicKeyData
    //  }

    def extractData(publicKey: PublicKey): Array[Byte] = {
      val subjectPublicKeyInfo = SubjectPublicKeyInfo.getInstance(publicKey.getEncoded)
      val encodedBytes = subjectPublicKeyInfo.getPublicKeyData.getBytes
      val publicKeyData = new Array[Byte](encodedBytes.length - 1)
      System.arraycopy(encodedBytes, 1, publicKeyData, 0, encodedBytes.length - 1)
      publicKeyData
    }

    import org.bouncycastle.jce.ECNamedCurveTable
    import org.bouncycastle.jce.ECPointUtil
    import org.bouncycastle.jce.provider.BouncyCastleProvider
    import org.bouncycastle.jce.spec.ECNamedCurveSpec
    import java.security.KeyFactory

    // This works from:
    //  https://stackoverflow.com/questions/26159149/how-can-i-get-a-publickey-object-from-ec-public-key-bytes
    private def getPublicKeyFromBytes(pubKey: Array[Byte]) = {
      val spec = ECNamedCurveTable.getParameterSpec("secp256k1")
      val kf = KeyFactory.getInstance("ECDSA", new BouncyCastleProvider)
      val params = new ECNamedCurveSpec("secp256k1", spec.getCurve, spec.getG, spec.getN)
      val point = ECPointUtil.decodePoint(params.getCurve, pubKey)
      val pubKeySpec = new ECPublicKeySpec(point, params)
      val pk = kf.generatePublic(pubKeySpec).asInstanceOf[ECPublicKey]
      pk
    }
    private def getPrivateKeyFromBytes(privkeyHex: String) = {
      val spec = ECNamedCurveTable.getParameterSpec("secp256k1")
      val kf = KeyFactory.getInstance("ECDSA", new BouncyCastleProvider)
      val priv = new BigInteger(privkeyHex, 16)
      val pubKeySpec = new ECPrivateKeySpec(priv, spec)
      val pk = kf.generatePrivate(pubKeySpec).asInstanceOf[PrivateKey]
      pk
    }

    def run(key: Int): KeyPair =
      SecurityProvider
        .forAsync[IO]
        .use { implicit sp =>
          //      val value = KeyStoreUtils.migrateKeyStoreToSinglePassword(s"/Users/ryle/projects2/tessellation/keys/key-${key}.p12", "alias", "storepass".toCharArray,
          //        "keypass".toCharArray, DistinguishedName("a", "a", "a", "a", "a", "a"), 10000000)(Async[IO], sp)
          //      value
          KeyStoreUtils
            .readKeyPairFromStore(
              s"/Users/ryle/projects2/tessellation/keys/key-${key}_v2.p12",
              "alias",
              "storepass".toCharArray,
              "storepass".toCharArray
            )(Async[IO], sp)
            .map { kp =>
              import org.tessellation.security.key.ops._


              //            println("public hex: " + kp.getPublic.toHex)
              //            println("private hex: " + kp.getPrivate.toHex)
              //            println("address: " + kp.getPublic.toAddress.value.value)
              //            val enc = extractData(kp.getPublic)

              //            println("extracted public hex " + Hex.fromBytes(enc).toString)

              //
              //            val hex = "02c0cec83ddd60b3acbba15384c0774575e5003365dd8278c825b4ad8f9cd3272d"
              //            val hexBytes = Hex(hex).toBytes
              //            val ks = new RawEncodedKeySpec(hexBytes)
              //            val kf = KeyFactory.getInstance(ECDSA, sp.provider)
              //            val genpub = kf.generatePublic(ks)
              //            val genpub = getPublicKeyFromBytes(hexBytes)
              //            println("Gen public worked " + genpub.getEncoded.toSeq)
              //
              //            val hex2 = Hex.fromBytes(kp.getPublic.asInstanceOf[ECPublicKey].getEncoded)
              //            println("Cast bytes hex " + hex2)

              //            val priv = getPrivateKeyFromBytes("d27337b4cf6b2badacead599ed7e4fa0a6436e0254631c91df0b28b69e9e4996")
              //            val pub = Hex(
              //              "bb2c6883916fc5fd703a69dbb8685cb3db1f6cbef131afe9e8fbd72faf7f6129e068f1ea48f2e4dd9f1297a5ce0acfe7b570b7a22a3452ef182f2533188270e6"
              //            ).toPublicKey(Async[IO], sp).unsafeRunSync()
              ////            println("Private key decoded +" + priv)
              //            println("Private key decoded +" + priv.toHex.value)
              //            println("Pub second reloaded +" + pub.toAddress.value)
              //            println("Private key address +" + priv.)

              kp
            }
        }
        .unsafeRunSync()

    def main(args: Array[String]): Unit =
      for (i <- 0 until 2) {
        println(run(i))
        println(i)
      }
  }
 */

}