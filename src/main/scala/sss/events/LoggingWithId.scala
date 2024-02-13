package sss.events

import com.typesafe.scalalogging.Logger

trait Logging {
  lazy val log: Logger = Logger(this.getClass)
}

object LogFactory extends Logging {

  def getLogger(category: String): Logger = {
    Logger(category)
  }

}

trait LoggingWithId extends Logging {

  def id: String

  def logDebug(s: String): Unit = log.debug(s)

  def logInfo(s: String): Unit = log.info(s)
  def logInfo(idOnly: String, s: String): Unit = if(id == idOnly) logInfo(s)

  def logError(s: String): Unit = log.error(s)

  def logWarn(s: String): Unit = log.warn(s)

}
