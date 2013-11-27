package org.http4s

import org.scalatest.{OptionValues, Matchers, WordSpec}
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

/**
 * @author Bryce Anderson
 *         Created on 11/26/13
 */



class SpoolSpec extends WordSpec with Matchers with OptionValues {

  import scala.concurrent.ExecutionContext.Implicits.global

  def await[T](f: Future[T]) = Await.result(f, 2.seconds)

  val data = Spool(0 until 10: _*)

  "A Spool" should {
    "Give a head" in {
      val a = Spool.cons(4)

      a.head should equal(4)
    }

    "Map and fold properly" in {
      await(data.map(i => i+1).fold(0)(_ + _)) should equal((0 until 10).map(_ + 1).fold(0)(_ + _))
    }
  }
}
