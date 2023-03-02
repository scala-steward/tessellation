package org.tessellation.sdk.http.p2p.clients

import cats.Applicative
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.StateChannelSnapshotBinary

import org.http4s.Method.POST
import org.http4s.Status
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.Client
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait StateChannelSnapshotClient[F[_]] {
  def sendStateChannelSnapshot(data: Signed[StateChannelSnapshotBinary]): PeerResponse[F, Boolean]
}

object StateChannelSnapshotClient {

  def make[F[_]: Async: SecurityProvider: KryoSerializer](
    client: Client[F],
    identifier: Address
  ): StateChannelSnapshotClient[F] =
    new StateChannelSnapshotClient[F] {

      private val logger = Slf4jLogger.getLogger

      def sendStateChannelSnapshot(data: Signed[StateChannelSnapshotBinary]): PeerResponse[F, Boolean] =
        PeerResponse(s"state-channels/${identifier.value.value}/snapshot", POST)(client) { (req, c) =>
          c.run(req.withEntity(data)).use {
            case Status.Successful(_) => Applicative[F].pure(true)
            case res => res.as[String].flatTap(msg => logger.warn(s"Sending SC snapshot to GL0 failed: ${res.status} $msg")).as(false)
          }
        }
    }
}
