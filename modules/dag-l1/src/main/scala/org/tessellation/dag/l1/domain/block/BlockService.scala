package org.tessellation.dag.l1.domain.block

import cats.effect.Async
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._

import scala.util.control.NoStackTrace

import org.tessellation.dag.block.processing.{BlockAcceptanceContext, BlockAcceptanceManager, BlockNotAcceptedReason}
import org.tessellation.dag.domain.block.{Block, BlockReference}
import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.transaction
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

trait BlockService[F[_], A <: Transaction, B <: Block[A]] {
  def accept(signedBlock: Signed[B]): F[Unit]
}

object BlockService {

  def make[F[_]: Async: KryoSerializer: SecurityProvider, A <: Transaction, B <: Block[A]](
    blockAcceptanceManager: BlockAcceptanceManager[F, A, B],
    addressStorage: AddressStorage[F],
    blockStorage: BlockStorage[F, A, B],
    transactionStorage: TransactionStorage[F, A],
    collateral: Amount
  ): BlockService[F, A, B] =
    new BlockService[F, A, B] {

      def accept(signedBlock: Signed[B]): F[Unit] =
        for {
          hashedBlock <- signedBlock.toHashed
          blockRef = BlockReference(hashedBlock.height, hashedBlock.proofsHash)

          errorOrAccepted <- blockAcceptanceManager.acceptBlock(signedBlock, context)
          (contextUpdate, _) <- errorOrAccepted.leftMap(BlockAcceptanceError(blockRef, _)).liftTo[F]

          hashedTransactions <- signedBlock.transactions.toNonEmptyList
            .sortBy(_.ordinal)
            .traverse(_.toHashed)

          _ <- hashedTransactions.traverse(transactionStorage.accept)
          _ <- blockStorage.accept(hashedBlock)
          _ <- addressStorage.updateBalances(contextUpdate.balances)
        } yield ()

      private val context: BlockAcceptanceContext[F] = new BlockAcceptanceContext[F] {

        def getBalance(address: Address): F[Option[Balance]] =
          addressStorage.getBalance(address).map(_.some)

        def getLastTxRef(address: Address): F[Option[transaction.TransactionReference]] =
          transactionStorage.getLastAcceptedReference(address).map(_.some)

        def getParentUsage(blockReference: BlockReference): F[Option[NonNegLong]] =
          blockStorage.getUsages(blockReference.hash)

        def getCollateral: Amount = collateral
      }
    }

  case class BlockAcceptanceError(blockReference: BlockReference, reason: BlockNotAcceptedReason) extends NoStackTrace {
    override def getMessage: String = s"Block ${blockReference.show} could not be accepted ${reason.show}"
  }
}
