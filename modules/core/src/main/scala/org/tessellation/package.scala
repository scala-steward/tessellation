package org

import java.security.PublicKey

import cats.effect.Async

import scala.util.control.NoStackTrace

import org.tessellation.ext.kryo._
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.security.SecurityProvider
import org.tessellation.schema.statechannels.StateChannelOutput

import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Greater
import io.estatico.newtype.ops._

package object tessellation {

  type CoreKryoRegistrationIdRange = Greater[100]
  type CoreKryoRegistrationId = KryoRegistrationId[CoreKryoRegistrationIdRange]

  val coreKryoRegistrar: Map[Class[_], CoreKryoRegistrationId] = Map(
    classOf[StateChannelOutput] -> 700
  )

  implicit class PeerIdToPublicKey(id: PeerId) {

    def toPublic[F[_]: Async: SecurityProvider]: F[PublicKey] =
      id.coerce.toPublicKey
  }

  case object OwnCollateralNotSatisfied extends NoStackTrace

}
