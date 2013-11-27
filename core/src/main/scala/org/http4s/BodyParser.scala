package org.http4s

import scala.language.reflectiveCalls
import play.api.libs.iteratee._
import java.io._
import xml.{Elem, XML, NodeSeq}
import org.xml.sax.{SAXException, InputSource}
import javax.xml.parsers.SAXParser
import scala.util.{Failure, Success, Try}
import util.Execution.{overflowingExecutionContext => oec, trampoline => tec}
import scala.concurrent.{Promise, Future, ExecutionContext}
import scala.util.control.NonFatal
import akka.util.ByteString

case class BodyParser[A](parser: Spool[Chunk] => Future[Either[Response, A]]) {
  def apply(spool: Future[Spool[Chunk]])(f: A => Future[Response])(implicit ec: ExecutionContext): Future[Response] = {
    spool.flatMap(parser).flatMap(_.fold(Future.successful(_), f))
  }

  def map[B](f: A => B)(implicit ec: ExecutionContext): BodyParser[B] = {
    BodyParser(parser.andThen(_.map(_.right.map(f))))
  }

//  def flatMap[B](f: A => BodyParser[B])(implicit ec: ExecutionContext): BodyParser[B] =
//    BodyParser(it.flatMap[Either[Response, B]](_.fold(
//      { response: Response => Done(Left(response)) },
//      { a: A => f(a).it }
//    )))

  def joinRight[A1 >: A, B](implicit ev: A1 <:< Either[Response, B]): BodyParser[B] = {
    BodyParser(parser.andThen(f => f.map(_.joinRight)(oec)))
  }
}

object BodyParser {
  private implicit val ec = tec

  val DefaultMaxEntitySize = Http4sConfig.getInt("org.http4s.default-max-entity-size")

//  implicit def bodyParserToResponderIteratee(bodyParser: BodyParser[Response]): Iteratee[Chunk, Response] =
//    bodyParser(identity)

  def text[A](charset: CharacterSet, limit: Int = DefaultMaxEntitySize): BodyParser[String] = {
    def go(s: Spool[Chunk]): Future[Either[Response, String]] = {
      consumeUpTo(s, limit)
        .map(_.fold[Either[Response, String]](Left(Status.RequestEntityTooLarge()))(c => Right(c.decodeString(charset))))
    }
    BodyParser(go)
  }


  /**
   * Handles a request body as XML.
   *
   * TODO Not an ideal implementation.  Would be much better with an asynchronous XML parser, such as Aalto.
   * TODO how to pass the EC correctly?
   *
   * @param charset the charset of the input
   * @param limit the maximum size before an EntityTooLarge error is returned
   * @param parser the SAX parser to use to parse the XML
   * @return a request handler
   */
  def xml(charset: CharacterSet,
          limit: Int = DefaultMaxEntitySize,
          parser: SAXParser = XML.parser,
          onSaxException: SAXException => Response = { saxEx => /*saxEx.printStackTrace();*/ Status.BadRequest() })
  : BodyParser[Elem] = {
    implicit val ec = tec
    def go(s: Spool[Chunk]) = consumeUpTo(s, limit).map (_.fold[Either[Response,Elem]](Left(Status.RequestEntityTooLarge())){ bytes =>
      val in = bytes.iterator.asInputStream
      val source = new InputSource(in)
      source.setEncoding(charset.name)
      Try(XML.loadXML(source, parser)).map(Right(_)).recover {
        case e: SAXException => Left(onSaxException(e))
      }.get
    })

    BodyParser(go)
  }

  def trailer: BodyParser[Option[TrailerChunk]] = {
    def folder(s: Spool[Chunk]): Future[Either[Response, Option[TrailerChunk]]] = {
      val p = Promise[Either[Response, Option[TrailerChunk]]]
      def go(s: Spool[Chunk]): Unit = {
        if (s.isEmpty) p.success(Right(None))
        else {
          if (p.isInstanceOf[TrailerChunk]) p.success(Right(Some(p.asInstanceOf[TrailerChunk])))
          else s.tail.onComplete {
            case Success(s) => go(s)
            case Failure(t) => p.failure(t)
          }
        }

      }
      p.future
    }
    BodyParser(folder)
  }

  def consumeUpTo(spool: Spool[Chunk], limit: Int = DefaultMaxEntitySize): Future[Option[BodyChunk]] = {
    // Dont flatmap a million futures.
    val p = Promise[Option[BodyChunk]]
    def go(total: ByteString, s: Spool[Chunk], size: Int): Unit = {
      if (s.isEmpty || !s.head.isInstanceOf[BodyChunk]) p.success(Some(BodyChunk(total)))
      else {
        val c = s.head.asInstanceOf[BodyChunk]
        val sz = c.length + size
        if (sz > limit) p.success(None)
        else s.tail.onComplete {
          case Success(s) => go(total ++ c.bytes, s, sz)
          case Failure(t) => p.failure(t)
        }
      
      }
    }
    go(ByteString.empty, spool, 0)
    p.future
  }

//
//  // TODO: why are we using blocking file ops here!?!
//  // File operations
//  def binFile(file: java.io.File)(f: => Response)(implicit ec: ExecutionContext): Iteratee[Chunk,Response] = {
//    val out = new java.io.FileOutputStream(file)
//    whileBodyChunk &>> Iteratee.foreach[BodyChunk]{ d => out.write(d.toArray) }(ec).map{ _ => out.close(); f }(oec)
//  }
//
//  def textFile(req: RequestPrelude, in: java.io.File)(f: => Response)(implicit ec: ExecutionContext): Iteratee[Chunk,Response] = {
//    val is = new java.io.PrintStream(new FileOutputStream(in))
//    whileBodyChunk &>> Iteratee.foreach[BodyChunk]{ d => is.print(d.decodeString(req.charset)) }(ec).map{ _ => is.close(); f }(oec)
//  }
}
