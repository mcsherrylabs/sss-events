package sss.events

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Logger => LogbackLogger}
import ch.qos.logback.core.read.ListAppender
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.{Logger, LoggerFactory}

import scala.jdk.CollectionConverters._

/**
  * Regression for the broadcast/subscription concurrency defect found in the
  * evolver 130 EC2 grow/shrink run (see FINDINGS-broadcast-cme):
  *
  *   `Subscriptions.broadcast` iterated a channel's subscriber `ListBuffer` on
  *   the dispatcher thread while a concurrent `subscribe`/`unsubscribe` (running
  *   on another thread under the internal `lock`) mutated that same buffer in
  *   place (`addOne`/`filterInPlace`). The two paths did not share the lock, so
  *   the iterate-while-mutate raised `java.util.ConcurrentModificationException`.
  *   The engine caught it, logged a `TaskException`, and reposted the failed
  *   task, surfacing as `Unhandled -> (Broadcast(...), CME)` — and the in-flight
  *   broadcast was aborted part-way, so some subscribers never got the event.
  *
  * This test hammers a broadcast against a channel whose subscriber set is being
  * mutated concurrently and asserts nothing bad is logged for that channel.
  *
  * Detection is keyed on the unique channel token `race-channel`: on correct
  * code no WARN/ERROR ever mentions it (a healthy broadcast logs nothing); the
  * defect logs `Unhandled -> (Broadcast(...Set(race-channel)...), CME)`. Keying
  * on the token keeps the assertion immune to log noise from other suites that
  * run in parallel (the engine logs on a process-wide logger). A large stable
  * subscriber set widens each broadcast's iteration window so a CME is near
  * certain per broadcast on the buggy code, and the broadcaster is throttled so
  * the reposted (channel-bearing) event reliably surfaces rather than being
  * starved out of a saturated mailbox.
  */
class SubscriptionBroadcastRaceSpec extends AnyFlatSpec with Matchers {

  implicit val engine: EventProcessingEngine = EventProcessingEngine()
  engine.start()

  // Any registered processor gives us access to newEventProcessor / broadcast.
  private val host = new TestEventProcessor()
  engine.register(host)

  "Subscriptions" should "broadcast without a CME while its subscriber set mutates concurrently" in {
    val channel = "race-channel"

    // A large stable subscriber set keeps the buffer big (each broadcast's
    // iteration window is long) and its size > 1 (so unsubscribe takes the
    // in-place filterInPlace path). None of these are ever unsubscribed.
    val stable = (1 to 1500).map(_ => host.newEventProcessor((_, _) => (), Set(channel)))
    // Togglers are repeatedly (un)subscribed on the same channel to mutate the buffer.
    val togglers = (1 to 50).map(_ => host.newEventProcessor((_, _) => ()))
    val sender = host.newEventProcessor((_, _) => ())

    // Capture WARN/ERROR from the whole process; we filter to our channel below.
    val appender = new ListAppender[ILoggingEvent]()
    appender.start()
    val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME).asInstanceOf[LogbackLogger]
    root.addAppender(appender)

    @volatile var stop = false

    val broadcaster = new Thread(() => {
      while (!stop) {
        sender.broadcast(channel, "msg")
        // Throttle: keep the Subscriptions mailbox from saturating so a failed
        // task's reposted (channel-bearing) event actually surfaces in the log.
        Thread.sleep(1)
      }
    }, "test-broadcaster")

    val mutator = new Thread(() => {
      var i = 0
      while (!stop) {
        val ep = togglers(i % togglers.size)
        ep.subscribe(channel)
        ep.unsubscribe(channel)
        i += 1
      }
    }, "test-mutator")

    try {
      broadcaster.start()
      mutator.start()
      Thread.sleep(3000)
      stop = true
      broadcaster.join()
      mutator.join()
      // Let the dispatcher drain any still-queued broadcasts and reposts.
      Thread.sleep(300)

      // On correct code nothing is logged for our channel. The defect logs
      // `Unhandled -> (Broadcast(...Set(race-channel)...), ConcurrentModificationException...)`.
      val offending = appender.list.asScala.toList
        .map(_.getFormattedMessage)
        .filter(_.contains(channel))

      assert(
        offending.isEmpty,
        s"broadcast raced subscription mutation and failed:\n${offending.take(5).mkString("\n")}")
    } finally {
      root.detachAppender(appender)
      appender.stop()
      // keep the processors referenced; the engine is torn down at JVM exit
      val _ = (stable, togglers, sender)
    }
  }
}
