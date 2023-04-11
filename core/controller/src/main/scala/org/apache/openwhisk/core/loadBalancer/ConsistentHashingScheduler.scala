package org.apache.openwhisk.core.loadBalancer
import org.apache.openwhisk.common.{Logging, NestedSemaphore, TransactionId}
import org.apache.openwhisk.core.connector.ActivationMessage
import org.apache.openwhisk.core.entity.{ExecutableWhiskActionMetaData, FullyQualifiedEntityName, InvokerInstanceId}

object ConsistentHashingScheduler extends CommonScheduler {

  override  def scheduleFunction(
    action: ExecutableWhiskActionMetaData,
    msg: ActivationMessage,
    invokers: IndexedSeq[InvokerEnergyHealth],
    dispatched: IndexedSeq[NestedSemaphore[FullyQualifiedEntityName]],
    stepSizes: Seq[Int])(implicit logging: Logging, transId: TransactionId): Option[(InvokerInstanceId, Boolean)] = {

    val functionHash = getFunctionHash(msg.user.namespace.name, action.fullyQualifiedName(false))
    val step = stepSizes(functionHash % stepSizes.size)
    val fqn = action.fullyQualifiedName(true)
    val numInvokers = invokers.size
    val maxConcurrent = action.limits.concurrency.maxConcurrent
    val slots = action.limits.memory.megabytes

    if (numInvokers > 0) {
      val sortedInvokers: IndexedSeq[InvokerEnergyHealth] = invokers.sortBy { invoker =>
        val invokerHash = getInvokerHash(invoker.id)
        val distance = if (invokerHash <= functionHash) {
          functionHash - invokerHash
        } else {
          numBuckets - (invokerHash - functionHash)
        }
        distance
      }
      schedule(maxConcurrent, fqn, sortedInvokers, dispatched, slots, 0, step)
    } else {
      None
    }
  }
}
