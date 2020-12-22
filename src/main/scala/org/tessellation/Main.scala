package org.tessellation

import cats.effect.{ExitCase, ExitCode, IOApp, Sync}
import org.tessellation.schema.{AciF, Cell, Cell2, Cocell, Context, Hom, Topos, Ω}
import org.tessellation.schema.Hom._
import fs2.{Pipe, Stream}
import cats.syntax.all._
import higherkindness.droste.{Algebra, CVAlgebra, Coalgebra, CoalgebraM, GAlgebra, GAlgebraM, GCoalgebra, Gather, RAlgebra, RCoalgebra, RCoalgebraM, Scatter, scheme}
import higherkindness.droste.data.{:<, Attr, Fix}
import org.tessellation.ConsensusExample.{intGather, intScatter}
import org.tessellation.StreamExample.pipeline
import org.tessellation.hypergraph.EdgePartitioner
import org.tessellation.hypergraph.EdgePartitioner.EdgePartitioner
import org.tessellation.serialization.{Kryo, KryoRegistrar, SerDe}

object Main extends App {
  println("Welcome to " |+| "Tessellation!")
  LiftExample
}

object LiftExample extends App {
  println("Lift " |+| "Example!")

  val intCell: Hom[Int, Int] = Cell[Int, Int](0)
  val intTopos: Hom[Int, Int] = Cell2[Int, Int](2, 3)
  val constellation = intCell >>> intTopos

  val intToposClone = Cell2[Int, Int](0, 1)
  val myClone = constellation >>> intToposClone
}

object RunExample extends App {

  println(AciF.coalgebra.apply(5))

  case class IntToStringCell(a: Int) extends Topos[Int, String] {

    def execute: () => String = {
      val cvAlgebra: CVAlgebra[Hom[Ω, *], Ω] = CVAlgebra {
        case cell@Cell(aa) => {
          println(aa)
          // cell.run(Context())
          cell
        }
      }
      val rcoalgebra: RCoalgebra[Ω, Hom[Ω, *], Ω] = RCoalgebra {
        case cell: Cell[Ω, Ω] => {
          println(cell.a)
          Cell2(cell.a, Left(cell.run(cell.a)))
        }
        case ohm: Ω => Cell[Ω, Either[Ω, Ω]](ohm)
      }
      val coalgebra: Coalgebra[Hom[Ω, *], Ω] = Coalgebra[Hom[Ω, *], Ω] { thing: Ω => {
        println("thing")
        Cell(thing)
      } }

      val gather = cvAlgebra.gather(Gather.histo)
      val scatter = rcoalgebra.scatter(Scatter.gapo(coalgebra))

      val run: Ω => Ω = scheme.ghylo(gather, scatter)

      println(run(this))

      () => a.toString
    }
  }

  val cell1 = IntToStringCell(1)

  println(cell1.execute)

//  def cellPipe[F[_]](cell: IntToStringCell): Pipe[F, Int, String] = IntToStringCell

//  Stream(1, 2, 3).through(cellPipe(cell1))
}

object StreamExample extends App {
  import fs2.Stream
  import cats.effect.IO

  def intCell(i: Int): Hom[Int, Int] = Cell[Int, Int](i)
  def chainEffects(operad: Hom[Int, Int])(i: Int): Hom[Int, Int] = operad >>> intCell(i)
  def pipeline(i: Int): Stream[Hom[Int, *], Int] = Stream.eval[Hom[Int, *], Int] (chainEffects(Context())(i))

  val dummyStream = Stream(1,2,3)
  val effectfulStream = dummyStream.flatMap(pipeline)
}

import higherkindness.droste.data.{:<, Coattr}
import higherkindness.droste.syntax.compose._
object ConsensusExample extends App {
  import fs2.Stream
  import cats.effect.IO

  val submitResult: RCoalgebra[Int, Hom[Int, *], Int] = RCoalgebra {
    case int: Int => {
      println("sr", int)
      Cell2(int, Left(2))
    }
  }

  val cellCoalgebra: Coalgebra[Hom[Int, *], Int] = Coalgebra[Hom[Int, *], Int] { thing: Int => {
    println("cc", thing)
    Cell(thing)
  } }

  val fromCellHelper: Algebra[Hom[Int, *], Int] = Algebra {
    case Cell(n) => {
      println(n)
      n + 1
    }
    case Cell2(a, b)    => {
      println(a, b)
      a + b
    }
  }

  // note: n is the fromCellHelper helper value from the previous level of recursion
  val proposalRingAlgebra: RAlgebra[Int, Hom[Int, *], Int] = RAlgebra {
    case Cell2(a, (n, value)) => {
      println(a, value, n)
      value + (n + 1) * (n + 1)
    }
    case Cell(a) => {
      println(a)
      a
    }
  }

  val combineProposals: CVAlgebra[Hom[Int, *], Int] = CVAlgebra {
    case Cell2(a, r1 :< Cell(r2)) =>{
      println("cp cell2", a, r1, r2)
      a + r2
    }
    case cell@Cell(aa) => {
      println("cp cell@Cell", aa)
      aa
    }
  }

  val intGather: GAlgebra.Gathered[Hom[Int, *], Attr[Hom[Int, *], Int], Int] = combineProposals.gather(Gather.histo)


  val intScatter: GCoalgebra.Scattered[Hom[Int, *], Int, Either[Int, Int]] = submitResult.scatter(Scatter.gapo(cellCoalgebra))


  val takeHighestIntegerConsensus: Int => Int = scheme.ghylo(
    intGather,
    intScatter)
  //todo note we can just use Cell istead of new class I just ran out of time to make new constructor

  case class MyNewCell[A, B](override val a: A) extends Topos[A, B] {
    val takeHighestIntegerConsensus: Int => Int = scheme.ghylo(
      intGather,
      intScatter)
  }

  def intCell(i: Int): Hom[Int, Int] = MyNewCell[Int, Int](i)
  def chainEffects(operad: Hom[Int, Int])(i: Int): Hom[Int, Int] = operad >>> intCell(i)
  def pipeline(i: Int): Stream[Hom[Int, *], Int] = Stream.eval[Hom[Int, *], Int] (chainEffects(Context())(i))

  val dummyStream = Stream(1,2,3)
  val effectfulStream = dummyStream.flatMap(pipeline)

//  effectfulStream.compile
}

object ConsensusExample2 extends App {
  val natCoalgebra: Coalgebra[Option, BigDecimal] =
    Coalgebra(n => if (n > 0) Some(n - 1) else None)

  val cellCoalgebra: Coalgebra[Hom[Int, *], Int] = Coalgebra { int =>
    Cell(int)
  }

  val submitResult: RCoalgebra[Int, Hom[Int, *], Int] = RCoalgebra {
    case int => Cell(int)
  }

  val combineProposals: CVAlgebra[Hom[Int, *], Int] = CVAlgebra {
    case Cell2(a, r1 :< Cell(r2)) => a + r2
    case cell@Cell(aa) => aa
  }

  val intGather = combineProposals.gather(Gather.histo)
  val intScatter = submitResult.scatter(Scatter.gapo(cellCoalgebra))

  println(scheme.ghylo(intGather, intScatter).apply(10))

  // ---

  val fibAlgebra: CVAlgebra[Option, BigDecimal] = CVAlgebra {
    case Some(r1 :< Some(r2 :< _)) => r1 + r2
    case Some(_ :< None) => 1
    case None => 0
  }

//  val fib: BigDecimal => BigDecimal = scheme.ghylo(
//    fibAlgebra.gather(Gather.histo),
//    natCoalgebra.scatter(Scatter.ana)
//  )
//
//  val fib10 = fib(10)

  // ---

  val fromNatAlgebra: Algebra[Option, BigDecimal] = Algebra {
    case Some(n) => n + 1
    case None => 0
  }

  val sumSquaresAlgebra: RAlgebra[BigDecimal, Option, BigDecimal] = RAlgebra {
    case Some((n, value)) => value + (n + 1) * (n + 1)
    case None => 0
  }

//  val sumSquares: BigDecimal => BigDecimal = scheme.ghylo(
//    sumSquaresAlgebra.gather(Gather.zygo(fromNatAlgebra)),
//    natCoalgebra.scatter(Scatter.ana))
//
//  val sumSquares10 = sumSquares(10)

  // ---

//  val fused: BigDecimal => (BigDecimal, BigDecimal) =
//    scheme.ghylo(
//      fibAlgebra.gather(Gather.histo) zip
//        sumSquaresAlgebra.gather(Gather.zygo(fromNatAlgebra)),
//      natCoalgebra.scatter(Scatter.ana))
//
//  val fused10 = fused(10)

}

/**
 * Serialization use-case example
 * Remember to remove registration entry in `KryoRegistrar` when removing this example
 */
object SerializationExample extends App {
  import cats.syntax.all._
  /**
   * Instantiate the de/serialization service.
   * Use `SerDe` as a dependency trait.
   */
  val registrar = new KryoRegistrar()
  val ser: SerDe = Kryo(registrar)

  def roundtrip[T <: Any](obj: T) = {
    val result = for {
      serialized <- ser.serialize(obj).leftMap(e => s"Serialization error: ${e.reason.getMessage}")
      _ = println(s"Serialized ${obj} is ${serialized}")
      deserialized <- ser.deserialize[T](serialized).leftMap(e => s"Deserialization error: ${e.reason.getMessage}")
      _ = println(s"Deserialized is ${deserialized}")
    } yield ()

    result match {
      case Left(a) => println(a)
      case _ => ()
    }
  }


  case class Lorem(a: String)

  roundtrip(2L)
  roundtrip(Lorem("ipsum"))

  case class UnknownLorem(a: Int)
  roundtrip(UnknownLorem(4))
}

object TryDoobie extends App {

  import doobie._
  import doobie.implicits._
  import cats._
  import cats.data._
  import cats.effect.IO
  import cats.syntax.all._

  implicit val cs = IO.contextShift(doobie.ExecutionContexts.synchronous)

  val xa = Transactor.fromDriverManager[IO](
    "org.sqlite.JDBC", "jdbc:sqlite:sample.db", "", ""
  )

  val y = xa.yolo
  import y._

  val drop =
    sql"""
    DROP TABLE IF EXISTS person
  """.update.run

  val create =
    sql"""
    CREATE TABLE person (
      name TEXT NOT NULL UNIQUE,
      age  INTEGER
    )
  """.update.run

  val res = (drop, create).mapN(_ + _).transact(xa).unsafeRunSync
  println(res)

  def insert1(name: String, age: Option[Short]): Update0 =
    sql"insert into person (name, age) values ($name, $age)".update

  insert1("Alice", Some(12)).run.transact(xa).unsafeRunSync
  insert1("Bob", None).quick.unsafeRunSync // switch to YOLO mode

  case class Person(id: Long, name: String, age: Option[Short])

  val l = sql"select rowid, name, age from person".query[Person].to[List].transact(xa).unsafeRunSync
  l.foreach(println)
}