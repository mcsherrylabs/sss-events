package sss.events

import EventProcessor.{CreateEventHandler, EventHandler, EventProcessorId}
import sss.events
import sss.events.Subscriptions.Subscribed

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.util.Random

trait Event {
  type EventType;

}
object EventProcessor {
  type CreateEventHandler = EventProcessor => EventHandler
  type EventHandler = PartialFunction[Any, Any]
  type EventProcessorId = String
}

trait CanProcessEvents {
  def ! (ev:Event): Boolean = post(ev)
  def post(ev: Event): Boolean
  def id: EventProcessorId
  def queueSize: Int
  def currentQueueSize: Int
  def dispatcherName: String = ""
}

trait EventProcessorSupport {
  def parent: Option[EventProcessor] = None
  def channels: Set[String] = Set.empty
  def id: Option[String] = None
  def createOnEvent(self: EventProcessor): EventHandler = {
    case ev => onEvent(self, ev)
  }

  def onEvent(self: EventProcessor, event: Event): Unit = ()
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
                         onEvent: (EventProcessor, Any) => Unit,
                         channels: Set[String] = Set.empty,
                         idOpt: Option[String] = None): EventProcessor

}

abstract class BaseEventProcessor[M <: Event](implicit val engine: EventProcessingEngine) extends EventProcessor with LoggingWithId {


  private[events] val q: LinkedBlockingQueue[M] = new LinkedBlockingQueue(queueSize)
  private var qMaxxed: Boolean = false

  private[events] val taskLock = new Object()
  protected implicit val self: EventProcessor = this

  lazy private val handlers: mutable.Stack[EventHandler] = mutable.Stack(onEvent)

  lazy val uniqueId: String = Random.nextInt().toString
  def id: EventProcessorId = s"EP_${this.getClass.getName}_${uniqueId}"

  engine.register(this)

  def queueSize: Int = 100000

  def currentQueueSize: Int = q.size()


  private[events] def poll(msWaitTime: Long): Any = q.poll(msWaitTime, TimeUnit.MILLISECONDS)
  private[events] def take(): Any = q.take()

  override def equals(obj: Any): Boolean = {
    obj match {
      case other: EventProcessor if other.id == id => true
      case _ => false
    }
  }

  override def hashCode(): Int = id.hashCode

  def processElement[A](element: A)(implicit ev: A =:= M): Unit = {
    println("A")
  }

  /*def ask[R](ev: M): Future[R] = {
    val r = Promise[R]()

  }*/
  def post(ev: M): Boolean = {
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
