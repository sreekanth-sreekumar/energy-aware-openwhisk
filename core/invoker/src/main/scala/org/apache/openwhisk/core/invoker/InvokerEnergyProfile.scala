package org.apache.openwhisk.core.invoker

import akka.actor.ActorSystem
import org.apache.openwhisk.common.{Logging, Scheduler}
import akka.actor.{Actor, Props}
import org.apache.openwhisk.utils.ExecutionContextFactory

import pureconfig._
import pureconfig.generic.auto._

import scala.concurrent.{ExecutionContext, Future}
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.entity.InvokerInstanceId

import java.time.Instant
import scala.collection.immutable.SortedMap
import scala.concurrent.duration.DurationInt

case class UpdateEnergyProfile(cpuUtil: Double)
case object GetCurrEnergy

object EnergyProfileConstants {

  // We are assuming a near linear relationship with power and cpu-util
  // At 100% cpu util, rpi4B has a max power of 6.5W
  val MAX_POWER = 6.5
  val BAT_DISCHARGE_RATE = 0.9
  val BAT_CHARGE_RATE = 0.8
}

case class SolarPowerConfig(panelSize: Double, batterySize: Double, startTime: Option[String]) {
}

object InvokerEnergyProfile {

  def props(instance: InvokerInstanceId)(actorSystem: ActorSystem, logging: Logging) = {

    val initialPowerConfig: SolarPowerConfig =
      loadConfigOrThrow[SolarPowerConfig](ConfigKeys.getSolarKeys(instance.instance) )
    val solarDataStream: SortedMap[Instant, SolarDataStruct] =
      SolarDataStream.instance(instance.instance)

    Props(new InvokerEnergyProfile(solarDataStream, initialPowerConfig.panelSize,
      initialPowerConfig.batterySize, initialPowerConfig.startTime)(actorSystem, logging))
  }
}

class InvokerEnergyProfile(
  stream: SortedMap[Instant, SolarDataStruct],
  panelSize: Double,
  batterySize: Double, startTime: Option[String])(implicit actorSystem: ActorSystem, logging: Logging) extends Actor {

  val headTimestamp: Instant = stream.head._1

  var currBatAvail = batterySize
  implicit val ec: ExecutionContext = ExecutionContextFactory.makeCachedThreadPoolExecutionContext()
  var currKey: Instant = startTime match {
    case Some(value: String) =>
      val date = OwTimestamp.instance(value)
      val instant = date match {
        case Some(inst: Instant) => inst
        case None => headTimestamp
      }
      instant
    case None => headTimestamp
  }
  var iterator: Iterator[Instant] = stream.from(currKey).keysIterator
  var excessEnergy: Double = 0

  Scheduler.scheduleWaitAtLeast(10.minutes)(() => gotoNextKey())

  def gotoNextKey() = Future {
    logging.info(this, s"Shifted to the next solar data key")
    if (iterator.hasNext) {
      currKey = iterator.next()
    } else {
      currKey = headTimestamp
    }
  }

  def getPowerFromPanel(): Double = {
    stream.get(currKey) match {
      case Some(solarData) => {
        panelSize * solarData.panelOutput
      }
      case None => 0
    }
  }

  def updateEnergyProfile(cpuUtil: Double): EnergyProfile = {
    val powerNeededByInvoker = cpuUtil * EnergyProfileConstants.MAX_POWER
    val powerFromPanel = getPowerFromPanel()
    val diff = powerFromPanel - powerNeededByInvoker
    if (diff >= 0) {
      val availToBat = math.min(diff, batterySize - currBatAvail)
      excessEnergy += (diff - availToBat)
      currBatAvail += availToBat * EnergyProfileConstants.BAT_CHARGE_RATE
    }
    else {
      val reduction = math.abs(diff)/EnergyProfileConstants.BAT_DISCHARGE_RATE
      if (currBatAvail >= reduction) {
        currBatAvail -= math.abs(diff)/EnergyProfileConstants.BAT_DISCHARGE_RATE
      } else {
        currBatAvail = 0
      }
    }
    getCurrentEnergyProfile()
  }

  def getCurrentEnergyProfile(): EnergyProfile = {
    EnergyProfile(getPowerFromPanel(), currBatAvail, currBatAvail / batterySize, excessEnergy)
  }

  override def receive: Receive = {
    case UpdateEnergyProfile(cpuUtil: Double) => sender() ! updateEnergyProfile(cpuUtil)
    case GetCurrEnergy => sender() ! getCurrentEnergyProfile()
  }
}

case class EnergyProfile(panelOutput: Double, currBat: Double, batRatio: Double, excessEnergy: Double) {
  override def equals(obj: scala.Any): Boolean = obj match {
    case that: EnergyProfile => that.panelOutput == this.panelOutput &&
      that.currBat == this.currBat &&
      that.batRatio == this.batRatio
    case _ => false
  }

  override def toString = s"InvokerEnergyProfile($panelOutput, $currBat, $batRatio)"
}