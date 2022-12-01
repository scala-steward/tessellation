package org.tessellation.dag.l1.infrastructure.block.storage

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.dag.dagSharedKryoRegistrar
import org.tessellation.dag.l1.config.types.DBConfig
import org.tessellation.dag.l1.domain.block.storage.BlockIndexStorage
import org.tessellation.dag.l1.infrastructure.db.Database
import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.crypto._
import org.tessellation.ext.kryo.MapRegistrationId
import org.tessellation.keytool.KeyPairGenerator
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.sdkKryoRegistrar
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hex.Hex
import org.tessellation.security.signature.Signed

import ciris.Secret
import eu.timepit.refined.auto._
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object BlockIndexDbStorageSuite extends SimpleIOSuite with Checkers {
  val dbConfig = DBConfig("org.sqlite.JDBC", "jdbc:sqlite::memory:", "sa", Secret(""))

  def testResource: Resource[IO, (BlockIndexStorage[IO], KryoSerializer[IO], SecurityProvider[IO])] =
    Database.forAsync[IO](dbConfig).flatMap { implicit db =>
      KryoSerializer
        .forAsync[IO](dagSharedKryoRegistrar.union(sdkKryoRegistrar))
        .flatMap(implicit kryo => SecurityProvider.forAsync[IO].map(x => (BlockIndexDbStorage.make[IO], kryo, x)))
    }

  def mkSnapshots(
    implicit K: KryoSerializer[IO],
    S: SecurityProvider[IO]
  ): IO[(Signed[GlobalSnapshot], Signed[GlobalSnapshot])] =
    KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      Signed.forAsyncKryo[IO, GlobalSnapshot](GlobalSnapshot.mkGenesis(Map.empty), keyPair).flatMap { genesis =>
        def snapshot = GlobalSnapshot(
          genesis.value.ordinal.next,
          Height.MinValue,
          SubHeight.MinValue,
          genesis.value.hash.toOption.get,
          SortedSet.empty,
          SortedMap.empty,
          SortedSet.empty,
          NonEmptyList.of(PeerId(Hex("peer1"))), // TODO
          genesis.info,
          genesis.tips
        )

        Signed.forAsyncKryo[IO, GlobalSnapshot](snapshot, keyPair).map((genesis, _))
      }
    }

  test("Test writing a Signed GlobalSnapshot - snapshot hash") {
    testResource.use {
      case (store, kryo, security) =>
        implicit val kryoImplicit = kryo
        implicit val securityImplicit = security

        mkSnapshots.flatMap {
          case (snapshot1, _) =>
            snapshot1.hash
              .map(
                hash =>
                  kryo
                    .serialize(snapshot1)
                    .map(
                      bytes =>
                        store.updateStoredBlockIndexValues(
                          Map(hash -> (snapshot1.height.value.value, bytes))
                        ) >>
                          store
                            .getStoredBlockIndexValue(Some(hash), None)
                            .map(_.map(x => expect.same(x.hash, hash)).getOrElse(expect(false)))
                    )
                    .toOption
                    .getOrElse(IO.pure(expect(false)))
              )
              .toOption
              .getOrElse(IO.pure(expect(false)))
        }
    }
  }

  test("Test writing a Signed GlobalSnapshot - snapshot hash") {
    testResource.use {
      case (store, kryo, security) =>
        implicit val kryoImplicit = kryo
        implicit val securityImplicit = security

        mkSnapshots.flatMap {
          case (snapshot1, _) =>
            snapshot1.hash
              .map(
                hash =>
                  kryo
                    .serialize(snapshot1)
                    .map(
                      bytes =>
                        store.updateStoredBlockIndexValues(
                          Map(hash -> (snapshot1.height.value.value, bytes))
                        ) >>
                          store
                            .getStoredBlockIndexValue(Some(hash), None)
                            .map(_.map(x => expect.same(x.hash, hash)).getOrElse(expect(false)))
                    )
                    .toOption
                    .getOrElse(IO.pure(expect(false)))
              )
              .toOption
              .getOrElse(IO.pure(expect(false)))
        }
    }
  }

  test("Test writing a Signed GlobalSnapshot - snapshot index") {
    testResource.use {
      case (store, kryo, security) =>
        implicit val kryoImplicit = kryo
        implicit val securityImplicit = security

        mkSnapshots.flatMap {
          case (snapshot1, _) =>
            snapshot1.hash
              .map(
                hash =>
                  kryo
                    .serialize(snapshot1)
                    .map(
                      bytes =>
                        store.updateStoredBlockIndexValues(
                          Map(hash -> (snapshot1.height.value.value, bytes))
                        ) >>
                          store
                            .getStoredBlockIndexValue(None, Some(snapshot1.height.value.value))
                            .map(
                              _.map(x => expect.same(x.height, snapshot1.height.value.value)).getOrElse(expect(false))
                            )
                    )
                    .toOption
                    .getOrElse(IO.pure(expect(false)))
              )
              .toOption
              .getOrElse(IO.pure(expect(false)))
        }
    }
  }

  test("Test indexing a Signed GlobalSnapshot") {
    testResource.use {
      case (store, kryo, security) =>
        implicit val kryoImplicit = kryo
        implicit val securityImplicit = security

        mkSnapshots.flatMap {
          case (snapshot1, _) =>
            snapshot1.hash
              .map(
                hash =>
                  store.indexGlobalSnapshot(snapshot1) >>
                    store
                      .getStoredBlockIndexValue(Some(hash), None)
                      .map(_.map(x => expect.same(x.hash, hash)).getOrElse(expect(false)))
              )
              .toOption
              .getOrElse(IO.pure(expect(false)))
        }
    }
  }
}
