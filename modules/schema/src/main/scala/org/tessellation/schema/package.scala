package org.tessellation

import java.security.Signature

import org.tessellation.ext.kryo.KryoRegistrationId
import org.tessellation.schema.address.{Address, AddressCache}
import org.tessellation.schema.gossip._
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.SignRequest
import org.tessellation.schema.security.signature.Signed
import org.tessellation.schema.security.signature.Signed.SignedOrdering
import org.tessellation.schema.security.signature.signature.SignatureProof
import org.tessellation.schema.transaction.{RewardTransaction, Transaction, TransactionReference}
import org.tessellation.schema.trust.PublicTrust

import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Greater

package object schema {

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
