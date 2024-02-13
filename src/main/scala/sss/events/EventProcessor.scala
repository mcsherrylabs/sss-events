package sss.events

import EventProcessor.{CreateEventHandler, EventHandler, EventProcessorId}
import sss.events.Subscriptions.Subscribed

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.collection.mutable
import scala.util.Random

object EventProcessor {
  type CreateEventHandler = EventProcessor => EventHandler
  type EventHandler = PartialFunction[Any, Any]
  type EventProcessorId = String
}

trait CanProcessEvents {
  def ! (ev:Any): Boolean = post(ev)
  def post(ev: Any): Boolean
  def id: EventProcessorId
  def queueSize: Int
}

trait EventProcessor extends CanProcessEvents {

  def parent: EventProcessor = null

  def hasParent: Boolean = parent != null

  implicit def engine: EventProcessingEngine

  def become(newHandler: EventHandler, stackPreviousHandler: Boolean = true): Unit
  def unbecome(): Unit

  def subscribe(channels: Set[String]): Subscribed

  def subscribe(channel: String): Subscribed = subscribe(Set(channel))

  def setSubscriptions(channels: Set[String]): Subscribed

  def broadcast(channel: String, msg: Any): Unit = broadcast(Set(channel), msg)
  def broadcast(channels: Set[String], msg: Any): Unit
  def unsubscribe(channels: Set[String]): Subscribed
  def unsubscribe(channels: String*): Subscribed = unsubscribe(channels.toSet)
  def unsubscribeAll(): Subscribed

  def newEventProcessor(
                         onEvent: CreateEventHandler,
                         channels: Set[String] = Set.empty,
                         idOpt: Option[String] = None): EventProcessor

}

abstract class BaseEventProcessor(implicit val engine: EventProcessingEngine) extends EventProcessor with LoggingWithId {

  private[events] val q: LinkedBlockingQueue[Any] = new LinkedBlockingQueue(queueSize)
  private var qMaxxed: Boolean = false

  protected implicit val self: EventProcessor = this

  lazy private val handlers: mutable.Stack[EventHandler] = mutable.Stack(onEvent)

  lazy val uniqueId: String = Random.nextInt().toString
  def id: EventProcessorId = s"EP_${this.getClass.getName}_${uniqueId}"

  engine.register(this)

  def queueSize: Int = 100000

  def currentQSize: Int = q.size()


  private[events] def poll(msWaitTime: Long): Any = q.poll(msWaitTime, TimeUnit.MILLISECONDS)

  override def equals(obj: Any): Boolean = {
    obj match {
      case other: EventProcessor if other.id == id => true
      case _ => false
    }
  }

  override def hashCode(): Int = id.hashCode


  def post(ev: Any): Boolean = {
    val s = q.size()
    qMaxxed.synchronized {
      if (!qMaxxed && s == queueSize) {
        logInfo(s"Q size now $s")
        qMaxxed = true
      } else if (s < queueSize / 2) {
        qMaxxed = false
      }
    }
    q.offer(ev)
  }

  private[events] def processEvent(ev: Any) : Unit = {
    val resultOpt = handlers.head.lift(ev)
    maybeUnhandled(ev, resultOpt)
  }

  private def maybeUnhandled(ev: Any, result: Option[Any]): Unit = {
    if(result.isEmpty) unhandled(ev)
  }

  protected def unhandled(ev: Any): Unit = {
    logWarn(s"Unhandled -> ${ev}")
  }

  def become(newHandler: EventHandler, stackPreviousHandler: Boolean = true): Unit = {
    if(!stackPreviousHandler) {
      unbecome()
    }
    handlers.push(newHandler)
  }

  def unbecome(): Unit = {
    if(handlers.size > 1) {
      handlers.pop()
    }
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
                 onEvent: CreateEventHandler,
                 channels: Set[String] = Set.empty,
                 idOpt: Option[String] = None): EventProcessor = {
    engine.newEventProcessor(onEvent, channels, idOpt, Some(this))
  }

  protected val onEvent: EventHandler

}
