package org.apache.openwhisk.core.loadBalancer

import akka.actor.{Actor, ActorRef, ActorRefFactory, ActorSystem, Cancellable, Props}
import akka.stream.ActorMaterializer
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.openwhisk.common.LoggingMarkers._
import org.apache.openwhisk.common.{CounterSemaphore, Logging, LoggingMarkers, MetricEmitter, NestedSemaphore, Scheduler, TransactionId}
import org.apache.openwhisk.core.WhiskConfig.kafkaHosts
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.core.connector.{ActivationMessage, MessageProducer, MessagingProvider}
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size.SizeLong
import org.apache.openwhisk.core.loadBalancer.InvokerState.{Healthy, Offline, Unhealthy, Unresponsive}
import org.apache.openwhisk.spi.SpiLoader
import pureconfig._
import pureconfig.generic.auto._

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success}


class EnergyAwareLoadBalancer(config: WhiskConfig, controllerInstance: ControllerInstanceId,
                                     feedFactory: FeedFactory, val invokerPoolFactory: InvokerPoolFactory,
                                     implicit val messagingProvider: MessagingProvider = SpiLoader.get[MessagingProvider])(
                                     implicit actorSystem: ActorSystem,
                                     logging: Logging,
                                     materializer: ActorMaterializer
                                   ) extends CommonLoadBalancer(config, feedFactory, controllerInstance) {

  override protected def emitMetrics() = {
    super.emitMetrics()
    MetricEmitter.emitGaugeMetric(
      INVOKER_TOTALMEM_BLACKBOX,
      schedulingState.blackboxInvokers.foldLeft(0L) { (total, curr) =>
        if (curr.status.isUsable) {
          curr.id.userMemory.toMB + total
        } else {
          total
        }
      })
    MetricEmitter.emitGaugeMetric(
      INVOKER_TOTALMEM_MANAGED,
      schedulingState.managedInvokers.foldLeft(0L) { (total, curr) =>
        if (curr.status.isUsable) {
          curr.id.userMemory.toMB + total
        } else {
          total
        }
      })
    MetricEmitter.emitGaugeMetric(HEALTHY_INVOKER_MANAGED, schedulingState.managedInvokers.count(_.status == Healthy))
    MetricEmitter.emitGaugeMetric(
      UNHEALTHY_INVOKER_MANAGED,
      schedulingState.managedInvokers.count(_.status == Unhealthy))
    MetricEmitter.emitGaugeMetric(
      UNRESPONSIVE_INVOKER_MANAGED,
      schedulingState.managedInvokers.count(_.status == Unresponsive))
    MetricEmitter.emitGaugeMetric(OFFLINE_INVOKER_MANAGED, schedulingState.managedInvokers.count(_.status == Offline))
    MetricEmitter.emitGaugeMetric(HEALTHY_INVOKER_BLACKBOX, schedulingState.blackboxInvokers.count(_.status == Healthy))
    MetricEmitter.emitGaugeMetric(
      UNHEALTHY_INVOKER_BLACKBOX,
      schedulingState.blackboxInvokers.count(_.status == Unhealthy))
    MetricEmitter.emitGaugeMetric(
      UNRESPONSIVE_INVOKER_BLACKBOX,
      schedulingState.blackboxInvokers.count(_.status == Unresponsive))
    MetricEmitter.emitGaugeMetric(OFFLINE_INVOKER_BLACKBOX, schedulingState.blackboxInvokers.count(_.status == Offline))
  }

  /** State needed for scheduling. */
  val schedulingState = EnergyAwarePoolBalancerState()(lbConfig)

  private val monitor = actorSystem.actorOf(Props(new Actor {
    override def receive: Receive = {
      case CurrentInvokerPoolState(newState: IndexedSeq[InvokerEnergyHealth], printLog: Boolean) =>
        schedulingState.updateInvokers(newState, printLog)
    }
  }))

  override val invokerPool =
    invokerPoolFactory.createInvokerPool(
      actorSystem,
      messagingProvider,
      messageProducer,
      sendActivationToInvoker,
      Some(monitor))

  /** Loadbalancer interface methods */
  override def invokerHealth(): Future[IndexedSeq[InvokerEnergyHealth]] = Future.successful(schedulingState.invokers)

  override protected def releaseInvoker(invoker: InvokerInstanceId, entry: ActivationEntry) = {
    schedulingState.invokerSlots
      .lift(invoker.toInt)
      .foreach(_.releaseConcurrent(entry.activation.fullyQualifiedEntityName, entry.activation.maxConcurrent, entry.activation.memoryLimit))
  }

  val invokerScheduler: CommonScheduler = InvokerScheduler.getScheduler(lbConfig)
  val redisConf: RedisConfig = loadConfigOrThrow[RedisConfig](ConfigKeys.redisConf)
  protected[loadBalancer] val redisDataStore: Option[RedisAwarePriorityQueue] = RedisAwarePriorityQueue.instance(redisConf, "priority-queue")(logging)
  if (!redisDataStore.isEmpty) {
    logging.info(this, "Starting redis store scheduling")
    Scheduler.scheduleWaitAtLeast(redisConf.pollInterval)(pullMessagesFromRedisQueue)
  }

  override def publish(action: ExecutableWhiskActionMetaData, msg: ActivationMessage, fromOutside: Boolean = true)(
    implicit transid: TransactionId): Future[Future[Either[ActivationId, WhiskActivation]]] = {

    val isBlackboxInvocation = action.exec.pull
    val activationRecord = ActivationRecord(
        msg,
        action.limits.memory.megabytes,
        action.limits.timeout.duration,
        action.limits.concurrency.maxConcurrent,
        action.fullyQualifiedName(true),
        isBlackboxInvocation)

    publishFunction(activationRecord, fromOutside)

  }

  def periodicLogging(): Future[Unit] = {
    val currState = schedulingState
    logging.info(this,
      s"Current invoker energy map :=: ${currState.getAllInvokerEnergy()}")
    logging.info(this,
      s"Current invoker excess energy map :=: ${currState.getAllInvokerExcessEnergy()}")
    logging.info(this, s"Current invoker distribution map :=: ${currState.readAllInvokerDist()}")
    schedulingState.resetAllInvokerDist()
  }

  Scheduler.scheduleWaitAtLeast(1.minutes)(() => periodicLogging())

  /** 1. Publish a message to the loadbalancer */
  def publishFunction(activationRecord: ActivationRecord, fromOutside: Boolean = true)(
    implicit transid: TransactionId): Future[Future[Either[ActivationId, WhiskActivation]]] = {

    val actionType = if (!activationRecord.isBlackbox) "managed" else "blackbox"
    val (invokersToUse, stepSizes) =
      if (!activationRecord.isBlackbox) (schedulingState.managedInvokers, schedulingState.managedStepSizes)
      else (schedulingState.blackboxInvokers, schedulingState.blackboxStepSizes)
    val chosen = {
      val invoker = invokerScheduler.scheduleFunction(activationRecord, invokersToUse,
        schedulingState.invokerSlots, stepSizes)(logging, transid)
      invoker.foreach {
        case (_, true) =>
          val metric =
            if (activationRecord.isBlackbox)
              LoggingMarkers.BLACKBOX_SYSTEM_OVERLOAD
            else
              LoggingMarkers.MANAGED_SYSTEM_OVERLOAD
          MetricEmitter.emitCounterMetric(metric)
        case _ =>
      }
      invoker.map(_._1)
    }
    chosen
      .map { invoker =>
        // MemoryLimit() and TimeLimit() return singletons - they should be fast enough to be used here
        val memoryLimit = activationRecord.memoryLimit
        val memoryLimitInfo = if (memoryLimit == MemoryLimit().megabytes) {
          "std"
        } else {
          "non-std"
        }
        val timeLimit = activationRecord.timeLimit
        val timeLimitInfo = if (timeLimit == TimeLimit().duration) {
          "std"
        } else {
          "non-std"
        }

        logging.info(this, s"Scheduled activation on: ${invoker} ")
        schedulingState.increment(invoker.toInt)
        logging.info(
          this,
          s"scheduled activation ${activationRecord.msg.activationId}, action '${activationRecord.msg.action.asString}' ($actionType), ns '${activationRecord.msg.user.namespace.name.asString}', mem limit ${memoryLimit.megabytes} MB (${memoryLimitInfo}), time limit ${timeLimit.toMillis} ms (${timeLimitInfo}) to ${invoker}")
        val activationResult = setupActivation(activationRecord, invoker)
        sendActivationToInvoker(messageProducer, activationRecord.msg, invoker).map(_ => activationResult)
      }
      .getOrElse {
        // report the state of all invokers
        val invokerStates = invokersToUse.foldLeft(Map.empty[InvokerState, Int]) { (agg, curr) =>
          val count = agg.getOrElse(curr.status, 0) + 1
          agg + (curr.status -> count)
        }

        logging.warn(
          this,
          s"failed to schedule activation ${activationRecord.msg.activationId}, action '${activationRecord.msg.action.asString}' ($actionType), ns '${activationRecord.msg.user.namespace.name.asString}' - " +
            s"invokers to use: $invokerStates now. Will try again later for a max of ${redisConf.maxRetries}")
        if(fromOutside) {
          redisDataStore.map(store => store.addToQueue(Left(activationRecord)).andThen {
            case Success(_) =>
              logging.error(this, s"No invokers available right now for ${activationRecord.msg.activationId}. Will try again until retry limit (${redisConf.maxRetries})")
              Future.failed(LoadBalancerException(s"No invokers available right now for ${activationRecord.msg.activationId}. Will try again until retry limit (${redisConf.maxRetries})"))
            case Failure(_) =>
              logging.error(this, s"No invokers available right now. Cannot add ${activationRecord.msg.activationId} to dataStore either")
              Future.failed(LoadBalancerException(s"No invokers available right now. Cannot add ${activationRecord.msg.activationId} to dataStore either"))
          })
        }
        Future.failed(LoadBalancerException(s"No invokers available right now"))
      }
  }

  def pullMessagesFromRedisQueue(): Future[Option[RedisActivationEntry]] = {
    redisDataStore match {
      case Some(store: RedisAwarePriorityQueue) => store.popQueue().andThen {
        case Success(activation) => activation match {
          case Some(act: RedisActivationEntry) =>
            logging.info(this ,s"Pulled an activation from the redis store ${act.activationRecord.msg.activationId}")
            publishFunction(act.activationRecord, false)(TransactionId.loadbalancer).andThen {
              case Failure(_) =>
                if (act.attempts + 1 == redisConf.maxRetries) {
                  logging.error(this, s"Max attempts reached to schedule ${act.activationRecord.msg.activationId}. Activation dropped")
                } else {
                  store.addToQueue(Right(act))
                }
            }
          case None =>
            logging.info(this, "No activations in data store")
            Future.failed(LoadBalancerException("No activations in data store"))
        }
        case _ =>
          logging.error(this, "Activations cannot be pulled from store")
          Future.failed(LoadBalancerException("Activations cannot be pulled from store"))
      }
      case _ =>
        logging.info(this, "No redis data store available")
        Future.failed(LoadBalancerException("No redis data store available"))
    }
  }
}

object EnergyAwareLoadBalancer extends LoadBalancerProvider {

  override def instance(whiskConfig: WhiskConfig, instance: ControllerInstanceId)(
    implicit actorSystem: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): LoadBalancer = {

    val invokerPoolFactory = new InvokerPoolFactory {
      override def createInvokerPool(
        actorRefFactory: ActorRefFactory,
        messagingProvider: MessagingProvider,
        messagingProducer: MessageProducer,
        sendActivationToInvoker: (MessageProducer, ActivationMessage, InvokerInstanceId) => Future[RecordMetadata],
        monitor: Option[ActorRef]): ActorRef = {

          InvokerPool.prepare(instance, WhiskEntityStore.datastore())

          actorRefFactory.actorOf(
            InvokerPool.props(
              (f, i) => f.actorOf(InvokerActor.props(i, instance)),
              (m, i) => sendActivationToInvoker(messagingProducer, m, i),
              messagingProvider.getConsumer(whiskConfig, s"energy${instance.asString}", "energyProfile", maxPeek = 128),
              monitor))
        }
    }
    new EnergyAwareLoadBalancer(
      whiskConfig,
      instance,
      createFeedFactory(whiskConfig, instance),
      invokerPoolFactory)
  }

  def requiredProperties: Map[String, String] = kafkaHosts

  /** Euclidean algorithm to determine the greatest-common-divisor */
  @tailrec
  def gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

  /** Returns pairwise coprime numbers until x. Result is memoized. */
  def pairwiseCoprimeNumbersUntil(x: Int): IndexedSeq[Int] = {
    if (x == 0) {
      return IndexedSeq.empty[Int]
    }
    (1 to x).foldLeft(IndexedSeq.empty[Int])((primes, cur) => {
      if (gcd(cur, x) == 1 && primes.forall(i => gcd(i, cur) == 1)) {
        primes :+ cur
      } else primes
    })
  }

}

/**
 * Holds the state necessary for scheduling of actions.
 *
 * @param _invokers all of the known invokers in the system
 * @param _managedInvokers all invokers for managed runtimes
 * @param _blackboxInvokers all invokers for blackbox runtimes
 * @param _managedStepSizes the step-sizes possible for the current managed invoker count
 * @param _blackboxStepSizes the step-sizes possible for the current blackbox invoker count
 * @param _invokerSlots state of accessible slots of each invoker
 */
case class EnergyAwarePoolBalancerState(
                                         private var _invokers: IndexedSeq[InvokerEnergyHealth] = IndexedSeq.empty[InvokerEnergyHealth],
                                         private var _managedInvokers: IndexedSeq[InvokerEnergyHealth] = IndexedSeq.empty[InvokerEnergyHealth],
                                         private var _blackboxInvokers: IndexedSeq[InvokerEnergyHealth] = IndexedSeq.empty[InvokerEnergyHealth],
                                         private var _managedStepSizes: Seq[Int] = EnergyAwareLoadBalancer.pairwiseCoprimeNumbersUntil(0),
                                         private var _blackboxStepSizes: Seq[Int] = EnergyAwareLoadBalancer.pairwiseCoprimeNumbersUntil(0),
                                         protected[loadBalancer] var _invokerSlots: IndexedSeq[NestedSemaphore[FullyQualifiedEntityName]] =
                                         IndexedSeq.empty[NestedSemaphore[FullyQualifiedEntityName]],
                                         protected var _invokerDist: IndexedSeq[CounterSemaphore] = IndexedSeq.empty[CounterSemaphore],
                                         private var _clusterSize: Int = 1)(
                                               lbConfig: EnergyAwarePoolBalancerConfig =
                                               loadConfigOrThrow[EnergyAwarePoolBalancerConfig](ConfigKeys.loadbalancer))
                                        (implicit logging: Logging, actorSystem: ActorSystem) {

  // Managed fraction and blackbox fraction can be between 0.0 and 1.0. The sum of these two fractions has to be between
  // 1.0 and 2.0.
  // If the sum is 1.0 that means, that there is no overlap of blackbox and managed invokers. If the sum is 2.0, that
  // means, that there is no differentiation between managed and blackbox invokers.
  // If the sum is below 1.0 with the initial values from config, the blackbox fraction will be set higher than
  // specified in config and adapted to the managed fraction.
  private val managedFraction: Double = Math.max(0.0, Math.min(1.0, lbConfig.managedFraction))
  private val blackboxFraction: Double = Math.max(1.0 - managedFraction, Math.min(1.0, lbConfig.blackboxFraction))
  logging.info(this, s"managedFraction = $managedFraction, blackboxFraction = $blackboxFraction")(
    TransactionId.loadbalancer)

  private implicit val ec: ExecutionContext = actorSystem.dispatcher

  /** Getters for the variables, setting from the outside is only allowed through the update methods below */
  def invokers: IndexedSeq[InvokerEnergyHealth] = _invokers
  def managedInvokers: IndexedSeq[InvokerEnergyHealth] = _managedInvokers
  def blackboxInvokers: IndexedSeq[InvokerEnergyHealth] = _blackboxInvokers
  def managedStepSizes: Seq[Int] = _managedStepSizes
  def blackboxStepSizes: Seq[Int] = _blackboxStepSizes
  def invokerSlots: IndexedSeq[NestedSemaphore[FullyQualifiedEntityName]] = _invokerSlots

  def invokerDist: IndexedSeq[CounterSemaphore] = _invokerDist
  def clusterSize: Int = _clusterSize

  /**
   * @param memory
   * @return calculated invoker slot
   */
  private def getInvokerSlot(memory: ByteSize): ByteSize = {
    val invokerShardMemorySize = memory / _clusterSize
    val newThreshold = if (invokerShardMemorySize < MemoryLimit.MIN_MEMORY) {
      logging.error(
        this,
        s"registered controllers: calculated controller's invoker shard memory size falls below the min memory of one action. "
          + s"Setting to min memory. Expect invoker overloads. Cluster size ${_clusterSize}, invoker user memory size ${memory.toMB.MB}, "
          + s"min action memory size ${MemoryLimit.MIN_MEMORY.toMB.MB}, calculated shard size ${invokerShardMemorySize.toMB.MB}.")(
        TransactionId.loadbalancer)
      MemoryLimit.MIN_MEMORY
    } else {
      invokerShardMemorySize
    }
    newThreshold
  }

  def increment(invokerId: Int): Future[Unit] = {
    invokerDist(invokerId).increment()
  }

  def resetAllInvokerDist(): Future[Unit] = {
    Future.traverse(_invokerDist)(counter => Future { counter.reset() }).map(_ => ())
  }

  def readAllInvokerDist(): Map[String, Int] = {
    val counts = _invokerDist.map(_.read())
    val distMap = _invokers.foldLeft(Map.empty[String, Int]) { (agg, curr) =>
      agg + (curr.id.toString -> counts(curr.id.toInt) )
    }
    distMap
  }

  def getAllInvokerEnergy(): Map[String, Double] = {
    val energyMap = invokers.foldLeft(Map.empty[String, Double]) { (agg, curr) =>
      val totalEnergy = curr.energyProfile.currBat + curr.energyProfile.panelOutput
      agg + (curr.id.toString -> totalEnergy)
    }
    energyMap
  }

  def getAllInvokerExcessEnergy(): Map[String, Double] = {
    val excessEnergyMap = invokers.foldLeft(Map.empty[String, Double]) { (agg, curr) =>
      agg + (curr.id.toString -> curr.energyProfile.excessEnergy)
    }
    excessEnergyMap
  }

  def updateInvokers(newInvokers: IndexedSeq[InvokerEnergyHealth], printLog: Boolean): Unit = {
    val oldSize = _invokers.size
    val newSize = newInvokers.size

    // for small N, allow the managed invokers to overlap with blackbox invokers, and
    // further assume that blackbox invokers << managed invokers
    val managed = Math.max(1, Math.ceil(newSize.toDouble * managedFraction).toInt)
//    val blackboxes = Math.max(1, Math.floor(newSize.toDouble * blackboxFraction).toInt)
    val blackboxes = 0
    _invokers = newInvokers
    _managedInvokers = _invokers.take(managed)
    _blackboxInvokers = _invokers.takeRight(blackboxes)

    val logDetail = if (oldSize != newSize) {

      if (oldSize < newSize) {

        _managedStepSizes = EnergyAwareLoadBalancer.pairwiseCoprimeNumbersUntil(managed)
        _blackboxStepSizes = EnergyAwareLoadBalancer.pairwiseCoprimeNumbersUntil(blackboxes)

        // Keeps the existing state..
        val onlyNewInvokers = _invokers.drop(_invokerSlots.length)
        _invokerSlots = _invokerSlots ++ onlyNewInvokers.map { invoker =>
          new NestedSemaphore[FullyQualifiedEntityName](getInvokerSlot(invoker.id.userMemory).toMB.toInt)
        }
        _invokerDist = _invokerDist ++ onlyNewInvokers.map { invoker =>
          new CounterSemaphore(actorSystem, invoker.id.toString)
        }
        val newInvokerDetails = onlyNewInvokers
          .map(i =>
            s"${i.id.toString}: ${i.status} / ${getInvokerSlot(i.id.userMemory).toMB.MB} of ${i.id.userMemory.toMB.MB}")
          .mkString(", ")
        s"number of known invokers increased: new = $newSize, old = $oldSize. details: $newInvokerDetails."
      } else {
        s"number of known invokers decreased: new = $newSize, old = $oldSize."
      }
    } else {
      s"no update required - number of known invokers unchanged: $newSize."
    }
    if (printLog) {
      logging.info(
        this,
        s"loadbalancer invoker status updated. managedInvokers = $managed blackboxInvokers = $blackboxes. $logDetail")(
        TransactionId.loadbalancer)
    }
  }
}

case class EnergyAwarePoolBalancerConfig(managedFraction: Double,
                                         blackboxFraction: Double,
                                         scheduler: String,
                                         timeoutFactor: Int,
                                         timeoutAddon: FiniteDuration)

/**
 * State kept for each activation slot until completion.
 *
 * @param id id of the activation
 * @param namespaceId namespace that invoked the action
 * @param invokerName invoker the action is scheduled to
 * @param memoryLimit memory limit of the invoked action
 * @param timeLimit time limit of the invoked action
 * @param maxConcurrent concurrency limit of the invoked action
 * @param fullyQualifiedEntityName fully qualified name of the invoked action
 * @param timeoutHandler times out completion of this activation, should be canceled on good paths
 * @param isBlackbox true if the invoked action is a blackbox action, otherwise false (managed action)
 * @param isBlocking true if the action is invoked in a blocking fashion, i.e. "somebody" waits for the result
 */
case class ActivationEntry(
                           activation: ActivationRecord,
                           invokerName: InvokerInstanceId,
                           timeoutHandler: Cancellable,
                          )

case class ActivationRecord(msg: ActivationMessage,
                            memoryLimit: Int,
                            timeLimit: FiniteDuration,
                            maxConcurrent: Int,
                            fullyQualifiedEntityName: FullyQualifiedEntityName,
                            isBlackbox: Boolean
                           )