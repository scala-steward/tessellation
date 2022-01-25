package org.tessellation.csv

import java.security.KeyPair

import cats.effect.unsafe.implicits.global
import cats.effect.{Async, IO}
import cats.implicits._

import scala.collection.mutable

import org.tesselation.domain.gossip.Gossip
import org.tesselation.ext.crypto
import org.tesselation.ext.http4s.refined._
import org.tesselation.keytool.security.{KeyProvider, SecurityProvider}
import org.tesselation.kryo.KryoSerializer

import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import org.tessellation.csv.CSVTypes._
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait Validator {
  def validate(data: String): Boolean
}

class BasicCSVValidator extends Validator {
  override def validate(data: String): Boolean =
    data.length > 5
}





class CSVExample[F[_]: Async](
  val kryo: KryoSerializer[F],
  val keyPair: KeyPair,
  val securityProvider: SecurityProvider[F]
) {

  val hashToPrev = new mutable.HashMap[String, String]()
  val channelNameToId = new mutable.HashMap[String, String]()
  val channelDataLatest = new mutable.HashMap[String, String]()
  val message = new mutable.HashMap[String, ChannelData]()
  val dataHashToData = new mutable.HashMap[String, String]()

  val keyPairInvalid: KeyPair = SecurityProvider
    .forAsync[IO]
    .use { implicit securityProvider =>
      KeyProvider.getKeyPairGenerator(Async[IO], securityProvider).map { kpg =>
        kpg.generateKeyPair()
        kpg.generateKeyPair()
      }
    }
    .unsafeRunSync()

  //val stateData =
  val test = 1

}

object CSVExample {

  def keyPair[F[_]: Async: SecurityProvider]() =
    KeyProvider.makeKeyPair
//
//  def main(args: Array[String]) = {
//    val sp = SecurityProvider
//      .forAsync[IO]
//      .use { implicit securityProvider =>
//        KeyProvider.getKeyPairGenerator(Async[IO], securityProvider).map { kpg =>
////        kpg.generateKeyPair()
//          kpg.generateKeyPair()
//        }
//      }
//      .unsafeRunSync()
//    val sp2 = SecurityProvider
//      .forAsync[IO]
//      .use { implicit securityProvider =>
//        KeyProvider.getKeyPairGenerator(Async[IO], securityProvider).map { kpg =>
//          kpg.generateKeyPair()
//        }
//      }
//      .unsafeRunSync()
//    println("test")
//    println(sp.getPublic.getEncoded.toList)
//    println(sp2.getPublic.getEncoded.toList)
//
//  }
  val validationMapper: Map[String, Validator] = Map("BasicCSVValidator" -> new BasicCSVValidator())

}

final case class CSVExampleRoutes[F[_]: Async](
  gossip: Gossip[F],
  csvExample: CSVExample[F]
) extends Http4sDsl[F] {

  implicit val logger = Slf4jLogger.getLogger[F]

  private val prefixPath = "/csv"

  private val cli: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "test" =>
      for (_ <- logger.info("Test CSV route")) yield {}
      println("Test CSV route")
      println(
        s"Gossip key pair: ${csvExample.keyPair.getPublic.getEncoded.toList}"
      )
      println(
        s"Invalid Gossip key pair: ${csvExample.keyPairInvalid.getPublic.getEncoded.toList}"
      )
      println("done")
      Ok()
    //    case POST -> Root / "gossip" / "spread" / strContent =>
    case GET -> Root / "query" / "channel_name" / strContent => {
      Ok(csvExample.channelDataLatest.get(csvExample.channelNameToId(strContent)).flatMap { csvExample.message.get })
    }
    case GET -> Root / "query" / "channel_id" / strContent => {
      Ok(csvExample.channelDataLatest.get(strContent).flatMap { csvExample.message.get })
    }
    case GET -> Root / "query" / "message" / strContent => {
      Ok(csvExample.message.get(strContent))
    }
    case GET -> Root / "query" / "data" / strContent => {
      Ok(csvExample.dataHashToData.get(strContent))
    }

//    case req @ POST -> Root / "query" =>
//      req.decodeR[QueryDataRequest] { qdr =>
//
//        Ok()
//      }
    case req @ POST -> Root / "upload" =>
      req.decodeR[UploadDataRequest] { udr =>
        println(s"Received UploadDataRequest: ${udr}")
        implicit val kryo = csvExample.kryo
        implicit val sec = csvExample.securityProvider
        val data = udr.data.getOrElse(scala.io.Source.fromFile(udr.dataPath.getOrElse("")).getLines().mkString)
        println(s"data ${data}")

        val hash = crypto.RefinedHashable(data)(csvExample.kryo).hash.map(_.value).getOrElse("")
        val channelId = udr.channelId.getOrElse(csvExample.channelNameToId.getOrElse(udr.channelName.get, hash))
        udr.channelName.foreach(n => csvExample.channelNameToId.put(n, channelId))
        println(s"hash ${hash}")
        println(s"channelId ${channelId}")
        csvExample.dataHashToData.put(hash, data)

        val prevMessage = csvExample.channelDataLatest
          .get(channelId)
          .flatMap { csvExample.message.get }
          .map { x =>
            val prv = crypto.RefinedHashable(x.message)(csvExample.kryo).hash.map(_.value).getOrElse("")
            prv
          }
          .getOrElse(hash)
        val signed = if (!udr.useInvalidKey) {
          crypto.RefinedSignedF(hash).sign(csvExample.keyPair)
        } else {
          val sigOriginal = crypto.RefinedSignedF(hash).sign(csvExample.keyPair)
          val invalid = crypto.RefinedSignedF(hash).sign(csvExample.keyPairInvalid)
          sigOriginal.flatMap(
            real =>
              invalid.map { i =>
                real.copy(hashSignature = i.hashSignature)
              }
          )
        }
        println(s"Signed data")

        for {
          s <- signed
          scData = StateChannelData(hash, prevMessage, channelId, data, s)
          messageHash = crypto.RefinedHashable(scData)(csvExample.kryo).hash.map(_.value).getOrElse("")
          _ = csvExample.message.put(messageHash, ChannelData(scData, Set()))
          channelData = csvExample.channelDataLatest.getOrElse(channelId, ChannelData(scData, Set()))
          _ = csvExample.channelDataLatest.put(channelId, messageHash)
          _ <- logger.info(s"Spreading state channel upload data: ${scData}")
          _ <- gossip.spread(scData)
          response <- Ok(UploadDataResponse(messageHash, scData))
        } yield {
          println(s"Gossiping state channel data: ${scData}")
          response
        }
      }
//        Ok()
//        request.uploadDataRequest.map(udr =>
//          Ok()
//        ).orElse(Ok())

//        request.createChannelRequest.map { ccr =>
//          // Placeholder
//          Ok() // CSVAppResponse(Some(CreateChannelResponse(ccr.channelName, ccr.channelName))))
//        }.getOrElse(
//          request.uploadDataRequest.map { udr =>
//            gossip.spread(udr.data)
//            Ok(
//
//            )
//          }.getOrElse(Ok())
//        )
    //          .recoverWith {
    //            case _ =>
    //              Conflict(s"Internal trust update failure")
    //          }
    //      }
//      }
  }

  val cliRoutes: HttpRoutes[F] = Router(
    prefixPath -> cli
  )
}
