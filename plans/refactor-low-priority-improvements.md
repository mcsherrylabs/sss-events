# Low Priority Improvements for sss-events

**Type:** refactor
**Status:** Planned
**Date Created:** 2026-01-24
**Version Target:** 0.1.0

## Overview

This plan addresses four low-priority improvements identified during the codebase assessment:

1. **Thread Safety** - Fix potential race conditions in handler stack operations
2. **Configurable Settings** - Replace hardcoded values with flexible configuration
3. **Performance Testing** - Add JMH benchmarks and profiling infrastructure
4. **Modernize Concurrency** - Update to modern concurrency patterns (ExecutorService, virtual threads)

These improvements enhance production readiness, flexibility, and performance visibility without changing core functionality.

## Problem Statement

The sss-events framework currently has several areas that limit its production readiness and flexibility:

### Thread Safety Concerns
- Handler stack (`mutable.Stack[EventHandler]`) is modified by `become()`/`unbecome()` without full synchronization
- Race conditions possible when handler state changes concurrently with message processing
- No happens-before relationship between become() and processEvent()

### Hardcoded Configuration
- Processor queue size: 100,000 (EventProcessor.scala:81)
- Dispatcher queue size: 10,000 (EventProcessingEngine.scala:51)
- Max poll time: 40ms (EventProcessingEngine.scala:50)
- Users cannot tune these for their specific workloads

### No Performance Visibility
- No benchmarking infrastructure
- No baseline performance metrics
- No way to detect performance regressions
- Memory profiling requires manual setup

### Legacy Concurrency Patterns
- Manual `Thread` creation instead of `ExecutorService`
- No support for modern JDK 21+ virtual threads
- Shutdown uses simple interrupt without graceful draining
- No structured concurrency (parent-child lifecycle)

## Proposed Solution

Implement improvements in four independent phases, each deliverable independently:

### Phase 1: Thread Safety (CRITICAL)
Replace mutable handler stack with thread-safe implementation using `AtomicReference[List[EventHandler]]`.

### Phase 2: Configuration System (HIGH PRIORITY)
Add typed configuration case classes for all tunable parameters.

### Phase 3: Performance Testing (MEDIUM PRIORITY)
Set up JMH benchmark suite via sbt-jmh plugin.

### Phase 4: Concurrency Modernization (NICE-TO-HAVE)
Refactor to use `ExecutorService` with optional virtual thread support.

## Technical Approach

### Phase 1: Thread Safety Improvements

#### Architecture

Replace the mutable `Stack[EventHandler]` with immutable `List[EventHandler]` wrapped in `AtomicReference`:

```scala
// Current (EventProcessor.scala:74)
lazy private val handlers: mutable.Stack[EventHandler] = mutable.Stack(onEvent)

// Proposed
private val handlers = new AtomicReference(List[EventHandler](onEvent))
```

#### Implementation Details

**File:** `src/main/scala/sss/events/EventProcessor.scala`

**Changes:**
1. Replace mutable.Stack with AtomicReference[List[EventHandler]]
2. Update become() to use atomic compare-and-set
3. Update unbecome() to use atomic compare-and-set
4. Update processEvent() to read current handler atomically

**Code Example:**

```scala
import java.util.concurrent.atomic.AtomicReference

abstract class BaseEventProcessor(implicit val engine: EventProcessingEngine)
  extends EventProcessor with Logging {

  // Thread-safe handler stack using AtomicReference + immutable List
  private val handlers = new AtomicReference(List[EventHandler](onEvent))

  def become(newHandler: EventHandler, stackPreviousHandler: Boolean = true): Unit = {
    handlers.updateAndGet { current =>
      if (stackPreviousHandler) newHandler :: current
      else newHandler :: current.tail
    }
  }

  def unbecome(): Unit = {
    handlers.updateAndGet {
      case _ :: tail if tail.nonEmpty => tail
      case current => current // Don't remove last handler
    }
  }

  private[events] def processEvent(ev: Any): Unit = {
    val currentHandler = handlers.get().head
    if (!currentHandler.lift(ev).isDefined) {
      unhandled(ev)
    }
  }
}
```

**Testing:**

```scala
// src/test/scala/sss/events/ConcurrentBecomeSpec.scala
class ConcurrentBecomeSpec extends AnyFlatSpec with Matchers {

  "EventProcessor" should "handle concurrent become() safely" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    val processor = engine.builder()
      .withCreateHandler { ep =>
        case "ping" => ep.post("pong")
      }
      .build()

    // Concurrent become() calls from multiple threads
    val threads = (1 to 10).map { i =>
      new Thread(() => {
        (1 to 100).foreach { _ =>
          processor.become {
            case msg => println(s"Handler $i: $msg")
          }
        }
      })
    }

    threads.foreach(_.start())
    threads.foreach(_.join())

    // Processor should still be functional
    processor ! "ping"
    Thread.sleep(100)

    engine.shutdown()
  }
}
```

### Phase 2: Configuration System

#### Architecture

Create typed configuration case classes with hierarchy:

```
EngineConfig
├── schedulerThreads: Int
├── globalDefaults: QueueConfig
└── dispatchers: List[DispatcherConfig]
    ├── name: String
    ├── threadCount: Int
    └── queueConfig: QueueConfig (overrides globalDefaults)

QueueConfig
├── processorQueueSize: Int
├── dispatcherQueueSize: Int
└── maxPollTimeMs: Int
```

#### Implementation Details

**File:** `src/main/scala/sss/events/Config.scala` (NEW)

```scala
package sss.events

/** Configuration for queue behavior */
case class QueueConfig(
  processorQueueSize: Int = 100000,
  dispatcherQueueSize: Int = 10000,
  maxPollTimeMs: Int = 40
) {
  require(processorQueueSize > 0, "processorQueueSize must be positive")
  require(dispatcherQueueSize > 0, "dispatcherQueueSize must be positive")
  require(maxPollTimeMs > 0, "maxPollTimeMs must be positive")
}

/** Configuration for a single dispatcher */
case class DispatcherConfig(
  name: String,
  threadCount: Int,
  queueConfig: Option[QueueConfig] = None
) {
  require(threadCount > 0, "threadCount must be positive")
  require(threadCount <= Runtime.getRuntime.availableProcessors * 10,
    s"threadCount too high: $threadCount > ${Runtime.getRuntime.availableProcessors * 10}")
}

/** Top-level engine configuration */
case class EngineConfig(
  schedulerThreads: Int = 2,
  globalQueueConfig: QueueConfig = QueueConfig(),
  dispatchers: List[DispatcherConfig] = List(
    DispatcherConfig("default", 1)
  )
) {
  require(schedulerThreads > 0, "schedulerThreads must be positive")

  /** Get effective queue config for a dispatcher */
  def queueConfigFor(dispatcherName: String): QueueConfig = {
    dispatchers
      .find(_.name == dispatcherName)
      .flatMap(_.queueConfig)
      .getOrElse(globalQueueConfig)
  }
}
```

**File:** `src/main/scala/sss/events/EventProcessingEngine.scala`

**Changes:**
1. Add new `apply(config: EngineConfig)` factory method
2. Update internal queue size references to use config
3. Deprecate old hardcoded values
4. Maintain backward compatibility with existing `apply()` method

```scala
object EventProcessingEngine {

  /** Create engine with typed configuration (recommended) */
  def apply(config: EngineConfig): EventProcessingEngine = {
    implicit val registrar: Registrar = new Registrar
    implicit val scheduler: Scheduler = Scheduler(config.schedulerThreads)
    implicit val dispatcherImp: Map[EventProcessorId, Int] =
      config.dispatchers.map(d => d.name -> d.threadCount).toMap
    implicit val queueConfig: EngineConfig = config
    new EventProcessingEngine
  }

  /** Backward compatible factory (uses defaults) */
  def apply(numThreadsInSchedulerPool: Int = 2,
            dispatchers: Map[String, Int] = Map(("" -> 1))): EventProcessingEngine = {
    val config = EngineConfig(
      schedulerThreads = numThreadsInSchedulerPool,
      dispatchers = dispatchers.map { case (name, threads) =>
        DispatcherConfig(name, threads)
      }.toList
    )
    apply(config)
  }
}

class EventProcessingEngine(
  implicit val scheduler: Scheduler,
  val registrar: Registrar,
  dispatcherConfig: Map[String, Int],
  engineConfig: EngineConfig
) extends Logging {

  // Use configuration instead of hardcoded values
  private val maxPollTimeMs = engineConfig.globalQueueConfig.maxPollTimeMs

  private val dispatchers: Map[String, LinkedBlockingQueue[BaseEventProcessor]] =
    dispatcherConfig.map { case (name, _) =>
      val queueSize = engineConfig.queueConfigFor(name).dispatcherQueueSize
      (name -> new LinkedBlockingQueue[BaseEventProcessor](queueSize))
    }

  // ... rest of implementation
}
```

**File:** `src/main/scala/sss/events/Builder.scala`

**Changes:**
1. Add `.withQueueSize(Int)` method to CanBuildBuilder
2. Store per-processor queue size override

```scala
class CanBuildBuilder(handler: Either[CreateEventHandler, EventHandler], engine: EventProcessingEngine) {
  private var idOpt: Option[EventProcessorId] = None
  private var subs: Set[String] = Set.empty
  private var parentOpt: Option[EventProcessor] = None
  private var dispatcherName = ""
  private var queueSizeOpt: Option[Int] = None  // NEW

  /** Configure queue size for this processor (overrides dispatcher default) */
  def withQueueSize(size: Int): CanBuildBuilder = {
    require(size > 0, "Queue size must be positive")
    queueSizeOpt = Some(size)
    this
  }

  def build(): EventProcessor = {
    engine.newEventProcessor(handler, idOpt, subs, parentOpt, dispatcherName, queueSizeOpt)
  }
}
```

**Backward Compatibility:**
- Existing code continues to work unchanged
- New configuration API available for power users
- Defaults match current hardcoded values

### Phase 3: Performance Testing Infrastructure

#### Architecture

Add JMH benchmarking subproject with benchmark scenarios covering:
1. Single processor throughput
2. Multi-processor concurrent load
3. Subscription/broadcast latency
4. Handler become/unbecome overhead
5. Memory allocation patterns

#### Implementation Details

**File:** `project/plugins.sbt`

```scala
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.8")
```

**File:** `build.sbt`

```scala
lazy val benchmark = project
  .in(file("benchmark"))
  .dependsOn(root)
  .enablePlugins(JmhPlugin)
  .settings(
    name := "sss-events-benchmark",
    scalaVersion := "3.6.4",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "com.mcsherrylabs" %% "sss-events" % version.value
    )
  )
```

**File:** `benchmark/src/main/scala/sss/events/ThroughputBenchmark.scala` (NEW)

```scala
package sss.events.benchmark

import org.openjdk.jmh.annotations._
import sss.events._
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, warmups = 1)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 20, time = 1)
class ThroughputBenchmark {

  var engine: EventProcessingEngine = _
  var processor: EventProcessor = _
  val counter = new AtomicInteger(0)

  @Setup(Level.Trial)
  def setup(): Unit = {
    val config = EngineConfig(
      schedulerThreads = 2,
      dispatchers = List(DispatcherConfig("default", 4))
    )
    engine = EventProcessingEngine(config)
    engine.start()

    processor = engine.newEventProcessor(
      Right({ case _: String => counter.incrementAndGet() }),
      None
    )
  }

  @TearDown(Level.Trial)
  def teardown(): Unit = {
    engine.shutdown()
    println(s"Processed ${counter.get()} messages")
  }

  @Benchmark
  def singleProcessorThroughput(): Boolean = {
    processor.post("test message")
  }
}
```

**File:** `benchmark/src/main/scala/sss/events/ConcurrentLoadBenchmark.scala` (NEW)

```scala
package sss.events.benchmark

import org.openjdk.jmh.annotations._
import sss.events._
import java.util.concurrent.TimeUnit

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
@Threads(8)  // 8 concurrent threads
class ConcurrentLoadBenchmark {

  var engine: EventProcessingEngine = _
  var processors: List[EventProcessor] = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    engine = EventProcessingEngine()
    engine.start()

    // Create 10 processors
    processors = (1 to 10).map { i =>
      engine.newEventProcessor(
        Right({ case _ => Thread.sleep(1) }),  // 1ms processing
        Some(s"processor-$i")
      )
    }.toList
  }

  @TearDown(Level.Trial)
  def teardown(): Unit = {
    engine.shutdown()
  }

  @Benchmark
  def concurrentPosting(): Unit = {
    // Each thread posts to random processor
    val processor = processors(scala.util.Random.nextInt(processors.size))
    processor.post("message")
  }
}
```

**File:** `benchmark/src/main/scala/sss/events/BecomeUnbecomeBenchmark.scala` (NEW)

```scala
package sss.events.benchmark

import org.openjdk.jmh.annotations._
import sss.events._
import java.util.concurrent.TimeUnit

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(value = 1)
@Warmup(iterations = 10)
@Measurement(iterations = 20)
class BecomeUnbecomeBenchmark {

  var engine: EventProcessingEngine = _
  var processor: EventProcessor = _

  @Setup
  def setup(): Unit = {
    engine = EventProcessingEngine()
    engine.start()

    processor = engine.builder()
      .withCreateHandler { ep =>
        case "msg" => ()
      }
      .build()
  }

  @TearDown
  def teardown(): Unit = {
    engine.shutdown()
  }

  @Benchmark
  def becomeUnbecomeOverhead(): Unit = {
    processor.become { case _ => () }
    processor.unbecome()
  }
}
```

**Running Benchmarks:**

```bash
# Run all benchmarks
sbt "benchmark/Jmh/run"

# Run specific benchmark with profiling
sbt "benchmark/Jmh/run ThroughputBenchmark -prof gc"
sbt "benchmark/Jmh/run -prof jfr"

# Generate flame graphs
sbt "benchmark/Jmh/run -prof async:dir=benchmark/results"
```

**CI Integration:**

Update `.github/workflows/build.yml` to run benchmarks on tags:

```yaml
- name: Run benchmarks
  if: startsWith(github.ref, 'refs/tags/')
  run: |
    sbt "benchmark/Jmh/run -rf json -rff benchmark-results.json"

- name: Upload benchmark results
  if: startsWith(github.ref, 'refs/tags/')
  uses: actions/upload-artifact@v4
  with:
    name: benchmark-results
    path: benchmark-results.json
```

### Phase 4: Concurrency Modernization

#### Architecture

Refactor thread management to use `ExecutorService` with optional virtual thread support:

```
EventProcessingEngine
├── DispatcherPool (NEW)
│   ├── ExecutorService (platform threads or virtual threads)
│   ├── WorkQueue (LinkedBlockingQueue[BaseEventProcessor])
│   └── Shutdown hooks
└── Backward compatibility layer
```

#### Implementation Details

**File:** `src/main/scala/sss/events/DispatcherPool.scala` (NEW)

```scala
package sss.events

import java.util.concurrent._
import scala.util.Try

/** Thread pool backing for a dispatcher */
private[events] class DispatcherPool(
  name: String,
  threadCount: Int,
  queueSize: Int,
  useVirtualThreads: Boolean
) extends Logging {

  private val workQueue = new LinkedBlockingQueue[BaseEventProcessor](queueSize)

  private val executor: ExecutorService = createExecutorService()

  private def createExecutorService(): ExecutorService = {
    if (useVirtualThreads && isVirtualThreadsSupported()) {
      log.info(s"Dispatcher '$name' using virtual threads")
      createVirtualThreadExecutor()
    } else {
      if (useVirtualThreads) {
        log.warn(s"Virtual threads requested but not supported (JDK ${System.getProperty("java.version")}), using platform threads")
      }
      createPlatformThreadExecutor()
    }
  }

  private def isVirtualThreadsSupported(): Boolean = {
    Try {
      // Check if JDK 21+ virtual thread APIs are available
      Class.forName("java.lang.Thread$Builder")
      true
    }.getOrElse(false)
  }

  private def createVirtualThreadExecutor(): ExecutorService = {
    // Use reflection to avoid JDK 21 compile dependency
    val executorsClass = Class.forName("java.util.concurrent.Executors")
    val method = executorsClass.getMethod("newVirtualThreadPerTaskExecutor")
    method.invoke(null).asInstanceOf[ExecutorService]
  }

  private def createPlatformThreadExecutor(): ExecutorService = {
    new ThreadPoolExecutor(
      threadCount,
      threadCount,
      0L,
      TimeUnit.MILLISECONDS,
      new LinkedBlockingQueue[Runnable](),
      new ThreadFactory {
        private val counter = new java.util.concurrent.atomic.AtomicInteger(0)
        override def newThread(r: Runnable): Thread = {
          val t = new Thread(r)
          t.setName(s"dispatcher-$name-${counter.incrementAndGet()}")
          t.setDaemon(false)
          t
        }
      }
    )
  }

  def submit(processor: BaseEventProcessor): Unit = {
    workQueue.put(processor)
  }

  def start(processTaskFn: (Long, LinkedBlockingQueue[BaseEventProcessor]) => Boolean,
           keepGoingFlag: AtomicBoolean,
           maxPollTimeMs: Int): Unit = {
    (1 to threadCount).foreach { _ =>
      executor.submit(new Runnable {
        override def run(): Unit = Try {
          var noTaskCount = 0
          while (keepGoingFlag.get()) {
            val taskWaitTime = calculateWaitTime(noTaskCount, workQueue.size(), maxPollTimeMs)

            if (processTaskFn(taskWaitTime, workQueue)) {
              noTaskCount = 0
            } else {
              noTaskCount = noTaskCount + 1
            }
          }
        } recover {
          case _: InterruptedException if !keepGoingFlag.get() => ()
        }
      })
    }
  }

  private def calculateWaitTime(noTaskCount: Int, queueSize: Int, maxPollTimeMs: Int): Long = {
    if (noTaskCount == 0) 0
    else {
      val tmp: Long = if (queueSize == 0) maxPollTimeMs
      else Math.max(1, (maxPollTimeMs / queueSize).toLong)

      if (noTaskCount > tmp) tmp
      else noTaskCount
    }
  }

  def shutdown(graceful: Boolean, timeoutSeconds: Int = 30): Unit = {
    if (graceful) {
      executor.shutdown()
      try {
        if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
          log.warn(s"Dispatcher '$name' did not terminate gracefully within ${timeoutSeconds}s, forcing shutdown")
          executor.shutdownNow()
          if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
            log.error(s"Dispatcher '$name' did not terminate after force shutdown")
          }
        }
      } catch {
        case _: InterruptedException =>
          executor.shutdownNow()
          Thread.currentThread().interrupt()
      }
    } else {
      executor.shutdownNow()
    }
  }
}
```

**File:** `src/main/scala/sss/events/EventProcessingEngine.scala`

**Changes:**
1. Replace manual thread creation with DispatcherPool
2. Add virtual thread configuration option
3. Implement graceful shutdown

```scala
class EventProcessingEngine(
  implicit val scheduler: Scheduler,
  val registrar: Registrar,
  dispatcherConfig: Map[String, Int],
  engineConfig: EngineConfig
) extends Logging {

  private val keepGoing: AtomicBoolean = new AtomicBoolean(true)

  private val dispatcherPools: Map[String, DispatcherPool] = dispatcherConfig.map {
    case (name, threadCount) =>
      val queueConfig = engineConfig.queueConfigFor(name)
      val pool = new DispatcherPool(
        name,
        threadCount,
        queueConfig.dispatcherQueueSize,
        engineConfig.useVirtualThreads
      )
      (name -> pool)
  }

  def start(): Unit = {
    dispatcherPools.foreach { case (name, pool) =>
      pool.start(processTask, keepGoing, engineConfig.globalQueueConfig.maxPollTimeMs)
    }
  }

  def shutdown(graceful: Boolean = true, timeoutSeconds: Int = 30): Unit = {
    keepGoing.set(false)
    dispatcherPools.values.foreach(_.shutdown(graceful, timeoutSeconds))
    scheduler.shutdown()
  }

  // ... rest of implementation
}
```

**Configuration Update:**

Add virtual thread option to EngineConfig:

```scala
case class EngineConfig(
  schedulerThreads: Int = 2,
  globalQueueConfig: QueueConfig = QueueConfig(),
  dispatchers: List[DispatcherConfig] = List(DispatcherConfig("default", 1)),
  useVirtualThreads: Boolean = false  // NEW
)
```

**Usage Example:**

```scala
// Use virtual threads (JDK 21+)
val config = EngineConfig(
  dispatchers = List(DispatcherConfig("default", 100)),
  useVirtualThreads = true
)
val engine = EventProcessingEngine(config)
engine.start()

// Graceful shutdown with 60s timeout
engine.shutdown(graceful = true, timeoutSeconds = 60)
```

## Acceptance Criteria

### Phase 1: Thread Safety
- [ ] AtomicReference[List[EventHandler]] replaces mutable.Stack
- [ ] All handler stack operations are thread-safe
- [ ] Concurrent become() test passes (10 threads, 100 calls each)
- [ ] No race conditions detected in stress tests
- [ ] All existing tests pass
- [ ] Performance impact <5% vs current implementation

### Phase 2: Configuration System
- [ ] EngineConfig, DispatcherConfig, QueueConfig case classes created
- [ ] Validation enforces positive values and reasonable ranges
- [ ] Builder.withQueueSize() method added
- [ ] Backward compatibility maintained (old apply() still works)
- [ ] Documentation updated with configuration examples
- [ ] scaladoc added to all config classes

### Phase 3: Performance Testing
- [ ] sbt-jmh plugin configured in benchmark subproject
- [ ] ThroughputBenchmark implemented and passing
- [ ] ConcurrentLoadBenchmark implemented and passing
- [ ] BecomeUnbecomeBenchmark implemented and passing
- [ ] SubscriptionBenchmark implemented and passing
- [ ] Baseline results documented in benchmark/RESULTS.md
- [ ] CI runs benchmarks on tagged releases
- [ ] README updated with benchmark instructions

### Phase 4: Concurrency Modernization
- [ ] DispatcherPool class created with ExecutorService
- [ ] Virtual thread support works on JDK 21+
- [ ] Graceful fallback to platform threads on JDK <21
- [ ] Graceful shutdown drains queues with timeout
- [ ] shutdown() supports both graceful and immediate modes
- [ ] Thread naming preserved for debugging
- [ ] All existing tests pass with new implementation
- [ ] Backward compatibility maintained

## Dependencies & Risks

### Dependencies

**Phase 1:**
- None (pure Scala standard library)

**Phase 2:**
- None (optional: Typesafe Config for file-based config)

**Phase 3:**
- sbt-jmh plugin 0.4.8
- JMH 1.37+

**Phase 4:**
- JDK 21+ for virtual threads (optional, falls back to JDK 17)

### Risks

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| AtomicReference performance overhead | Medium | Medium | Benchmark before/after, accept <5% degradation |
| Configuration API design regret | High | Low | Get community feedback before release |
| JMH benchmark variance | Low | High | Run sufficient warmup iterations (10+) |
| Virtual thread pinning on synchronized | Medium | High | Document pinning behavior, measure impact |
| Breaking changes in refactor | High | Medium | Extensive backward compatibility testing |
| Shutdown deadlock | High | Low | Timeout on graceful shutdown, force after timeout |

### Rollback Strategy

Each phase is independently deployable:
- **Phase 1:** Revert if performance degrades >5%
- **Phase 2:** Maintain old factory methods, deprecate instead of remove
- **Phase 3:** Benchmark subproject optional, doesn't affect runtime
- **Phase 4:** Feature flag to disable ExecutorService, fall back to manual threads

## Success Metrics

### Functional
- Zero regressions in existing test suite
- New concurrent stress tests pass consistently
- Configuration validation prevents invalid states

### Performance
- Message throughput degradation <5% after Phase 1
- Benchmark suite completes in <5 minutes
- Virtual threads show >20% throughput improvement on JDK 21+

### Quality
- Test coverage maintained at 60%+ statement coverage
- Scaladoc coverage 100% for new public APIs
- Zero FindBugs/SpotBugs warnings

### Developer Experience
- Configuration examples in README
- Migration guide for breaking changes
- Clear error messages on invalid configuration

## References & Research

### Internal Codebase
- EventProcessingEngine.scala:51 - Dispatcher queue size (10K hardcoded)
- EventProcessingEngine.scala:50 - MaxPollTimeMs (40ms hardcoded)
- EventProcessor.scala:74 - Handler stack (mutable.Stack)
- EventProcessor.scala:81 - Processor queue size (100K default)
- EventProcessor.scala:119 - become() implementation
- EventProcessor.scala:124 - unbecome() implementation
- EventProcessingEngine.scala:145 - processEvent() with taskLock

### External References

**Thread Safety:**
- [Scala Best Practices - Concurrency](https://github.com/alexandru/scala-best-practices/blob/master/sections/4-concurrency-parallelism.md)
- [Thread Safety and Contention](https://deepwiki.com/alexandru/scala-best-practices/4.4-thread-safety-and-contention)
- [Twitter Scala School - Concurrency](https://twitter.github.io/scala_school/concurrency.html)

**Configuration:**
- [MacWire - Zero-cost DI](https://github.com/softwaremill/macwire)
- [Scala Best Practices - Architecture](https://github.com/alexandru/scala-best-practices/blob/master/sections/3-architecture.md)

**Performance Testing:**
- [sbt-jmh Plugin](https://github.com/sbt/sbt-jmh)
- [Benchmarking Scala Code with JMH](https://www.gaurgaurav.com/java/scala-benchmarking-jmh/)
- [Guide to Java Profilers](https://www.baeldung.com/java-profilers)
- [YourKit Java Profiler](https://www.yourkit.com/)

**Modern Concurrency:**
- [Scala 3 Book - Concurrency](https://docs.scala-lang.org/scala3/book/concurrency.html)
- [ExecutorService Documentation](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ExecutorService.html)
- [Virtual Threads in Scala](https://softwaremill.com/protecting-state-using-virtual-threads/)
- [Ox - Direct-style Scala with Loom](https://github.com/softwaremill/ox)

### Related Work
- PR #1: Build config, CI, documentation improvements
- PR #2: Removed unused ExecutionContext, added scaladoc

---

**Estimated Effort:**
- Phase 1: 2-3 days
- Phase 2: 3-4 days
- Phase 3: 2-3 days
- Phase 4: 4-5 days
- **Total:** 11-15 days

**Target Version:** 0.1.0 (minor version bump due to new features and potential breaking changes in configuration)