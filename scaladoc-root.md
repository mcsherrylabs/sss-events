# sss-events API Documentation

A lightweight, actor-like event processing framework for Scala 3.

## Overview

sss-events provides queue-based message passing, multi-threaded dispatch, pub/sub subscriptions, and scheduled event delivery with minimal dependencies and overhead.

## Key Components

- **[[sss.events.EventProcessor]]** - Lightweight actor-like entity for processing events
- **[[sss.events.EventProcessingEngine]]** - Central dispatch engine managing thread pools and routing
- **[[sss.events.Builder]]** - Fluent API for creating event processors
- **[[sss.events.Subscriptions]]** - Pub/sub system for channel-based broadcasting
- **[[sss.events.Scheduler]]** - Time-delayed event delivery

## Quick Start

```scala
import sss.events._

// Create the engine
implicit val engine = EventProcessingEngine()
engine.start()

// Create a processor
val processor = engine.builder()
  .withCreateHandler { ep =>
    case "ping" => println("pong")
  }
  .build()

// Send messages
processor ! "ping"
```

## GitHub

[https://github.com/mcsherrylabs/sss-events](https://github.com/mcsherrylabs/sss-events)
