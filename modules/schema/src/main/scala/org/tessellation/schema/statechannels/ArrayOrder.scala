package org.tessellation.schema.statechannels

import cats.kernel.Order

object ArrayOrder {
  implicit def arrayOrder[A: Order]: Order[Array[A]] = Order.by(_.toSeq)
}
