package org.apache.openwhisk.core.loadBalancer

import org.apache.openwhisk.core.entity.{FullyQualifiedEntityName}
import spray.json._

import scala.concurrent.duration.{Duration, FiniteDuration}

case class RedisActivationEntry(activationRecord: ActivationRecord,
   timeAdded: Long, attempts: Int)

object RedisActivationEntry extends DefaultJsonProtocol {
  private implicit val finiteDurationFormat: RootJsonFormat[FiniteDuration] = new RootJsonFormat[FiniteDuration] {
    override def write(finiteDuration: FiniteDuration): JsValue = JsString(finiteDuration.toString)

    override def read(duration: JsValue): FiniteDuration = duration match {
      case JsString(s) =>
        val duration = Duration(s)
        FiniteDuration(duration.length, duration.unit)
      case _ =>
        deserializationError("time unit not supported. Only milliseconds, seconds, minutes, hours, days are supported")
    }
  }
  private implicit val fullyQualifiedNameFormat = FullyQualifiedEntityName.serdes

  implicit val activationRecordFormat: RootJsonFormat[ActivationRecord] = jsonFormat6(ActivationRecord.apply)
  implicit val redisActivationEntryFormat: RootJsonFormat[RedisActivationEntry]  = jsonFormat3(RedisActivationEntry.apply)
}
