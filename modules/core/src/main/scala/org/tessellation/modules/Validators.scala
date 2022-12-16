package org.tessellation.modules

import cats.effect.Async

import org.tessellation.dag.block.BlockValidator
import org.tessellation.dag.domain.block.Block
import org.tessellation.dag.transaction.{TransactionChainValidator, TransactionValidator}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.Transaction
import org.tessellation.sdk.infrastructure.gossip.RumorValidator
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.SignedValidator

object Validators {

  def make[F[_]: Async: KryoSerializer: SecurityProvider, A <: Transaction, B <: Block[A]](
    seedlist: Option[Set[PeerId]]
  ) = {
    val signedValidator = SignedValidator.make[F]
    val transactionChainValidator = TransactionChainValidator.make[F, A]
    val transactionValidator = TransactionValidator.make[F, A](signedValidator)
    val blockValidator = BlockValidator.make[F, A, B](signedValidator, transactionChainValidator, transactionValidator)
    val rumorValidator = RumorValidator.make[F](seedlist, signedValidator)

    new Validators[F, A, B](
      signedValidator,
      transactionChainValidator,
      transactionValidator,
      blockValidator,
      rumorValidator
    ) {}
  }
}

sealed abstract class Validators[F[_], A <: Transaction, B <: Block[A]] private (
  val signedValidator: SignedValidator[F],
  val transactionChainValidator: TransactionChainValidator[F, A],
  val transactionValidator: TransactionValidator[F, A],
  val blockValidator: BlockValidator[F, A, B],
  val rumorValidator: RumorValidator[F]
)
