package arrow.fx.reactor

import arrow.core.Either
import arrow.core.Either.Left
import arrow.core.Either.Right
import arrow.core.NonFatal
import arrow.fx.OnCancel
import arrow.fx.internal.Platform
import arrow.fx.reactor.CoroutineContextReactorScheduler.asScheduler
import arrow.fx.typeclasses.Disposable
import arrow.fx.typeclasses.ExitCase
import reactor.core.publisher.Mono
import reactor.core.publisher.MonoSink
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

class ForMonoK private constructor() {
  companion object
}
typealias MonoKOf<A> = arrow.Kind<ForMonoK, A>
typealias MonoKKindedJ<A> = io.kindedj.Hk<ForMonoK, A>

@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
inline fun <A> MonoKOf<A>.fix(): MonoK<A> =
  this as MonoK<A>

fun <A> Mono<A>.k(): MonoK<A> = MonoK(this)

fun <A> MonoKOf<A>.value(): Mono<A> =
  this.fix().mono

data class MonoK<A>(val mono: Mono<A>) : MonoKOf<A>, MonoKKindedJ<A> {
  fun <B> map(f: (A) -> B): MonoK<B> =
    mono.map(f).k()

  fun <B> ap(fa: MonoKOf<(A) -> B>): MonoK<B> =
    flatMap { a -> fa.fix().map { ff -> ff(a) } }

  fun <B> flatMap(f: (A) -> MonoKOf<B>): MonoK<B> =
    mono.flatMap { f(it).fix().mono }.k()

  /**
   * A way to safely acquire a resource and release in the face of errors and cancellation.
   * It uses [ExitCase] to distinguish between different exit cases when releasing the acquired resource.
   *
   * @param use is the action to consume the resource and produce an [MonoK] with the result.
   * Once the resulting [MonoK] terminates, either successfully, error or disposed,
   * the [release] function will run to clean up the resources.
   *
   * @param release the allocated resource after the resulting [MonoK] of [use] is terminates.
   *
   * {: data-executable='true'}
   * ```kotlin:ank
   * import arrow.fx.*
   * import arrow.fx.reactor.*
   * import arrow.fx.typeclasses.ExitCase
   *
   * class File(url: String) {
   *   fun open(): File = this
   *   fun close(): Unit {}
   *   fun content(): MonoK<String> =
   *     MonoK.just("This file contains some interesting content!")
   * }
   *
   * fun openFile(uri: String): MonoK<File> = MonoK { File(uri).open() }
   * fun closeFile(file: File): MonoK<Unit> = MonoK { file.close() }
   *
   * fun main(args: Array<String>) {
   *   //sampleStart
   *   val safeComputation = openFile("data.json").bracketCase(
   *     release = { file, exitCase ->
   *       when (exitCase) {
   *         is ExitCase.Completed -> { /* do something */ }
   *         is ExitCase.Canceled -> { /* do something */ }
   *         is ExitCase.Error -> { /* do something */ }
   *       }
   *       closeFile(file)
   *     },
   *     use = { file -> file.content() }
   *   )
   *   //sampleEnd
   *   println(safeComputation)
   * }
   *  ```
   */
  fun <B> bracketCase(use: (A) -> MonoKOf<B>, release: (A, ExitCase<Throwable>) -> MonoKOf<Unit>): MonoK<B> =
    MonoK(Mono.create<B> { sink ->
      val isCanceled = AtomicBoolean(false)
      sink.onCancel { isCanceled.set(true) }
      val a: A? = mono.block()
      if (a != null) {
        if (isCanceled.get()) release(a, ExitCase.Canceled).fix().mono.subscribe({}, sink::error)
        else try {
          sink.onDispose(use(a).fix()
            .flatMap { b -> release(a, ExitCase.Completed).fix().map { b } }
            .handleErrorWith { e -> release(a, ExitCase.Error(e)).fix().flatMap { MonoK.raiseError<B>(e) } }
            .mono
            .doOnCancel { release(a, ExitCase.Canceled).fix().mono.subscribe({}, sink::error) }
            .subscribe(sink::success, sink::error)
          )
        } catch (e: Throwable) {
          if (NonFatal(e)) {
            release(a, ExitCase.Error(e)).fix().mono.subscribe({
              sink.error(e)
            }, { e2 ->
              sink.error(Platform.composeErrors(e, e2))
            })
          } else {
            throw e
          }
        }
      } else sink.success(null)
    })

  fun handleErrorWith(function: (Throwable) -> MonoK<A>): MonoK<A> =
    mono.onErrorResume { t: Throwable -> function(t).mono }.k()

  fun continueOn(ctx: CoroutineContext): MonoK<A> =
    mono.publishOn(ctx.asScheduler()).k()

  fun runAsync(cb: (Either<Throwable, A>) -> MonoKOf<Unit>): MonoK<Unit> =
    mono.flatMap { cb(Right(it)).value() }.onErrorResume { cb(Left(it)).value() }.k()

  fun runAsyncCancellable(cb: (Either<Throwable, A>) -> MonoKOf<Unit>): MonoK<Disposable> =
    Mono.fromCallable {
      val disposable: reactor.core.Disposable = runAsync(cb).value().subscribe()
      val dispose: Disposable = disposable::dispose
      dispose
    }.k()

  override fun equals(other: Any?): Boolean =
    when (other) {
      is MonoK<*> -> this.mono == other.mono
      is Mono<*> -> this.mono == other
      else -> false
    }

  override fun hashCode(): Int = mono.hashCode()

  companion object {
    fun <A> just(a: A): MonoK<A> =
      Mono.just(a).k()

    fun <A> raiseError(t: Throwable): MonoK<A> =
      Mono.error<A>(t).k()

    operator fun <A> invoke(fa: () -> A): MonoK<A> =
      defer { just(fa()) }

    fun <A> defer(fa: () -> MonoKOf<A>): MonoK<A> =
      Mono.defer { fa().value() }.k()

    /**
     * Creates a [MonoK] that'll run [MonoKProc].
     *
     * {: data-executable='true'}
     *
     * ```kotlin:ank
     * import arrow.core.Either
     * import arrow.core.right
     * import arrow.fx.reactor.MonoK
     * import arrow.fx.reactor.MonoKConnection
     * import arrow.fx.reactor.value
     *
     * class Resource {
     *   fun asyncRead(f: (String) -> Unit): Unit = f("Some value of a resource")
     *   fun close(): Unit = Unit
     * }
     *
     * fun main(args: Array<String>) {
     *   //sampleStart
     *   val result = MonoK.async { conn: MonoKConnection, cb: (Either<Throwable, String>) -> Unit ->
     *     val resource = Resource()
     *     conn.push(MonoK { resource.close() })
     *     resource.asyncRead { value -> cb(value.right()) }
     *   }
     *   //sampleEnd
     *   result.value().subscribe(::println)
     * }
     * ```
     */
    fun <A> async(fa: MonoKProc<A>): MonoK<A> =
      Mono.create<A> { sink ->
        val conn = MonoKConnection()
        val isCancelled = AtomicBoolean(false) // Sink is missing isCancelled so we have to do book keeping.
        conn.push(MonoK { if (!isCancelled.get()) sink.error(OnCancel.CancellationException) })
        sink.onCancel {
          isCancelled.compareAndSet(false, true)
          conn.cancel().value().subscribe()
        }

        fa(conn) { either: Either<Throwable, A> ->
          either.fold({
            sink.error(it)
          }, {
            sink.success(it)
          })
        }
      }.k()

    fun <A> asyncF(fa: MonoKProcF<A>): MonoK<A> =
      Mono.create { sink: MonoSink<A> ->
        val conn = MonoKConnection()
        val isCancelled = AtomicBoolean(false) // Sink is missing isCancelled so we have to do book keeping.
        conn.push(MonoK { if (!isCancelled.get()) sink.error(OnCancel.CancellationException) })
        sink.onCancel {
          isCancelled.compareAndSet(false, true)
          conn.cancel().value().subscribe()
        }

        fa(conn) { either: Either<Throwable, A> ->
          either.fold({
            sink.error(it)
          }, {
            sink.success(it)
          })
        }.fix().mono.subscribe({}, sink::error)
      }.k()

    tailrec fun <A, B> tailRecM(a: A, f: (A) -> MonoKOf<Either<A, B>>): MonoK<B> {
      val either = f(a).value().block()
      return when (either) {
        is Either.Left -> tailRecM(either.a, f)
        is Either.Right -> Mono.just(either.b).k()
      }
    }
  }
}
