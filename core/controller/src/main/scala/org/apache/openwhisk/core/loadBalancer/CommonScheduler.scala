package org.apache.openwhisk.core.loadBalancer

import org.apache.openwhisk.common.{Logging, NestedSemaphore, TransactionId}
import org.apache.openwhisk.core.entity.{EntityName, FullyQualifiedEntityName, InvokerInstanceId}

import java.util.concurrent.ThreadLocalRandom

trait InvokerScheduler {
  def getInvokerHash(invoker: InvokerInstanceId): Int
  def getFunctionHash(namespace: EntityName, action: FullyQualifiedEntityName): Int
  def scheduleFunction(
    activationRecord: ActivationRecord,
    invokers: IndexedSeq[InvokerEnergyHealth],
    dispatched: IndexedSeq[NestedSemaphore[FullyQualifiedEntityName]],
    stepSizes: Seq[Int])(implicit logging: Logging, transId: TransactionId): Option[(InvokerInstanceId, Boolean)]
}


abstract class CommonScheduler extends InvokerScheduler {

  protected val bucketSize = 1000

  /* Get invoker has from instance id and maps it to a 360 bucketSize*/
  override def getInvokerHash(invoker: InvokerInstanceId): Int = {
    val hash = invoker.hashCode().abs % bucketSize
    hash
  }

  override def getFunctionHash(namespace: EntityName, action: FullyQualifiedEntityName): Int = {
    val hash = (namespace.asString.hashCode() ^ action.asString.hashCode()).abs % bucketSize
    hash
  }

  def schedule(maxConcurrent: Int,
    fqn: FullyQualifiedEntityName,
    invokers: IndexedSeq[InvokerEnergyHealth],
    dispatched: IndexedSeq[NestedSemaphore[FullyQualifiedEntityName]],
    slots: Int,
    index: Int,
    step: Int,
    stepsDone: Int = 0)(implicit logging: Logging, transId: TransactionId): Option[(InvokerInstanceId, Boolean)] = {

    val numInvokers = invokers.size
    val invoker = invokers(index)
    //test this invoker - if this action supports concurrency, use the scheduleConcurrent function
    if (invoker.status.isUsable && dispatched(invoker.id.toInt).tryAcquireConcurrent(fqn, maxConcurrent, slots)) {
      Some(invoker.id, false)
    } else {
      // If we've gone through all invokers
      if (stepsDone == numInvokers + 1) {
        val healthyInvokers = invokers.filter(_.status.isUsable)
        if (healthyInvokers.nonEmpty) {
          // Choose a healthy invoker randomly
          val random = healthyInvokers(ThreadLocalRandom.current().nextInt(healthyInvokers.size)).id
          dispatched(random.toInt).forceAcquireConcurrent(fqn, maxConcurrent, slots)
          logging.warn(this, s"system is overloaded. Chose invoker${random.toInt} by random assignment.")
          Some(random, true)
        } else {
          None
        }
      } else {
        val newIndex = (index + step) % numInvokers
        schedule(maxConcurrent, fqn, invokers, dispatched, slots, newIndex, step, stepsDone + 1)
      }
    }
  }
}

object InvokerScheduler {

  def getScheduler(lbConfig: EnergyAwarePoolBalancerConfig): CommonScheduler = {
    lbConfig.scheduler match {
      case "consistent-hashing" => ConsistentHashingScheduler
      case "greedy" => GreedyEnergyScheduler
      case "weighted-dist" => WeightedConsistentHashingScheduler
    }
  }
}