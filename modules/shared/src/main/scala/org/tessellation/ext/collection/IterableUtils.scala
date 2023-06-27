package org.tessellation.ext.collection

object IterableUtils {
  def pickMajority[A](as: Iterable[A]): Option[A] =
    as.groupMapReduce(identity)(_ => 1)(_ + _).maxByOption(_._2).map(_._1)
}
