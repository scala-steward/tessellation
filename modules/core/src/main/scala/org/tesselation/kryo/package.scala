package org.tesselation

import org.tesselation.schema.kryo.schemaKryoRegistrar

import org.tessellation.csv.CSVTypes.{StateChannelData, StateChannelSignature}

package object kryo {

  val coreKryoRegistrar: Map[Class[_], Int] = (Map(
    classOf[StateChannelData] -> 323,
    classOf[StateChannelSignature] -> 324
  ) ++ schemaKryoRegistrar).toMap

}
