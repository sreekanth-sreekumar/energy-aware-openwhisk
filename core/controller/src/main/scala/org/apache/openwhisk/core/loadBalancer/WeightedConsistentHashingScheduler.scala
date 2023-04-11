package org.apache.openwhisk.core.loadBalancer

import org.apache.openwhisk.common.{Logging, NestedSemaphore, TransactionId}
import org.apache.openwhisk.core.connector.ActivationMessage
import org.apache.openwhisk.core.entity.{ExecutableWhiskActionMetaData, FullyQualifiedEntityName, InvokerInstanceId}

class WeightedConsistentHashingScheduler extends CommonScheduler {

  def getMod(a: Int): Int = a - a.floor.toInt

  override def scheduleFunction(action: ExecutableWhiskActionMetaData,
    msg: ActivationMessage,
    invokers: IndexedSeq[InvokerEnergyHealth],
    dispatched: IndexedSeq[NestedSemaphore[FullyQualifiedEntityName]],
    stepSizes: Seq[Int])(implicit logging: Logging, transId: TransactionId): Option[(InvokerInstanceId, Boolean)] = {

      val fqn = action.fullyQualifiedName(true)
      val functionHash = getFunctionHash(msg.user.namespace.name, action.fullyQualifiedName(false))
      val step = stepSizes(functionHash % stepSizes.size)
      val numInvokers = invokers.size
      val maxConcurrent = action.limits.concurrency.maxConcurrent
      val slots = action.limits.memory.megabytes

      if (numInvokers > 0) {
        val sortedInvokers = invokers.sortBy { invoker =>
          /* Performs weight dist hashing */
          val invokerHash = getInvokerHash(invoker.id)
          -math.log(getMod(1-(functionHash - invokerHash)))/invoker.totalEnergy
        }
        schedule(maxConcurrent, fqn, sortedInvokers, dispatched, slots, 0, step)
      } else {
        None
      }
  }
}
