package org.apache.openwhisk.core.loadBalancer

import org.apache.openwhisk.common.{Logging, NestedSemaphore, TransactionId}
import org.apache.openwhisk.core.connector.ActivationMessage
import org.apache.openwhisk.core.entity.{ExecutableWhiskActionMetaData, FullyQualifiedEntityName, InvokerInstanceId}

object GreedyEnergyScheduler extends CommonScheduler {

  override def scheduleFunction(
    action: ExecutableWhiskActionMetaData,
    msg: ActivationMessage,
    invokers: IndexedSeq[InvokerEnergyHealth],
    dispatched: IndexedSeq[NestedSemaphore[FullyQualifiedEntityName]],
    stepSizes: Seq[Int])(implicit logging: Logging, transId: TransactionId): Option[(InvokerInstanceId, Boolean)] = {

    val numInvokers = invokers.size
    val functionHash = getFunctionHash(msg.user.namespace.name, action.fullyQualifiedName(false))
    val maxConcurrent = action.limits.concurrency.maxConcurrent
    val fqn = action.fullyQualifiedName(true)
    val slots = action.limits.memory.megabytes
    val step = stepSizes(functionHash % stepSizes.size)

    if (numInvokers > 0) {
      /* Sorting based on totalEnergy available for all invokers */
      val sortedInvokers = invokers.sortBy {invoker => -invoker.totalEnergy}
      schedule(maxConcurrent, fqn, sortedInvokers, dispatched, slots, 0, step)
    } else {
      None
    }


  }
}
