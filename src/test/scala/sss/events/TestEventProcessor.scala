package sss.events

import sss.events.TestEventProcessor.{CompleteTest, StartBroadcastTest, StartSubTest, StartTest}
import sss.events.events.EventProcessor.{EventHandler, EventProcessorId}
import sss.events.events.Subscriptions.Subscribed
import sss.events.events.{EventProcessingEngine, EventProcessor}

import scala.concurrent.{Future, Promise}

object TestEventProcessor {
  case object CompleteTest
  case class StartTest(p: Promise[List[Any]])
  case class StartSubTest(p: Promise[List[Subscribed]], expectedCount: Int = 1)
  case class StartBroadcastTest(s: Promise[Unit], p: Promise[List[Any]])
}

class TestEventProcessor(implicit engine: EventProcessingEngine) extends EventProcessor {

  private var messages: List[Any] = List.empty

  override protected val onEvent: EventHandler = {
    case StartSubTest(p, c) =>
      messages = List.empty
      become(subTest(p, c, List.empty) orElse keepMessages)

    case StartTest(p) =>
      messages = List.empty
      become(test(p) orElse keepMessages)

    case StartBroadcastTest(s, p) =>
      become(subBroadcastTest(s, p) orElse keepMessages)

  }

  val keepMessages: EventHandler = {
    case msg =>
      messages = messages :+ msg
  }

  def subBroadcastTest(subed: Promise[Unit], p: Promise[List[Any]]): EventHandler = {
    case s: Subscribed =>
        subed.success(())

    case CompleteTest =>
      p.success(messages)
      unbecome()
  }

  def subTest(
               completePromise: Promise[List[Subscribed]],
               expectedCount: Int,
               received: List[Subscribed]): EventHandler = {
    case s: Subscribed if expectedCount == received.size + 1 =>
      completePromise.success(received :+ s)
      unbecome()

    case s: Subscribed =>
      become(subTest(completePromise, expectedCount, received :+ s), false)

  }

  def test(completePromise: Promise[List[Any]]): EventHandler = {
    case CompleteTest =>
      completePromise.success(messages)
      unbecome()

  }
}
