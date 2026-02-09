package sss.events

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sss.events.EventProcessor.EventHandler

import scala.concurrent.{ExecutionContext, Future, Promise}

/**
  * Measures actual latency of requestBecome operations to verify timeout requirements
  */
class RequestBecomeLatencySpec extends AnyFlatSpec with Matchers {

  implicit val ec: ExecutionContext = ExecutionContext.global

  "requestBecome latency" should "be measured to justify timeout settings" in {
    implicit val engine: EventProcessingEngine = EventProcessingEngine()
    engine.start()

    def measureLatency(): Long = {
      val becomeComplete = Promise[Unit]()
      val handler2: EventHandler = {
        case "becomeComplete" => becomeComplete.success(())
      }

      val processor = new BaseEventProcessor {
        override protected val onEvent: EventHandler = { case _ => () }
      }
      engine.register(processor)

      val start = System.nanoTime()

      Future {
        processor.requestBecome(handler2, stackPreviousHandler = false)
        processor.post("becomeComplete")
      }

      import scala.concurrent.Await
      import scala.concurrent.duration._
      Await.result(becomeComplete.future, 5.seconds)

      val latencyNs = System.nanoTime() - start
      engine.stop(processor.id)
      latencyNs
    }

    // Warm up
    (1 to 10).foreach(_ => measureLatency())

    // Measure
    val latencies = (1 to 100).map(_ => measureLatency())
    val latenciesMs = latencies.map(_ / 1_000_000.0)

    val min = latenciesMs.min
    val max = latenciesMs.max
    val avg = latenciesMs.sum / latenciesMs.length
    val p50 = latenciesMs.sorted.apply(latenciesMs.length / 2)
    val p95 = latenciesMs.sorted.apply((latenciesMs.length * 0.95).toInt)
    val p99 = latenciesMs.sorted.apply((latenciesMs.length * 0.99).toInt)

    println(f"\nrequestBecome Latency Statistics (ms):")
    println(f"  Min: $min%.2f ms")
    println(f"  Max: $max%.2f ms")
    println(f"  Avg: $avg%.2f ms")
    println(f"  P50: $p50%.2f ms")
    println(f"  P95: $p95%.2f ms")
    println(f"  P99: $p99%.2f ms")
    println()

    if (p99 >= 150) {
      println(f"⚠️  WARNING: P99 latency ($p99%.2f ms) exceeds default 150ms timeout!")
      println(s"   Recommendation: Use ${math.ceil(p99 * 2).toInt}ms+ timeout for CI reliability")
    } else {
      println(f"✅ P99 latency ($p99%.2f ms) is within 150ms default timeout")
    }

    engine.shutdown()

    // Document findings
    p99 should be < 1000.0 // Should be well under 1 second
    info(f"P99 latency: $p99%.2f ms (${if (p99 < 150) "within" else "exceeds"} 150ms default)")
  }
}
