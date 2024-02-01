package sss.events

import org.scalatest.concurrent.ScalaFutures.convertScalaFuture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sss.events.TestEventProcessor.{CompleteTest, StartBroadcastTest, StartSubTest, StartTest}
import sss.events.events.EventProcessingEngine
import sss.events.events.Subscriptions.Subscribed

import scala.concurrent.Promise

/**
  * Created by alan on 2/11/16.
  */
class SubscriptionsSpec extends AnyFlatSpec with Matchers {

  implicit val sut: EventProcessingEngine = EventProcessingEngine()
  sut.start(1)
  val test = new TestEventProcessor()
  val testMsg = "test"
  val subs: Set[String] = Set("sub1")

  "EventEngine" should "sub to channel" in {
    val p: Promise[List[Subscribed]] = Promise()
    test.setSubscriptions(subs)
    test ! StartSubTest(p)

    val messages = p.future.futureValue
    assert(messages == List(Subscribed(subs)))
  }

  it should "allow unsub" in {
    val subs: Set[String] = Set("sub1", "sub2")
    val p: Promise[List[Subscribed]] = Promise()
    test ! StartSubTest(p, 2)
    test.setSubscriptions(subs)
    test.unsubscribe("sub1")

    val messages = p.future.futureValue
    assert(messages == List(Subscribed(Set("sub1", "sub2")), Subscribed(Set("sub2"))))
  }

  it should "support broadcast" in {
    val subs: Set[String] = Set("sub1", "sub2")
    val p: Promise[List[Any]] = Promise()
    val s: Promise[Unit] = Promise()
    test ! StartBroadcastTest(s, p)
    test.setSubscriptions(subs)
    s.future.futureValue

    test.broadcast(subs, testMsg)
    val other = "ot"
    test.broadcast("sub2", other)
    test.broadcast("a channel we're not listening to", other)
    test.broadcast("sub1", CompleteTest)

    val messages = p.future.futureValue
    assert(messages == List(testMsg, testMsg, other))
  }
}
