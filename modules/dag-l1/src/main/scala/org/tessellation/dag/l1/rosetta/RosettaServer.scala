package org.tessellation.dag.l1.rosetta

import org.tessellation.ext.kryo.KryoRegistrationId
import org.tessellation.schema.transaction.{RewardTransaction, Transaction}
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.SignatureProof

import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Interval

object RosettaServer {
  type RunnerKryoRegistrationIdRange = Interval.Closed[300, 399]
  type RunnerKryoRegistrationId = KryoRegistrationId[RunnerKryoRegistrationIdRange]

  implicit val runnerKryoRegistrar: Map[Class[_], RunnerKryoRegistrationId] = Map(
    classOf[RewardTransaction] -> 389,
    classOf[Signed[Transaction]] -> 390,
    SignatureProof.OrderingInstance.getClass -> 391
  )
}
