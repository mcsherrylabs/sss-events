package sss

import scala.concurrent.{ExecutionContext, Future}

package object events {

  case class EventException(err: String) extends RuntimeException(err)

  def throwEventEx[T](err: String): T = throw EventException(err)

  object Ops {
    implicit class FutToEvent[T](val f: Future[T]) extends AnyVal {
      def postResultAsEvent(implicit am: EventProcessor, ec: ExecutionContext): Unit = {
        f.map(r => am.post(r)) recover {
          case e => am.post(e)
        }
      }
    }
  }
}
