package sss.events.events

import sss.events.events.EventProcessor.{EventHandler, EventProcessorId}
import sss.events.events.Subscriptions.{Broadcast, NotDelivered, SetSubscription, Subscribe, Subscribed, UnSubscribe, UnSubscribeAll}

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

class Subscriptions(implicit engine: EventProcessingEngine) extends EventProcessor {

  override def id: EventProcessorId = this.getClass.getName

  private var subscriptions: Map[String, ListBuffer[EventProcessor]] = Map.empty

  private def updateSubs(ep: EventProcessor,
                         channels: Set[EventProcessorId],
                         isSet: Boolean) = {

    subscriptions = subscriptions.map {
      case (k, values) if channels.contains(k) && values.contains(ep) => (k, values)
      case (k, values) if channels.contains(k) => (k, values.addOne(ep))
      case (k, values) if values.contains(ep) && isSet => (k, values.filterInPlace(_ != ep))
      case kv => kv
    } ++ {
      channels.filterNot(subscriptions.keys.toList.contains).map(_ -> ListBuffer(ep)).toMap
    }
    ep ! Subscribed(channels)
  }

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
      var remainingChannels = Set.empty[String]
      subscriptions = subscriptions.collect {
        case (k, values) if !channels.contains(k) =>

          if(values.contains(ep)) remainingChannels += k

          (k, values)
        case (k, values) if channels.contains(k) && values.size > 1 => (k, values.filterInPlace(_ != ep))
      }
      ep ! Subscribed(remainingChannels)
  }
}
