package org.http4s
package netty
package handlers

import io.netty.channel.{ChannelInboundHandlerAdapter, ChannelFutureListener, ChannelHandlerContext}
import org.http4s._

import io.netty.handler.codec.http
import io.netty.handler.codec.http.websocketx._
import org.http4s.netty.{SocketEnum}
import play.api.libs.iteratee._
import org.http4s.websocket.WebMessage
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.util.{CharsetUtil, ReferenceCountUtil}
import scala.util.control.Exception._
import org.http4s.websocket.StringMessage
import org.http4s.websocket.ByteMessage
import org.http4s.TrailerChunk
import com.typesafe.scalalogging.slf4j.Logging
import akka.util.ByteString
import scala.concurrent.ExecutionContext

/**
 * @author Bryce Anderson
 *         Created on 9/9/13
 */
class WebsocketHandler(nettyHandler: Http4sNetty)(implicit ec: ExecutionContext)
                  extends ChannelInboundHandlerAdapter with Logging {

  private var enum: SocketEnum = null

  // Just a front method to forward the request and finally release the buffer
  override def channelRead(ctx: ChannelHandlerContext, msg: Object) {
    try msg match {
      case frame: WebSocketFrame =>
        assert(enum != null)
        handleSocketFrame(ctx, frame)

      case msg =>
        ReferenceCountUtil.retain(msg)    // We will be releasing this ref, so need to inc to keep consistent
        ctx.fireChannelRead(msg)

    } finally {
      ReferenceCountUtil.release(msg)
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    logger.trace("Caught exception: %s", cause.getStackTraceString)
    // TODO: Proper exception handling for websockets
    printf("Caught exception: %s\n", cause.getStackTraceString)
    try {
      if (ctx.channel.isOpen) {
        val msg = (cause.getMessage + "\n" + cause.getStackTraceString).getBytes(CharsetUtil.UTF_8)
        val buff = Unpooled.wrappedBuffer(msg)
        val resp = new http.DefaultFullHttpResponse(http.HttpVersion.HTTP_1_0, http.HttpResponseStatus.INTERNAL_SERVER_ERROR, buff)
        ctx.channel.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE)
      }
    } catch {
      case t: Throwable =>
        logger.error(t.getMessage, t)
        allCatch(ctx.close())
    }
  }

  def startWebSocket(ctx: ChannelHandlerContext, req: http.HttpRequest, responder: SocketResponder): Iteratee[HttpChunk, Unit] = {
    // Accumulate the rest of the chunks
    val buff = ctx.alloc().buffer()
    def folder(input: Input[HttpChunk]): Iteratee[HttpChunk, ByteBuf] = input match {
      case Input.El(BodyChunk(bytes)) => buff.writeBytes(bytes.toArray); Cont(folder)
      case Input.El(TrailerChunk(_)) => sys.error("Cannot accept trailers when staring a WebSocket.")  // TODO: is this correct exception behavior?
      case Input.Empty => Cont(folder)
      case Input.EOF => Done(buff)
    }

    // Accumulate the data, then turn it into a FullHttpRequest for the WebSocketServerHandshaker
    Cont(folder).map { buff =>
      val fullReq = new DefaultFullHttpRequest(req.getProtocolVersion, req.getMethod, req.getUri, buff)
      fullReq.headers.set(req.headers)
      socketHandshake(ctx, fullReq, responder)
    }
  }


  private def handleSocketFrame(ctx: ChannelHandlerContext, frame: WebSocketFrame): Unit = frame match {
    case frame: TextWebSocketFrame => enum.push(StringMessage(frame.text()))

    case frame: BinaryWebSocketFrame =>
      enum.push(ByteMessage(ByteString(frame.content.nioBuffer())))

    case frame: CloseWebSocketFrame =>
      enum.handshaker.close(ctx.channel(), (frame.retain()))


    case frame: PingWebSocketFrame =>
      ctx.channel().write(new PongWebSocketFrame(frame.content().retain()))
  }

  private def socketHandshake(ctx: ChannelHandlerContext, req: http.FullHttpRequest, responder: SocketResponder) {

    val wsPath = "ws://" + req.headers().get(http.HttpHeaders.Names.HOST) + req.getUri
    val wsFactory = new WebSocketServerHandshakerFactory(wsPath, null, false)

    val handshaker = wsFactory.newHandshaker(req)
    if (handshaker == null) {  // Failure
      logger.trace("Websocket failed: %s", wsPath)
      WebSocketServerHandshakerFactory.sendUnsupportedWebSocketVersionResponse(ctx.channel())
      // TODO: what to do on a failure? Right now it we just send bad version, and let it run again.
    } else {
      logger.trace("Starting websocket: %s", wsPath)
      initializeSocket(ctx, handshaker, req, responder)
    }
  }

  def initializeSocket(ctx: ChannelHandlerContext, handshaker: WebSocketServerHandshaker, req: http.FullHttpRequest, responder: SocketResponder) {
    // Refactor the pipeline
    ctx.pipeline().replace(nettyHandler, "nettyWebSocketHandler", this)
    handshaker.handshake(ctx.channel(), req)

    // Connect the pipes and we are off.
    enum = new SocketEnum(handshaker)
    val (it, e) = responder.socket()
    enum.apply(it)
    e.run(Cont(socketMessageFolder(ctx)))
  }

  def socketMessageFolder(ctx: ChannelHandlerContext)(input: Input[WebMessage]): Iteratee[WebMessage, Unit] = if (ctx.channel.isOpen) input match {
    case Input.El(ByteMessage(bytes)) =>
      ctx.channel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(bytes.toArray)))
      Cont(socketMessageFolder(ctx))

    case Input.El(StringMessage(str)) =>
      ctx.channel.writeAndFlush(new TextWebSocketFrame(str))
      Cont(socketMessageFolder(ctx))

    case Input.Empty =>
      Cont(socketMessageFolder(ctx))

    case Input.EOF =>
      enum.handshaker.close(ctx.channel, new CloseWebSocketFrame())
        .onComplete(_ => enum.close())
      Done(())

  } else Done(())
}
