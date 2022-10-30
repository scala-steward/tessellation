package org.tessellation.dag.l1.rosetta.server

import org.tessellation.dag.l1.rosetta.server.Error.makeErrorCode
import org.tessellation.dag.l1.rosetta.server.TransactionUtilities.translateTransactionToOperations
import org.tessellation.kryo.KryoSerializer
import org.tessellation.rosetta.server.model
import org.tessellation.rosetta.server.model.TransactionIdentifier
import org.tessellation.rosetta.server.model.dag.schema.ChainObjectStatus
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.signature.Signed
import org.tessellation.rosetta.server.model.{Transaction => RosettaTransaction}

object RosettaTransactionUtilities {

  def translateRosettaTransaction[F[_]: KryoSerializer](
                                                         signedTransaction: Signed[Transaction],
                                                         status: String = ChainObjectStatus.Accepted.toString,
                                                         includeNegative: Boolean = true
                                                       ): Either[model.Error, RosettaTransaction] = {
    // Is this the correct hash reference here? Are we segregating the signature data here?
    import org.tessellation.ext.crypto._

    val tx = signedTransaction
    tx.hash.left.map(_ => makeErrorCode(0)).map { hash =>
      RosettaTransaction(
        TransactionIdentifier(hash.value),
        translateTransactionToOperations(tx, status, includeNegative),
        None,
        None
      )
    }
  }

}
