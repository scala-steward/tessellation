package org.tessellation.dag.l1.http.p2p

import org.tessellation.dag.domain.block.Block
import org.tessellation.schema.transaction.Transaction
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse
import org.tessellation.security.signature.Signed

import io.circe.Encoder
import org.http4s.Method.POST
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.Client

trait L0DAGClusterClient[F[_], A <: Transaction, B <: Block[A]] {
  def sendL1Output(output: Signed[B]): PeerResponse[F, Boolean]
}

object L0DAGClusterClient {

  def make[F[_], A <: Transaction, B <: Block[A]: Encoder](client: Client[F]): L0DAGClusterClient[F, A, B] =
    new L0DAGClusterClient[F, A, B] {

      def sendL1Output(output: Signed[B]): PeerResponse[F, Boolean] =
        PeerResponse("dag/l1-output", POST)(client) { (req, c) =>
          c.successful(req.withEntity(output))
        }
    }
}
