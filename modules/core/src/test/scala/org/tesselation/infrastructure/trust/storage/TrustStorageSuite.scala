package org.tesselation.infrastructure.trust.storage

import java.security.KeyPair

import cats.effect.{Async, IO, Ref}
import cats.effect.unsafe.implicits.global

import org.tesselation.ext.crypto
import org.tesselation.keytool.security.{KeyProvider, SecurityProvider}
import org.tesselation.kryo.{KryoSerializer, coreKryoRegistrar}
import org.tesselation.schema.cluster.{InternalTrustUpdate, InternalTrustUpdateBatch, TrustInfo}
import org.tesselation.schema.generators._
import org.tesselation.schema.peer.PeerId
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object TrustStorageSuite extends SimpleIOSuite with Checkers {

  test("trust update is applied") {

    implicit val kryo = KryoSerializer
      .forAsync[IO](coreKryoRegistrar)
      .use(k => IO.pure(k))
      .unsafeRunSync()

    implicit val securityProvider = SecurityProvider
      .forAsync[IO]
      .use { k =>
        IO.pure(k)
      }
      .unsafeRunSync()

    val keyPair: KeyPair = SecurityProvider
      .forAsync[IO]
      .use { implicit securityProvider =>
        KeyProvider.getKeyPairGenerator(Async[IO], securityProvider).map { kpg =>
          kpg.generateKeyPair()
          kpg.generateKeyPair()
        }
      }
      .unsafeRunSync()

    val keyPairInvalid: KeyPair = SecurityProvider
      .forAsync[IO]
      .use { implicit securityProvider =>
        KeyProvider.getKeyPairGenerator(Async[IO], securityProvider).map { kpg =>
          kpg.generateKeyPair()
        }
      }
      .unsafeRunSync()

    val data = "test"
    val hash = crypto.RefinedHashable(data)(kryo).hash.map(_.value).getOrElse("")
    println(s"hash ${hash}")

    val useInvalidKey = false
    val signed = if (useInvalidKey) {
      crypto.RefinedSignedF(hash).sign(keyPair)
    } else {
      crypto.RefinedSignedF(hash).sign(keyPairInvalid)
    }

    val signedDone = signed.unsafeRunSync()
    println(s"signedDone ${signedDone}")
    println(s"done ${signedDone}")

    forall(peerGen) { peer =>
      for {
        trust <- Ref[IO].of(Map.empty[PeerId, TrustInfo])
        cs = TrustStorage.make[IO](trust)
        _ <- cs.updateTrust(
          InternalTrustUpdateBatch(List(InternalTrustUpdate(peer.id, 0.5)))
        )
        updatedTrust <- cs.getTrust()
      } yield expect(updatedTrust(peer.id).trustLabel.get == 0.5)
    }
  }
}
