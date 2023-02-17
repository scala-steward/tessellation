package org.tessellation.dag.l1.domain.consensus.block

import scala.reflect.runtime.universe._
import scala.util.Try

import org.tessellation.dag.domain.block.Block
import org.tessellation.dag.l1.domain.consensus.round.RoundId
import org.tessellation.kernel.Ω
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.Hashed

sealed trait BlockConsensusOutput extends Ω

object BlockConsensusOutput {
  case class FinalBlock[A <: Transaction: TypeTag, B <: Block[A]: TypeTag](hashedBlock: Hashed[B])
      extends TypeTagged[FinalBlock[A, B]]
      with BlockConsensusOutput
  object FinalBlock {
    def extractor[A <: Transaction: TypeTag, B <: Block[A]: TypeTag] = new TypeTaggedExtractor[FinalBlock[A, B]]
  }
  case class CleanedConsensuses(ids: Set[RoundId]) extends BlockConsensusOutput
}

trait TypeTaggedTrait[Self] { self: Self =>
  // val selfTypeTag: TypeTag[Self]
  val selfTypeTag: String

  def hasType[Other: TypeTag]: Boolean =
    // typeOf[Other] =:= selfTypeTag.tpe
    typeOf[Other].toString == selfTypeTag

  def cast[Other: TypeTag]: Option[Other] =
    if (hasType[Other])
      // Some(this.asInstanceOf[Other])
      Try(this.asInstanceOf[Other]).toOption
    else
      None
}

abstract class TypeTagged[Self: TypeTag] extends TypeTaggedTrait[Self] { self: Self =>
  // val selfTypeTag: TypeTag[Self] = typeTag[Self]
  val selfTypeTag: String = typeTag[Self].tpe.toString
}

class TypeTaggedExtractor[T: TypeTag] {
  def unapply(a: Any): Option[T] = a match {
    case t: TypeTaggedTrait[_] => t.cast[T]
    case _                     => None
  }
}
