package sss.events

import com.typesafe.config.{Config, ConfigFactory}

/**
 * Application-level configuration holder.
 * Loads ConfigFactory once at system initialization time.
 *
 * This follows the scala_config_load.md pattern:
 * - Single instance of Config at system level
 * - Created at initialization time (not lazy)
 * - Made available to components via parameter injection
 */
object AppConfig {
  /**
   * Single instance of Config loaded at startup.
   * Not lazy - created eagerly when AppConfig is first accessed.
   */
  val config: Config = ConfigFactory.load()
}
