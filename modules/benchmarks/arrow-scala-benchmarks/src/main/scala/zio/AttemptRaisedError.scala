package arrow.benchmarks.effects.scala.zio

import scalaz.zio._

object dummy extends RuntimeException {
  override def fillInStackTrace(): Throwable = this
}

object AttemptRaisedError {

  def ioLoopNotHappy(size: Int, i: Int): IO[Nothing, Int] =
    if (i < size) {
      IO.sync {
        throw dummy
      }.attempt.flatMap { it =>
        it.fold(_ => ioLoopNotHappy(size, i + 1), IO.succeed)
      }
    } else IO.succeed(1)

}
