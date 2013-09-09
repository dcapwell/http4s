package org.http4s.examples.netty

import org.http4s.netty.SimpleNettyServer
import play.api.libs.iteratee.{Done, Iteratee, Concurrent}
import org.http4s.WebSocket
import org.http4s.websocket.{ByteMessage, StringMessage, WebMessage}
import org.http4s.Status.Ok

/**
 * @author Bryce Anderson
 *         Created on 9/8/13
 */
object Netty4WebsocketExample extends App {

  val server = SimpleNettyServer(){

    case req if req.pathInfo == "/websocket" =>
      WebSocket {
        var chan: Concurrent.Channel[WebMessage] = null
        val enum = Concurrent.unicast[WebMessage](chan=_)
        val it = Iteratee.foreach[WebMessage]{
          case StringMessage(s) =>
            val str = s"Received string: $s"
            println(str)
            chan.push(StringMessage(str))

          case ByteMessage(_) => sys.error("Received ByteMessage... That is Unexpected!")

        }.map(_ => println("Connection Closed! This is from the App..."))

        (it,enum)
      }

    case req => Done(Ok("Hello world"))  // Define a route
  }

  server.run()

  println("Hello world!")


}
