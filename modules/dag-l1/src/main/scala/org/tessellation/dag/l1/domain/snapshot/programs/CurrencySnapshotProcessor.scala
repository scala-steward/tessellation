package org.tessellation.dag.l1.domain.snapshot.programs

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tessellation.dag.domain.block.CurrencyBlock
import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.snapshot.programs.SnapshotProcessor.SnapshotProcessingResult
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.dag.snapshot._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.transaction.{CurrencyTransaction, TransactionReference}
import org.tessellation.sdk.domain.snapshot.Validator
import org.tessellation.sdk.domain.snapshot.storage.{LastCurrencySnapshotStorage, LastGlobalSnapshotStorage}
import org.tessellation.security.hash.ProofsHash
import org.tessellation.security.{Hashed, SecurityProvider}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

sealed trait CurrencySnapshotProcessor[F[_]] extends SnapshotProcessor[F, CurrencyTransaction, CurrencyBlock, CurrencySnapshot]

object CurrencySnapshotProcessor {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    identifier: Address,
    addressStorage: AddressStorage[F],
    blockStorage: BlockStorage[F, CurrencyTransaction, CurrencyBlock],
    lastGlobalSnapshotStorage: LastGlobalSnapshotStorage[F],
    lastCurrencySnapshotStorage: LastCurrencySnapshotStorage[F],
    transactionStorage: TransactionStorage[F, CurrencyTransaction]
  ): CurrencySnapshotProcessor[F] =
    new CurrencySnapshotProcessor[F] {
      def process(globalSnapshot: Hashed[GlobalSnapshot]): F[SnapshotProcessingResult] = ???

      private def fetchCurrencySnapshots(globalSnapshot: GlobalSnapshot): Option[NonEmptyList[CurrencySnapshot]] =
        globalSnapshot.stateChannelSnapshots
          .get(identifier)
          .map(
            _.toList.flatMap(binary => KryoSerializer[F].deserialize[CurrencySnapshot](binary.content).toOption)
          ) // TODO: nie powinniśmy odrzucać gdy dostajemy choć jeden None -> traverse zamiast flatmap
          .flatMap(NonEmptyList.fromList)

      private def checkCurrencySnapshotAlignment(
        currencySnapshot: CurrencySnapshot,
        maybeLast: F[Option[Hashed[CurrencySnapshot]]]
      ): F[Alignment] =
        for {
          acceptedInMajority <- currencySnapshot.blocks.toList.traverse {
            case BlockAsActiveTip(block, usageCount) =>
              block.toHashedWithSignatureCheck.flatMap(_.liftTo[F]).map(b => b.proofsHash -> (b, usageCount))
          }.map(_.toMap)

          GlobalSnapshotTips(gsDeprecatedTips, gsRemainedActive) = currencySnapshot.tips

          result <- maybeLast.flatMap {
            case Some(last) =>
              Validator.compare[CurrencyBlock, CurrencySnapshot](last, currencySnapshot) match {
                case Validator.NextSubHeight => Applicative[F].unit
                case Validator.NextHeight    => Applicative[F].unit
                case Validator.NotNext       => Applicative[F].unit
              }

            case None => Applicative[F].unit
          }
        } yield AlignedAtNewHeight(Set.empty, Set.empty, Set.empty, Set.empty, Map.empty)

      private def checkAlignment(globalSnapshot: GlobalSnapshot): F[Either[Ignore, List[(Alignment, CurrencySnapshot)]]] =
        for {
          lastGlobal <- lastGlobalSnapshotStorage.get
          lastSnapshot <- lastCurrencySnapshotStorage.get
          currencySnapshots = fetchCurrencySnapshots(globalSnapshot)
          result <- (lastGlobal, currencySnapshots) match {
            case (None, _) =>
              Applicative[F].unit // Accept initial GlobalSnapshot
            case (Some(lastGlobalSnapshot), None) if Validator.isNextSnapshot(lastGlobalSnapshot, globalSnapshot) =>
              Applicative[F].unit // Accept GlobalSnapshot
            case (Some(lastGlobalSnapshot), Some(currencySnapshots)) if Validator.isNextSnapshot(lastGlobalSnapshot, globalSnapshot) =>
              lastSnapshot match {
                case Some(lastSnapshot) => Applicative[F].unit // check and accept
                case None               => Applicative[F].unit // perform download???
              }
            case (_, _) => Applicative[F].unit
          }
        } yield
          Ignore(Height(10L), SubHeight(10L), SnapshotOrdinal(10L), Height(10L), SubHeight(10L), SnapshotOrdinal(10L))
            .asLeft[List[(Alignment, CurrencySnapshot)]]

      def extractMajorityTxRefs(
        acceptedInMajority: Map[ProofsHash, (Hashed[CurrencyBlock], NonNegLong)],
        snapshot: CurrencySnapshot
      ): Map[Address, TransactionReference] = {
        val sourceAddresses =
          acceptedInMajority.values
            .flatMap(_._1.transactions.toSortedSet)
            .map(_.source)
            .toSet

        snapshot.lastTxRefs.view.filterKeys(sourceAddresses.contains).toMap
      }
    }
}
