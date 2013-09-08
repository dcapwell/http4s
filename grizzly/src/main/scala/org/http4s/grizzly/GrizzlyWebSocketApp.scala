package org.http4s.grizzly

import org.glassfish.grizzly.http.HttpRequestPacket
import org.glassfish.grizzly.websockets._
import concurrent.{Await, ExecutionContext, Future}
import concurrent.duration._
import play.api.libs.iteratee.{Input, Iteratee, Enumerator}

import org.http4s.websocket._
import org.http4s._
import java.net.{InetAddress,URI}
import websocket.ByteMessage
import websocket.StringMessage
import org.http4s.RequestPrelude
import akka.util.ByteString
import org.glassfish.grizzly.http.server.Request

/**
 * @author Bryce Anderson
 * Created on 2/17/13 at 4:29 PM
 */


class GrizzlyWebSocketApp(context: String, address: String, port: Int, route: Route)
     (implicit ctx: ExecutionContext = ExecutionContext.global)
  extends WebSocketApplication {

  def isApplicationRequest(request: HttpRequestPacket) = route.lift(toRequest(request)).fold(false){ it =>
    Await.result(Enumerator.eof.run(it), 1 second) match {
      case responder: SocketResponder =>
        // Store the socket route in the request so we can pick it up on the createSocket method
        request.setAttribute("WebSocketRoute" , responder)
        true
      case _ => false
    }
  }

  override def createSocket(handler: ProtocolHandler,request: HttpRequestPacket,listeners: WebSocketListener*) = {

    val (_it,enum) = (request.getAttribute("WebSocketRoute").asInstanceOf[SocketResponder]).socket()

    var it: Future[Iteratee[WebMessage,_]] = Future.successful(_it)

    def feedSocket(in: Input[WebMessage]) = synchronized(it = it.flatMap(_.feed(in)))

    val socket = new DefaultWebSocket(handler,request,listeners:_*) {
      override def onMessage(str: String) = {
        feedSocket(Input.El(StringMessage(str)))
      }

      override def onMessage(data: Array[Byte]) = {
        feedSocket(Input.El(ByteMessage(ByteString(data))))
      }

      override def onClose(frame: DataFrame) = {
        feedSocket(Input.EOF)
        super.onClose(frame)
      }
    }

    enum.run(Iteratee.foreach[WebMessage]{
        case StringMessage(str) => socket.send(str)
        case ByteMessage(data)  => socket.send(data.toArray)
      })

    socket
  }

  protected def toRequest(req: HttpRequestPacket): RequestPrelude = {
    val uri = URI.create(buildRequestURL(req) + "?" + Option(req.getQueryString).getOrElse(""))
    RequestPrelude(
      requestMethod = Method(req.getMethod.toString),
      pathInfo = req.getRequestURI,
      queryString = Option(req.getQueryString).getOrElse(""),
      protocol = ServerProtocol(req.getProtocol.getProtocolString),
      headers = toHeaders(req),
      serverPort = req.getServerPort,
      serverSoftware = ServerSoftware(req.serverName().toString()),
      remote = InetAddress.getByName(req.getRemoteAddress) // TODO using remoteName would trigger a lookup
    )
  }

  // This is needed to build the request URL form the HttpRequestPacket.
  protected def buildRequestURL(req: HttpRequestPacket): String = {
    val sb = new StringBuilder
    val scheme = if(req.isSecure) "https" else "http"
    sb.append(scheme)
    sb.append("://")
    sb.append(address)
    if ((scheme.equals("http") && (port != 80))
      || (scheme.equals("https") && (port != 443))) {
      sb.append(':')
      sb.append(port)
    }
    sb.append(req.getRequestURI())
    return sb.result
  }

  def toHeaders(req: HttpRequestPacket): HttpHeaders = {
    import scala.collection.JavaConverters._

    val headers = for {
      name <- req.getHeaders.names.asScala :Iterable[String]
    } yield HttpHeaders.RawHeader(name, req.getHeader(name))
    HttpHeaders(headers.toSeq : _*)
  }
}
