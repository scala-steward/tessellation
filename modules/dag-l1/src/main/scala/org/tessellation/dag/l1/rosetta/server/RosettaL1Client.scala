package org.tessellation.dag.l1.rosetta.server

import cats.Applicative
import cats.effect.Async
import cats.implicits.{toFlatMapOps, toFunctorOps}

import scala.util.Random

import org.tessellation.dag.l1.domain.rosetta.server.L1Client
import org.tessellation.dag.l1.rosetta.search.{SnapshotMemoryService, TransactionMemoryService}
import org.tessellation.dag.l1.rosetta.server.api.model.SnapshotInfo
import org.tessellation.kryo.KryoSerializer
import org.tessellation.rosetta.server.model._
import org.tessellation.rosetta.server.model.dag.schema
import org.tessellation.rosetta.server.model.dag.schema.ConstructionPayloadsRequestMetadata
import org.tessellation.schema.{address, transaction}
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

object RosettaL1Client {

  def make[F[_]: Async](
    transactionMemoryService: TransactionMemoryService[F],
    snapshotMemoryService: SnapshotMemoryService[F]
  ): L1Client[F] =
    new L1Client[F] {
      val transactionService = transactionMemoryService
      val snapshotService = snapshotMemoryService
      val suggestedFee = 0L
      val currentVersion = "2.0"

      override def queryCurrentSnapshotHashAndHeight(): F[Either[String, SnapshotInfo]] =
        snapshotService.getCurrentHashAndSnapshot.map(_.map {
          case (hash, snapshot) =>
            SnapshotInfo(hash.value, snapshot.ordinal.value.value, System.currentTimeMillis())
        })

      override def queryGenesisSnapshotHash(): F[Either[String, String]] =
        snapshotService.getCurrentHashAndSnapshot.map(_.map {
          case (hash, _) => hash.value
        })

      override def queryPeerIds(): F[Either[String, List[String]]] =
        snapshotService.getCurrentHashAndSnapshot.map(_.map {
          case (_, snapshot) =>
            snapshot.nextFacilitators.toList.map(_.value.value)
        })

      override def queryNetworkStatus(): F[Either[String, NetworkStatusResponse]] =
        snapshotService.getCurrentHashAndSnapshot.flatMap {
          _ match {
            case Left(err) => Applicative[F].pure(Left(err): Either[String, NetworkStatusResponse])
            case Right((currentSnapshotHash, currentSnapshot)) =>
              snapshotService.getGenesisHashAndSnapshot.map(_.map {
                case (genesisSnapshotHash, genesisSnapshot) =>
                  NetworkStatusResponse(
                    BlockIdentifier(currentSnapshot.ordinal.value.value, currentSnapshotHash.value),
                    999L, // System.currentTimeMillis(),
                    BlockIdentifier(genesisSnapshot.ordinal.value.value, genesisSnapshotHash.value),
                    Some(BlockIdentifier(genesisSnapshot.ordinal.value.value, genesisSnapshotHash.value)),
                    Some(
                      SyncStatus(
                        Some(currentSnapshot.ordinal.value.value),
                        Some(currentSnapshot.ordinal.value.value),
                        Some("synced"),
                        Some(true)
                      )
                    ),
                    currentSnapshot.nextFacilitators.toList.map(id => Peer(id.value.value, None))
                  )
              })
          }
        }

      override def queryVersion(): F[Either[String, String]] = Async[F].pure(Right(currentVersion))

      override def submitTransaction[Q[_]: KryoSerializer](
        stx: Signed[transaction.Transaction]
      ): F[Either[String, Unit]] =
        transactionService.submitTransaction(stx).map(_.left.map(_.toString).map(_ => ()))

      override def requestSuggestedFee(): F[Either[String, Option[Long]]] = Async[F].pure(Right(Some(suggestedFee)))

      override def requestLastTransactionMetadataAndFee(
        addressActual: address.Address
      ): F[Either[String, Option[schema.ConstructionPayloadsRequestMetadata]]] =
        transactionService
          .getLastAcceptedTransactionReference(addressActual)
          .map(
            transactionReference =>
              Right(
                Some(
                  ConstructionPayloadsRequestMetadata(
                    addressActual.value.value,
                    transactionReference.hash.value,
                    transactionReference.ordinal.value.value,
                    suggestedFee,
                    Some(new Random().nextLong())
                  )
                )
              )
          )

      override def queryMempool(): F[List[String]] =
        transactionService.getAllTransactions.map(_.map(_.hash.value))

      override def queryMempoolTransaction(hash: String): F[Option[Signed[transaction.Transaction]]] =
        transactionService.getTransaction(Hash(hash)).map(_.map(_.signed))
    }

}
