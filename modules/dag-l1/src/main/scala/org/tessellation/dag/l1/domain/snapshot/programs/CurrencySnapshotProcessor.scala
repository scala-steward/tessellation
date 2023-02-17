package org.tessellation.dag.l1.domain.snapshot.programs

import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.all._
import cats.{Applicative, ApplicativeError}

import org.tessellation.dag.domain.block.CurrencyBlock
import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.snapshot.programs.SnapshotProcessor._
import org.tessellation.dag.l1.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.dag.l1.infrastructure.address.storage.AddressStorage
import org.tessellation.dag.snapshot._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction.{CurrencyTransaction, TransactionReference}
import org.tessellation.sdk.domain.snapshot.Validator
import org.tessellation.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.security.hash.ProofsHash
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.Signed.InvalidSignatureForHash
import org.tessellation.security.{Hashed, SecurityProvider}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

sealed trait CurrencySnapshotProcessor[F[_]] extends SnapshotProcessor[F, CurrencyTransaction, CurrencyBlock, CurrencySnapshot]

object CurrencySnapshotProcessor {

  def make[F[_]: Async: Random: KryoSerializer: SecurityProvider](
    identifier: Address,
    addressStorage: AddressStorage[F],
    blockStorage: BlockStorage[F, CurrencyTransaction, CurrencyBlock],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalSnapshot],
    lastCurrencySnapshotStorage: LastSnapshotStorage[F, CurrencySnapshot],
    transactionStorage: TransactionStorage[F, CurrencyTransaction]
  ): CurrencySnapshotProcessor[F] =
    new CurrencySnapshotProcessor[F] {
      def process(globalSnapshot: Hashed[GlobalSnapshot]): F[SnapshotProcessingResult] = {
        val globalSnapshotReference = GlobalSnapshotReference.fromHashedGlobalSnapshot(globalSnapshot)

        lastGlobalSnapshotStorage.get.flatMap {
          case Some(lastGlobal) =>
            Validator.compare(lastGlobal, globalSnapshot.signed.value) match {
              case _: Validator.Next =>
                val setGlobalSnapshot =
                  lastGlobalSnapshotStorage
                    .set(globalSnapshot)
                    .as[SnapshotProcessingResult](Aligned(globalSnapshotReference, Set.empty))

                processCurrencySnapshots(globalSnapshot, setGlobalSnapshot)

              case Validator.NotNext =>
                Applicative[F].pure(SnapshotIgnored(globalSnapshotReference))
            }
          case None => // TODO: add verification
            // either accept, or check if there are currency snapshots
            // and only then process that global snapshot (perform download)
            val setGlobalSnapshot =
              lastGlobalSnapshotStorage
                .setInitial(globalSnapshot)
                .as[SnapshotProcessingResult](DownloadPerformed(globalSnapshotReference, Set.empty, Set.empty))

            processCurrencySnapshots(globalSnapshot, setGlobalSnapshot)
        }
      }

      private def processCurrencySnapshots(
        globalSnapshot: Hashed[GlobalSnapshot],
        setGlobalSnapshot: F[SnapshotProcessingResult]
      ): F[SnapshotProcessingResult] =
        fetchCurrencySnapshots(globalSnapshot).flatMap {
          case Some(Validated.Valid(hashedSnapshots)) =>
            prepareIntermediateStorages(addressStorage, blockStorage, lastCurrencySnapshotStorage, transactionStorage).flatMap {
              case (intermediateAS, intermediateBS, intermediateLCSS, intermediateTS) =>
                hashedSnapshots.traverse { hcs =>
                  checkAlignment(hcs.signed.value, intermediateBS, intermediateLCSS).flatTap {
                    case _: Ignore =>
                      ApplicativeError[F, Throwable].raiseError[Unit](
                        new Throwable(s"Invalid currency snapshot detected!")
                      ) // TODO: not sure we can throw, unless! we handle that throw before the end of this function, but actually I think tailRecM would be better
                    case _: AlignedAtNewOrdinal | _: AlignedAtNewHeight | _: DownloadNeeded | _: RedownloadNeeded => Applicative[F].unit
                  }
                    .flatTap(
                      processAlignment(hcs, _, intermediateBS, intermediateTS, intermediateLCSS, intermediateAS)
                    )
                    .map((_, hcs))
                }
            }.flatMap(_.traverse {
              case (alignment, hashedSnapshot) =>
                processAlignment(
                  hashedSnapshot,
                  alignment,
                  blockStorage,
                  transactionStorage,
                  lastCurrencySnapshotStorage,
                  addressStorage
                )
            }).flatMap { results =>
              setGlobalSnapshot
                .map(BatchResult(_, results))
            }
//              .map[SnapshotProcessingResult](BatchResult)
//              .flatTap(_ => lastGlobalSnapshotStorage.set(globalSnapshot))
          case Some(Validated.Invalid(_)) =>
            ApplicativeError[F, Throwable].raiseError[SnapshotProcessingResult](
              new Throwable("Not all currency snapshots are signed correctly!")
            ) // TODO: handle error on the
          case None => // TODO: add verification of global snapshot
            setGlobalSnapshot
        }

      private def prepareIntermediateStorages(
        addressStorage: AddressStorage[F],
        blockStorage: BlockStorage[F, CurrencyTransaction, CurrencyBlock],
        lastCurrencySnapshotStorage: LastSnapshotStorage[F, CurrencySnapshot],
        transactionStorage: TransactionStorage[F, CurrencyTransaction]
      ): F[
        (
          AddressStorage[F],
          BlockStorage[F, CurrencyTransaction, CurrencyBlock],
          LastSnapshotStorage[F, CurrencySnapshot],
          TransactionStorage[F, CurrencyTransaction]
        )
      ] = {
        val intermediateBS = blockStorage.getState().flatMap(BlockStorage.make[F, CurrencyTransaction, CurrencyBlock](_))
        val intermediateLCSS = lastCurrencySnapshotStorage.get.flatMap(LastSnapshotStorage.make(_))
        val intermediateAS = addressStorage.getState().flatMap(AddressStorage.make(_))
        val intermediateTS =
          transactionStorage.getState().flatMap { case (lastTxs, waitingTxs) => TransactionStorage.make(lastTxs, waitingTxs) }

        (intermediateAS, intermediateBS, intermediateLCSS, intermediateTS).mapN((_, _, _, _))
      }

      // We are extracting all currency snapshots, but we don't assume that all the state channel binaries need to be
      // currency snapshots. So if there would be an error while deserializing we will ignore such snapshot.
      private def fetchCurrencySnapshots(
        globalSnapshot: GlobalSnapshot
      ): F[Option[ValidatedNel[InvalidSignatureForHash[CurrencySnapshot], NonEmptyList[Hashed[CurrencySnapshot]]]]] =
        globalSnapshot.stateChannelSnapshots
          .get(identifier)
          .map {
            _.toList.flatMap(binary => KryoSerializer[F].deserialize[Signed[CurrencySnapshot]](binary.content).toOption)
          }
          .flatMap(NonEmptyList.fromList)
          .map(_.traverse(_.toHashedWithSignatureCheck))
          .sequence
          .map(_.map(_.traverse(_.toValidatedNel)))

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
