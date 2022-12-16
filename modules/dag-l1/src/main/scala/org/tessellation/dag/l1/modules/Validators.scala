package org.tessellation.dag.l1.modules

import cats.effect.Async

import org.tessellation.dag.block.BlockValidator
import org.tessellation.dag.domain.block.Block
import org.tessellation.dag.transaction.{ContextualTransactionValidator, TransactionChainValidator, TransactionValidator}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.Transaction
import org.tessellation.sdk.infrastructure.gossip.RumorValidator
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.SignedValidator

object Validators {

  def make[F[_]: Async: KryoSerializer: SecurityProvider, A <: Transaction, B <: Block[A]](
    storages: Storages[F, A, B],
    seedlist: Option[Set[PeerId]]
  ): Validators[F, A, B] = {
    val signedValidator = SignedValidator.make[F]
    val transactionChainValidator = TransactionChainValidator.make[F, A]
    val transactionValidator = TransactionValidator.make[F, A](signedValidator)
    val blockValidator =
      BlockValidator.make[F, A, B](signedValidator, transactionChainValidator, transactionValidator)
    val contextualTransactionValidator = ContextualTransactionValidator.make[F, A](
      transactionValidator,
      (address: Address) => storages.transaction.getLastAcceptedReference(address)
    )
    val rumorValidator = RumorValidator.make[F](seedlist, signedValidator)

    new Validators[F, A, B](
      signedValidator,
      blockValidator,
      transactionValidator,
      contextualTransactionValidator,
      rumorValidator
    ) {}
  }
}

sealed abstract class Validators[F[_], A <: Transaction, B <: Block[A]] private (
  val signed: SignedValidator[F],
  val block: BlockValidator[F, A, B],
  val transaction: TransactionValidator[F, A],
  val transactionContextual: ContextualTransactionValidator[F, A],
  val rumorValidator: RumorValidator[F]
)
