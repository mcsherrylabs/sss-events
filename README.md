# sss-events

[![CI](https://github.com/mcsherrylabs/sss-events/actions/workflows/build.yml/badge.svg)](https://github.com/mcsherrylabs/sss-events/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.mcsherrylabs/sss-events_3.svg)](https://search.maven.org/artifact/com.mcsherrylabs/sss-events_3)

A lightweight, actor-like event processing framework for Scala 3, providing queue-based message passing, multi-threaded dispatch, pub/sub subscriptions, and scheduled event delivery.

**📚 For comprehensive usage guide, see [AI API Guide](docs/AI_API_GUIDE.md)** - Detailed documentation with patterns, best practices, and pitfalls.

## Features

- **Lightweight Event Processors**: Actor-like message processing without the overhead of full actor systems
- **Configurable Queue Sizes**: Customize queue capacity per processor (default: 100,000 messages)
- **Multi-Threaded Dispatch**: Configurable thread pools with isolated dispatcher support
- **Pub/Sub Subscriptions**: Channel-based broadcasting and subscription management
- **Scheduled Events**: Time-delayed event delivery with cancellation support
- **Handler Stacking**: Dynamic behavior changes using `become`/`unbecome` pattern
- **Fluent Builder API**: Clean, composable processor creation
- **Parent-Child Hierarchies**: Organize processors in supervision trees

## Installation

Add to your `build.sbt`:

```scala
libraryDependencies += "com.mcsherrylabs" %% "sss-events" % "0.0.8"
```

Requires Scala 3.6+ and Java 17+.

## Quick Start

### Basic Event Processor

```scala
import sss.events._

// Create the event processing engine
implicit val engine = EventProcessingEngine()
engine.start()

// Create a simple event processor
val processor = new EventProcessorSupport {
  override def onEvent(self: EventProcessor, event: Any): Unit = event match {
    case "ping" => println("Received ping!")
    case count: Int => println(s"Count: $count")
  }
}

// Send messages
val ep = engine.newEventProcessor(processor)
ep ! "ping"
ep ! 42
```

### Using the Builder API

```scala
import sss.events._

implicit val engine = EventProcessingEngine()
engine.start()

val processor = engine.builder()
  .withId("my-processor")
  .withCreateHandler { ep =>
    case "start" =>
      println("Starting...")
      ep.become {
        case "stop" => println("Stopping...")
      }
  }
  .build()

processor ! "start"
processor ! "stop"
```

### Pub/Sub Subscriptions

```scala
import sss.events._

implicit val engine = EventProcessingEngine()
engine.start()

// Subscribe to a channel
val subscriber = engine.builder()
  .withCreateHandler { ep =>
    case msg: String => println(s"Received: $msg")
  }
  .withSubscriptions("news-channel")
  .build()

// Broadcast to all subscribers
engine.subscriptions.broadcast("news-channel", "Breaking news!")
```

### Scheduled Events

```scala
import sss.events._
import scala.concurrent.duration._

implicit val engine = EventProcessingEngine()
engine.start()

val processor = engine.builder()
  .withCreateHandler { ep =>
    case "delayed-message" => println("This message was delayed!")
  }
  .build()

// Schedule a message to be delivered after 5 seconds
val cancellable = engine.schedule(5.seconds, processor, "delayed-message")

// Cancel the scheduled message if needed
cancellable.cancel()
```

### Multiple Dispatchers

```scala
import sss.events._

implicit val engine = EventProcessingEngine()
engine.start()

// Create processors on different thread pools
val fastProcessor = engine.builder()
  .withDispatcher("fast")
  .withCreateHandler { ep => /* handler */ }
  .build()

val slowProcessor = engine.builder()
  .withDispatcher("slow")
  .withCreateHandler { ep => /* handler */ }
  .build()
```

### Configurable Queue Sizes

Customize processor queue capacity for memory-constrained environments or burst traffic scenarios:

```scala
import sss.events._

implicit val engine = EventProcessingEngine()
engine.start()

// Small queue for low-latency, memory-constrained scenarios
val lowLatencyProcessor = engine.builder()
  .withQueueSize(1000)
  .withCreateHandler { ep =>
    case msg => // handle message
  }
  .build()

// Large queue for high-throughput burst traffic
val burstProcessor = engine.builder()
  .withQueueSize(500000)
  .withCreateHandler { ep =>
    case msg => // handle message
  }
  .build()

// Default queue size (100,000) when not specified
val defaultProcessor = engine.builder()
  .withCreateHandler { ep =>
    case msg => // handle message
  }
  .build()
```

**Queue Sizing Guidelines:**
- **1K-10K**: Low-latency scenarios, limited memory
- **10K-50K**: Balanced for typical workloads
- **100K (default)**: High-throughput, burst traffic
- **500K+**: Extreme burst scenarios (monitor memory usage)

**Tradeoffs:**
- Larger queues = higher throughput + more memory + higher latency
- Smaller queues = lower latency + less memory + risk of backpressure/message loss

## Core Concepts

### EventProcessor

Lightweight actor-like entity that processes events asynchronously. Each processor has:
- A message queue (default capacity: 100,000 messages)
- An event handler (partial function)
- Optional unique ID for lookups
- Optional parent processor

### EventProcessingEngine

Central dispatch engine that:
- Manages thread pools for event processing
- Routes messages to processors
- Provides registration for processor lookup by ID
- Manages subscriptions and scheduled events

### Handler Stacking

Processors can dynamically change behavior using `become` and `unbecome`:

```scala
val processor = new EventProcessorSupport {
  override def onEvent(self: EventProcessor, event: Any): Unit = event match {
    case "login" =>
      self.become {
        case "logout" => self.unbecome()
        case msg => println(s"Logged in, received: $msg")
      }
    case other => println("Not logged in")
  }
}
```

**Important**: `become()` and `unbecome()` are protected methods that can only be called from within event handlers. For thread-safe behavior changes from external threads, use `requestBecome()` and `requestUnbecome()`:

```scala
// From external thread - post a message to change handler
processor.requestBecome({
  case "new-message" => println("New handler!")
}, stackPreviousHandler = false)

// From within handler - direct call
override def onEvent(self: EventProcessor, event: Any): Unit = event match {
  case "switch" =>
    self.become {
      case msg => println(s"Switched: $msg")
    }
}
```

### Subscriptions

Pub/sub system for broadcasting messages to multiple processors:

```scala
// Subscribe to channels
processor.subscriptions.subscribe("channel1", "channel2")

// Broadcast to channel
engine.subscriptions.broadcast("channel1", "Hello subscribers!")

// Unsubscribe
processor.subscriptions.unsubscribe("channel1")
```

## Performance Characteristics

- **Message Throughput**: Queue-based with 100K default capacity per processor
- **Threading**: Lock-based dispatcher queues with configurable thread-to-dispatcher pinning
- **Scaling Efficiency**: 83.4% at 16 threads with 1:1 thread-to-dispatcher mapping
- **Lock Contention**: Exponential backoff strategy (10μs to 10ms) reduces CPU waste
- **Thread Coordination**: LockSupport.park/unpark for efficient sleeping and clean shutdown
- **Overhead**: Minimal - no complex actor supervision or remote messaging
- **Scheduling**: Built-in ScheduledExecutorService for time-based events

### Backoff Policy

The engine employs an exponential backoff strategy when lock contention occurs:

- **Base delay**: 10 microseconds (configurable)
- **Growth rate**: 1.5x multiplier per failed attempt
- **Maximum delay**: 10 milliseconds cap
- **Impact**: Benchmarks show < 2% variance between strategies - focus optimization on thread-to-dispatcher ratio instead

The fixed 100μs park when queues are empty maintains responsive polling without exponential delays.

### Thread Coordination

The engine uses `LockSupport.parkNanos()` and `LockSupport.unpark()` for efficient thread coordination:

- **Empty queue polling**: Threads park for 100μs when no work available
- **Clean shutdown**: `unpark()` wakes threads, `keepGoing` flag triggers graceful exit
- **No exceptions**: Unlike interrupt-based approaches, unpark doesn't throw exceptions
- **Validated**: All 25 core tests pass with this mechanism

### Performance Testing

The library includes comprehensive benchmarks and stress tests to measure and verify performance:

```bash
# Run all benchmarks
sbt "benchmarks/Jmh/run"

# Run thread safety stress tests
sbt "benchmarks/test"

# Quick smoke test
sbt "benchmarks/Jmh/run -wi 1 -i 1"
```

For detailed information on benchmarking, stress tests, and interpreting results, see [benchmarks/README.md](benchmarks/README.md).

**Typical Performance** (modern hardware):
- Single processor throughput: 30K-100K messages/second
- Concurrent scaling: Linear up to thread pool size
- Handler switching overhead: 50-200 microseconds

## When to Use sss-events vs Akka

**Use sss-events when:**
- You need lightweight in-process message passing
- You want minimal dependencies and overhead
- You don't need distributed actors or clustering
- You want simple pub/sub without actor selection complexity

**Use Akka when:**
- You need distributed actors across multiple JVMs
- You require sophisticated supervision strategies
- You need features like cluster sharding or persistence
- You're building large-scale reactive systems

## Architecture

```
EventProcessingEngine
├── Thread-to-Dispatcher Pinning
│   ├── Configurable thread assignments
│   ├── Lock-based dispatcher queues
│   ├── Type-safe DispatcherName
│   └── Exponential backoff on contention
├── Dedicated Dispatchers
│   ├── "subscriptions" - Dedicated subscription thread
│   ├── "" (default) - General purpose
│   └── Custom dispatchers (user-defined)
├── Configuration (Typesafe Config)
│   ├── Centralized ConfigFactory
│   ├── Thread-dispatcher assignments
│   └── Backoff policy tuning
├── Registrar (ID-based lookup)
├── Subscriptions (Pub/Sub)
└── Scheduler (Delayed events)

EventProcessor
├── Message Queue (LinkedBlockingQueue)
├── Handler Stack (become/unbecome)
├── Parent Reference (optional)
├── Dispatcher Assignment (type-safe)
└── Subscriptions (channels)
```

### Configuration Management

The library uses a centralized configuration pattern following best practices:

- **Single ConfigFactory Instance**: System-level configuration loaded once via `AppConfig.config`
- **Type-Safe Configuration**: All engine settings validated at startup
- **Flexible Thread Assignment**: Configure thread-to-dispatcher mappings via `application.conf`

```hocon
sss-events.engine {
  scheduler-pool-size = 2

  # Thread-to-dispatcher assignment
  # First thread is dedicated to "subscriptions" dispatcher
  thread-dispatcher-assignment = [
    ["subscriptions"],  # Thread 0: Subscriptions (required)
    [""],               # Thread 1: Default dispatcher
    ["api"],            # Thread 2: API workload
    ["background"]      # Thread 3: Background tasks
  ]

  # Exponential backoff on lock contention
  backoff {
    base-delay-micros = 10
    multiplier = 1.5
    max-delay-micros = 10000
  }
}
```

### Type-Safe Dispatcher Names

Dispatcher names are type-safe using the `DispatcherName` case class:

```scala
import sss.events.DispatcherName

// Pre-defined dispatchers
DispatcherName.Default        // "" (default dispatcher)
DispatcherName.Subscriptions  // "subscriptions" (dedicated)

// Custom dispatchers
val apiDispatcher = DispatcherName("api")

// Use in builder
val processor = engine.builder()
  .withDispatcher(apiDispatcher)
  .withCreateHandler { ep => /* handler */ }
  .build()
```

### Thread-to-Dispatcher Pinning

The engine uses lock-based dispatcher queues with configurable thread assignments:

- **1:1 mapping** achieves 83.4% scaling efficiency (validated via benchmarks)
- **Exponential backoff** reduces CPU waste during lock contention
- **LockSupport.unpark** for clean thread coordination and shutdown

For detailed configuration guidance, see [docs/best-practices/thread-dispatcher-configuration.md](docs/best-practices/thread-dispatcher-configuration.md).

## Documentation

### Core Guides

- **[Graceful Shutdown Semantics](docs/graceful-shutdown.md)** - Comprehensive guide to stopping processors safely, queue draining, timeouts, and best practices
- **[Thread-Dispatcher Configuration](docs/best-practices/thread-dispatcher-configuration.md)** - Performance tuning, configuration patterns, and optimization strategies
- **[Architecture: Dispatcher Queue Contention](docs/architecture/dispatcher-queue-contention.md)** - Implementation details and design decisions

### Performance & Testing

- **[Benchmark Comparison](docs/benchmark-comparison.md)** - Performance benchmarks and scaling analysis
- **[Testing and Validation](docs/TESTING_AND_VALIDATION.md)** - Test coverage and validation results

## Thread Safety

- Message queues are thread-safe (LinkedBlockingQueue)
- Multiple threads can send to the same processor safely
- Handler execution is single-threaded per processor
- Subscription operations are synchronized

## License

This project is licensed under the GPL3 License - see [LICENSE](https://www.gnu.org/licenses/gpl-3.0.en.html) for details.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Author

- **Alan McSherry** - [mcsherrylabs](http://mcsherrylabs.com)

## Links

- [GitHub Repository](https://github.com/mcsherrylabs/sss-events)
- [Maven Central](https://search.maven.org/artifact/com.mcsherrylabs/sss-events_3)
- [Issue Tracker](https://github.com/mcsherrylabs/sss-events/issues)
