package scalikejdbc

import cats.effect.IO
import org.mockito.Mockito.{ mock, when }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ FlatSpec, Matchers }
import scalikejdbc.TxBoundary.IO.ioTxBoundary

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class IOTxBoundarySpec extends FlatSpec with Matchers with ScalaFutures {

  behavior of "closeConnection()"

  sealed abstract class MyIO[+A] {
    import MyIO._

    def flatMap[B](f: A => MyIO[B]): MyIO[B] = {
      this match {
        case Delay(thunk) => Delay(() => f(thunk()).run)
      }
    }

    def map[B](f: A => B): MyIO[B] = flatMap(x => MyIO(f(x)))

    def run: A = {
      this match {
        case Delay(f) => f.apply()
      }
    }

    def attempt: MyIO[Either[Throwable, A]] =
      MyIO(try {
        Right(run)
      } catch {
        case scala.util.control.NonFatal(t) => Left(t)
      })

  }

  object MyIO {
    def apply[A](test: => A): MyIO[A] = Delay(test _)

    final case class Delay[+A](thunk: () => A) extends MyIO[A]
  }

  implicit def myIOTxBoundary[A]: TxBoundary[MyIO[A]] = new TxBoundary[MyIO[A]] {

    def finishTx(result: MyIO[A], tx: Tx): MyIO[A] = {
      result.attempt.flatMap {
        case Right(x) => MyIO(tx.commit()).flatMap(_ => result)
        case e => MyIO(tx.rollback()).flatMap(_ => result)
      }
    }

    override def closeConnection(result: MyIO[A], doClose: () => Unit): MyIO[A] = {
      for {
        x <- result
        _ <- MyIO(doClose).map(x => x.apply())
      } yield x
    }
  }

  it should "returns Failure when onClose() throws an exception" in {
    val exception = new RuntimeException
    val result = ioTxBoundary[Int].closeConnection(IO(1), () => throw exception)
    result.attempt.unsafeRunSync() should be(Left(exception))
  }

  it should "returns Failure when onClose() throws an exception 2" in {
    val exception = new RuntimeException
    val result = myIOTxBoundary[Int].closeConnection(MyIO(1), () => throw exception)
    result.attempt.run should be(Left(exception))
  }
}
