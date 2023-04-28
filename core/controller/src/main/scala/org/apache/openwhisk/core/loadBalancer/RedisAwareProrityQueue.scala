package org.apache.openwhisk.core.loadBalancer

import org.apache.commons.pool2.impl.GenericObjectPoolConfig
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.loadBalancer.RedisActivationEntry.redisActivationEntryFormat
import org.apache.openwhisk.utils.ExecutionContextFactory
import redis.clients.jedis.{Jedis, JedisPool}
import spray.json._

import scala.collection.convert.ImplicitConversions.`collection AsScalaIterable`
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}


class RedisAwarePriorityQueue(
  jedisPool: JedisPool,
  config: RedisConfig,
  poolConfig: GenericObjectPoolConfig[Jedis],
  queueKey: String)(logging: Logging) {

  private implicit val ec: ExecutionContext = RedisAwarePriorityQueue.getExecutionContext()

  def getPriority(entry: RedisActivationEntry) = 1/entry.timeAdded

  def renterAct(entry: RedisActivationEntry) = {
    val newAct = entry.copy(attempts = entry.attempts + 1)
    jedisPool.getResource.zadd(queueKey, getPriority(newAct), newAct.toJson.compactPrint)
  }

  def createNewEntry(elm: ActivationRecord) = {
    val timeAdded: Long = System.currentTimeMillis()
    val activationEntry = RedisActivationEntry(elm, timeAdded, 1)
    jedisPool.getResource.zadd(queueKey, getPriority(activationEntry), activationEntry.toJson.compactPrint)
  }

  def addToQueue(element: Either[ActivationRecord, RedisActivationEntry]) = {
      element match {
        case Left(actMeta) => Future(createNewEntry(actMeta))
        case Right(actEntry) => Future(renterAct(actEntry))
      }
  }

  def popQueue(): Future[Option[RedisActivationEntry]] = Future {
    try {
      val rangeResult = jedisPool.getResource.zrevrange(queueKey, 0, 0)
      if (rangeResult.isEmpty) {
        None
      } else {
        val serializedEntry = rangeResult.head
        val entry = serializedEntry.parseJson.convertTo[RedisActivationEntry]
        jedisPool.getResource.zrem(queueKey, serializedEntry)
        Some(entry)
      }
    } catch {
      case e: Exception =>
        logging.info(this, s"Redis data store pop error encountered: ${e.getMessage}")
        None
    }
  }

  def size(): Long = jedisPool.getResource.zcard(queueKey)
}

object RedisAwarePriorityQueue {

  def instance(config: RedisConfig, key: String)(logging: Logging): Option[RedisAwarePriorityQueue] = {
    try {
      val poolConfig: GenericObjectPoolConfig[Jedis] = new GenericObjectPoolConfig()
      poolConfig.setMaxTotal(100)
      poolConfig.setMaxIdle(20)
      poolConfig.setMinIdle(5)
      poolConfig.setTestOnBorrow(true)
      val jedisPool = new JedisPool(
        poolConfig, config.host, config.port, 1000, config.password)
      Some(new RedisAwarePriorityQueue(jedisPool, config, poolConfig, key)(logging))
    } catch {
      case e: Throwable =>
        logging.error(this, s"Redis store cannot be started ${e.getMessage}")
        None
    }
  }

  def getExecutionContext() = ExecutionContextFactory.makeCachedThreadPoolExecutionContext()
}

case class RedisConfig(host: String, port: Int, password: String = null,
  dampFactor: Double, maxRetries: Int, pollInterval: FiniteDuration)
