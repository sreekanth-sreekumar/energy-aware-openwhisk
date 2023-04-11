package org.apache.openwhisk.core.invoker


import java.sql.Timestamp
import java.text.{DateFormat, SimpleDateFormat}
import scala.collection.immutable.SortedMap
import scala.io.Source

object OwTimestamp {
  def instance(timeStr: String): Timestamp = {
    val dateFormat: DateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm")
    val date: Option[Timestamp] = {
      try {
        Some(new Timestamp(dateFormat.parse(timeStr).getTime))
      } catch {
        case _: Exception => Some(Timestamp.valueOf("19700101'T'000000"))
      }
    }

    date.getOrElse(Timestamp.valueOf(timeStr))
  }
}

object SolarDataStream {

  implicit def ordered: Ordering[Timestamp] = (x: Timestamp, y: Timestamp) => x compareTo y

  def instance(path: String): SortedMap[Timestamp, SolarDataStruct] = {
    val bufferedSource = Source.fromFile(path)
    var dataMap: Map[Timestamp, SolarDataStruct] = Map()
    val lines = bufferedSource.getLines().drop(1)

    for (line <- lines) {
      val cols = line.split(",").map(_.trim)
      val periodStart = OwTimestamp.instance(cols(0))
      val solarData = SolarDataStruct(
        airTemp = cols(3).toDouble,
        tilt = cols(4).toInt,
        panelTemp = cols(5).toFloat,
        panelOutput = cols(6).toFloat
      )
      dataMap += (periodStart -> solarData)
    }
    SortedMap[Timestamp, SolarDataStruct]() += dataMap
  }
}

case class SolarDataStruct(
  airTemp: Double,
  tilt: Int,
  panelTemp: Float,
  panelOutput: Float
                          )