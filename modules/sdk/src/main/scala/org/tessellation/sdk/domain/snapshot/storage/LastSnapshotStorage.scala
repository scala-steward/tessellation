package org.tessellation.sdk.domain.snapshot.storage

import org.tessellation.dag.snapshot._
import org.tessellation.schema.height.Height
import org.tessellation.security.Hashed

trait LastSnapshotStorage[F[_], S <: Snapshot[_]] {
  def set(snapshot: Hashed[S]): F[Unit]
  def setInitial(snapshot: Hashed[S]): F[Unit]
  def get: F[Option[Hashed[S]]]
  def getOrdinal: F[Option[SnapshotOrdinal]]
  def getHeight: F[Option[Height]]
}
