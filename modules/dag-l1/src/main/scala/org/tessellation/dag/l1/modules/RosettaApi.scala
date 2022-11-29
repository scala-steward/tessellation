package org.tessellation.dag.l1.modules

import cats.effect.Async

import org.tessellation.dag.l1.rosetta.RosettaRoutes
import org.tessellation.kryo.KryoSerializer
import org.tessellation.security.SecurityProvider

object RosettaApi {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](clients: RosettaClients[F]): RosettaRoutes[F] =
    RosettaRoutes(blockIndexClient = clients.blockIndexClient, l1Client = clients.l1Client)

}
