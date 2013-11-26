package org.http4s

import scala.language.reflectiveCalls

import concurrent.{Await, ExecutionContext, Future}
import concurrent.duration._
import play.api.libs.iteratee.{Enumerator, Iteratee}

class MockServer(route: Route)(implicit executor: ExecutionContext = ExecutionContext.global) {
  import MockServer.MockResponse

  def apply(req: RequestPrelude, body: Body): Future[MockResponse] = {
    try {
      route.lift((body,req)).fold(Future.successful(onNotFound))(_.flatMap { resp: Response =>
        val status = resp.status
        val prelude = resp.prelude
        val body = resp.body
        val attributes: AttributeMap = resp.attributes
        resp.body.flatMap{ spool =>
         spool.fold(BodyChunk())((c1: BodyChunk, c2: Chunk) => c2 match {
           case c: BodyChunk => c1 ++ c
           case e => c1
         }).map(c => MockResponse(status, prelude.headers, c.toArray, attributes))
        }
      })
    } catch {
      case t: Throwable => Future.successful(onError(t))
    }
  }

  def response(req: RequestPrelude,
               body: Body = Response.EmptyBody,
               wait: Duration = 5.seconds): MockResponse = {
    Await.result(apply(req, body), 5.seconds)
  }

  def onNotFound: MockResponse = MockResponse(statusLine = Status.NotFound)

  def onError: PartialFunction[Throwable, MockResponse] = {
    case e: Exception =>
      e.printStackTrace()
      MockResponse(statusLine = Status.InternalServerError)
  }
}

object MockServer {
  private[MockServer] val emptyBody = Array.empty[Byte]   // Makes direct Response comparison possible

  case class MockResponse(
    statusLine: Status = Status.Ok,
    headers: HeaderCollection = HeaderCollection.empty,
    body: Array[Byte] = emptyBody,
    attributes: AttributeMap = AttributeMap.empty
  )
}
