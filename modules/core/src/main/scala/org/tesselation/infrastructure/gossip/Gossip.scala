package org.tesselation.infrastructure.gossip

import java.security.KeyPair

import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.flatMap._
import cats.syntax.functor._

import scala.reflect.runtime.universe._

import org.tesselation.domain.gossip.Gossip
import org.tesselation.ext.crypto._
import org.tesselation.ext.kryo._
import org.tesselation.keytool.security.{KeyProvider, SecurityProvider}
import org.tesselation.kryo.KryoSerializer
import org.tesselation.schema.gossip.{Rumor, RumorBatch}
import org.tesselation.schema.peer.PeerId

import org.typelevel.log4cats.slf4j.Slf4jLogger

object Gossip {

  def make[F[_]: Async: SecurityProvider: KryoSerializer](
    rumorQueue: Queue[F, RumorBatch],
    nodeId: PeerId,
    keyPair: KeyPair
  ): Gossip[F] =
    new Gossip[F] {
      implicit val logger = Slf4jLogger.getLogger[F]

      override def spread[A <: AnyRef: TypeTag](rumorContent: A): F[Unit] =
        for {
          contentBinary <- rumorContent.toBinaryF
          rumor = Rumor(typeOf[A].toString, nodeId, contentBinary)
          _ <- logger.info("Gossip spread log test")
          signedRumor <- {
            rumorContent match {
              case x: String if x == "invalid" =>
                println("Gossiping with invalid keypair")
                KeyProvider.makeKeyPair(Async[F], SecurityProvider[F]).flatMap { kp =>
                  println(
                    s"Gossip key pair: ${keyPair.getPublic.getEncoded.toList}, invalid: ${kp.getPublic.getEncoded.toList}"
                  )
                  val invalid = rumor.sign(kp)
                  rumor
                    .sign(keyPair)
                    .flatMap(
                      real =>
                        invalid.map { i =>
                          real.copy(hashSignature = i.hashSignature)
                        }
                    )
                }
              case _ =>
                rumor.sign(keyPair)
            }
          }
          hash <- rumor.hashF
          _ <- rumorQueue.offer(List(hash -> signedRumor))
        } yield ()
    }

}
