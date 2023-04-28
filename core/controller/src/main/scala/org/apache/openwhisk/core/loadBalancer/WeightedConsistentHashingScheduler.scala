package org.apache.openwhisk.core.loadBalancer

import org.apache.openwhisk.common.{Logging, NestedSemaphore, TransactionId}
import org.apache.openwhisk.core.entity.{FullyQualifiedEntityName, InvokerInstanceId}

object WeightedConsistentHashingScheduler extends CommonScheduler {

  def getDifference(funcHash: Int, invHash: Int): Double = {
    ((funcHash.toDouble/bucketSize.toDouble) - (invHash.toDouble/bucketSize.toDouble)).abs
  }

  override def scheduleFunction(activationRecord: ActivationRecord,
    invokers: IndexedSeq[InvokerEnergyHealth],
    dispatched: IndexedSeq[NestedSemaphore[FullyQualifiedEntityName]],
    stepSizes: Seq[Int])(implicit logging: Logging, transId: TransactionId): Option[(InvokerInstanceId, Boolean)] = {

      val fqn = activationRecord.fullyQualifiedEntityName
      val functionHash = getFunctionHash(activationRecord.msg.user.namespace.name, activationRecord.fullyQualifiedEntityName)
      val step = stepSizes(functionHash % stepSizes.size)
      val numInvokers = invokers.size
      val maxConcurrent = activationRecord.maxConcurrent
      val slots = activationRecord.memoryLimit

    /* Invoker weight is the ratio of total energy available to all invokers to the curr invoker energy*/
      if (numInvokers > 0) {
        val totalEnergy = invokers.foldLeft(0.0)((acc, invoker) => acc + invoker.energyProfile.currBat + invoker.energyProfile.panelOutput)
        val sortedInvokers = invokers.sortBy { invoker =>
          /* Scaling hashes between 0 and 1 */
          val invokerHash = getInvokerHash(invoker.id)
          -totalEnergy * math.log((1 - getDifference(functionHash, invokerHash)))/(invoker.energyProfile.currBat + invoker.energyProfile.panelOutput)
        }
        schedule(maxConcurrent, fqn, sortedInvokers, dispatched, slots, 0, step)
      } else {
        None
      }
  }
}
