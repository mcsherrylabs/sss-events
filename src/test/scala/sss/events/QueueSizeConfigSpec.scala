package sss.events

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class QueueSizeConfigSpec extends AnyFlatSpec with Matchers {

  "EventProcessor" should "use custom queue size when specified via withQueueSize" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    val processor = engine.builder()
      .withCreateHandler { ep => { case _ => } }
      .withQueueSize(5000)
      .build()

    processor.queueSize shouldBe 5000

    engine.stop(processor.id)
    engine.shutdown()
  }

  it should "use default queue size of 10000 when not specified" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    val processor = engine.builder()
      .withCreateHandler { ep => { case _ => } }
      .build()

    processor.queueSize shouldBe 10000

    engine.stop(processor.id)
    engine.shutdown()
  }

  it should "reject negative queue sizes" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    assertThrows[IllegalArgumentException] {
      engine.builder()
        .withCreateHandler { ep => { case _ => } }
        .withQueueSize(-100)
        .build()
    }

    engine.shutdown()
  }

  it should "reject zero queue size" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    assertThrows[IllegalArgumentException] {
      engine.builder()
        .withCreateHandler { ep => { case _ => } }
        .withQueueSize(0)
        .build()
    }

    engine.shutdown()
  }

  it should "allow different queue sizes for different processors" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    val processor1 = engine.builder()
      .withCreateHandler { ep => { case _ => } }
      .withQueueSize(1000)
      .build()

    val processor2 = engine.builder()
      .withCreateHandler { ep => { case _ => } }
      .withQueueSize(50000)
      .build()

    val processor3 = engine.builder()
      .withCreateHandler { ep => { case _ => } }
      .build() // Uses default

    processor1.queueSize shouldBe 1000
    processor2.queueSize shouldBe 50000
    processor3.queueSize shouldBe 10000

    engine.stop(processor1.id)
    engine.stop(processor2.id)
    engine.stop(processor3.id)
    engine.shutdown()
  }

  it should "use configured default queue size from config" in {
    import com.typesafe.config.ConfigFactory

    // Create config with custom default queue size
    val customConfig = ConfigFactory.parseString(
      """
        |sss-events {
        |  engine {
        |    scheduler-pool-size = 2
        |    default-queue-size = 5000
        |    thread-dispatcher-assignment = [
        |      ["subscriptions"],
        |      [""]
        |    ]
        |    backoff {
        |      base-delay-micros = 10
        |      multiplier = 1.5
        |      max-delay-micros = 10000
        |    }
        |  }
        |}
        |""".stripMargin
    ).withFallback(ConfigFactory.load())

    val engineConfig = EngineConfig.loadOrThrow(customConfig)
    implicit val engine = EventProcessingEngine(engineConfig)
    engine.start()

    val processor = engine.builder()
      .withCreateHandler { ep => { case _ => } }
      .build()

    processor.queueSize shouldBe 5000

    engine.stop(processor.id)
    engine.shutdown()
  }

  it should "allow queueSizeOverride to take precedence over configured default" in {
    import com.typesafe.config.ConfigFactory

    // Create config with custom default queue size
    val customConfig = ConfigFactory.parseString(
      """
        |sss-events {
        |  engine {
        |    scheduler-pool-size = 2
        |    default-queue-size = 5000
        |    thread-dispatcher-assignment = [
        |      ["subscriptions"],
        |      [""]
        |    ]
        |    backoff {
        |      base-delay-micros = 10
        |      multiplier = 1.5
        |      max-delay-micros = 10000
        |    }
        |  }
        |}
        |""".stripMargin
    ).withFallback(ConfigFactory.load())

    val engineConfig = EngineConfig.loadOrThrow(customConfig)
    implicit val engine = EventProcessingEngine(engineConfig)
    engine.start()

    val processor = engine.builder()
      .withCreateHandler { ep => { case _ => } }
      .withQueueSize(20000)
      .build()

    // Override should take precedence
    processor.queueSize shouldBe 20000

    engine.stop(processor.id)
    engine.shutdown()
  }
}
