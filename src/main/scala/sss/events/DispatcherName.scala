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

  /** Creates a DispatcherName from a string value.
    * This is the primary way to create typed dispatcher names.
    *
    * @param name the dispatcher name string
    * @return a DispatcherName instance
    */
  def apply(name: String): DispatcherName = new DispatcherName(name)

  /** Creates a DispatcherName from a string, validating it against engine configuration.
    *
    * @param name the dispatcher name string
    * @param validNames the set of valid dispatcher names from engine configuration
    * @return Some(DispatcherName) if valid, None otherwise
    */
  def validated(name: String, validNames: Set[String]): Option[DispatcherName] = {
    if (validNames.contains(name)) Some(DispatcherName(name))
    else None
  }

  /** Returns all built-in dispatcher names. */
  def builtInNames: Set[DispatcherName] = Set(Default)
}
