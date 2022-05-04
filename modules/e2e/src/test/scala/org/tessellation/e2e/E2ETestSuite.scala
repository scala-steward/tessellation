package org.tessellation.e2e

import cats.effect.unsafe.implicits.global
import cats.effect.{Async, IO, Resource}
import ciris.Secret
import fs2.io.file.{Files, Path}
import fs2.text
import org.tessellation.cli.env.{KeyAlias, Password, StorePath}
import org.tessellation.keytool.KeyStoreUtils
import org.tessellation.kryo.KryoSerializer
import org.tessellation.security.SecurityProvider
import weaver.IOSuite
import weaver.scalacheck.Checkers

import java.security.KeyPair

object E2ETestSuite extends IOSuite with Checkers {

  override type Res = (SecurityProvider[IO], KryoSerializer[IO])

  override def sharedResource: Resource[IO, Res] = {
    val res = SecurityProvider.forAsync[IO].flatMap { implicit sp =>
      KryoSerializer.forAsync[IO](Map.empty).map { implicit kp =>
        (sp, kp)
      }
    }
    res
  }

  private def loadKeyPair[A[_]: Async: SecurityProvider](
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password
  ): A[KeyPair] =
    KeyStoreUtils
      .readKeyPairFromStore[A](
        keyStore.value.toString,
        alias.value.value,
        password.value.value.toCharArray,
        password.value.value.toCharArray
      )

  test("End to end post-deployment test") { resources =>
    implicit val spi: SecurityProvider[IO] = resources._1
    implicit val kpi: KryoSerializer[IO] = resources._2
    val hosts = System.getenv("HOSTS_FILE")
    val genesisKeyStore = Path.apply(System.getenv("GENESIS_KEY_STORE"))
    val path = Path.apply(hosts)
    val hostLines = Files[IO]
      .readAll(path)
      .through(text.utf8.decode)
      .compile
      .toList
      .unsafeRunSync()

    // Required as otherwise comes as a List of length 1
    val splitHostLines = hostLines.mkString.split("\n").toList
    val kp = loadKeyPair[IO](
      StorePath(genesisKeyStore),
      KeyAlias(Secret("node")),
      Password(Secret(""))
    ).unsafeRunSync()

    IO.pure(expect.all(true))
  }

}
