package org.tessellation.sdk.cli

import cats.data.NonEmptySet

import scala.io.Source

import org.tessellation.schema.address.Address
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.AppEnvironment._

import io.circe.parser.decode

object StateChannelAllowanceLists {

  def get(env: AppEnvironment): Option[Map[Address, NonEmptySet[PeerId]]] =
    env match {
      case Dev     => None
      case Testnet => Some(loadTestnet())
      case Mainnet => Some(loadMainnet())
    }

  def loadTestnet(): Map[Address, NonEmptySet[PeerId]] = load("state_channels_allowance_lists_testnet.txt")
  def loadMainnet(): Map[Address, NonEmptySet[PeerId]] = load("state_channels_allowance_lists_mainnet.txt")
  def load(name: String): Map[Address, NonEmptySet[PeerId]] = Source
    .fromResource(name)
    .getLines()
    .map(decode[(Address, NonEmptySet[PeerId])])
    .map(_.fold(e => throw e, identity))
    .toMap
}
