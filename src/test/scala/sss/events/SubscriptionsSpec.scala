package sss.events

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.ScalaFutures.convertScalaFuture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import sss.events.Subscriptions.Subscribed
import sss.events.TestEventProcessor.{CompleteTest, StartBroadcastTest, StartSubTest}

import scala.concurrent.Promise

/**
  * Created by alan on 2/11/16.
  */
class SubscriptionsSpec extends AnyFlatSpec with Matchers with ScalaFutures {

  implicit val sut: EventProcessingEngine = EventProcessingEngine()
  sut.start(1)
  val test = new TestEventProcessor()
  val testMsg = "test"
  val subs: Set[String] = Set("sub1")


  "EventEngine" should "sub all to channel" in {

    val Subscribed(newSubs) = test.setSubscriptions(subs)

    assert(newSubs == subs)
  }

  it should "sub to channel" in {
    val p: Promise[List[Subscribed]] = Promise()
    test.setSubscriptions(subs)
    val Subscribed(latest) = test.subscribe("another")

    assert(latest == subs + "another")
  }

  it should "allow unsub" in {
    val subs: Set[String] = Set("sub1", "sub2")
    val p: Promise[List[Subscribed]] = Promise()

    test.setSubscriptions(subs)
    val Subscribed(latest) = test.unsubscribe("sub1")

    assert(latest == Set("sub2"))
  }

  it should "support broadcast" in {
    val subs: Set[String] = Set("sub1", "sub2")
    val p: Promise[List[Any]] = Promise()
    val s: Promise[Unit] = Promise()
    test ! StartBroadcastTest(s, p)
    test.setSubscriptions(subs)
    //s.future.futureValue

    test.broadcast(subs, testMsg)
    val other = "ot"
    test.broadcast("sub2", other)
    test.broadcast("a channel we're not listening to", other)
    test.broadcast("sub1", CompleteTest)

    val messages = p.future.futureValue
    assert(messages == List(testMsg, testMsg, other))
  }
}
