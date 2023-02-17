package org.tessellation.currency.l1

import java.util.UUID

import org.tessellation.schema.address.Address
import org.tessellation.schema.cluster.ClusterId

import eu.timepit.refined.auto._

object Main
    extends L1CurrencyIOApp(
      "TST",
      ClusterId(UUID.fromString("517c3a05-9219-471b-a54c-21b7d72f4ae5")),
      Address("DAG77VVVRvdZiYxZ2hCtkHz68h85ApT5b2xzdTkn")
    ) {}
