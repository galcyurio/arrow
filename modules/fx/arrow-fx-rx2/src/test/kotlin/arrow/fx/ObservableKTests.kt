package arrow.fx

import arrow.core.Try
import arrow.fx.rx2.ForObservableK
import arrow.fx.rx2.ObservableK
import arrow.fx.rx2.ObservableKOf
import arrow.fx.rx2.extensions.concurrent
import arrow.fx.rx2.extensions.fx
import arrow.fx.rx2.extensions.observablek.async.async
import arrow.fx.rx2.extensions.observablek.functor.functor
import arrow.fx.rx2.extensions.observablek.functorFilter.functorFilter
import arrow.fx.rx2.extensions.observablek.monad.flatMap
import arrow.fx.rx2.extensions.observablek.timer.timer
import arrow.fx.rx2.extensions.observablek.traverse.traverse
import arrow.fx.rx2.k
import arrow.fx.rx2.value
import arrow.fx.typeclasses.Dispatchers
import arrow.fx.typeclasses.ExitCase
import arrow.test.UnitSpec
import arrow.test.laws.ConcurrentLaws
import arrow.test.laws.FunctorFilterLaws
import arrow.test.laws.TimerLaws
import arrow.test.laws.TraverseLaws
import arrow.typeclasses.Eq
import io.kotlintest.runner.junit4.KotlinTestRunner
import io.kotlintest.shouldBe
import io.reactivex.Observable
import io.reactivex.observers.TestObserver
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.rx2.asCoroutineDispatcher
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.coroutines.CoroutineContext

@RunWith(KotlinTestRunner::class)
class ObservableKTests : UnitSpec() {

  fun <T> EQ(): Eq<ObservableKOf<T>> = object : Eq<ObservableKOf<T>> {
    override fun ObservableKOf<T>.eqv(b: ObservableKOf<T>): Boolean {
      val res1 = Try { value().timeout(5, SECONDS).blockingFirst() }
      val res2 = Try { b.value().timeout(5, SECONDS).blockingFirst() }
      return res1.fold({ t1 ->
        res2.fold({ t2 ->
          (t1::class.java == t2::class.java)
        }, { false })
      }, { v1 ->
        res2.fold({ false }, {
          v1 == it
        })
      })
    }
  }

  val CO = ObservableK.concurrent(object : Dispatchers<ForObservableK> {
    override fun default(): CoroutineContext = Schedulers.io().asCoroutineDispatcher()
  })

  init {
    testLaws(
      TraverseLaws.laws(ObservableK.traverse(), ObservableK.functor(), { ObservableK.just(it) }, EQ()),
      ConcurrentLaws.laws(CO, EQ(), EQ(), EQ(), testStackSafety = false),
      TimerLaws.laws(ObservableK.async(), ObservableK.timer(), EQ()),
      FunctorFilterLaws.laws(ObservableK.functorFilter(), { Observable.just(it).k() }, EQ())
    )

    "Multi-thread Observables finish correctly" {
      val value: Observable<Long> = ObservableK.fx {
        val a = Observable.timer(2, SECONDS).k().bind()
        a
      }.value()

      val test: TestObserver<Long> = value.test()
      test.awaitDone(5, SECONDS)
      test.assertTerminated().assertComplete().assertNoErrors().assertValue(0)
    }

    "Observable cancellation forces binding to cancel without completing too" {
      val value: Observable<Long> = ObservableK.fx {
        val a = Observable.timer(3, SECONDS).k().bind()
        a
      }.value()
      val test: TestObserver<Long> = value.doOnSubscribe { subscription -> Observable.timer(1, SECONDS).subscribe { subscription.dispose() } }.test()
      test.awaitTerminalEvent(5, SECONDS)

      test.assertNotTerminated().assertNotComplete().assertNoErrors().assertNoValues()
    }

    "ObservableK bracket cancellation should release resource with cancel exit status" {
      lateinit var ec: ExitCase<Throwable>
      val countDownLatch = CountDownLatch(1)

      ObservableK.just(Unit)
        .bracketCase(
          use = { ObservableK.async<Nothing> { } },
          release = { _, exitCase ->
            ObservableK {
              ec = exitCase
              countDownLatch.countDown()
            }
          }
        )
        .value()
        .subscribe()
        .dispose()

      countDownLatch.await(100, TimeUnit.MILLISECONDS)
      ec shouldBe ExitCase.Canceled
    }

    "ObservableK should cancel KindConnection on dispose" {
      Promise.uncancelable<ForObservableK, Unit>(ObservableK.async()).flatMap { latch ->
        ObservableK {
          ObservableK.cancelable<Unit> {
            latch.complete(Unit)
          }.observable.subscribe().dispose()
        }.flatMap { latch.get() }
      }.value()
        .test()
        .assertValue(Unit)
        .awaitTerminalEvent(100, TimeUnit.MILLISECONDS)
    }

    "ObservableK async should be cancellable" {
      Promise.uncancelable<ForObservableK, Unit>(ObservableK.async())
        .flatMap { latch ->
          ObservableK {
            ObservableK.async<Unit> { }
              .value()
              .doOnDispose { latch.complete(Unit).value().subscribe() }
              .subscribe()
              .dispose()
          }.flatMap { latch.get() }
        }.observable
        .test()
        .assertValue(Unit)
        .awaitTerminalEvent(100, TimeUnit.MILLISECONDS)
    }
  }
}
