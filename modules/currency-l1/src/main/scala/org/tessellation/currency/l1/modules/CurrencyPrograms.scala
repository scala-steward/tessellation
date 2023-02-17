package org.tessellation.currency.l1.modules

import cats.effect.Async
import cats.effect.std.Random

import org.tessellation.dag.domain.block.Block
import org.tessellation.dag.l1.domain.snapshot.programs.SnapshotProcessor
import org.tessellation.dag.l1.http.p2p.P2PClient
import org.tessellation.dag.l1.modules.Programs
import org.tessellation.dag.snapshot.Snapshot
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.transaction.Transaction
import org.tessellation.sdk.domain.cluster.programs.L0PeerDiscovery
import org.tessellation.sdk.modules.SdkPrograms
import org.tessellation.security.SecurityProvider

object CurrencyPrograms {

  def make[
    F[_]: Async: KryoSerializer: SecurityProvider: Random,
    T <: Transaction,
    B <: Block[T],
    S <: Snapshot[B]
  ](
    sdkPrograms: SdkPrograms[F],
    p2pClient: P2PClient[F, T, B],
    storages: CurrencyStorages[F, T, B, S],
    snapshotProcessorProgram: SnapshotProcessor[F, T, B, S]
  ): CurrencyPrograms[F, T, B, S] = {
    val l0PeerDiscoveryProgram = L0PeerDiscovery.make(p2pClient.l0Cluster, storages.l0Cluster)
    val globalL0PeerDiscoveryProgram = L0PeerDiscovery.make(p2pClient.l0Cluster, storages.globalL0Cluster)

    new CurrencyPrograms[F, T, B, S] {
      val peerDiscovery = sdkPrograms.peerDiscovery
      val l0PeerDiscovery = l0PeerDiscoveryProgram
      val globalL0PeerDiscovery = globalL0PeerDiscoveryProgram
      val joining = sdkPrograms.joining
      val snapshotProcessor = snapshotProcessorProgram
    }
  }
}

trait CurrencyPrograms[F[_], T <: Transaction, B <: Block[T], S <: Snapshot[B]] extends Programs[F, T, B, S] {
  val globalL0PeerDiscovery: L0PeerDiscovery[F]
}
