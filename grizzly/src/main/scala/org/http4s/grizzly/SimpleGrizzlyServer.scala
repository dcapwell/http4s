package org.http4s
package grizzly

import org.glassfish.grizzly.http.server.{NetworkListener, HttpServer}
import org.glassfish.grizzly.threadpool.ThreadPoolConfig
import concurrent.ExecutionContext
import org.glassfish.grizzly.websockets.{WebSocketEngine, WebSocketAddOn}

/**
 * @author Bryce Anderson
 * Created on 2/10/13 at 3:06 PM
 */

object SimpleGrizzlyServer {
//<<<<<<< HEAD
  def apply(port: Int = 8080, address: String ="0.0.0.0", serverRoot:String = "/*", chunkSize: Int = 32 * 1024)(route: Route)(implicit executionContext: ExecutionContext = concurrent.ExecutionContext.fromExecutorService(java.util.concurrent.Executors.newCachedThreadPool())) =
  new SimpleGrizzlyServer(port = port, address = address, serverRoot = serverRoot, chunkSize = chunkSize)(Seq(route))
//=======
//  def apply(port: Int = 8080, serverRoot:String = "/*")(route: Route)
//           (implicit executionContext: ExecutionContext = concurrent.ExecutionContext.fromExecutorService(java.util.concurrent.Executors.newCachedThreadPool())) =
//  new SimpleGrizzlyServer(port = port, serverRoot = serverRoot)(Seq(route))
//>>>>>>> wip/grizwebsocket
}

class SimpleGrizzlyServer(port: Int=8080,
                          address: String = "0.0.0.0",
                          serverRoot:String = "/*",
                          serverName:String="simple-grizzly-server",
                          chunkSize: Int = 32 * 1024,
                          corePoolSize:Int = 10,
//<<<<<<< HEAD
                          maxPoolSize:Int = 20,
                          maxReqHeaders: Int = -1,
                           maxHeaderSize: Int = -1)(routes:Seq[Route])(implicit executionContext: ExecutionContext = ExecutionContext.global)
{
  val http4sServlet = new Http4sGrizzly(routes reduce (_ orElse _), chunkSize)(executionContext)
  val httpServer = new HttpServer
  val networkListener = new NetworkListener(serverName, address, port)
  // For preventing DoS attacks
  if (maxHeaderSize > 0) networkListener.setMaxHttpHeaderSize(maxHeaderSize)
  if (maxReqHeaders > 0) networkListener.setMaxRequestHeaders(maxReqHeaders)
//=======
//                          maxPoolSize:Int = 20)
//                         (routes:Seq[Route])
//                         (implicit executionContext: ExecutionContext = ExecutionContext.global)
//{
//  private[grizzly] val httpServer = new HttpServer
//  private[grizzly] val networkListener = new NetworkListener(serverName, address, port)
//>>>>>>> wip/grizwebsocket

  private[this] val route = routes reduce (_ orElse _)

  private[this] val threadPoolConfig = ThreadPoolConfig
    .defaultConfig()
    .setCorePoolSize(corePoolSize)
    .setMaxPoolSize(maxPoolSize)

  networkListener.getTransport().setWorkerThreadPoolConfig(threadPoolConfig)

  // Add websocket support
  val grizWebSocketApp = new GrizzlyWebSocketApp(serverRoot.substring(0, serverRoot.length-1), address, port, route)

  networkListener.registerAddOn(new WebSocketAddOn())
  WebSocketEngine.getEngine().register(grizWebSocketApp)


  httpServer.addListener(networkListener)

  private[this] val http4sGrizzlyServlet = new Http4sGrizzly(route)(executionContext)
  httpServer.getServerConfiguration().addHttpHandler(http4sGrizzlyServlet,serverRoot)



  try {
    httpServer.start()
    Thread.currentThread().join()

  } catch  {
    case e: Throwable => println(e)
  }
}
