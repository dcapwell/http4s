package org.http4s

import scala.language.reflectiveCalls
import concurrent.{Future, ExecutionContext}
import play.api.libs.iteratee._
import akka.util.ByteString
import org.http4s.dsl._

object ExampleRoute {
  import Status._
  import Writable._
  import BodyParser._


  val flatBigString = (0 until 1000).map{ i => s"This is string number $i" }.foldLeft(""){_ + _}

  val MyVar = AttributeKey[Int]("myVar")

  def apply(implicit executor: ExecutionContext = ExecutionContext.global): Route = {
    case (_, Get -> Root / "ping") =>
      Ok("pong")

    case (body, Post -> Root / "echo")  =>
      Response(ResponsePrelude(Status.Ok), body)

//    case Get -> Root / "echo" =>
//      Done(Ok(Enumeratee.map[Chunk] {
//        case BodyChunk(e) => BodyChunk(e.slice(6, e.length)): Chunk
//        case chunk => chunk
//      }))
//
//    case Get -> Root / "echo2" =>
//      Done(Ok(Enumeratee.map[Chunk] {
//        case BodyChunk(e) => BodyChunk(e.slice(6, e.length)): Chunk
//        case chunk => chunk
//      }))
//
//    case req @ Post -> Root / "sum" =>
//      text(req.charset, 16) { s =>
//        val sum = s.split('\n').map(_.toInt).sum
//        Ok(sum)
//      }
//
//    case req @ Get -> Root / "attributes" =>
//      val req2 = req.updated(MyVar, 55)
//      Ok("Hello" + req(MyVar) +  " and " + req2(MyVar) + ".\n")
//
//    case Get -> Root / "html" =>
//      Ok(
//        <html><body>
//          <div id="main">
//            <h2>Hello world!</h2><br/>
//            <h1>This is H1</h1>
//          </div>
//        </body></html>
//      )
//
//    case req @ Get -> Root / "stream" =>
//      Ok(Concurrent.unicast[ByteString]({
//        channel =>
//          new Thread {
//            override def run() {
//              for (i <- 1 to 10) {
//                channel.push(ByteString("%d\n".format(i), req.charset.name))
//                Thread.sleep(1000)
//              }
//              channel.eofAndEnd()
//            }
//          }.start()
//
//      }))
//
//    case Get -> Root / "bigstring" =>
//      Done{
//        Ok((0 until 1000) map { i => s"This is string number $i" })
//      }
//
    case (_, Get -> Root / "future") =>
      Ok(Future("Hello from the future!"))

    case (_, req @ Get -> Root / "bigstring2") =>
      Ok((0 until 1000) map { i =>s"This is string number $i"})

    case (_, req @ Get -> Root / "bigstring3") =>
      Ok(flatBigString)
//
//    case Get -> Root / "contentChange" =>
//      Ok("<h2>This will have an html content type!</h2>", MediaType.`text/html`)
//
//      // Ross wins the challenge
//    case req @ Get -> Root / "challenge" =>
//      Iteratee.head[Chunk].map {
//        case Some(bits: BodyChunk) if (bits.decodeString(req.charset)).startsWith("Go") =>
//          Ok(Enumeratee.heading(Enumerator(bits: Chunk)))
//        case Some(bits: BodyChunk) if (bits.decodeString(req.charset)).startsWith("NoGo") =>
//          BadRequest("Booo!")
//        case _ =>
//          BadRequest("No data!")
//      }

    case (_, req @ Get -> Root / "fail") =>
      sys.error("FAIL")
  }
}