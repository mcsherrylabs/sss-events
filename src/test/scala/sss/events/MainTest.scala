package sss.events

object MainTest {

  def main(args: Array[String]): Unit = {

    val engine = EventProcessingEngine()

    val newEp = new EventProcessorSupport {
      var count = 0
      override def onEvent(self: EventProcessor, event: Any): Unit = event match {
        case "SEND" if count % 30 == 0 => {
          println(s"SEND! count = $count Q: ${self.currentQueueSize}")
          count += 1
          self ! "SEND"
        }
        case "SEND" =>
          count += 1
          self ! "SEND"

      }
    }

    engine.newEventProcessor(newEp) ! "SEND"

    engine.start()
  }
}
