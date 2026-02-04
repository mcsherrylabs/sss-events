package sss.events

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EngineConfigSpec extends AnyFlatSpec with Matchers {

  "EngineConfig" should "reject negative schedulerPoolSize" in {
    intercept[IllegalArgumentException] {
      EngineConfig(
        schedulerPoolSize = -1,
        threadDispatcherAssignment = Array(Array("")),
        defaultQueueSize = 10000,
        backoff = BackoffConfig(10, 1.5, 10000)
      )
    }
  }

  it should "reject zero schedulerPoolSize" in {
    intercept[IllegalArgumentException] {
      EngineConfig(
        schedulerPoolSize = 0,
        threadDispatcherAssignment = Array(Array("")),
        defaultQueueSize = 10000,
        backoff = BackoffConfig(10, 1.5, 10000)
      )
    }
  }

  it should "reject empty threadDispatcherAssignment" in {
    intercept[IllegalArgumentException] {
      EngineConfig(
        schedulerPoolSize = 2,
        threadDispatcherAssignment = Array(),
        defaultQueueSize = 10000,
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
        defaultQueueSize = 10000,
        backoff = BackoffConfig(10, 1.5, 10000)
      )
    }
  }

  it should "accept empty string as valid dispatcher name" in {
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(Array("")),
      defaultQueueSize = 10000,
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
      defaultQueueSize = 10000,
      backoff = BackoffConfig(10, 1.5, 10000)
    )
    config.validDispatcherNames shouldBe Set("api", "batch", "realtime")
  }

  it should "reject defaultQueueSize less than 1" in {
    intercept[IllegalArgumentException] {
      EngineConfig(
        schedulerPoolSize = 2,
        threadDispatcherAssignment = Array(Array("")),
        defaultQueueSize = 0,
        backoff = BackoffConfig(10, 1.5, 10000)
      )
    }

    intercept[IllegalArgumentException] {
      EngineConfig(
        schedulerPoolSize = 2,
        threadDispatcherAssignment = Array(Array("")),
        defaultQueueSize = -1,
        backoff = BackoffConfig(10, 1.5, 10000)
      )
    }
  }

  it should "reject defaultQueueSize greater than 1000000" in {
    intercept[IllegalArgumentException] {
      EngineConfig(
        schedulerPoolSize = 2,
        threadDispatcherAssignment = Array(Array("")),
        defaultQueueSize = 1000001,
        backoff = BackoffConfig(10, 1.5, 10000)
      )
    }
  }

  it should "accept defaultQueueSize of 1" in {
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(Array("")),
      defaultQueueSize = 1,
      backoff = BackoffConfig(10, 1.5, 10000)
    )
    config.defaultQueueSize shouldBe 1
  }

  it should "accept defaultQueueSize of 1000000" in {
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(Array("")),
      defaultQueueSize = 1000000,
      backoff = BackoffConfig(10, 1.5, 10000)
    )
    config.defaultQueueSize shouldBe 1000000
  }

  it should "accept defaultQueueSize of 10000" in {
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(Array("")),
      defaultQueueSize = 10000,
      backoff = BackoffConfig(10, 1.5, 10000)
    )
    config.defaultQueueSize shouldBe 10000
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

  "DispatcherName.validated" should "reject invalid dispatcher names" in {
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(
        Array("subscriptions"),    // Dedicated dispatcher for Subscriptions
        Array("api")
      ),
      defaultQueueSize = 10000,
      backoff = BackoffConfig(10, 1.5, 10000)
    )

    // Attempt to create dispatcher with unknown name should return None
    val result = DispatcherName.validated("unknown", config)
    result shouldBe None

    // Valid dispatcher names should succeed
    val validResult = DispatcherName.validated("api", config)
    validResult shouldBe defined
    validResult.get.value shouldBe "api"
  }
}
