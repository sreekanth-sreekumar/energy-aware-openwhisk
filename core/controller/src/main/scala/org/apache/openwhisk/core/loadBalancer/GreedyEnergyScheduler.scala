package org.apache.openwhisk.core.loadBalancer

import org.apache.openwhisk.common.{Logging, NestedSemaphore, TransactionId}
import org.apache.openwhisk.core.entity.{FullyQualifiedEntityName, InvokerInstanceId}

object GreedyEnergyScheduler extends CommonScheduler {

  override def scheduleFunction(
    activationRecord: ActivationRecord,
    invokers: IndexedSeq[InvokerEnergyHealth],
    dispatched: IndexedSeq[NestedSemaphore[FullyQualifiedEntityName]],
    stepSizes: Seq[Int])(implicit logging: Logging, transId: TransactionId): Option[(InvokerInstanceId, Boolean)] = {

    val numInvokers = invokers.size
    val functionHash = getFunctionHash(activationRecord.msg.user.namespace.name, activationRecord.fullyQualifiedEntityName)
    val maxConcurrent = activationRecord.maxConcurrent
    val fqn = activationRecord.fullyQualifiedEntityName
    val slots = activationRecord.memoryLimit
    val step = stepSizes(functionHash % stepSizes.size)

    /* Invoker weight is the ratio of total energy available to all invokers to the curr invoker energy*/
    if (numInvokers > 0) {
      val totalEnergy = invokers.foldLeft(0.0)((acc, invoker) => acc + invoker.energyProfile.currBat + invoker.energyProfile.panelOutput)
      /* Sorting based on totalEnergy available for all invokers */
      val sortedInvokers = invokers.sortBy {invoker => -(invoker.energyProfile.currBat + invoker.energyProfile.panelOutput)/totalEnergy}
      schedule(maxConcurrent, fqn, sortedInvokers, dispatched, slots, 0, step)
    } else {
      None
    }


  }
}
