package org.apache.openwhisk.core.loadBalancer
import org.apache.openwhisk.common.{Logging, NestedSemaphore, TransactionId}
import org.apache.openwhisk.core.entity.{FullyQualifiedEntityName, InvokerInstanceId}

object ConsistentHashingScheduler extends CommonScheduler {

  override  def scheduleFunction(
    activationRecord: ActivationRecord,
    invokers: IndexedSeq[InvokerEnergyHealth],
    dispatched: IndexedSeq[NestedSemaphore[FullyQualifiedEntityName]],
    stepSizes: Seq[Int])(implicit logging: Logging, transId: TransactionId): Option[(InvokerInstanceId, Boolean)] = {

    val functionHash = getFunctionHash(activationRecord.msg.user.namespace.name, activationRecord.fullyQualifiedEntityName)
    logging.info(this, s"NamespaceName: ${activationRecord.msg.user.namespace.name.asString}" )
    logging.info(this, s"FullyQualifiedEntityName: ${activationRecord.fullyQualifiedEntityName.asString}" )
    logging.info(this, s"FunctionHash: ${functionHash}" )
    val step = stepSizes(functionHash % stepSizes.size)
    val fqn = activationRecord.fullyQualifiedEntityName
    val numInvokers = invokers.size
    val maxConcurrent = activationRecord.maxConcurrent
    val slots = activationRecord.memoryLimit

    if (numInvokers > 0) {
      val sortedInvokers: IndexedSeq[InvokerEnergyHealth] = invokers.sortBy { invoker =>
        val invokerHash = getInvokerHash(invoker.id)
        val distance = if (invokerHash <= functionHash) {
          functionHash - invokerHash
        } else {
          bucketSize - (invokerHash - functionHash)
        }
        distance
      }
      schedule(maxConcurrent, fqn, sortedInvokers, dispatched, slots, 0, step)
    } else {
      None
    }
  }
}
