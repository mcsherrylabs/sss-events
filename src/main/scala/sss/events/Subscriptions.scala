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

  // NOTE: every read and write of `subscriptions` (and of the ListBuffers it
  // holds) MUST run under `lock`. The mutating impls below take it directly, so
  // both the synchronous public API and the event-handler cases share one lock;
  // `lock` is re-entrant (JVM monitor), so nesting from a public method is safe.
  private def unsubscribeImpl(ep: EventProcessor,
                              channels: Set[String]): Subscribed = lock.synchronized {
    var remainingChannels = Set.empty[String]
    subscriptions = subscriptions.flatMap { case (channel, values) =>
      if (channels.contains(channel)) values.filterInPlace(p => !(p eq ep))
      if (values.exists(_ eq ep)) remainingChannels += channel
      // Remove a channel only when no subscribers remain.
      Option.when(values.nonEmpty)(channel -> values)
    }
    Subscribed(remainingChannels)
  }

  private def updateSubImpl(ep: EventProcessor,
                            channels: Set[String],
                            isSet: Boolean): Subscribed = lock.synchronized {

    // Queued subscription updates must not revive a retired processor.
    val stopping = ep match {
      case base: BaseEventProcessor => base.stopping.get()
      case _ => false
    }
    val requested = if (stopping) Set.empty[String] else channels

    // Drop empty channel metadata along with retired subscribers.
    subscriptions = subscriptions.flatMap { case (channel, values) =>
      if (requested.contains(channel)) {
        if (!values.exists(_ eq ep)) values.addOne(ep)
      } else if (isSet || stopping) values.filterInPlace(p => !(p eq ep))
      Option.when(values.nonEmpty)(channel -> values)
    }
    subscriptions ++= requested.filterNot(subscriptions.contains).map(_ -> ListBuffer(ep))

    Subscribed(subscriptions.iterator.collect {
      case (channel, values) if values.exists(_ eq ep) => channel
    }.toSet)
  }

  private def updateSubs(ep: EventProcessor,
                         channels: Set[String],
                         isSet: Boolean): Boolean = {

    val updates = updateSubImpl(ep, channels, isSet)
    ep ! updates
  }

  def setSubscriptions(ep: EventProcessor, channels: Set[String]): Subscribed =
    updateSubImpl(ep, channels, true)

  def subscribe(ep: EventProcessor, channels: Set[String]): Subscribed =
    updateSubImpl(ep, channels, false)

  def unsubscribeAll(ep: EventProcessor): Subscribed =
    updateSubImpl(ep, Set.empty, isSet = true)

  def unsubscribe(ep: EventProcessor, channels: Set[String]): Subscribed =
    unsubscribeImpl(ep, channels)

  def subscribe(am: EventProcessor, channel: String): Subscribed =
    subscribe(am, Set(channel))

  def broadcast(am: EventProcessor, channels: Set[String], msg: Any): Unit = {
    self ! Broadcast(am, channels, msg)
  }

  def unsubscribe(am: EventProcessor, channels: String*): Subscribed =
    unsubscribe(am, channels.toSet)

  override protected val onEvent: EventHandler = {

    case b@Broadcast(sendr, targetChannels, a) =>
      // Snapshot the target subscribers under `lock` (copying each ListBuffer),
      // then deliver from the immutable copy OUTSIDE the lock. This is the fix
      // for the iterate-while-mutate CME: a concurrent subscribe/unsubscribe can
      // no longer mutate a buffer we are iterating, and we never hold the lock
      // while posting (`ep ! a`), so delivery can't stall or deadlock the lock.
      // Per-channel duplicate delivery (an ep on N matched channels gets N
      // copies) is preserved, matching the previous behaviour.
      val targets: List[EventProcessor] = lock.synchronized {
        subscriptions.iterator.collect {
          case (k, values) if targetChannels.contains(k) => values.toList
        }.flatten.toList
      }
      targets.foreach { ep =>
        val ok = ep ! a
        if (!ok) {
          sendr ! NotDelivered(ep, b)
        }
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
