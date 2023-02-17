package org.tessellation.currency

import org.tessellation.ext.kryo.KryoRegistrationId

import eu.timepit.refined.numeric.Interval

package object l1 {

  type CurrencyL1KryoRegistrationIdRange = Interval.Closed[900, 999]

  type CurrencyL1KryoRegistrationId = KryoRegistrationId[CurrencyL1KryoRegistrationIdRange]

  val currencyL1KryoRegistrar: Map[Class[_], CurrencyL1KryoRegistrationId] = Map()
}
