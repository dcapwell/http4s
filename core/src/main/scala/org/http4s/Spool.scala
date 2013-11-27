package org.http4s


import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer
import scala.concurrent._
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.{Try, Failure, Success}
import org.http4s.Spool.LazyCons
import scala.util.Failure
import scala.Some
import scala.util.Success

/**
 * A spool is an asynchronous stream. It more or less
 * mimics the scala {{Stream}} collection, but with cons
 * cells that have deferred tails.
 *
 * Construction is done with Spool.cons, Spool.empty.  Convenience
 * syntax like that of [[scala.Stream]] is provided.  In order to use
 * these operators for deconstruction, they must be imported
 * explicitly ({{import Spool.{*::, **::}}})
 *
 * {{{
 *   def fill(rest: Promise[Spool[Int]]) {
 *     asyncProcess foreach { result =>
 *       if (result.last) {
 *         rest() = Return(result **:: Spool.empty)
 *       } else {
 *         val next = new Promise[Spool[Int]]
 *         rest() = Return(result *:: next)
 *         fill(next)
 *       }
 *     }
 *   }
 *   val rest = new Promise[Spool[Int]]
 *   fill(rest)
 *   firstElem *:: rest
 * }}}
 */
sealed trait Spool[+A] {
  import Spool._

  def isEmpty: Boolean = false

  /**
   * The first element of the spool. Invalid for empty spools.
   */
  def head: A

  /**
   * The (deferred) tail of the spool. Invalid for empty spools.
   */
  def tail: Future[Spool[A]]

  /** Traverse the tail until f returns false or the end of the stream, returning the tail of the spool
    * @param f method to execute until it returns fase
    * @param executor Trampolining (hopefully, this method is recursive) execution context
    * @return Remaining Spool
    */
  def foreachUntil(f: A => Boolean)(implicit executor: ExecutionContext): Future[Spool[A]] = {
    val p = Promise[Spool[A]]
    def go(spool: Spool[A]) {
      if (spool.isEmpty) p.success(spool)
      else {
        if (f(spool.head)) spool.tail.onComplete {
          case Success(spool) => go(spool)
          case Failure(t)     => p.failure(t)
        }
        else p.completeWith(spool.tail)
      }
    }
    go(this)
    p.future
  }

  /**
   * Apply {{f}} for each item in the spool, until the end.  {{f}} is
   * applied as the items become available.
   */
  def foreach(f: A => Any)(implicit executor: ExecutionContext): Future[Unit] = {
    val p = Promise[Unit]
    def go(spool: Spool[A]) {
      if (spool.isEmpty) p.success(Unit)
      else {
        f(spool.head)
        spool.tail.onComplete {
          case Success(spool) => go(spool)
          case Failure(t)     => p.failure(t)
        }
      }
    }
    go(this)
    p.future
  }

  /**
   * A version of {{foreach}} that wraps each element in an
   * {{Option}}, terminating the stream (EOF or failure) with
   * {{None}}.
   */
  def foreachElem(f: Option[A] => Any)(implicit executor: ExecutionContext) {
    if (!isEmpty) {
      f(Some(head))
      // note: this hack is to avoid deep
      // stacks in case a large portion
      // of the stream is already defined
      var next = tail
      while (next.value.fold(false)(_.isSuccess) && !next.value.get.get.isEmpty) {
        f(Some(next.value.get.get.head))
        next = next.value.get.get.tail
      }
      next.onComplete {
        case Success(s) => s.foreachElem(f)
        case Failure(e) => f(None)
      }
    } else {
      f(None)
    }
  }

  def zip[B](other: Spool[B])(implicit executor: ExecutionContext): Spool[(A,B)] = {
    def go(left: Future[Spool[A]], right: Future[Spool[B]]): Future[Spool[(A,B)]] = left.flatMap{ left =>
      if (!left.isEmpty) right.map { right =>
        if (!right.isEmpty) new LazyCons((left.head, right.head), go(left.tail, right.tail))
        else Empty
      } else Future.successful(Empty)
    }
    if (this.isEmpty || other.isEmpty) Empty
    else new LazyCons((this.head,other.head), go(this.tail, other.tail))
  }

  def interleave[B >: A](other: Spool[B])(implicit executor: ExecutionContext): Spool[B] = {
    def go(next: Spool[B], after: Spool[B]): Spool[B] = {
      if (next.isEmpty) after // Or do we want an empty?
      else new LazyCons(next.head, next.tail.map(tail => go(after, tail)))
    }
    if (this.isEmpty) other
    else new LazyCons[B](head, tail.map(go(other, _)))
  }

  def nondeterminateinterleave[B >: A](other: Spool[B])(implicit executor: ExecutionContext): Spool[B] = {
    def go(left: Future[Spool[B]], right: Future[Spool[B]]): Future[Spool[B]] = {
      val next = Promise[Spool[B]]

      // Set our two Futures off on a race to complete the promise.
      left.onComplete {
        case Success(left) =>
          if (left.isEmpty) next.tryCompleteWith(right)
          else {
            val p = Promise[Spool[B]]
            if (next.trySuccess(cons(left.head, p.future)))  // We won the race! Start the spool again
              p.completeWith(go(left.tail, right))
          }

        case Failure(t) =>
          // All we can do. If the other beats us it must still be alive and will continue to try to abort
          next.tryCompleteWith(right)
      }

      right.onComplete {  // The mirror image of the code above
        case Success(right) =>
          if (right.isEmpty) next.tryCompleteWith(left)
          else {
            val p = Promise[Spool[B]]
            if (next.trySuccess(new LazyCons(right.head, p.future)))
              p.completeWith(go(left, right.tail))
          }

        case Failure(t) => next.tryCompleteWith(left)
      }

      next.future
    }
    cons(head, new LazyCons(other.head, go(tail, other.tail)))
  }

  /**
   * The standard Scala collect, in order to implement map & filter.
   *
   * It may seem unnatural to return a Future[…] here, but we cannot
   * know whether the first element exists until we have applied its
   * filter.
   */
  def collect[B](f: PartialFunction[A, B])(implicit executor: ExecutionContext): Future[Spool[B]] = {
    val next_ = tail flatMap { _.collect(f) }
    if (f.isDefinedAt(head)) Future.successful(new LazyCons(f(head), next_))
    else next_
  }

  def map[B](f: A => B)(implicit executor: ExecutionContext): Spool[B] = {
    def go(spool: Spool[A]): Spool[B] = {
      if (spool.isEmpty) Empty
      else new LazyCons(f(spool.head), spool.tail.map(go))
    }
    go(this)
  }

  def filter(f: A => Boolean)(implicit executor: ExecutionContext): Future[Spool[A]] = collect {
    case x if f(x) => x
  }

  def fold[B](initial: B)(f: (B, A) => B)(implicit executor: ExecutionContext): Future[B] = {
    def folder(state: B, next: Future[Spool[A]]): Future[B] = {
      next.flatMap{ next =>
        if(next.isEmpty) Future.successful(state)
        else {
          folder(f(state, next.head), next.tail)          // Will probably get into the recursion problem
        }
      }
    }
    folder(initial, Future.successful(this))
  }

  /**
   * Concatenates two spools.
   */
  def ++[B >: A](that: Spool[B])(implicit executor: ExecutionContext): Spool[B] =
    if (isEmpty) that else new LazyCons[B](head, tail map { _ ++ that })

  /**
   * Concatenates two spools.
   */
  def ++[B >: A](that: Future[Spool[B]])(implicit executor: ExecutionContext): Future[Spool[B]] =
    if (isEmpty) that else Future.successful(new LazyCons[B](head, tail flatMap { _ ++ that }))

  /**
   * Applies a function that generates a spool to each element in this spool,
   * flattening the result into a single spool.
   */
  def flatMap[B](f: A => Future[Spool[B]])(implicit executor: ExecutionContext): Future[Spool[B]] =
    if (isEmpty) Future.successful(empty[B])
    else f(head) flatMap { _ ++ (tail flatMap { _ flatMap f }) }

  /**
   * Fully buffer the spool to a {{Seq}}.  The returned future is
   * satisfied when the entire result is ready.
   */
  def toSeq(implicit executor: ExecutionContext): Future[Seq[A]] = {
    val p = Promise[Seq[A]]
    val as = new ArrayBuffer[A]
    foreachElem {
      case Some(a) => as += a
      case None => p.success(as)
    }
    p.future
  }
}

object Spool {

  /** A pattern matching helper
    */
  object Cons {
    def unapply[A](spool: Spool[A]): Option[(A, Future[Spool[A]])] =
      if (spool.isEmpty) None else Some(spool.head, spool.tail)
  }

  case object Empty extends Spool[Nothing] {
    override def isEmpty = true
    def head = throw new NoSuchElementException("spool is empty")
    def tail = throw new UnsupportedOperationException("spool is empty")
    override def collect[B](f: PartialFunction[Nothing, B])(implicit executor: ExecutionContext) = Future.successful(this)
    override def toString = "Empty"
  }

  private class EagerCons[+A](value: A, next: Future[Spool[A]] = Future.successful(Spool.empty))
    extends Spool[A]
  {
    def head = value
    def tail = next

    override def toString = "Cons(%s, %c)".format(head, if (tail.isCompleted) '*' else '?')
  }

  /** A lazy Cons cell which will call the thunk exactly once, memorizing the result
    * It can be used to trigger backend events as the spool is unwound, offering a hook
    * for backpressure
    * @param head Value of this cons cell
    * @param thunktail Block that will be executed exactly once
    * @tparam A Type of value this Spool contains
    */
  final class LazyCons[+A](val head: A, thunktail: => Future[Spool[A]] = Future.successful(Spool.empty)) extends Spool[A] {

    /**
     * The (deferred) tail of the spool. Invalid for empty spools.
     */
    lazy val tail = thunktail
  }

  //def lazyCons[A](head: A)(tail: => Future[Spool[A]]): Spool[A] = new LazyCons(head, tail)

  def cons[A](value: A, next: Future[Spool[A]] = Future.successful(empty)): Spool[A] = new EagerCons(value, next)

  def cons[A](value: A, nextStream: Spool[A]): Spool[A] = new EagerCons(value, Future.successful(nextStream))

  /**
   * The empty spool.
   */
  def empty[A]: Spool[A] = Empty

  /**
   * Syntax support.  We retain different constructors for future
   * resolving vs. not.
   *
   * *:: constructs and deconstructs deferred tails
   * **:: constructs and deconstructs eager tails
   */

  class Syntax[A](tail: => Future[Spool[A]]) {
    def *::(head: A) = cons(head, tail)
  }

  def apply[T](in: T*): Spool[T] = in.foldRight[Spool[T]](Spool.empty)((i, s) => cons(i, s))

  implicit def syntax[A](s: Future[Spool[A]]): Syntax[A] = new Syntax(s)

  object *:: {
    def unapply[A](s: => Spool[A]): Option[(A, Future[Spool[A]])] = {
      if (s.isEmpty) None
      else Some((s.head, s.tail))
    }
  }
  class Syntax1[A](tail: => Spool[A]) {
    def **::(head: A) = cons(head, tail)
  }

  implicit def syntax1[A](s: => Spool[A]): Syntax1[A] = new Syntax1(s)

  object **:: {
    def unapply[A](s: Spool[A])(implicit timeout: FiniteDuration): Option[(A, Spool[A])] = {
      if (s.isEmpty) None
      else Some((s.head, Await.result(s.tail, timeout)))
    }
  }

  /** Acts as a gateway for the construction of lazy spool sources
    * Allows the delayed creation of the spool source until an access attempt
    * @param thunk Thunk which will start the lazy spool builder and get its future
    * @tparam A Type of result of this spool
    */
  class LazyTail[A](thunk: => Future[Spool[A]]) extends Future[Spool[A]] {

    private lazy val tail = thunk

    @throws(classOf[TimeoutException])
    @throws(classOf[InterruptedException])
    def ready(atMost: Duration)(implicit permit: CanAwait): this.type = {
      tail.ready(atMost)(permit)
      this
    }

    @throws(classOf[Exception])
    def result(atMost: Duration)(implicit permit: CanAwait): Spool[A] = tail.result(atMost)(permit)

    def onComplete[U](func: (Try[Spool[A]]) => U)(implicit executor: ExecutionContext): Unit =
      tail.onComplete(func)(executor)

    def isCompleted: Boolean = tail.isCompleted

    def value: Option[Try[Spool[A]]] = tail.value
  }
}

trait LazySpoolSource[A] {

  implicit def ec: ExecutionContext

  protected def getNext(): Future[Option[A]]

  /** Gets the Future[Spool[A]] and starts the ball rolling
    * @return The Future Spool[A]
    */
  def get(): Future[Spool[A]] = getNext().map  {
    case Some(a) => new Spool.LazyCons(a, get())
    case None    => Spool.Empty         // EOF
  }
}

class InputStreamSpool(is: java.io.InputStream, buffersize: Int = 1024*20)(implicit val ec: ExecutionContext)
  extends LazySpoolSource[BodyChunk] {

  private val buffer = new Array[Byte](buffersize)

  protected def getNext() = Future {
    //println("Reading bytes.")
    val bytes = is.read(buffer)
    if (bytes > 0) {
      val c = BodyChunk.fromArray(buffer, 0, bytes)
      Some(c)
    } else None
  }
}

abstract class BufferingSpoolSource[A](lowWater: Int, highWater: Int) {

  protected implicit def ec: ExecutionContext
  
  def highWaterReached(): Unit
  
  def lowWaterReached(): Unit
  
  def close() {
    if (promiseRef != null) {
      promiseRef.success(Spool.empty)
      promiseRef = null
    }
  }
  
  private val ticks = new java.util.concurrent.atomic.AtomicInteger(0)

  private var promiseRef: Promise[Spool[A]] = Promise[Spool[A]]

  /** Offer an element to the spool
    * This method is not thread safe! Make sure you call it sequentially
    * @param a Value to to be placed on the spool
    */
  def offer(a: A) {
    if (promiseRef == null) return    // Already finished
    val p = promiseRef
    promiseRef = Promise[Spool[A]]
    p.success({
      val p2 = promiseRef  // Get it this scope so it doesn't switch...
      new LazyCons[A](a, {
      if (ticks.decrementAndGet() <= lowWater) lowWaterReached()   // let the method know its good
      p2.future
    })})
    
    if (ticks.incrementAndGet() > highWater) highWaterReached()
  }

  def get() = {
    if (promiseRef == null) sys.error("Spool already closed")
    promiseRef.future
  }
}

/**
 * A SpoolSource is a simple object for creating and populating a Spool-chain.  apply()
 * returns a Future[Spool] that is populated by calls to offer().  This class is thread-safe.
 */
class SpoolSource[A] {
  // a reference to the current outstanding promise for the next Future[Spool[A]] result
  private val promiseRef = new AtomicReference[Promise[Spool[A]]]

  // when the SpoolSource is closed, promiseRef will be permanently set to emptyPromise,
  // which always returns an empty spool.
  private val emptyPromise = Promise.successful(Spool.empty[A])

  // set the first promise to be fulfilled by the first call to offer()
  promiseRef.set(Promise[Spool[A]])

  /**
   * Gets the current outstanding Future for the next Spool value.  The returned Spool
   * will see all future values passed to offer(), up until close() is called.
   * Previous values passed to offer() will not be seen in the Spool.
   */
  def apply(): Future[Spool[A]] = promiseRef.get.future

  /**
   * Puts a value into the spool.  Unless this SpoolSource has been closed, the current
   * Future[Spool[A]] value will be fulfilled with a Spool that contains the
   * provided value.  If the SpoolSoruce has been closed, then this value is ignored.
   * If multiple threads call offer simultaneously, the operation is thread-safe but
   * the resulting order of values in the spool is non-deterministic.
   */
  final def offer(value: A) {
    val nextPromise = Promise[Spool[A]]

    @tailrec def set() {
      val currentPromise = promiseRef.get
      // if the current promise is emptyPromise, then this source has been closed
      if (currentPromise ne emptyPromise) {
        // need to check that promiseRef hasn't already been offerd
        if (promiseRef.compareAndSet(currentPromise, nextPromise)) {
          currentPromise.success(Spool.cons(value, nextPromise.future))
        } else {
          // try again
          set()
        }
      }
    }

    set()
  }

  /**
   * Closes this SpoolSource, which also terminates the generated Spool.  This method
   * is idempotent.
   */
  @tailrec
  final def close() {
    val currentPromise = promiseRef.get
    // if the current promise is emptyPromise, then this source has already been closed
    if (currentPromise ne emptyPromise) {
      if (promiseRef.compareAndSet(currentPromise, emptyPromise)) {
        currentPromise.success(Spool.empty[A])
      } else {
        // try again
        close()
      }
    }
  }
}