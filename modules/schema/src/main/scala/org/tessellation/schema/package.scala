package org.tessellation

import java.security.Signature

import cats._

import org.tessellation.ext.kryo.KryoRegistrationId
import org.tessellation.ext.refined._
import org.tessellation.schema.address.{Address, AddressCache}
import org.tessellation.schema.gossip._
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.SignRequest
import org.tessellation.schema.security.signature.Signed
import org.tessellation.schema.security.signature.Signed.SignedOrdering
import org.tessellation.schema.security.signature.signature.SignatureProof
import org.tessellation.schema.transaction.{RewardTransaction, Transaction, TransactionReference}
import org.tessellation.schema.trust.PublicTrust

import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.{Greater, NonNegative, Positive}
import eu.timepit.refined.types.numeric._
import io.circe._

package object schema extends OrphanInstances {

  type SchemaKryoRegistrationIdRange = Greater[100]

  type SchemaKryoRegistrationId = KryoRegistrationId[SchemaKryoRegistrationIdRange]

  val schemaKryoRegistrar: Map[Class[_], SchemaKryoRegistrationId] = Map(
    classOf[SignatureProof] -> 301,
    SignatureProof.OrderingInstance.getClass -> 302,
    classOf[Signature] -> 303,
    classOf[SignRequest] -> 304,
    classOf[Signed[_]] -> 306,
    classOf[AddressCache] -> 307,
    classOf[PeerRumorRaw] -> 308,
    NodeState.Initial.getClass -> 313,
    NodeState.ReadyToJoin.getClass -> 314,
    NodeState.LoadingGenesis.getClass -> 315,
    NodeState.GenesisReady.getClass -> 316,
    NodeState.RollbackInProgress.getClass -> 317,
    NodeState.RollbackDone.getClass -> 318,
    NodeState.StartingSession.getClass -> 319,
    NodeState.SessionStarted.getClass -> 320,
    NodeState.WaitingForDownload.getClass -> 321,
    NodeState.DownloadInProgress.getClass -> 322,
    NodeState.Ready.getClass -> 323,
    NodeState.Leaving.getClass -> 324,
    NodeState.Offline.getClass -> 325,
    classOf[PublicTrust] -> 326,
    classOf[Ordinal] -> 327,
    classOf[CommonRumorRaw] -> 328,
    classOf[Transaction] -> 329,
    Transaction.OrderingInstance.getClass -> 330,
    classOf[TransactionReference] -> 331,
    classOf[RewardTransaction] -> 333,
    RewardTransaction.OrderingInstance.getClass -> 334,
    classOf[SignedOrdering[_]] -> 335,
    Address.OrderingInstance.getClass -> 336,
    NodeState.Observing.getClass -> 337,
    classOf[SnapshotOrdinal] -> 602,
    classOf[BlockReference] -> 605,
    classOf[SnapshotTips] -> 607,
    classOf[ActiveTip] -> 608,
    ActiveTip.OrderingInstance.getClass -> 609,
    classOf[DeprecatedTip] -> 612,
    DeprecatedTip.OrderingInstance.getClass -> 613
  )
}

// instances for types we don't control
trait OrphanInstances {
  implicit val hostDecoder: Decoder[Host] =
    Decoder[String].emap(s => Host.fromString(s).toRight("Invalid host"))

  implicit val hostEncoder: Encoder[Host] =
    Encoder[String].contramap(_.toString)

  implicit val portDecoder: Decoder[Port] =
    Decoder[Int].emap(p => Port.fromInt(p).toRight("Invalid port"))

  implicit val portEncoder: Encoder[Port] =
    Encoder[Int].contramap(_.value)

  implicit val posLongEq: Eq[PosLong] =
    eqOf[Long, Positive]

  implicit val posLongShow: Show[PosLong] =
    showOf[Long, Positive]

  implicit val posLongOrder: Order[PosLong] =
    orderOf[Long, Positive]

  implicit val posLongEncoder: Encoder[PosLong] =
    encoderOf[Long, Positive]

  implicit val posLongKeyEncoder: KeyEncoder[PosLong] =
    keyEncoderOf[Long, Positive]

  implicit val posLongKeyDecoder: KeyDecoder[PosLong] =
    keyDecoderOf[Long, Positive]

  implicit val posLongDecoder: Decoder[PosLong] =
    decoderOf[Long, Positive]

  implicit val nonNegLongEncoder: Encoder[NonNegLong] =
    encoderOf[Long, NonNegative]

  implicit val nonNegLongDecoder: Decoder[NonNegLong] =
    decoderOf[Long, NonNegative]

  implicit val nonNegLongKeyEncoder: KeyEncoder[NonNegLong] =
    keyEncoderOf[Long, NonNegative]

  implicit val nonNegLongKeyDecoder: KeyDecoder[NonNegLong] =
    keyDecoderOf[Long, NonNegative]

  implicit def tupleKeyEncoder[A, B](implicit A: KeyEncoder[A], B: KeyEncoder[B]): KeyEncoder[(A, B)] =
    KeyEncoder.instance[(A, B)] { case (a, b) => A(a) + ":" + B(b) }

  implicit def tupleKeyDecoder[A, B](implicit A: KeyDecoder[A], B: KeyDecoder[B]): KeyDecoder[(A, B)] =
    KeyDecoder.instance[(A, B)] {
      case s"$as:$bs" => A(as).flatMap(a => B(bs).map(b => a -> b))
      case _          => None
    }

  implicit val nonNegLongEq: Eq[NonNegLong] =
    eqOf[Long, NonNegative]

  implicit val nonNegLongShow: Show[NonNegLong] =
    showOf[Long, NonNegative]

  implicit val nonNegLongOrder: Order[NonNegLong] =
    orderOf[Long, NonNegative]

  implicit val errorShow: Show[Throwable] = e => s"${e.getClass.getName}{message=${e.getMessage}}"

  implicit def arrayShow[A: Show]: Show[Array[A]] = a => a.iterator.map(Show[A].show(_)).mkString("Array(", ", ", ")")

  implicit def arrayEq[A: Eq]: Eq[Array[A]] = (xs: Array[A], ys: Array[A]) =>
    Eq[Int].eqv(xs.length, ys.length) && xs.zip(ys).forall { case (x, y) => Eq[A].eqv(x, y) }

  implicit def arrayOrder[A: Order]: Order[Array[A]] = Order.by(_.toSeq)
}
