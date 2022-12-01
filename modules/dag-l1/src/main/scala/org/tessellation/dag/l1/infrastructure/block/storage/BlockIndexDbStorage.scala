package org.tessellation.dag.l1.infrastructure.block.storage

import cats.Applicative
import cats.effect.MonadCancelThrow
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tessellation.dag.l1.domain.block.storage.{BlockIndexEntry, BlockIndexStorage, StoredBlockIndex}
import org.tessellation.dag.l1.infrastructure.db.Database
import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import doobie.implicits._
import doobie.quill.DoobieContext
import io.getquill.context.Context
import io.getquill.{Literal, SqliteDialect}

object BlockIndexDbStorage {
  type Ctx = DoobieContext.SQLite[Literal] with Context[SqliteDialect, Literal]

  def make[F[_]: MonadCancelThrow: Database: KryoSerializer]: BlockIndexStorage[F] =
    make[F](new DoobieContext.SQLite[Literal](Literal))

  def make[F[_]: MonadCancelThrow: KryoSerializer](ctx: Ctx)(implicit db: Database[F]): BlockIndexStorage[F] =
    new BlockIndexStorage[F] {
      val xa = db.xa
      import ctx._

      val getStoredBlockIndex = quote {
        querySchema[StoredBlockIndex]("GlobalSnapshotIndex")
      }

      val getStoredBlockIndexByHash = quote { (hash: Hash) =>
        getStoredBlockIndex.filter(_.hash == hash).take(1)
      }

      val getStoredBlockIndexByHashIndexOrLastTransaction = quote { (hash: Option[Hash], index: Option[Long]) =>
        getStoredBlockIndex.filter(x => hash.forall(_ == x.hash) || index.forall(_ == x.indexValue))
      }

      val insertStoredBlockIndex = quote { (hash: Hash, index: Long, bytes: Array[Byte]) =>
        getStoredBlockIndex.insert(
          _.hash -> hash,
          _.indexValue -> index,
          _.snapshotBytes -> bytes
        )
      }

      val updateStoredBlockIndex = quote { (hash: Hash, index: Long, bytes: Array[Byte]) =>
        getStoredBlockIndex.update(
          _.hash -> hash,
          _.indexValue -> index,
          _.snapshotBytes -> bytes
        )
      }

      def updateStoredBlockIndexValues(values: Map[Hash, (Long, Array[Byte])]) =
        values.toList.traverse {
          case (hash, moreArgs) =>
            val index = moreArgs._1
            val bytes = moreArgs._2

            run(getStoredBlockIndexByHash(lift(hash)))
              .map(_.headOption)
              .flatMap {
                case Some(_) => run(updateStoredBlockIndex(lift(hash), lift(index), lift(bytes)))
                case None    => run(insertStoredBlockIndex(lift(hash), lift(index), lift(bytes)))
              }
              .transact(xa)
              .as(())
        }.map(_.reduce((y, _) => y))

      def indexGlobalSnapshot(signedGlobalSnapshot: Signed[GlobalSnapshot]) =
        signedGlobalSnapshot.hash.toOption
          .flatMap(
            hash =>
              KryoSerializer[F]
                .serialize(signedGlobalSnapshot)
                .map(
                  bytes => updateStoredBlockIndexValues(Map(hash -> (signedGlobalSnapshot.height.value.value, bytes)))
                )
                .toOption
          )
          .getOrElse(Applicative[F].pure(()))

      def getStoredBlockIndexValue(hash: Option[Hash], index: Option[Long]) = {
        val queryResult = run(getStoredBlockIndexByHashIndexOrLastTransaction(lift(hash), lift(index)))
          .map(_.headOption)
          .transact(xa)
        finalizeQueryResult(queryResult)
      }

      private def finalizeQueryResult(queryResult: F[Option[StoredBlockIndex]]) =
        queryResult.map(
          _.flatMap(
            x =>
              KryoSerializer[F]
                .deserialize[Signed[GlobalSnapshot]](x.snapshotBytes)
                .map(snapshot => BlockIndexEntry(x.hash, x.indexValue, snapshot))
                .toOption
          )
        )
    }
}
