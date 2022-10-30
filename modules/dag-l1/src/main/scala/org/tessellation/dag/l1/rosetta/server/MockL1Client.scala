package org.tessellation.dag.l1.rosetta.server

import cats.effect.Async
import cats.implicits.{toFlatMapOps, toFunctorOps}

import org.tessellation.dag.l1.domain.rosetta.server.L1Client
import org.tessellation.dag.l1.rosetta.MockData.mockup
import org.tessellation.dag.l1.rosetta.server.api.model.SnapshotInfo
import org.tessellation.dag.l1.rosetta.{MockData, examples}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.rosetta.server.model._
import org.tessellation.rosetta.server.model.dag.schema.ConstructionPayloadsRequestMetadata
import org.tessellation.schema.{address, transaction}
import org.tessellation.security.signature.Signed

object MockL1Client {

  def make[F[_]: Async]: L1Client[F] =
    new L1Client[F] {
      override def queryCurrentSnapshotHashAndHeight() =
        Async[F].pure(
          Right(
            SnapshotInfo(
              MockData.mockup.currentBlockHash.value,
              MockData.mockup.height.value.value,
              MockData.mockup.currentTimeStamp
            )
          )
        )

      def queryGenesisSnapshotHash() = Async[F].pure(Right(MockData.mockup.genesisHash))

      def queryPeerIds() = Async[F].pure(Right(List(examples.sampleHash)))

      def queryNetworkStatus(): F[Either[String, NetworkStatusResponse]] =
        queryPeerIds().flatMap { x =>
          x match {
            case Left(err) => Async[F].pure(Left(err): Either[String, NetworkStatusResponse])
            case Right(peerIds) =>
              queryGenesisSnapshotHash().flatMap {
                case Left(err) => Async[F].pure(Left(err): Either[String, NetworkStatusResponse])
                case Right(genesisHash) =>
                  queryCurrentSnapshotHashAndHeight().map {
                    case Left(err) => Left(err)
                    case Right(SnapshotInfo(snapshotHash, snapshotHeight, timestamp)) =>
                      Right(
                        NetworkStatusResponse(
                          BlockIdentifier(snapshotHeight, snapshotHash),
                          timestamp,
                          BlockIdentifier(0, genesisHash),
                          Some(BlockIdentifier(0, genesisHash)),
                          Some(SyncStatus(Some(snapshotHeight), Some(snapshotHeight), Some("synced"), Some(true))),
                          peerIds.map(id => Peer(id, None))
                        )
                      )
                  }
              }
          }
        }

      def queryVersion() = Async[F].pure(Right("2.0.0"))

      def submitTransaction[Q[_]: KryoSerializer](stx: Signed[transaction.Transaction]) =
        Async[F].pure(mockup.acceptTransactionIncrementBlock(stx))

      def requestSuggestedFee() = Async[F].pure(Right(Some(123)))

      def requestLastTransactionMetadataAndFee(addressActual: address.Address) =
        Async[F].pure(
          Right(
            Some(
              ConstructionPayloadsRequestMetadata(
                addressActual.value.value,
                "emptylasttxhashref",
                0L,
                0L,
                None
              )
            )
          )
        )

      def queryMempool() = Async[F].pure(List(examples.sampleHash))

      def queryMempoolTransaction(hash: String) = Async[F].pure(
        if (hash == examples.sampleHash)
          Some(examples.transaction)
        else
          None
      )
    }

}
