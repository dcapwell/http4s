package org.http4s.util
package middleware

import org.http4s._
import scala.concurrent.Future
import org.http4s.RequestPrelude
import scala.Some

/**
* @author Bryce Anderson
*         Created on 3/9/13 at 10:43 AM
*/

object URITranslation {

  def TranslateRoot(prefix: String)(in: Route): Route = {
    val newPrefix = if (!prefix.startsWith("/")) "/" + prefix else prefix
    new Route {
      private def stripPath(req: RequestPrelude): Option[RequestPrelude] = {
        if (req.pathInfo.startsWith(newPrefix)) Some(req.copy(pathInfo = req.pathInfo.substring(newPrefix.length)))
        else None
      }

      def apply(request: (Body, RequestPrelude)): Future[Response] = {
        in((request._1, stripPath(request._2).getOrElse(throw new MatchError(s"Missing Context: '$newPrefix'"))))
      }

      def isDefinedAt(x: (Body, RequestPrelude)): Boolean = stripPath(x._2) match {
        case Some(req) => in.isDefinedAt((x._1, req))
        case None => false
      }
    }
  }

  def TranslatePath(trans: String => String)(in: Route): Route = new Route {
    def apply(req: (Body, RequestPrelude)): Future[Response] =
        in((req._1, req._2.copy(pathInfo = trans(req._2.pathInfo))))

    def isDefinedAt(req: (Body, RequestPrelude)): Boolean =
      in.isDefinedAt((req._1, req._2.copy(pathInfo = trans(req._2.pathInfo))))
  }

}
