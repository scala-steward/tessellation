package org.tessellation.sdk.cli

import cats.data.NonEmptySet
import cats.syntax.eq._

import org.tessellation.schema.address.Address
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.cli.StateChannelAllowanceLists
import org.tessellation.security.hex.Hex

import eu.timepit.refined.auto._
import weaver._
import weaver.scalacheck.Checkers

object StateChannelAllowanceListsSuite extends SimpleIOSuite with Checkers {

  pureTest("allowance list config should be loaded correctly") {

    val result = StateChannelAllowanceLists.load("test_state_channels_allowance_lists.txt")
    val expected = Map(
      Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB") -> NonEmptySet.of(PeerId(Hex("AAA1")), PeerId(Hex("AAA2"))),
      Address("DAG53ho9ssY8KYQdjxsWPYgNbDJ1YqM2RaPDZebU") -> NonEmptySet.of(PeerId(Hex("BBB2")))
    )

    expect(expected === result)
  }

  pureTest("allowance list for testnet can be loaded") {

    val result = StateChannelAllowanceLists.loadTestnet()

    expect(0 === result.size)
  }

  pureTest("allowance list for mainnet can be loaded") {

    val result = StateChannelAllowanceLists.loadMainnet()

    expect(0 === result.size)
  }

}
