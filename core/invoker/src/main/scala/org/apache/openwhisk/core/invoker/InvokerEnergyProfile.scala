package org.apache.openwhisk.core.invoker

import akka.actor.ActorSystem
import org.apache.openwhisk.common.{Logging, Scheduler}
import akka.actor.{Actor, Props}

import java.sql.Timestamp
import pureconfig._
import pureconfig.generic.auto._

import scala.concurrent.{ExecutionContext, Future}
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.entity.InvokerInstanceId

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.DurationInt

case class UpdateEnergyProfile(cpuUtil: Double)
case object GetCurrEnergy

object EnergyProfileConstants {

  // We are assuming a near linear relationship with power and cpu-util
  // At 100% cpu util, rpi4B has a max power of 6.5W
  val MAX_POWER = 6.5
  val BAT_CHARGE_RATE = 0.8
}


case class SolarDataConfig(startTimePoint: String, dataFiles: String) {
  def getPath(invokerVal: Int): String = {
    dataFiles ++ "invoker" ++ invokerVal.toString
  }
  def getStartTime(): Timestamp = {
    OwTimestamp.instance(startTimePoint)
  }
}

case class SolarPowerConfig(panelSize: String, batterySize: String) {
  def getPanelSize(): Double = {
    panelSize.toDouble
  }
  def getBatterySize(): Double = {
    batterySize.toDouble
  }
}

object InvokerEnergyProfile {

  def props(instance: InvokerInstanceId)(actorSystem: ActorSystem, logging: Logging) = {

    val solarConfig: SolarDataConfig = loadConfigOrThrow[SolarDataConfig](ConfigKeys.solarData)
    val initialPowerConfig: SolarPowerConfig =
      loadConfigOrThrow[SolarPowerConfig](ConfigKeys.solarInitialConfig ++ "." ++ instance.uniqueName++ )
    val solarDataStream: SortedMap[Timestamp, SolarDataStruct] =
      SolarDataStream.instance(solarConfig.getPath(instance.instance))

    Props(new InvokerEnergyProfile(solarDataStream, solarConfig.getStartTime(),
      initialPowerConfig.getPanelSize(), initialPowerConfig.getBatterySize())(actorSystem, logging))
  }
}

class InvokerEnergyProfile(
  stream: SortedMap[Timestamp, SolarDataStruct],
  startTime: Timestamp,
  panelSize: Double,
  batterySize: Double)(implicit actorSystem: ActorSystem, logging: Logging) extends Actor {

  val headTimestamp = stream.head._1

  var currBatAvail = batterySize
  implicit val ec: ExecutionContext = actorSystem.dispatcher
  var currKey: Timestamp = startTime
  var iterator: Iterator[Timestamp] = stream.from(startTime).keysIterator
  var excessEnergy = 0

  def gotoNextKey() = Future {
    if (iterator.hasNext) {
      currKey = iterator.next()
    }
    currKey = headTimestamp
  }

  def getPowerFromPanel(): Double = {
    stream.get(currKey).match {
      case Some(solarData) => {
        panelSize * solarData.panelOutput
      }
      case None => 0
    }
  }

  def updateEnergyProfile(cpuUtil: Double): Double = {
    val powerNeededByInvoker = cpuUtil * EnergyProfileConstants.MAX_POWER
    val powerFromPanel = getPowerFromPanel()
    val diff = powerFromPanel - powerNeededByInvoker
    if (diff >= 0) {
      // Include charging rate
      val availToBat = math.min(diff, batterySize - currBatAvail)
      excessEnergy += (diff - availToBat)
      currBatAvail += availToBat
    }
    else {
      // Include discharge rate
      currBatAvail -= math.abs(diff)
    }
    getCurrentEnergyProfile()
  }

  def getCurrentEnergyProfile(): Double = {
    getPowerFromPanel() + currBatAvail
  }

  Scheduler.scheduleWaitAtLeast(10.minutes)(() => gotoNextKey())

  override def receive: Receive = {
    case UpdateEnergyProfile(cpuUtil: Double) => updateEnergyProfile(cpuUtil)
    case GetCurrEnergy: Double => getCurrentEnergyProfile
  }
}
