package org.skyluc.aws.utils

import scala.collection.immutable.TreeMap

object Watcher {

  var items = TreeMap[Long, List[WatchItem]]()

  var thread: Option[WatcherThread] = None

  val threadLock = new Object()

  def toWatch(item: WatchItem) {
    threadLock.synchronized{
      addToBucket(System.currentTimeMillis(), item)
      threadLock.notify()
      if (!thread.isDefined) {
        val newThread = new WatcherThread()
        thread = Some(newThread)
        newThread.start()
      }
    }
  }

  /**
   * Not synchronized. To call inside a block synchronized on `threadLock`.
   */
  private def addToBucket(baseTime: Long, item: org.skyluc.aws.utils.WatchItem) = {
    val bucketTime = baseTime / 1000 + item.delay
    items.get(bucketTime) match {
      case Some(bucket) =>
        items = items + ((bucketTime, item :: bucket))
      case None =>
        items = items + ((bucketTime, List(item)))
    }
  }

  class WatcherThread extends Thread("watcher") {
    override def run() {
      while (true) {
        threadLock.synchronized{
          if (items.isEmpty) {
            threadLock.wait()
          } else {
            val firstKey = items.firstKey
            val firstTimeMillis =  firstKey * 1000
            val currentTime = System.currentTimeMillis()
            if (currentTime < firstTimeMillis) {
              threadLock.wait(firstTimeMillis - currentTime)
            } else {
              val itemsToCheck = items(firstKey)
              items = items - firstKey
              for (
                item <- itemsToCheck
              ) {
                if (item.check()) {
                  item.effect()
                } else {
                  addToBucket(currentTime, item)
                }
              }
            }
          }
        }
      }
    }
  }
}


// TODO: maxDelay is not used
case class WatchItem(delay: Int, maxDelay: Int)(val check: () => Boolean)(val effect: () => Unit)