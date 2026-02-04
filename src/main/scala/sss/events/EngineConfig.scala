package sss.events

import com.typesafe.config.Config
import pureconfig.*
import pureconfig.generic.derivation.default.*

/**
 * Configuration for exponential backoff behavior when lock acquisition fails.
 *
 * @param baseDelayMicros Initial delay in microseconds (e.g., 10 = 10μs)
 * @param multiplier Factor to multiply delay by on each failure (e.g., 1.5)
 * @param maxDelayMicros Maximum delay cap in microseconds (e.g., 10000 = 10ms)
 */
case class BackoffConfig(
  baseDelayMicros: Long,
  multiplier: Double,
  maxDelayMicros: Long
) derives ConfigReader {
  require(baseDelayMicros > 0, s"baseDelayMicros must be positive, got: $baseDelayMicros")
  require(multiplier > 1.0, s"multiplier must be > 1.0, got: $multiplier")
  require(maxDelayMicros >= baseDelayMicros,
    s"maxDelayMicros ($maxDelayMicros) must be >= baseDelayMicros ($baseDelayMicros)")
}

/**
 * Engine configuration loaded from HOCON.
 *
 * @param schedulerPoolSize Number of threads in the scheduler thread pool
 * @param threadDispatcherAssignment Array of dispatcher name arrays. Each element defines
 *                                    which dispatchers a thread should work on.
 *                                    Outer array length = number of threads to create.
 *                                    Example: [["api", "realtime"], ["api"], ["batch"]]
 *                                    creates 3 threads: thread 0 works on api+realtime,
 *                                    thread 1 works on api only, thread 2 works on batch only.
 * @param defaultQueueSize Default queue size for EventProcessors when not overridden (default: 10000)
 * @param backoff Exponential backoff configuration
 */
case class EngineConfig(
  schedulerPoolSize: Int,
  threadDispatcherAssignment: Array[Array[String]],
  defaultQueueSize: Int,
  backoff: BackoffConfig
) derives ConfigReader {
  require(schedulerPoolSize > 0, s"schedulerPoolSize must be positive, got: $schedulerPoolSize")
  require(threadDispatcherAssignment.nonEmpty, "threadDispatcherAssignment cannot be empty")
  require(defaultQueueSize >= 1 && defaultQueueSize <= 1000000,
    s"defaultQueueSize must be in range [1, 1000000], got: $defaultQueueSize")

  // Validate each thread has at least one dispatcher
  threadDispatcherAssignment.zipWithIndex.foreach { case (dispatchers, threadIdx) =>
    require(dispatchers.nonEmpty,
      s"Thread $threadIdx has empty dispatcher list - each thread must have at least one dispatcher")
    // Note: empty string "" is a valid dispatcher name (default dispatcher)
  }

  // Validate thread count is reasonable
  val threadCount = threadDispatcherAssignment.length
  val maxThreads = Runtime.getRuntime.availableProcessors() * 10
  if (threadCount > maxThreads) {
    Console.err.println(
      s"WARNING: threadDispatcherAssignment has $threadCount threads, " +
      s"which exceeds ${maxThreads} (10x available processors). " +
      s"This may cause excessive contention.")
  }

  /**
   * Get the set of all unique dispatcher names referenced in the configuration.
   * These are the only valid dispatcher names processors can use.
   */
  def validDispatcherNames: Set[String] =
    threadDispatcherAssignment.flatten.toSet
}

object EngineConfig {
  /**
   * Load engine configuration from a provided Config instance.
   * This follows the pattern: load class-specific config from the system config instance.
   *
   * @param config The Config instance to load from (typically from AppConfig.config)
   * @return Either configuration errors or valid EngineConfig
   */
  def load(config: Config): Either[pureconfig.error.ConfigReaderFailures, EngineConfig] = {
    ConfigSource.fromConfig(config)
      .at("sss-events.engine")
      .load[EngineConfig]
  }

  /**
   * Load engine configuration from the system-level config instance.
   * Uses AppConfig.config as the source.
   *
   * @return Either configuration errors or valid EngineConfig
   */
  def load(): Either[pureconfig.error.ConfigReaderFailures, EngineConfig] = {
    load(AppConfig.config)
  }

  /**
   * Load engine configuration from a provided Config instance or throw exception.
   *
   * @param config The Config instance to load from
   * @throws RuntimeException if configuration is invalid
   */
  def loadOrThrow(config: Config): EngineConfig = {
    load(config) match {
      case Right(config) => config
      case Left(errors) =>
        val errorMessages = errors.toList.map { failure =>
          s"  - ${failure.description}"
        }.mkString("\n")
        throw new RuntimeException(
          s"Failed to load engine configuration from sss-events.engine:\n$errorMessages"
        )
    }
  }

  /**
   * Load engine configuration from system-level config or throw exception.
   * Uses AppConfig.config as the source.
   *
   * @throws RuntimeException if configuration is invalid
   */
  def loadOrThrow(): EngineConfig = {
    loadOrThrow(AppConfig.config)
  }
}
