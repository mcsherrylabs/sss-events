package sss.events.events

import sss.events.events.EventProcessor.EventProcessorId

import scala.collection.concurrent.TrieMap

class Registrar {

  private val registrarMap = TrieMap[EventProcessorId, EventProcessor]()

  private[events] def register(target: EventProcessor): Unit = {
    registrarMap.put(target.id, target)
  }

  private[events] def unRegister(id: EventProcessorId): Unit = {
    registrarMap -= id
  }

  def get[T](id: EventProcessorId): Option[EventProcessor] = {
    registrarMap.get(id)
  }

  def post(ids: Seq[EventProcessorId], msg: Any): Unit =
    ids.foreach(post(_, msg))

  def post(id: EventProcessorId, msg: Any): Boolean = {
    val foundOpt = get[Any](id)
    val found = foundOpt.getOrElse(throwEventEx(s"No such id ($id)"))
    found.post(msg)

  }

}
