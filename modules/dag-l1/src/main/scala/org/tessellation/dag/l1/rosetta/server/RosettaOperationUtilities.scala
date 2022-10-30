package org.tessellation.dag.l1.rosetta.server

import eu.timepit.refined.refineV
import eu.timepit.refined.types.all.{NonNegLong, PosLong}
import org.tessellation.rosetta.server.model.Operation
import org.tessellation.schema.address.{Address, DAGAddressRefined}
import org.tessellation.schema.address.DAGAddressRefined._
import org.tessellation.schema.transaction.{Transaction, TransactionAmount, TransactionFee, TransactionOrdinal, TransactionReference, TransactionSalt}
import org.tessellation.security.hash.Hash

object RosettaOperationUtilities {

  def operationsToDAGTransaction(
                                  src: String,
                                  fee: Long,
                                  operations: List[Operation],
                                  parentHash: String,
                                  parentOrdinal: Long,
                                  salt: Option[Long]
                                ): Either[String, Transaction] = {
    // TODO: Same question here as below, one or two operations?
    val operationPositive = operations.filter(_.amount.exists(_.value.toLong > 0)).head
    val amount = operationPositive.amount.get.value.toLong
    val destination = operationPositive.account.get.address
    // TODO: Validate at higher level
    //import DAGAddressRefined._
    /*
    // e1
    .left.map{map the error into a response code type}
    .map{
    e2.map{
    e3.msp{
    e4.map{
    => result_Type(e1a,e2,e3a,0

    }.merge
    .merge
    .merge

     */
    for {
      // Option[String] if the result is None, it won't execute the later maps.
      srcA <- refineV[DAGAddressRefined](src) // Either[String error, address thats been validated]
      destinationA <- refineV[DAGAddressRefined](destination)
      amountA <- PosLong.from(amount) // Either[Error, Long validated amount]
      feeA <- NonNegLong.from(fee)
      parentOrdinalA <- NonNegLong.from(parentOrdinal)
    } yield {
      Transaction(
        Address(srcA),
        Address(destinationA),
        TransactionAmount(amountA),
        TransactionFee(feeA),
        TransactionReference(TransactionOrdinal(parentOrdinalA), Hash(parentHash)),
        TransactionSalt(salt.getOrElse(0L))
      )
    }
  }

}
