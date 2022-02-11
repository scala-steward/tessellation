package org.tessellation.sdk.domain.cluster.services

import org.tessellation.schema.peer._
import org.tessellation.security.signature.Signed

trait Cluster[F[_]] {
  def getRegistrationRequest: F[RegistrationRequest]
  def signRequest(signRequest: SignRequest): F[Signed[SignRequest]]
  def leave(): F[Unit]

  def info: F[Set[Peer]]
}
