package org.apache.openwhisk.core.invoker

import java.time.Instant
import scala.collection.immutable.SortedMap
import scala.io.Source

object OwTimestamp {
  def instance(timeStr: String): Option[Instant] = {
    val instant: Option[Instant] = {
      try {
        Some(Instant.parse(timeStr))
      } catch {
        case _: Exception => {
          None
        }
      }
    }
    instant
  }
}

object SolarDataStream {

  implicit def ordered: Ordering[Instant] = (x: Instant, y: Instant) => x compareTo y

  def instance(instance: Int): SortedMap[Instant, SolarDataStruct] = {
    val bufferedSource = Source.fromFile("/solar-data/" ++ "invoker" + instance.toString ++ ".csv")
    var dataMap: Map[Instant, SolarDataStruct] = Map()
    val lines = bufferedSource.getLines().drop(1)

    for (line <- lines) {
      val cols = line.split(",").map(_.trim)
      val periodStart = OwTimestamp.instance(cols(2))
      periodStart match {
        case Some(instant: Instant) =>
          val solarData = SolarDataStruct(
            airTemp = cols(5).toDouble,
            tilt = cols(6).toInt,
            panelTemp = cols(7).toFloat,
            panelOutput = cols(8).toFloat
          )
          dataMap = dataMap + (instant -> solarData)
        case _ => ()
      }

    }
    SortedMap[Instant, SolarDataStruct]() ++ dataMap
  }
}

case class SolarDataStruct(
  airTemp: Double,
  tilt: Int,
  panelTemp: Float,
  panelOutput: Float
                          )