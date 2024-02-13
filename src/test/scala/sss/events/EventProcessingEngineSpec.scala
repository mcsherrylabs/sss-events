package sss.events

import org.scalatest.concurrent.ScalaFutures.convertScalaFuture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sss.events.TestEventProcessor.{CompleteTest, StartTest}

import scala.concurrent.Promise
/**
  * Created by alan on 2/11/16.
  */
class EventProcessingEngineSpec extends AnyFlatSpec with Matchers {

  implicit val sut: EventProcessingEngine = EventProcessingEngine()
  sut.start(1)
  val test = new TestEventProcessor()
  val testMsg = "test"

  "EventEngine" should "send messages " in {

    val p: Promise[List[Any]] = Promise()
    test ! StartTest(p)
    test ! testMsg
    test ! CompleteTest
    val messages = p.future.futureValue
    assert(messages == List(testMsg))
  }

  it should "send by id" in {
    val p: Promise[List[Any]] = Promise()
    sut.registrar.post(test.id, StartTest(p))
    sut.registrar.post(test.id, testMsg)
    sut.registrar.post(test.id, CompleteTest)
    val messages = p.future.futureValue
    assert(messages == List(testMsg))
  }

}
