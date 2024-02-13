package sss.events

import org.scalatest.concurrent.ScalaFutures.convertScalaFuture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Promise

/**
  * Created by alan on 2/11/16.
  */
class CreateProcessorSpec extends AnyFlatSpec with Matchers {

  implicit val sut: EventProcessingEngine = EventProcessingEngine()
  sut.start(1)

  "EventEngine" should "send messages " in {
    val isGood = Promise[Boolean]()
    sut.newEventProcessor(ep => {
      case "S" => ep.become {
        case "E" =>
            isGood.success(true)
      }
      ep ! "E"
    }) ! "S"
    assert(isGood.future.futureValue, "What?")
  }

}