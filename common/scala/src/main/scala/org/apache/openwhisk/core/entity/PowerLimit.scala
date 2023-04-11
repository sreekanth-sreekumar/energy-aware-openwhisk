package org.apache.openwhisk.core.entity

import org.apache.openwhisk.core.ConfigKeys
import pureconfig.loadConfigOrThrow
import spray.json.{JsNumber, JsValue, RootJsonFormat, deserializationError}

import scala.util.{Failure, Success, Try}

case class PowerLimitConfig(min: Double, max: Double, std: Double)

protected[entity] class PowerLimit private (val power: Double) extends AnyVal

protected[core] object PowerLimit extends ArgNormalizer[PowerLimit] {
  val config = loadConfigOrThrow[PowerLimitConfig](ConfigKeys.power)

  protected[core] val MIN_POWER: Double = config.min
  protected[core] val MAX_POWER: Double = config.max
  protected[core] val STD_POWER: Double = config.std

  protected[core] val standardPowerLimit = PowerLimit(STD_POWER)

  protected[core] def apply(): PowerLimit = standardPowerLimit


  @throws[IllegalArgumentException]
  protected[core] def apply(power: Double): PowerLimit = {
    require(power >= MIN_POWER, s"Power $power below allowed threshold of $MIN_POWER")
    require(power <= MAX_POWER, s"Power $power exceeds allowed threshold of $MAX_POWER")
    new PowerLimit(power)
  }

  override protected[core] implicit val serdes = new RootJsonFormat[PowerLimit] {
    def write(p: PowerLimit) = JsNumber(p.power)

    def read(value: JsValue) =
      Try {
        val JsNumber(pow) = value
        PowerLimit(pow.doubleValue)
      } match {
        case Success(limit)                       => limit
        case Failure(e: IllegalArgumentException) => deserializationError(e.getMessage, e)
        case Failure(e: Throwable)                => deserializationError("power limit malformed", e)
      }
  }
}
