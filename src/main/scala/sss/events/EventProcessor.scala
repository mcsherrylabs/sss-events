package sss.events

import EventProcessor.{CreateEventHandler, EventHandler, EventProcessorId, BecomeRequest, UnbecomeRequest}
import sss.events
import sss.events.Subscriptions.Subscribed

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.collection.mutable
import scala.util.Random

object EventProcessor {
  type CreateEventHandler = EventProcessor => EventHandler
  type EventHandler = PartialFunction[Any, Any]
  type EventProcessorId = String

  /** Internal message to request a handler change. Posted by requestBecome(). */
  private[events] case class BecomeRequest(newHandler: EventHandler, stackPreviousHandler: Boolean)

  /** Internal message to request reverting to previous handler. Posted by requestUnbecome(). */
  private[events] case object UnbecomeRequest
}

trait CanProcessEvents {
  def ! (ev:Any): Boolean = post(ev)
  def post(ev: Any): Boolean
  def id: EventProcessorId
  def queueSize: Int
  def currentQueueSize: Int
  def dispatcherName: DispatcherName = DispatcherName.Default
}

trait EventProcessorSupport {
  def parent: Option[EventProcessor] = None
  def channels: Set[String] = Set.empty
  def id: Option[String] = None
  def createOnEvent(self: EventProcessor): EventHandler = {
    case ev => onEvent(self, ev)
  }

  def onEvent(self: EventProcessor, event: Any): Unit = ()
}

trait EventProcessor extends CanProcessEvents {

  def parent: EventProcessor = null

  def hasParent: Boolean = parent != null

  implicit def engine: EventProcessingEngine

  /** Changes the current event handler. Must be called from within a handler.
    *
    * @param newHandler the new handler to install
    * @param stackPreviousHandler if true, push new handler onto stack; if false, replace current handler
    */
  protected def become(newHandler: EventHandler, stackPreviousHandler: Boolean = true): Unit

  /** Removes the current handler from the stack, reverting to the previous handler. Must be called from within a handler. */
  protected def unbecome(): Unit

  /** Safely requests a handler change by posting an internal message. Can be called from any thread.
    *
    * @param newHandler the new handler to install
    * @param stackPreviousHandler if true, push new handler onto stack; if false, replace current handler
    * @return true if message was successfully posted
    */
  def requestBecome(newHandler: EventHandler, stackPreviousHandler: Boolean = true): Boolean

  /** Safely requests reverting to the previous handler by posting an internal message. Can be called from any thread.
    *
    * @return true if message was successfully posted
    */
  def requestUnbecome(): Boolean

  def subscribe(channels: Set[String]): Subscribed

  def subscribe(channel: String): Subscribed = subscribe(Set(channel))

  def setSubscriptions(channels: Set[String]): Subscribed

  def broadcast(channel: String, msg: Any): Unit = broadcast(Set(channel), msg)
  def broadcast(channels: Set[String], msg: Any): Unit
  def unsubscribe(channels: Set[String]): Subscribed
  def unsubscribe(channels: String*): Subscribed = unsubscribe(channels.toSet)
  def unsubscribeAll(): Subscribed

  def newEventProcessor(
                         onEvent: (EventProcessor, Any) => Unit,
                         channels: Set[String] = Set.empty,
                         idOpt: Option[String] = None): EventProcessor

}

abstract class BaseEventProcessor(implicit val engine: EventProcessingEngine) extends EventProcessor with LoggingWithId {

  // Configurable queue size using engine config default
  def queueSize: Int = queueSizeOverride.getOrElse(engine.config.defaultQueueSize)
  private[events] var queueSizeOverride: Option[Int] = None

  private[events] val q: LinkedBlockingQueue[Any] = new LinkedBlockingQueue(queueSize)

  private[events] val taskLock = new Object()
  protected implicit val self: EventProcessor = this

  lazy private val handlers: mutable.Stack[EventHandler] = mutable.Stack(onEvent)

  lazy val uniqueId: String = Random.nextInt().toString
  def id: EventProcessorId = s"EP_${this.getClass.getName}_${uniqueId}"

  // Registration moved to factory methods (newEventProcessor) to ensure it happens after construction.
  // If creating BaseEventProcessor directly, call engine.register(processor) after construction.

  def currentQueueSize: Int = q.size()


  private[events] def poll(msWaitTime: Long): Any = q.poll(msWaitTime, TimeUnit.MILLISECONDS)

  override def equals(obj: Any): Boolean = {
    obj match {
      case other: EventProcessor if other.id == id => true
      case _ => false
    }
  }

  override def hashCode(): Int = id.hashCode


  def post(ev: Any): Boolean = {
    q.offer(ev)
  }

  private[events] def processEvent(ev: Any) : Unit = {
    // Handle internal messages first
    ev match {
      case BecomeRequest(newHandler, stackPreviousHandler) =>
        become(newHandler, stackPreviousHandler)
      case UnbecomeRequest =>
        unbecome()
      case _ =>
        // Delegate to user handlers
        val resultOpt = handlers.head.lift(ev)
        maybeUnhandled(ev, resultOpt)
    }
  }

  private def maybeUnhandled(ev: Any, result: Option[Any]): Unit = {
    if(result.isEmpty) unhandled(ev)
  }

  protected def unhandled(ev: Any): Unit = {
    logWarn(s"Unhandled -> ${ev}")
  }

  protected def become(newHandler: EventHandler, stackPreviousHandler: Boolean = true): Unit = {
    if(!stackPreviousHandler) {
      unbecome()
    }
    handlers.push(newHandler)
  }

  protected def unbecome(): Unit = {
    if(handlers.size > 1) {
      handlers.pop()
    }
  }

  def requestBecome(newHandler: EventHandler, stackPreviousHandler: Boolean = true): Boolean = {
    post(BecomeRequest(newHandler, stackPreviousHandler))
  }

  def requestUnbecome(): Boolean = {
    post(UnbecomeRequest)
  }

  def subscribe(channels: Set[String]): Subscribed = {
    engine.subscriptions.subscribe(self, channels)
  }

  def setSubscriptions(channels: Set[String]): Subscribed = {
    engine.subscriptions.setSubscriptions(self, channels)
  }

  def broadcast(channels: Set[String], msg: Any): Unit = {
    engine.subscriptions.broadcast(self, channels, msg)
  }

  def unsubscribe(channels: Set[String]): Subscribed = {
    engine.subscriptions.unsubscribe(self, channels)
  }

  def unsubscribeAll(): Subscribed = {
    engine.subscriptions.unsubscribeAll(self)
  }

  def newEventProcessor(
                 onEventF: (EventProcessor, Any) => Unit,
                 channelsToSubscribe: Set[String] = Set.empty,
                 idOpt: Option[String] = None): EventProcessor = {
    val ep = this
    engine.newEventProcessor(new EventProcessorSupport {
      override def channels: Set[EventProcessorId] = channelsToSubscribe
      override def parent: Option[EventProcessor] = Some(ep)
      override def id: Option[EventProcessorId] = idOpt

      override def onEvent(self: EventProcessor, event: Any): Unit = onEventF(self, event)
    })
  }

  protected val onEvent: EventHandler

}
