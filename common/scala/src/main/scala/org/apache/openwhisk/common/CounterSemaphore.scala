package org.apache.openwhisk.common

import akka.actor.ActorSystem

import java.util.concurrent.Semaphore
import scala.concurrent.{ExecutionContext, Future}

/*
*  A regular semaphore to handle a counter.
*  Maps the number of invocations assigned to each invoker every minute
* */

class CounterSemaphore(actorSystem: ActorSystem, invName: String) {

  implicit val ec: ExecutionContext = actorSystem.dispatcher
  private val semaphore = new Semaphore(1)
  private var counter = 0
  val invokerName: String = invName

  def increment(): Future[Unit] = Future {
    semaphore.acquire()
    counter += 1
    semaphore.release()
  }

  def reset(): Future[Unit] = Future {
    semaphore.acquire()
    counter = 0
    semaphore.release()
  }

  def read(): Int = {
    semaphore.acquire()
    val result = counter
    semaphore.release()
    result
  }


}
