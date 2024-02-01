package sss.events.events

import sss.events.events.Subscriptions.{Broadcast, SetSubscription, Subscribe, UnSubscribe, UnSubscribeAll}

trait SubscriptionSupport {

  protected def  subscriptions: Subscriptions

  def subscribe(am: EventProcessor, channels: Set[String]): Unit = {
    subscriptions ! Subscribe(am, channels)
  }

  def subscribe(am: EventProcessor, channel: String): Unit = subscribe(am, Set(channel))

  def setSubscriptions(am: EventProcessor, channels: Set[String]): Unit = {
    subscriptions ! SetSubscription(am, channels)
  }

  def broadcast(am: EventProcessor, channels: Set[String], msg: Any): Unit = {
    subscriptions ! Broadcast(am, channels, msg)
  }

  def unsubscribe(am: EventProcessor, channels: Set[String]): Unit = {
    subscriptions ! UnSubscribe(am, channels)
  }

  def unsubscribe(am: EventProcessor, channels: String*): Unit = unsubscribe(am, channels.toSet)

  def unsubscribeAll(am: EventProcessor): Unit = {
    subscriptions ! UnSubscribeAll(am)
  }
}
