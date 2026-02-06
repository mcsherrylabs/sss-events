# SSS-Events API Guide for AI Assistants

This guide helps AI assistants (Claude, GPT, Gemini, etc.) and developers understand how to effectively use the sss-events library in Scala projects.

## Overview

**sss-events** is a lightweight, high-performance actor-like event processing framework for Scala 3. It provides:
- Queue-based message passing with configurable queue sizes
- Multi-threaded dispatch with thread pinning
- Pub/sub subscriptions
- Scheduled event delivery
- Handler stacking (become/unbecome pattern)
- Graceful shutdown with queue draining

**Maven Coordinates**: `com.mcsherrylabs %% sss-events % "0.0.9"`

## Installation

Add to `build.sbt`:
```scala
libraryDependencies += "com.mcsherrylabs" %% "sss-events" % "0.0.9"
```

**Requirements**: Scala 3.6+, Java 17+

## Core Architecture

### Key Components

1. **EventProcessingEngine** - The main engine managing all processors and dispatchers
2. **EventProcessor** - Actor-like entities that process messages
3. **Dispatcher** - Thread pools that execute processors
4. **DispatcherName** - Type-safe dispatcher identifiers

### Thread Model

- Each processor has a message queue (default: 10,000 messages)
- Multiple threads can share dispatchers (thread pinning via configuration)
- Lock-free queue operations with condition variables for low latency
- Worker threads use exponential backoff when idle

## Creating Processors

### Pattern 1: Builder Pattern (Recommended)

**Use when**: Simple message handling, no need for become/unbecome

```scala
import sss.events._

implicit val engine: EventProcessingEngine = EventProcessingEngine()
engine.start()

val processor = engine.builder()
  .withCreateHandler { ep => {
    case "ping" => println("Received ping!")
    case count: Int => println(s"Count: $count")
  }}
  .withDispatcher(DispatcherName.Default)  // Optional: specify dispatcher
  .withQueueSize(50000)                     // Optional: override queue size
  .build()

// Send messages
processor ! "ping"
processor ! 42

// Cleanup
engine.stop(processor.id)
engine.shutdown()
```

**Advantages**:
- Automatic registration (no manual `register()` call needed)
- Clean, concise syntax
- Consistent with framework conventions

### Pattern 2: BaseEventProcessor (Advanced)

**Use when**: Need become/unbecome, custom dispatcher logic, or complex state

```scala
import sss.events._

implicit val engine: EventProcessingEngine = EventProcessingEngine()
engine.start()

val processor = new BaseEventProcessor {
  private var state = 0

  override def dispatcherName: DispatcherName = DispatcherName.Default

  override protected val onEvent: EventHandler = {
    case "switch" => become(alternateHandler, stackPreviousHandler = true)
    case count: Int => state += count
  }

  private val alternateHandler: EventHandler = {
    case "back" => unbecome()
    case msg => println(s"Alternate: $msg")
  }
}

// CRITICAL: Must manually register!
engine.register(processor)

// Use processor
processor ! "switch"

// Cleanup
engine.stop(processor.id)
engine.shutdown()
```

**Requirements**:
- Must call `engine.register(processor)` after creation
- Needed for `become()`/`unbecome()` (protected methods)
- Full control over processor lifecycle

## Configuration

### Engine Configuration

```scala
import sss.events._

val config = EngineConfig(
  schedulerPoolSize = 2,

  // Thread-to-dispatcher assignment
  // Each array element = one thread, array contents = dispatchers it serves
  threadDispatcherAssignment = Array(
    Array("subscriptions"),    // Thread 0: dedicated to subscriptions
    Array("io-bound"),         // Thread 1: IO-heavy tasks
    Array("cpu-1", "cpu-2"),   // Thread 2: shared between CPU dispatchers
    Array("cpu-1", "cpu-2"),   // Thread 3: shared between CPU dispatchers
    Array("cpu-3"),            // Thread 4: dedicated CPU work
    Array("cpu-3")             // Thread 5: dedicated CPU work
  ),

  // Default queue size for processors (per processor!)
  defaultQueueSize = 10000,  // 10K default (was 100K, reduced to prevent memory explosion)

  // Exponential backoff for idle threads
  backoff = BackoffConfig(
    baseDelayMicros = 10,      // Start with 10μs delay
    multiplier = 1.5,          // Exponential growth factor
    maxDelayMicros = 10000     // Cap at 10ms
  )
)

val engine = EventProcessingEngine(config)
engine.start()
```

### HOCON Configuration (application.conf)

```hocon
sss-events {
  engine {
    default-queue-size = 10000
    scheduler-pool-size = 2

    backoff {
      base-delay-micros = 10
      multiplier = 1.5
      max-delay-micros = 10000
    }
  }
}
```

## Dispatcher Management

### Built-in Dispatchers

```scala
// Default dispatcher (empty string)
DispatcherName.Default

// Subscriptions dispatcher (for pub/sub)
DispatcherName.Subscriptions
```

### Custom Dispatchers

```scala
val config = EngineConfig(
  threadDispatcherAssignment = Array(
    Array("subscriptions"),
    Array("my-custom-dispatcher")
  )
)

val engine = EventProcessingEngine(config)

// Type-safe dispatcher creation
val customDispatcher = DispatcherName.validated("my-custom-dispatcher", config).getOrElse(
  throw new IllegalArgumentException("Invalid dispatcher")
)

val processor = engine.builder()
  .withCreateHandler { ep => { case msg => println(msg) }}
  .withDispatcher(customDispatcher)
  .build()
```

## Message Patterns

### Basic Messaging

```scala
processor ! "message"           // Fire and forget
processor.post("message")       // Alternative syntax (same behavior)
```

### Request-Response Pattern

```scala
case class Request(id: Int, replyTo: EventProcessor)
case class Response(id: Int, result: String)

val requester = engine.builder()
  .withCreateHandler { ep => {
    case Response(id, result) => println(s"Got response: $result")
  }}
  .build()

val responder = engine.builder()
  .withCreateHandler { ep => {
    case Request(id, replyTo) =>
      // Process and respond
      replyTo ! Response(id, "processed")
  }}
  .build()

responder ! Request(1, requester)
```

### Scheduled Messages

```scala
import scala.concurrent.duration._

val processor = engine.builder()
  .withCreateHandler { ep => {
    case "delayed" => println("Received delayed message")
  }}
  .build()

// Schedule a message
val cancellable = processor.scheduleOnce(5.seconds, "delayed")

// Cancel if needed
cancellable.cancel()
```

## Pub/Sub Pattern

```scala
val publisher = engine.builder()
  .withCreateHandler { ep => {
    case msg: String => ep.publish("updates", msg)
  }}
  .withChannels(Set("updates"))  // Publish to channel
  .build()

val subscriber1 = engine.builder()
  .withCreateHandler { ep => {
    case msg: String => println(s"Sub1: $msg")
  }}
  .withSubscriptions(Set("updates"))  // Subscribe to channel
  .build()

val subscriber2 = engine.builder()
  .withCreateHandler { ep => {
    case msg: String => println(s"Sub2: $msg")
  }}
  .withSubscriptions(Set("updates"))
  .build()

// All subscribers receive the message
publisher ! "broadcast message"
```

## Become/Unbecome Pattern

**Only works with BaseEventProcessor** (protected methods)

```scala
val processor = new BaseEventProcessor {
  override protected val onEvent: EventHandler = normalHandler

  private val normalHandler: EventHandler = {
    case "start-work" =>
      become(workingHandler, stackPreviousHandler = true)
      println("Switched to working mode")
  }

  private val workingHandler: EventHandler = {
    case "work" => println("Processing work...")
    case "done" =>
      unbecome()  // Return to normal handler
      println("Back to normal mode")
  }
}

engine.register(processor)

processor ! "start-work"
processor ! "work"
processor ! "done"
```

**Parameters**:
- `stackPreviousHandler = true` - Push current handler to stack (unbecome returns to it)
- `stackPreviousHandler = false` - Replace handler (unbecome returns to initial handler)

## Graceful Shutdown

```scala
import scala.concurrent.duration._

// Stop individual processor (drains queue first)
engine.stop(processor.id, timeout = 30.seconds)  // Default: 30s

// Shutdown entire engine
engine.shutdown()  // Stops all processors and dispatchers
```

**Shutdown behavior**:
1. Sets processor stopping flag
2. Drains message queue (processes remaining messages)
3. Removes from dispatcher
4. Unregisters from engine
5. Fails with timeout if queue doesn't drain

**Warnings**: Watch for these log messages:
- "Queue drain timeout" - Queue didn't empty in time
- "Message loss during stop" - Messages in queue when timeout occurred

## Performance Best Practices

### 1. Queue Sizing

```scala
// Memory-constrained: Use smaller queues
.withQueueSize(1000)  // 1K messages per processor

// High-throughput: Use larger queues
.withQueueSize(100000)  // 100K messages per processor

// Default: 10,000 is a good balance
```

**Memory calculation**: `processors × queueSize × avgMessageSize`
- 100 processors × 10K queue = 1M message capacity
- 1000 processors × 100K queue = 100M message capacity ⚠️

### 2. Thread Dispatcher Assignment

```scala
// ❌ BAD: Too many threads (excessive contention)
Array.fill(100)(Array("dispatcher"))

// ✅ GOOD: Reasonable thread count (cores × 2-4)
val cores = Runtime.getRuntime.availableProcessors()
Array.fill(cores * 2)(Array("dispatcher"))

// ✅ BETTER: Thread pinning for isolation
Array(
  Array("subscriptions"),  // Dedicated for pub/sub
  Array("io-bound"),       // Dedicated for I/O
  Array("cpu-1"),          // CPU work
  Array("cpu-1"),
  Array("cpu-2"),
  Array("cpu-2")
)
```

### 3. Backoff Configuration

```scala
// Aggressive (low latency, higher CPU)
BackoffConfig(
  baseDelayMicros = 1,
  multiplier = 1.1,
  maxDelayMicros = 1000
)

// Conservative (higher latency, lower CPU)
BackoffConfig(
  baseDelayMicros = 100,
  multiplier = 2.0,
  maxDelayMicros = 50000
)

// Balanced (default)
BackoffConfig(
  baseDelayMicros = 10,
  multiplier = 1.5,
  maxDelayMicros = 10000
)
```

### 4. Processor Lifecycle

```scala
// ❌ BAD: Creating processors in hot path
def handleRequest(data: String): Unit = {
  val processor = engine.builder().withCreateHandler { _ => {...}}.build()
  processor ! data
}

// ✅ GOOD: Reuse long-lived processors
class RequestHandler {
  private val processor = engine.builder()
    .withCreateHandler { ep => {
      case data: String => processData(data)
    }}
    .build()

  def handleRequest(data: String): Unit = processor ! data
}
```

## Testing Strategies

### Deterministic Testing

```scala
import java.util.concurrent.{CountDownLatch, TimeUnit}

"processor" should "process messages deterministically" in {
  val latch = new CountDownLatch(100)
  var count = 0

  val processor = engine.builder()
    .withCreateHandler { ep => {
      case "msg" =>
        count += 1
        if (count == 100) latch.countDown()
    }}
    .build()

  // Send messages
  (1 to 100).foreach(_ => processor ! "msg")

  // Wait with timeout
  val completed = latch.await(1, TimeUnit.SECONDS)

  completed shouldBe true
  count shouldBe 100
}
```

**Key principle**: Use synchronization primitives (CountDownLatch, Semaphore) instead of `Thread.sleep()` for deterministic tests.

### Testing Become/Unbecome

```scala
"processor" should "switch handlers" in {
  val results = new java.util.concurrent.ConcurrentLinkedQueue[String]()
  val latch = new CountDownLatch(3)

  val processor = new BaseEventProcessor {
    override protected val onEvent: EventHandler = {
      case "test" =>
        results.add("normal")
        latch.countDown()
      case "switch" => become(altHandler, stackPreviousHandler = true)
    }

    private val altHandler: EventHandler = {
      case "test" =>
        results.add("alt")
        latch.countDown()
      case "back" => unbecome()
    }
  }
  engine.register(processor)

  processor ! "test"      // "normal"
  processor ! "switch"
  processor ! "test"      // "alt"
  processor ! "back"
  processor ! "test"      // "normal"

  latch.await(1, TimeUnit.SECONDS)
  results.toArray shouldBe Array("normal", "alt", "normal")
}
```

## Common Pitfalls & Solutions

### 1. Forgetting to Register BaseEventProcessor

```scala
// ❌ WRONG: Processor never registered
val processor = new BaseEventProcessor { ... }
processor ! "msg"  // Will timeout on stop()

// ✅ CORRECT: Register after creation
val processor = new BaseEventProcessor { ... }
engine.register(processor)
processor ! "msg"
```

### 2. Not Starting the Engine

```scala
// ❌ WRONG: Messages never processed
val engine = EventProcessingEngine()
val processor = engine.builder().withCreateHandler {...}.build()
processor ! "msg"  // Sits in queue forever

// ✅ CORRECT: Start engine first
val engine = EventProcessingEngine()
engine.start()  // Critical!
val processor = engine.builder().withCreateHandler {...}.build()
```

### 3. Queue Overflow

```scala
// ❌ WRONG: Default 10K queue, sending 100K messages
val processor = engine.builder().withCreateHandler {...}.build()
(1 to 100000).foreach(i => processor ! i)  // Queue overflow!

// ✅ CORRECT: Size queue appropriately
val processor = engine.builder()
  .withCreateHandler {...}
  .withQueueSize(200000)  // Larger queue
  .build()
```

### 4. Memory Explosion with Many Processors

```scala
// ❌ WRONG: 1000 processors × 100K queue = 100M message capacity!
val processors = (1 to 1000).map { _ =>
  engine.builder()
    .withCreateHandler {...}
    .withQueueSize(100000)  // Too large!
    .build()
}

// ✅ CORRECT: Use smaller queues for many processors
val processors = (1 to 1000).map { _ =>
  engine.builder()
    .withCreateHandler {...}
    .withQueueSize(1000)  // Reasonable for many processors
    .build()
}
```

### 5. Blocking Operations in Handlers

```scala
// ❌ WRONG: Blocking operations on CPU dispatcher
val processor = engine.builder()
  .withCreateHandler { ep => {
    case url: String =>
      val response = Http.get(url)  // Blocks thread!
      println(response)
  }}
  .withDispatcher(DispatcherName.Default)
  .build()

// ✅ CORRECT: Use dedicated IO dispatcher
val config = EngineConfig(
  threadDispatcherAssignment = Array(
    Array("subscriptions"),
    Array("io"),      // Dedicated IO threads
    Array("cpu"),
    Array("cpu")
  )
)

val ioDispatcher = DispatcherName.validated("io", config).get

val processor = engine.builder()
  .withCreateHandler { ep => {
    case url: String =>
      val response = Http.get(url)  // OK on IO dispatcher
      println(response)
  }}
  .withDispatcher(ioDispatcher)
  .build()
```

## Logging Configuration

Create `src/main/resources/logback.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>[%level %logger{0} %d{HH:mm:ss.SSS}] %msg%n</pattern>
        </encoder>
    </appender>

    <!-- Production: WARN level -->
    <root level="WARN">
        <appender-ref ref="CONSOLE"/>
    </root>

    <!-- Development: DEBUG to see internal operations -->
    <!-- <logger name="sss.events" level="DEBUG"/> -->
</configuration>
```

## Performance Characteristics

Based on comprehensive benchmarks:

- **Throughput**: 1.8M+ messages/second (single dispatcher, 4 threads)
- **Latency**: P99 < 100μs (condition variable wakeup)
- **Scalability**: Linear scaling up to core count
- **Memory**: ~10KB per processor + (queueSize × message size)

**Benchmark configurations**:
- 8-16 CPU dispatchers: Best for CPU-bound work
- Mixed IO/CPU (20%/80%): Good for typical applications
- Thread pinning: Reduces contention vs shared dispatchers

## Migration Notes

### From Akka Actors

| Akka | sss-events |
|------|------------|
| `ActorSystem` | `EventProcessingEngine` |
| `Actor.receive` | `EventHandler` (builder or BaseEventProcessor) |
| `sender() ! reply` | `replyTo ! response` (pass reference explicitly) |
| `context.become()` | `become()` (BaseEventProcessor only) |
| `context.actorOf()` | `engine.builder().build()` |
| `actor ! msg` | `processor ! msg` |
| `system.terminate()` | `engine.shutdown()` |

**Key differences**:
- No implicit sender reference (pass explicitly)
- No supervision trees (handle errors manually)
- No actor selection by path
- No ask pattern (use request-response pattern)
- Explicit dispatcher configuration

## When to Use sss-events

**Good fit**:
- High-throughput message processing
- Actor-like concurrency patterns
- Pub/sub event distribution
- Background job processing
- Event-driven architectures
- Replacing simple Akka Actors usage

**Not ideal for**:
- Complex supervision hierarchies
- Distributed actors (remoting/clustering)
- Heavy backpressure requirements
- Very large actor systems (1M+ actors)

## Additional Resources

- Project README: Quickstart and basic examples
- `docs/graceful-shutdown.md`: Detailed shutdown behavior
- `docs/best-practices/thread-dispatcher-configuration.md`: Performance tuning
- CHANGELOG.md: Recent improvements and breaking changes

## Version Compatibility

- **Current**: 0.0.9
- **Scala**: 3.6+ required (uses Scala 3 syntax)
- **Java**: 17+ required
- **Breaking changes**: See CHANGELOG.md for migration guides

---

**Last Updated**: 2026-02-06
**Library Version**: 0.0.9
