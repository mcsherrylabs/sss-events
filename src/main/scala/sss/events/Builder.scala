package sss.events

import sss.events.EventProcessor.{CreateEventHandler, EventHandler, EventProcessorId}

/** Fluent API builder for creating event processors.
  *
  * Example:
  * {{{
  * engine.builder()
  *   .withCreateHandler { ep => { case "msg" => println("got msg") } }
  *   .withId("my-processor")
  *   .withSubscriptions("channel1", "channel2")
  *   .build()
  * }}}
  *
  * @param engine the event processing engine
  */
class Builder(engine: EventProcessingEngine) {

  /** Specifies a handler directly.
    *
    * @param eventHandler the event handler partial function
    * @return a CanBuildBuilder for further configuration
    */
  def withHandler(eventHandler: EventHandler): CanBuildBuilder = {
    new CanBuildBuilder(Right(eventHandler), engine)
  }

  /** Specifies a handler creation function that receives the processor instance.
    *
    * This is the recommended approach as it allows the handler to reference the processor.
    *
    * @param createEventHandler function that takes the processor and returns a handler
    * @return a CanBuildBuilder for further configuration
    */
  def withCreateHandler(createEventHandler: CreateEventHandler): CanBuildBuilder = {
    new CanBuildBuilder(Left(createEventHandler), engine)
  }


}

/** Builder with handler specified, ready for optional configuration and build.
  *
  * @param handler the event handler (either creation function or direct handler)
  * @param engine the event processing engine
  */
class CanBuildBuilder(handler: Either[CreateEventHandler, EventHandler], engine: EventProcessingEngine) {
  private var idOpt: Option[EventProcessorId] = None
  private var subs: Set[String] = Set.empty
  private var parentOpt: Option[EventProcessor] = None
  private var dispatcherName = DispatcherName.Default

  /** Sets a unique identifier for the processor (for lookup via registrar).
    *
    * @param id the processor identifier
    * @return this builder for chaining
    */
  def withId(id: EventProcessorId): CanBuildBuilder = {
    idOpt = Some(id)
    this
  }

  /** Subscribes the processor to the specified channels on creation.
    *
    * @param channels set of channel names to subscribe to
    * @return this builder for chaining
    */
  def withSubscriptions(channels: Set[String]): CanBuildBuilder = {
    subs = channels
    this
  }

  /** Sets a parent processor for hierarchical organization.
    *
    * @param parent the parent processor
    * @return this builder for chaining
    */
  def withParent(parent: EventProcessor): CanBuildBuilder = {
    parentOpt = Some(parent)
    this
  }

  /** Assigns the processor to a specific dispatcher (thread pool).
    *
    * @param name the dispatcher name (type-safe)
    * @return this builder for chaining
    */
  def withDispatcher(name: DispatcherName) : CanBuildBuilder = {
    dispatcherName = name
    this
  }

  /** Builds and registers the event processor with the engine.
    *
    * @return the newly created EventProcessor
    */
  def build(): EventProcessor = {
    engine.newEventProcessor(handler, idOpt, subs, parentOpt, dispatcherName)
  }

}