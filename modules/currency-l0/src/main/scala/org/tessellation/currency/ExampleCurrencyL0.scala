package org.tessellation.currency

import java.util.UUID

import org.tessellation.currency.l0.L0CurrencyIOApp
import org.tessellation.currency.schema.currency.TokenSymbol
import org.tessellation.schema.address.Address
import org.tessellation.schema.cluster.ClusterId

import eu.timepit.refined.auto._

object ExampleCurrencyL0
    extends L0CurrencyIOApp(
      TokenSymbol("TST"),
      ClusterId(UUID.fromString("517c3a05-9219-471b-a54c-21b7d72f4ae5")),
      Address("DAG7QrKeoHbYezJ6YHUCt7PJDixWgxM4vfKz8qVT")
    ) {}
