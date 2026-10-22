package sss.events

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Run: sbt "testOnly sss.events.TerminalRetirementSpec". No sleeps/timing assertions. */
class TerminalRetirementSpec extends AnyFlatSpec with Matchers {
  private case class Run(action: () => Unit, done: Promise[Unit])
  private case class Terminal(done: Promise[Unit])
  private case object Payload

  private class Fixture {
    private val registrar = new Registrar
    implicit val engine: EventProcessingEngine = new EventProcessingEngine()(
      Scheduler(1)(registrar), registrar, EngineConfig(
        schedulerPoolSize = 1,
        threadDispatcherAssignment = Array(Array(""), Array("subscriptions")),
        defaultQueueSize = 10000
      ))
    engine.start()
    val runner: EventProcessor = engine.builder().withHandler {
      case Run(action, done) =>
        try { action(); done.success(()) }
        catch { case e: Throwable => done.failure(e) }
    }.build()
    def run(action: => Unit): Unit = {
      val done = Promise[Unit]()
      runner ! Run(() => action, done)
      Await.result(done.future, 5.seconds)
    }
    def barrier(): Unit = run(())
    def assertRetired(ep: EventProcessor): Unit = {
      engine.registrar.get(ep.id) shouldBe None
      ep.currentQueueSize shouldBe 0
      val field = classOf[EventProcessingEngine].getDeclaredField("dispatchers")
      field.setAccessible(true)
      val dispatchers = field.get(engine).asInstanceOf[Map[String, LockedDispatcher]]
      dispatchers.values.flatMap(_.queue.asScala).exists(_.id == ep.id) shouldBe false
    }
  }
  private def withFixture(test: Fixture => Unit): Unit = {
    val fixture = new Fixture
    try test(fixture)
    finally fixture.engine.shutdown()
  }

  "EventProcessor.unRegister" should "retire self, discard trailing work and refuse late posts" in withFixture { f =>
    val work = new AtomicInteger(0)
    val done = Promise[Unit]()
    val ep = f.engine.builder().withCreateHandler { self =>
      {
        case Terminal(reply) =>
          self.unRegister()
          self.unRegister() // repeated completion in the same handler is harmless
          reply.success(()) // the current handler is allowed to finish
        case Payload => work.incrementAndGet()
      }
    }.build()
    f.run {
      ep ! Terminal(done)
      ep ! Payload
      ep ! Payload
    }
    Await.result(done.future, 5.seconds)
    f.barrier() // same worker, AFTER terminal handler's finally
    f.assertRetired(ep)
    (ep ! Payload) shouldBe false
    ep.currentQueueSize shouldBe 0
    work.get() shouldBe 0
    // Negative control: the runner remains live and usable.
    f.barrier()
    f.engine.registrar.get(f.runner.id) shouldBe defined
  }

  it should "reject external and other-processor invocation before any cleanup" in withFixture { f =>
    val ep = f.engine.builder().withHandler { case _: String => () }.build()
    intercept[IllegalStateException](ep.unRegister())
    f.run { intercept[IllegalStateException](ep.unRegister()) }
    f.engine.registrar.get(ep.id) shouldBe defined
    (ep ! "still-live") shouldBe true
    f.barrier()
  }

  it should "clear a post whose offer races after the terminal mailbox clear" in withFixture { f =>
    val entered = Promise[Unit]()
    val release = Promise[Unit]()
    val posted = Promise[Boolean]()
    val retired = Promise[Unit]()
    val work = new AtomicInteger(0)
    val queue = new LinkedBlockingQueue[Any](100) {
      override def offer(event: Any): Boolean = {
        if (event == Payload) {
          entered.trySuccess(())
          Await.result(release.future, 5.seconds)
        }
        super.offer(event)
      }
    }
    val ep = new BaseEventProcessor()(f.engine) {
      override private[events] val q: LinkedBlockingQueue[Any] = queue
      override protected val onEvent: EventProcessor.EventHandler = {
        case Terminal(done) => unRegister(); done.success(())
        case Payload => work.incrementAndGet()
      }
    }
    f.engine.register(ep)
    val poster = new Thread(() => {
      try posted.success(ep.post(Payload))
      catch { case e: Throwable => posted.failure(e) }
      ()
    })
    poster.start()
    try {
      Await.result(entered.future, 5.seconds)
      ep ! Terminal(retired)
      Await.result(retired.future, 5.seconds)
      f.barrier()
      release.success(())
      Await.result(posted.future, 5.seconds) shouldBe false
      f.assertRetired(ep)
      work.get() shouldBe 0
    } finally {
      release.trySuccess(())
      poster.join()
    }
  }

  it should "remove subscriptions and reject queued subscription revival" in withFixture { f =>
    val retired = Promise[Unit]()
    val delivered = Promise[Unit]()
    val ep = f.engine.builder().withCreateHandler { self =>
      { case Terminal(done) => self.unRegister(); done.success(()) }
    }.build()
    ep.subscribe("traffic")
    val live = f.engine.builder().withHandler { case "sentinel" => delivered.trySuccess(()) }.build()
    live.subscribe("traffic")
    ep ! Terminal(retired)
    Await.result(retired.future, 5.seconds)
    f.barrier()
    // These are real delayed subscription messages, not just synchronous calls after retirement.
    f.engine.subscriptions ! Subscriptions.Subscribe(ep, Set("traffic"))
    f.engine.subscriptions ! Subscriptions.SetSubscription(ep, Set("traffic", "new"))
    f.engine.subscriptions.broadcast(live, Set("traffic"), "sentinel")
    Await.result(delivered.future, 5.seconds)
    val subscriptions = subscriptionSnapshot(f)
    subscriptions.values.flatten.exists(_.id == ep.id) shouldBe false
    subscriptions("traffic") should contain(live)
    ep.subscribe("later").channels shouldBe empty
    f.assertRetired(ep)
  }

  it should "keep a live processor while 100 processors retire" in withFixture { f =>
    (1 to 100).foreach { _ =>
      val done = Promise[Unit]()
      val ep = f.engine.builder().withCreateHandler { self =>
        { case Terminal(reply) => self.unRegister(); reply.success(()) }
      }.build()
      ep ! Terminal(done)
      Await.result(done.future, 5.seconds)
      f.barrier()
      f.assertRetired(ep)
    }
    f.barrier()
    f.engine.registrar.get(f.runner.id) shouldBe defined
  }

  private def subscriptionSnapshot(f: Fixture): Map[String, List[EventProcessor]] = {
    val field = classOf[Subscriptions].getDeclaredFields.find(_.getName.endsWith("subscriptions")).get
    field.setAccessible(true)
    field.get(f.engine.subscriptions)
      .asInstanceOf[Map[String, scala.collection.mutable.ListBuffer[EventProcessor]]]
      .view.mapValues(_.toList).toMap
  }

  private def withSubscriptionsPaused(f: Fixture)(action: => Unit): Unit = {
    val sub = f.engine.subscriptions
    val method = classOf[Subscriptions].getDeclaredMethod("onEvent")
    method.setAccessible(true)
    val original = method.invoke(sub).asInstanceOf[EventProcessor.EventHandler]
    sub.requestBecome({
      case Run(run, done) =>
        try { run(); done.success(()) }
        catch { case e: Throwable => done.failure(e) }
      case event if original.isDefinedAt(event) => original(event)
    }) shouldBe true
    val entered = Promise[Unit]()
    val release = Promise[Unit]()
    val finished = Promise[Unit]()
    sub ! Run(() => { entered.success(()); Await.result(release.future, 5.seconds) }, finished)
    Await.result(entered.future, 5.seconds)
    try action
    finally {
      release.trySuccess(())
      Await.result(finished.future, 5.seconds)
      val drained = Promise[Unit]()
      sub ! Run(() => (), drained)
      Await.result(drained.future, 5.seconds)
    }
  }

  it should "preserve a sole live subscriber after queued unsubscribe of a retired peer" in withFixture { f =>
    val retired = Promise[Unit]()
    val delivered = Promise[Unit]()
    val old = f.engine.builder().withCreateHandler { self =>
      { case Terminal(done) => self.unRegister(); done.success(()) }
    }.withSubscriptions(Set("traffic")).build()
    val live = f.engine.builder().withHandler { case Payload => delivered.trySuccess(()) }
      .withSubscriptions(Set("traffic")).build()
    withSubscriptionsPaused(f) {
      f.engine.subscriptions ! Subscriptions.UnSubscribe(old, Set("traffic"))
      old ! Terminal(retired)
      Await.result(retired.future, 5.seconds)
      f.barrier()
    }
    subscriptionSnapshot(f).getOrElse("traffic", Nil).exists(_ eq live) shouldBe true
    f.engine.subscriptions.broadcast(f.runner, Set("traffic"), Payload)
    Await.result(delivered.future, 5.seconds)
  }

  it should "remove historical channel metadata after unique-channel subscriber churn" in withFixture { f =>
    val delivered = Promise[Unit]()
    val live = f.engine.builder().withHandler { case Payload => delivered.trySuccess(()) }
      .withSubscriptions(Set("live")).build()
    val baseline = subscriptionSnapshot(f).keySet
    (1 to 100).foreach { n =>
      val retired = Promise[Unit]()
      val transient = f.engine.builder().withCreateHandler { self =>
        { case Terminal(done) => self.unRegister(); done.success(()) }
      }.withSubscriptions(Set(s"transient-$n")).build()
      transient ! Terminal(retired)
      Await.result(retired.future, 5.seconds)
      f.barrier()
    }
    subscriptionSnapshot(f).keySet shouldBe baseline
    f.engine.subscriptions.broadcast(f.runner, Set("live"), Payload)
    Await.result(delivered.future, 5.seconds)
    f.engine.registrar.get(live.id).exists(_ eq live) shouldBe true
    live.unsubscribe("live").channels shouldBe empty
    subscriptionSnapshot(f) shouldBe empty
  }

  "Automatic processor IDs" should "increase across processor lifetimes and engines" in withFixture { f =>
    withFixture { other =>
      val ids = (1 to 32).map { n =>
        val engine = if (n % 2 == 0) f.engine else other.engine
        val retired = Promise[Unit]()
        val ep = engine.builder().withCreateHandler { self =>
          { case Terminal(done) => self.unRegister(); done.success(()) }
        }.build().asInstanceOf[BaseEventProcessor]
        val id = ep.id
        val sequence = ep.uniqueId.toLong
        ep ! Terminal(retired)
        Await.result(retired.future, 5.seconds)
        ep.id shouldBe id
        sequence
      }
      // Monotonic allocation, not a statistical check that random IDs happened not to collide.
      ids.sliding(2).foreach { pair => pair(1) should be > pair(0) }
      f.barrier()
      other.barrier()
    }
  }

  it should "allocate distinct increasing sequences concurrently across engines" in withFixture { f =>
    withFixture { other =>
      def allocate(engine: EventProcessingEngine): BaseEventProcessor =
        engine.builder().withHandler { case Payload => () }.withQueueSize(10)
          .build().asInstanceOf[BaseEventProcessor]
      val before = allocate(f.engine).uniqueId.toLong
      val start = Promise[Unit]()
      val workers = (1 to 4).map { n =>
        val result = Promise[Seq[Long]]()
        val thread = new Thread(() => {
          try {
            Await.result(start.future, 5.seconds)
            val engine = if (n % 2 == 0) f.engine else other.engine
            result.success((1 to 64).map(_ => allocate(engine).uniqueId.toLong))
          } catch { case e: Throwable => result.failure(e) }
          ()
        })
        thread.start()
        (thread, result)
      }
      try {
        start.success(())
        val sequences = workers.map { case (_, result) => Await.result(result.future, 5.seconds) }
        val after = allocate(other.engine).uniqueId.toLong
        sequences.foreach(_.sliding(2).foreach { pair => pair(1) should be > pair(0) })
        val all = sequences.flatten
        all.distinct.size shouldBe 256
        all.foreach { id => id should be > before; id should be < after }
        f.barrier()
        other.barrier()
      } finally {
        start.trySuccess(())
        workers.foreach(_._1.join())
      }
    }
  }
}
