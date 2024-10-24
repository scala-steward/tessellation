package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.config.types.SnapshotSizeConfig
import io.constellationnetwork.node.shared.nodeSharedKryoRegistrar
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.{Signed, signature}

import eu.timepit.refined.auto._
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object CurrencySnapshotCreatorSuite extends MutableIOSuite with Checkers {
  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  val snapshotSizeConfig = SnapshotSizeConfig(203L, 512000L)

  def sharedResource: Resource[IO, Res] =
    for {
      implicit0(k: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](nodeSharedKryoRegistrar)
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
      h = Hasher.forJson[IO]
      sp <- SecurityProvider.forAsync[IO]
    } yield (j, h, sp)

  def signatureProofsGen(implicit sp: SecurityProvider[IO], h: Hasher[IO]): Gen[signature.SignatureProof] = for {
    text <- Gen.alphaStr
    keyPair <- Gen.delay(KeyPairGenerator.makeKeyPair[IO].unsafeRunSync())
    signed = Signed.forAsyncHasher(text, keyPair).unsafeRunSync()
  } yield signed.proofs.head

  test("single signature size matches the size limit") { implicit res =>
    implicit val (json, h, sp) = res

    forall(signatureProofsGen) { proof =>
      for {
        sizeInBytes: Int <- JsonSerializer[F].serialize(proof).map(_.length)
        sizeDiff = Math.abs(sizeInBytes.toLong - snapshotSizeConfig.singleSignatureSizeInBytes.value)
      } yield expect(sizeDiff <= 7L) // ECDSA uses variable length for signature so the size may vary +/- 7 chars
    }
  }
}
