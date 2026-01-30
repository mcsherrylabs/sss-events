package sss.events

import EventProcessor.{EventHandler, EventProcessorId}
import Subscriptions.{Broadcast, NotDelivered, SetSubscription, Subscribe, Subscribed, UnSubscribe, UnSubscribeAll}

import scala.collection.mutable.ListBuffer

object Subscriptions {
  case class SetSubscription(ep: EventProcessor, channels: Set[String])

  object Subscribe {
    def apply(ep: EventProcessor, channel: String*): Subscribe = {
      Subscribe(ep, channel.toSet)
    }
  }

  case class UnSubscribeAll(ep: EventProcessor)
  case class UnSubscribe(ep: EventProcessor, channels: Set[String])
  case class Subscribe(ep: EventProcessor, channels: Set[String])
  case class Subscribed(channels: Set[String])

  case class Broadcast(sender: EventProcessor, channels: Set[String], a:Any)
  case class NotDelivered(target: EventProcessor, broadcast: Broadcast)
}

class Subscriptions(implicit engine: EventProcessingEngine) extends BaseEventProcessor {

  private val lock = new Object()

  override def id: EventProcessorId = this.getClass.getName

  override def dispatcherName: DispatcherName = DispatcherName.Subscriptions

  private var subscriptions: Map[String, ListBuffer[EventProcessor]] = Map.empty

  private def unsubscribeImpl(ep: EventProcessor,
                              channels: Set[String]): Subscribed = {
    var remainingChannels = Set.empty[String]
    subscriptions = subscriptions.collect {
      case (k, values) if !channels.contains(k) =>

        if (values.contains(ep)) remainingChannels += k

        (k, values)
      case (k, values) if channels.contains(k) && values.size > 1 => (k, values.filterInPlace(_ != ep))
    }
    Subscribed(remainingChannels)
  }

  private def updateSubImpl(ep: EventProcessor,
                            channels: Set[String],
                            isSet: Boolean): Subscribed = {

    //TODO don't iterate three times
    subscriptions = subscriptions.map {
      case (k, values) if channels.contains(k) && values.contains(ep) => (k, values)
      case (k, values) if channels.contains(k) => (k, values.addOne(ep))
      case (k, values) if values.contains(ep) && isSet => (k, values.filterInPlace(_ != ep))
      case kv => kv
    } ++ {
      channels.filterNot(subscriptions.keys.toList.contains).map(_ -> ListBuffer(ep)).toMap
    }

    Subscribed(subscriptions.filter(_._2.contains(ep)).keys.toSet)
  }

  private def updateSubs(ep: EventProcessor,
                         channels: Set[String],
                         isSet: Boolean): Boolean = {

    val updates = updateSubImpl(ep, channels, isSet)
    ep ! updates
  }

  def setSubscriptions(ep: EventProcessor, channels: Set[String]): Subscribed = lock.synchronized {
    updateSubImpl(ep, channels, true)
  }

  def subscribe(ep: EventProcessor, channels: Set[String]): Subscribed = lock.synchronized {
    updateSubImpl(ep, channels, false)
  }

  def unsubscribeAll(ep: EventProcessor): Subscribed = lock.synchronized {
    updateSubImpl(ep, Set.empty, isSet = true)
  }

  def unsubscribe(ep: EventProcessor, channels: Set[String]): Subscribed = lock.synchronized {
    unsubscribeImpl(ep, channels)
  }

  def subscribe(am: EventProcessor, channel: String): Subscribed =
    subscribe(am, Set(channel))

  def broadcast(am: EventProcessor, channels: Set[String], msg: Any): Unit = {
    self ! Broadcast(am, channels, msg)
  }

  def unsubscribe(am: EventProcessor, channels: String*): Subscribed =
    unsubscribe(am, channels.toSet)

  override protected val onEvent: EventHandler = {

    case b@Broadcast(sendr, targetChannels, a) =>
      subscriptions.foreach {
        case (k, values) if targetChannels.contains(k) =>
          values.foreach { ep =>
            val ok = ep ! a
            if (!ok) {
              sendr ! NotDelivered(ep, b)
            }
          }
        case _ =>
      }

    case SetSubscription(ep: EventProcessor, channels: Set[String]) =>
      updateSubs(ep, channels, isSet = true)

    case Subscribe(ep: EventProcessor, channels: Set[String]) =>
      updateSubs(ep, channels, isSet = false)

    case UnSubscribeAll(ep) =>
      updateSubs(ep, Set.empty, isSet = true)

    case UnSubscribe(ep: EventProcessor, channels: Set[String]) =>
      val update = unsubscribeImpl(ep, channels)
      ep ! update
  }
}
