package sss.events

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EngineConfigSpec extends AnyFlatSpec with Matchers {

  "EngineConfig" should "reject negative schedulerPoolSize" in {
    intercept[IllegalArgumentException] {
      EngineConfig(
        schedulerPoolSize = -1,
        threadDispatcherAssignment = Array(Array("")),
        backoff = BackoffConfig(10, 1.5, 10000)
      )
    }
  }

  it should "reject zero schedulerPoolSize" in {
    intercept[IllegalArgumentException] {
      EngineConfig(
        schedulerPoolSize = 0,
        threadDispatcherAssignment = Array(Array("")),
        backoff = BackoffConfig(10, 1.5, 10000)
      )
    }
  }

  it should "reject empty threadDispatcherAssignment" in {
    intercept[IllegalArgumentException] {
      EngineConfig(
        schedulerPoolSize = 2,
        threadDispatcherAssignment = Array(),
        backoff = BackoffConfig(10, 1.5, 10000)
      )
    }
  }

  it should "reject thread with empty dispatcher list" in {
    intercept[IllegalArgumentException] {
      EngineConfig(
        schedulerPoolSize = 2,
        threadDispatcherAssignment = Array(
          Array("api"),
          Array[String]()  // Empty dispatcher list
        ),
        backoff = BackoffConfig(10, 1.5, 10000)
      )
    }
  }

  it should "accept empty string as valid dispatcher name" in {
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(Array("")),
      backoff = BackoffConfig(10, 1.5, 10000)
    )
    config.validDispatcherNames should contain ("")
  }

  it should "extract valid dispatcher names correctly" in {
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(
        Array("api", "batch"),
        Array("api"),
        Array("batch", "realtime")
      ),
      backoff = BackoffConfig(10, 1.5, 10000)
    )
    config.validDispatcherNames shouldBe Set("api", "batch", "realtime")
  }

  "BackoffConfig" should "reject negative baseDelayMicros" in {
    intercept[IllegalArgumentException] {
      BackoffConfig(
        baseDelayMicros = -1,
        multiplier = 1.5,
        maxDelayMicros = 10000
      )
    }
  }

  it should "reject zero baseDelayMicros" in {
    intercept[IllegalArgumentException] {
      BackoffConfig(
        baseDelayMicros = 0,
        multiplier = 1.5,
        maxDelayMicros = 10000
      )
    }
  }

  it should "reject multiplier <= 1.0" in {
    intercept[IllegalArgumentException] {
      BackoffConfig(
        baseDelayMicros = 10,
        multiplier = 1.0,
        maxDelayMicros = 10000
      )
    }

    intercept[IllegalArgumentException] {
      BackoffConfig(
        baseDelayMicros = 10,
        multiplier = 0.5,
        maxDelayMicros = 10000
      )
    }
  }

  it should "reject maxDelayMicros < baseDelayMicros" in {
    intercept[IllegalArgumentException] {
      BackoffConfig(
        baseDelayMicros = 10000,
        multiplier = 1.5,
        maxDelayMicros = 100  // Less than base
      )
    }
  }

  it should "accept maxDelayMicros == baseDelayMicros" in {
    val config = BackoffConfig(
      baseDelayMicros = 100,
      multiplier = 1.5,
      maxDelayMicros = 100
    )
    config.baseDelayMicros shouldBe 100
    config.maxDelayMicros shouldBe 100
  }

  "EventProcessingEngine" should "reject processor with invalid dispatcher name" in {
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(
        Array("subscriptions"),    // Dedicated dispatcher for Subscriptions
        Array("api")
      ),
      backoff = BackoffConfig(10, 1.5, 10000)
    )
    implicit val engine: EventProcessingEngine = EventProcessingEngine(config)
    engine.start()

    // Try to register processor with unknown dispatcher
    val ex = intercept[IllegalArgumentException] {
      new BaseEventProcessor {
        override def dispatcherName: DispatcherName = DispatcherName("unknown")
        override protected val onEvent = { case _ => }
      }
    }
    ex.getMessage should include ("unknown")
    ex.getMessage should include ("Valid dispatchers:")

    engine.shutdown()
  }
}
