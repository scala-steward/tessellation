package org.tessellation.csv

import cats.MonadThrow
import cats.effect.Async
import cats.syntax.show._

import org.tesselation.domain.gossip.Gossip
import org.tesselation.ext.crypto
import org.tesselation.infrastructure.gossip.RumorHandler
import org.tesselation.kryo.KryoSerializer
import org.tesselation.schema.gossip.ReceivedRumor

import org.tessellation.csv.CSVTypes.{ChannelData, StateChannelData, StateChannelSignature}
import org.typelevel.log4cats.slf4j.Slf4jLogger

object CSVHandler {

  def csvHandler[F[_]: Async: KryoSerializer](
    csvExample: CSVExample[F],
    gossip: Gossip[F]
  ): RumorHandler[F] = {

    val logger = Slf4jLogger.getLogger[F]

    RumorHandler.fromReceivedRumorFn[F, StateChannelData] {
      case ReceivedRumor(origin, udr) =>
        val valid = new BasicCSVValidator().validate(udr.data)
        println(
          s"Received from authenticated id=${origin.show} data: ${udr} valid:${valid}"
        )
        if (valid) {
          import cats.implicits._

          implicit val kryo = csvExample.kryo
          implicit val sec = csvExample.securityProvider
          val signature = crypto.RefinedSignedF(udr)(Async[F], kryo, sec).sign(csvExample.keyPair)
          val messageHash = crypto.RefinedHashable(udr)(csvExample.kryo).hash.map(_.value).getOrElse("")
          val validSig = udr.signature.hasValidSignature(Async[F], sec, MonadThrow[F], kryo)
          // Move to below loop.
          for {
            v <- validSig
          } yield {
            println(s"Valid signature on received gossip: ${v}")
          }

          for {
            sig <- signature
            s = StateChannelSignature(sig)
            cd1 = ChannelData(
              udr,
              Set(s)
            )
            _ = csvExample.channelDataLatest.put(udr.channelId, messageHash)
            _ = csvExample.message.put(messageHash, cd1)
            _ <- gossip.spread(s)
            _ <- logger.info(
              s"Gossiping signature from authenticated id=${origin.show} data: ${udr} valid:${valid}"
            )
          } yield {
            println(
              s"Gossiping signature from authenticated id=${origin.show} data: ${udr} valid:${valid}"
            )
          }
        } else {
          Async[F].delay(())
        }
    }
  }

  def csvSignatureHandler[F[_]: Async: KryoSerializer](
    csvExample: CSVExample[F]
  ): RumorHandler[F] = {

    val logger = Slf4jLogger.getLogger[F]

    RumorHandler.fromReceivedRumorFn[F, StateChannelSignature] {
      case ReceivedRumor(origin, udr) =>
        println(
          s"Received StateChannelSignature from authenticated id=${origin.show} data: ${udr}"
        )
        val messageHash = crypto.RefinedHashable(udr.signature.value)(csvExample.kryo).hash.map(_.value).getOrElse("")

        val cd = csvExample.message.getOrElse(
          messageHash,
          ChannelData(
            udr.signature.value,
            Set()
          )
        )
        val cd1 = cd.copy(signatures = cd.signatures ++ Set(udr))
        csvExample.message.put(messageHash, cd1)
        import cats.implicits._

        for {
          _ <- logger.info(
            s"Received StateChannelSignature authenticated id=${origin.show} data: ${udr}"
          )
        } yield { () }
    }
  }

}
