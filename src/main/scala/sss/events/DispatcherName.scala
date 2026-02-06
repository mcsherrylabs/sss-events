package sss.events

/** Type-safe dispatcher name wrapper.
  *
  * Provides type safety for dispatcher names used by EventProcessors.
  * Each dispatcher corresponds to a thread pool configuration in the engine.
  *
  * The Subscriptions EventProcessor is exempt from this type safety and can use any dispatcher ID.
  */
final case class DispatcherName private(value: String) {
  override def toString: String = value
}

object DispatcherName {

  /** The default dispatcher (represented by empty string in configuration). */
  val Default: DispatcherName = DispatcherName("")

  /** The subscriptions dispatcher (dedicated for the Subscriptions EventProcessor). */
  val Subscriptions: DispatcherName = DispatcherName("subscriptions")

  /** Creates a DispatcherName from a string, validating it against engine configuration.
    * This is the ONLY way to create custom dispatcher names beyond the predefined constants.
    *
    * @param name the dispatcher name string
    * @param config the engine configuration to validate against
    * @return Some(DispatcherName) if valid, None otherwise
    */
  def validated(name: String, config: EngineConfig): Option[DispatcherName] = {
    if (config.validDispatcherNames.contains(name)) Some(new DispatcherName(name))
    else None
  }

  /** Returns all built-in dispatcher names. */
  def builtInNames: Set[DispatcherName] = Set(Default, Subscriptions)
}
