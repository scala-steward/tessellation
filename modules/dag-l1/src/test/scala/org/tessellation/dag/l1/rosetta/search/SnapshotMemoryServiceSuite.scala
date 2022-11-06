package org.tessellation.dag.l1.rosetta.search

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.dag.l1.domain.snapshot.storage.LastGlobalSnapshotStorage
import org.tessellation.dag.l1.{Main, TransactionGenerator}
import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.ext.crypto._
import org.tessellation.keytool.KeyPairGenerator
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.collateral.LatestBalances
import org.tessellation.sdk.sdkKryoRegistrar
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hex.Hex
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import weaver.SimpleIOSuite

object SnapshotMemoryServiceSuite extends SimpleIOSuite with TransactionGenerator {

  type TestResources = (
    SecurityProvider[IO],
    KryoSerializer[IO],
    LastGlobalSnapshotStorage[IO] with LatestBalances[IO]
  )

  def testResources: Resource[IO, TestResources] =
    SecurityProvider.forAsync[IO].flatMap { implicit sp =>
      KryoSerializer.forAsync[IO](Main.kryoRegistrar ++ sdkKryoRegistrar).flatMap { implicit kp =>
        LastGlobalSnapshotStorage.make.map(store => (sp, kp, store)).asResource
      }
    }

  def mkSnapshots(
    implicit K: KryoSerializer[IO],
    S: SecurityProvider[IO]
  ): IO[(Signed[GlobalSnapshot], Signed[GlobalSnapshot])] =
    KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      Signed.forAsyncKryo[IO, GlobalSnapshot](GlobalSnapshot.mkGenesis(Map.empty), keyPair).flatMap { genesis =>
        def snapshot =
          GlobalSnapshot(
            genesis.value.ordinal.next,
            Height.MinValue,
            SubHeight.MinValue,
            genesis.value.hash.toOption.get,
            SortedSet.empty,
            SortedMap.empty,
            SortedSet.empty,
            NonEmptyList.of(PeerId(Hex("peer1"))),
            genesis.info,
            genesis.tips
          )

        Signed.forAsyncKryo[IO, GlobalSnapshot](snapshot, keyPair).map((genesis, _))
      }
    }

  test("Get current snapshot and hash") {
    testResources.use {
      case (securityProvider, kryoSerializer, storage) =>
        implicit val sp = securityProvider
        implicit val kryo = kryoSerializer

        mkSnapshots.flatMap {
          case (genesisSnapshot, signedSnapshot) =>
            genesisSnapshot.toHashed.flatMap { hashedGenesisSnapshot =>
              storage.setInitial(hashedGenesisSnapshot) >>
                signedSnapshot.toHashed.flatMap { hashedSnapshot =>
                  storage.set(hashedSnapshot) >>
                    SnapshotMemoryService
                      .make(storage)
                      .getCurrentHashAndSnapshot
                      .map(
                        either =>
                          either.map {
                            case (_, snapshot) =>
                              expect.same(snapshot.ordinal.value.value, hashedSnapshot.ordinal.value.value)
                          }.toOption.getOrElse(expect(false))
                      )
                }
            }
        }
    }
  }

  test("Get genesis snapshot and hash") {
    testResources.use {
      case (securityProvider, kryoSerializer, storage) =>
        implicit val sp = securityProvider
        implicit val kryo = kryoSerializer

        mkSnapshots.flatMap {
          case (genesisSnapshot, signedSnapshot) =>
            genesisSnapshot.toHashed.flatMap { hashedGenesisSnapshot =>
              storage.setInitial(hashedGenesisSnapshot) >>
                signedSnapshot.toHashed.flatMap { hashedSnapshot =>
                  storage.set(hashedSnapshot) >>
                    SnapshotMemoryService
                      .make(storage)
                      .getGenesisHashAndSnapshot
                      .map(_.map {
                        case (_, snapshot) =>
                          expect.same(snapshot.ordinal.value.value, genesisSnapshot.ordinal.value.value)
                      }.toOption.getOrElse(expect(false)))
                }
            }
        }
    }
  }
}
