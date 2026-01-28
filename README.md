# sss-events

[![CI](https://github.com/mcsherrylabs/sss-events/actions/workflows/build.yml/badge.svg)](https://github.com/mcsherrylabs/sss-events/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.mcsherrylabs/sss-events_3.svg)](https://search.maven.org/artifact/com.mcsherrylabs/sss-events_3)

A lightweight, actor-like event processing framework for Scala 3, providing queue-based message passing, multi-threaded dispatch, pub/sub subscriptions, and scheduled event delivery.

## Features

- **Lightweight Event Processors**: Actor-like message processing without the overhead of full actor systems
- **Queue-Based Message Passing**: High-capacity message queues (100,000 messages per processor)
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
- **Threading**: Configurable thread pools, one thread per active processor
- **Overhead**: Minimal - no complex actor supervision or remote messaging
- **Scheduling**: Built-in ScheduledExecutorService for time-based events

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
├── Thread Pools (Dispatchers)
│   ├── "default" dispatcher
│   ├── Custom dispatchers
│   └── Thread per active processor
├── Registrar (ID-based lookup)
├── Subscriptions (Pub/Sub)
└── Scheduler (Delayed events)

EventProcessor
├── Message Queue (LinkedBlockingQueue)
├── Handler Stack (become/unbecome)
├── Parent Reference (optional)
└── Subscriptions (channels)
```

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
