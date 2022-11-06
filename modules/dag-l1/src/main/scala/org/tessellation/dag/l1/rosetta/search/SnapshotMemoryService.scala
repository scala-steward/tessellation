package org.tessellation.dag.l1.rosetta.search

import cats.effect.kernel.Async
import cats.implicits.toFunctorOps

import org.tessellation.dag.l1.domain.snapshot.storage.LastGlobalSnapshotStorage
import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.ext.crypto.RefinedHashable
import org.tessellation.kryo.KryoSerializer
import org.tessellation.security.hash.Hash

object SnapshotMemoryService {

  def make[F[_]: Async: KryoSerializer](
    storage: LastGlobalSnapshotStorage[F]
  ): SnapshotMemoryService[F] =
    new SnapshotMemoryService[F](store = storage) {
      override def getCurrentHashAndSnapshot: F[Either[String, (Hash, GlobalSnapshot)]] =
        store.get.map(
          _.map(snapshot => snapshot.hash.left.map(_.getMessage).map(hash => (hash, snapshot)))
            .getOrElse(Left("There is no current snapshot."))
        )

      override def getGenesisHashAndSnapshot: F[Either[String, (Hash, GlobalSnapshot)]] = {
        val genesisSnapshot = GlobalSnapshot.mkGenesis(Map.empty)
        Async[F].pure(genesisSnapshot.hash.left.map(_.getMessage).map(hash => (hash, genesisSnapshot)))
      }

    }
}

sealed abstract class SnapshotMemoryService[F[_]] private (
  val store: LastGlobalSnapshotStorage[F]
) {
  def getCurrentHashAndSnapshot: F[Either[String, (Hash, GlobalSnapshot)]]
  def getGenesisHashAndSnapshot: F[Either[String, (Hash, GlobalSnapshot)]]
}
