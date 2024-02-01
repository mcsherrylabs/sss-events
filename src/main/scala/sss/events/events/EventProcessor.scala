package sss.events.events

import sss.events.events.EventProcessor.{EventHandler, EventProcessorId}

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.collection.mutable
import scala.util.Random

object EventProcessor {
  type EventHandler = PartialFunction[Any, Any]
  type EventProcessorId = String
}

abstract class EventProcessor(implicit engine: EventProcessingEngine) extends LoggingWithId {

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

  def ! (ev:Any): Boolean = post(ev)

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

  def subscribe(channels: Set[String]): Unit = {
    engine.subscribe(self, channels)
  }

  def subscribe(channel: String): Unit = subscribe(Set(channel))

  def setSubscriptions(channels: Set[String]): Unit = {
    engine.setSubscriptions(self, channels)
  }

  def broadcast(channel: String, msg: Any): Unit = broadcast(Set(channel), msg)

  def broadcast(channels: Set[String], msg: Any): Unit = {
    engine.broadcast(self, channels, msg)
  }

  def unsubscribe(channels: Set[String]): Unit = {
    engine.unsubscribe(self, channels)
  }

  def unsubscribe(channels: String*): Unit = unsubscribe(channels.toSet)

  def unsubscribeAll(): Unit = {
    engine.unsubscribeAll(self)
  }

  protected val onEvent: EventHandler

}
