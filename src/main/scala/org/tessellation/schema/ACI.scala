package org.tessellation.schema

import higherkindness.droste.{Algebra, Coalgebra}

trait AciF[A]

case class Return[A](x: Int) extends AciF[A]
case class Execute[A](command: Command[A, Int, Int]) extends AciF[A]

trait Command[A, I, O] {
  def run(): O
}

case class ApiCall[A](request: Request[Int]) extends Command[A, Int, Int] {
  def run() = request.input // todo: make ApiCall a trait
}
case class Request[I](input: I)
case class Response[O](output: O)

object AciF {

  val algebra: Algebra[AciF, Int] = Algebra {
    case Return(x) => x
    case Execute(cmd@ApiCall(_)) => cmd.run()
  }

  val coalgebra: Coalgebra[AciF, Int] = Coalgebra {
    n => if (n <= 0) Return(n) else Execute(ApiCall(Request(n)))
  }

}
