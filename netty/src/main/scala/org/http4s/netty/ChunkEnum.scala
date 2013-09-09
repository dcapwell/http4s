package org.http4s.netty

import play.api.libs.iteratee._
import org.http4s.HttpChunk
import scala.concurrent.{ExecutionContext, Promise, Future}
import scala.util.{Failure, Success}
import org.http4s.websocket.WebMessage
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker

/**
 * @author Bryce Anderson
 *         Created on 9/1/13
 */

class ChunkEnum(implicit ec: ExecutionContext) extends PushEnum[HttpChunk]

class SocketEnum(val handshaker: WebSocketServerHandshaker)(implicit ec: ExecutionContext) extends PushEnum[WebMessage]

sealed abstract class PushEnum[T](implicit ec: ExecutionContext) extends Enumerator[T] {
  private var i: Future[Iteratee[T, _]] = null
  private val p = Promise[Iteratee[T, _]]

  def apply[A](i: Iteratee[T, A]): Future[Iteratee[T, A]] = {
    this.i = Future.successful(i)
    p.future.asInstanceOf[Future[Iteratee[T, A]]]
  }

  def push(chunk: T) {
    assert(i != null)
    i = i.flatMap(_.pureFold {
      case Step.Cont(f) =>
        f(Input.El(chunk))

        // Complete the future with these.
      case Step.Done(a, r) =>
        val i = Done(a, r)
        p.trySuccess(i)
        i

      case Step.Error(e, a) =>
        val i = Error(e, a)
        p.trySuccess(i)
        i
    })
  }

  def close() {
    assert(i != null)
    i.onComplete{
      case Success(it) => p.completeWith(it.feed(Input.EOF))
      case Failure(t) => sys.error("Failed to finish the set.")
    }
  }

  def abort(t: Throwable) {
    p.failure(t)
    i = null
  }
}