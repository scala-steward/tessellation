package org.tessellation.dag.l1.domain.consensus.block.http.p2p.clients

import cats.effect.Sync
import cats.syntax.functor._

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput.PeerBlockConsensusInput
import org.tessellation.schema.transaction.Transaction
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse
import org.tessellation.security.signature.Signed

import io.circe.Encoder
import org.http4s.Method.POST
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.Client

abstract class BlockConsensusClient[F[_], A <: Transaction] {
  def sendConsensusData(data: Signed[PeerBlockConsensusInput[A]]): PeerResponse[F, Unit]
}

object BlockConsensusClient {

  def make[F[_]: Sync, A <: Transaction: Encoder: TypeTag](client: Client[F]): BlockConsensusClient[F, A] =
    new BlockConsensusClient[F, A] {

      def sendConsensusData(data: Signed[PeerBlockConsensusInput[A]]): PeerResponse[F, Unit] =
        PeerResponse("consensus/data", POST)(client) { (req, c) =>
          c.successful(req.withEntity(data)).void
        }
    }
}
