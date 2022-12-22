package org.tessellation

import java.util.UUID

import cats.data.NonEmptyList

import org.tessellation.ext.kryo._

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Greater

package object shared {

  type SharedKryoRegistrationIdRange = Greater[100]

  type SharedKryoRegistrationId = KryoRegistrationId[SharedKryoRegistrationIdRange]

  val sharedKryoRegistrar: Map[Class[_], SharedKryoRegistrationId] = Map(
    classOf[UUID] -> 300,
    classOf[NonEmptyList[_]] -> 305,
    classOf[Refined[_, _]] -> 332
  )

}
