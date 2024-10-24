package io.constellationnetwork.node.shared.infrastructure.gossip

import java.security.KeyPair

import cats.effect.std.Queue
import cats.effect.{Async, Ref}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.schema.gossip._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.{Hashed, Hasher, SecurityProvider}

import eu.timepit.refined.auto._
import io.circe.Encoder
import io.circe.syntax._
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Gossip {

  def make[F[_]: Async: SecurityProvider: Hasher: Metrics](
    rumorQueue: Queue[F, Hashed[RumorRaw]],
    selfId: PeerId,
    generation: Generation,
    keyPair: KeyPair
  ): F[Gossip[F]] =
    Ref.of[F, Counter](Counter.MinValue).map { counter =>
      new Gossip[F] {

        private val rumorLogger = Slf4jLogger.getLoggerFromName[F](rumorLoggerName)

        def spread[A: TypeTag: Encoder](rumorContent: A): F[Unit] =
          for {
            contentJson <- rumorContent.asJson.pure[F]
            count <- counter.getAndUpdate(_.next)
            rumor = PeerRumorRaw(selfId, Ordinal(generation, count), contentJson, ContentType.of[A])
            _ <- signAndOffer(rumor)
          } yield ()

        def spreadCommon[A: TypeTag: Encoder](rumorContent: A): F[Unit] =
          for {
            contentJson <- rumorContent.asJson.pure[F]
            rumor = CommonRumorRaw(contentJson, ContentType.of[A])
            _ <- signAndOffer(rumor)
          } yield ()

        private def signAndOffer(rumor: RumorRaw): F[Unit] =
          for {
            signedRumor <- rumor.sign(keyPair)
            hashedRumor <- signedRumor.toHashed
            _ <- rumorQueue.offer(hashedRumor)
            _ <- metrics.updateRumorsSpread(signedRumor)
            _ <- logSpread(hashedRumor)
          } yield ()

        private def logSpread(hashedRumor: Hashed[RumorRaw]): F[Unit] =
          rumorLogger.info(
            s"Rumor spread {hash=${hashedRumor.hash.show}, rumor=${hashedRumor.signed.value.show}"
          )

      }
    }

}
