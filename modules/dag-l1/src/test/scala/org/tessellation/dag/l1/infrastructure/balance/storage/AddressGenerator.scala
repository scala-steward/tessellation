package org.tessellation.dag.l1.infrastructure.balance.storage

import org.tessellation.schema.address.{Address, DAGAddressRefined}
import org.tessellation.security.Base58

import eu.timepit.refined.refineV
import org.scalacheck.Gen

object AddressGenerator {
  private val base58CharGen: Gen[Char] = Gen.oneOf(Base58.alphabet)

  val addressGen: Gen[Address] =
    for {
      end <- Gen.stringOfN(36, base58CharGen)
      par = end.filter(_.isDigit).map(_.toString.toInt).sum % 9
    } yield Address(refineV[DAGAddressRefined].unsafeFrom(s"DAG$par$end"))
}
