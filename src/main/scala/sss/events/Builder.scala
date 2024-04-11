package sss.events

import sss.events.EventProcessor.{CreateEventHandler, EventHandler, EventProcessorId}

class Builder(engine: EventProcessingEngine) {

  def withHandler(eventHandler: EventHandler): CanBuildBuilder = {
    new CanBuildBuilder(Right(eventHandler), engine)
  }

  def withCreateHandler(createEventHandler: CreateEventHandler): CanBuildBuilder = {
    new CanBuildBuilder(Left(createEventHandler), engine)
  }


}

class CanBuildBuilder(handler: Either[CreateEventHandler, EventHandler], engine: EventProcessingEngine) {
  private var idOpt: Option[EventProcessorId] = None
  private var subs: Set[String] = Set.empty
  private var parentOpt: Option[EventProcessor] = None
  private var dispatcherName = ""

  def withId(id: EventProcessorId): CanBuildBuilder = {
    idOpt = Some(id)
    this
  }

  def withSubscriptions(channels: Set[String]): CanBuildBuilder = {
    subs = channels
    this
  }

  def withParent(parent: EventProcessor): CanBuildBuilder = {
    parentOpt = Some(parent)
    this
  }

  def withDispatcher(name: String) : CanBuildBuilder = {
    dispatcherName = name
    this
  }

  def build(): EventProcessor = {
    engine.newEventProcessor(handler, idOpt, subs, parentOpt, dispatcherName)
  }

}