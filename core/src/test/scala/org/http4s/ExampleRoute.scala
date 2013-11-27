package org.http4s

import scala.language.reflectiveCalls
import concurrent.{Future, ExecutionContext}
import play.api.libs.iteratee._
import akka.util.ByteString

object ExampleRoute {
  import Status._
  import Writable._
  import BodyParser._

  import scala.concurrent.ExecutionContext.Implicits.global

  val MyVar = AttributeKey[String]("myVar")

  /*
   * We can't see the dsl package from core.  This is an ad hoc thing
   * to make this test a little easier to write.
   */
  object Req {
    def unapply(req: RequestPrelude): Option[(Method, String)] = Some(req.requestMethod, req.pathInfo)
  }

  import Method._

//  import PushSupport._

  def apply(implicit executor: ExecutionContext = ExecutionContext.global): Route = {

//    case Req(Get, "/push") =>
//      Ok("Pushing: ").push("/pushed")
//
//    case Req(Get, "/pushed") =>
//      Ok("Pushed").push("/ping")

    case (_, Req(Get, "/ping")) =>
      Ok("pong")

    case (body, Req(Post, "/echo")) =>
      Response(ResponsePrelude(Status.Ok), body)

//    case (_, Req(Get, "/echo2")) =>
//      Ok(Enumeratee.map[Chunk]{
//        case BodyChunk(e) => BodyChunk(e.slice(6, e.length)): Chunk
//        case chunk => chunk
//      })

    case (body, req @ Req(Post, "/sum"))  =>
      text(req.charset, 16)(body){ s =>
        val sum = s.split('\n').map(_.toInt).sum
        Ok(sum)
      }

//    case (body, Req(Post, "/trailer")) =>
//      trailer(body)(t => t.fold(BadRequest())(t => Ok(t.headers.length)))
//
//    case (body, req @ Req(Post, "/body-and-trailer")) =>
//      for {
//        body <- text(req.charset)
//        trailer <- trailer
//      } yield trailer.headers.find(_.name == "hi".ci).fold(InternalServerError()){ hi =>  Ok(s"$body\n${hi.value}") }
//
//    case (_, req @ Req(Get, "/stream")) =>
//      Ok(Concurrent.unicast[ByteString]({
//        channel =>
//          for (i <- 1 to 10) {
//            channel.push(ByteString("%d\n".format(i), req.charset.name))
//            Thread.sleep(1000)
//          }
//          channel.eofAndEnd()
//      }))

    case (_, Req(Get, "/bigstring")) =>
      val builder = new StringBuilder(20*1028)
      Ok((0 until 1000) map { i => s"This is string number $i" })

    case (_, Req(Get, "/future")) =>
      Ok(Future("Hello from the future!"))

      // Ross wins the challenge
//    case (_, req @ Req(Get, "/challenge")) =>
//      Iteratee.head[Chunk].map {
//        case Some(bits: BodyChunk) if (bits.decodeString(req.charset)).startsWith("Go") =>
//          Ok(Enumeratee.heading(Enumerator(bits: Chunk)))
//        case Some(bits: BodyChunk) if (bits.decodeString(req.charset)).startsWith("NoGo") =>
//          BadRequest("Booo!")
//        case _ =>
//          BadRequest("No data!")
//      }

    case (body, req @ Req(Get, "/root-element-name")) =>
      xml(req.charset)(body){ elem =>
        Ok(elem.label)
      }

    case (_, Req(Get, "/html")) =>
      Ok(
        <html><body>
          <div id="main">
            <h2>Hello world!</h2><br/>
            <h1>This is H1</h1>
          </div>
        </body></html>
      )

    case (_, Req(Get, "/fail")) =>
      sys.error("FAIL")
  }
}