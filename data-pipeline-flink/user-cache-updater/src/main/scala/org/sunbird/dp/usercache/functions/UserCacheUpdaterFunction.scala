package org.sunbird.dp.usercache.functions

import java.util

import com.datastax.driver.core.Row
import com.datastax.driver.core.exceptions.DriverException
import com.datastax.driver.core.querybuilder.{Clause, QueryBuilder}
import com.google.gson.Gson
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.slf4j.LoggerFactory
import org.sunbird.dp.core.cache.{DataCache, RedisConnect}
import org.sunbird.dp.core.job.{BaseProcessFunction, Metrics}
import org.sunbird.dp.core.util.CassandraConnect
import org.sunbird.dp.usercache.domain.Event
import org.sunbird.dp.usercache.task.UserCacheUpdaterConfig

import scala.collection.JavaConverters._
import scala.collection.mutable

class UserCacheUpdaterFunction(config: UserCacheUpdaterConfig)(implicit val mapTypeInfo: TypeInformation[Event])
  extends BaseProcessFunction[Event, Event](config) {

  private[this] val logger = LoggerFactory.getLogger(classOf[UserCacheUpdaterFunction])
  private var dataCache: DataCache = _
  private var cassandraConnect: CassandraConnect = _

  override def metricsList(): List[String] = {
    List(config.dbHitCount, config.userCacheHit, config.userCacheMiss, config.skipCount, config.successCount)
  }

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)
    dataCache = new DataCache(config, new RedisConnect(config), config.userStore, config.userFields)
    dataCache.init()
    cassandraConnect = new CassandraConnect(config.cassandraHost, config.cassandraPort)
  }

  override def close(): Unit = {
    super.close()
    dataCache.close()
  }

  override def processElement(event: Event, context: ProcessFunction[Event, Event]#Context, metrics: Metrics): Unit = {
    Option(event.getId).map(id => {
      Option(event.getState).map(name => {
        val userData: mutable.Map[String, AnyRef] = name.toUpperCase match {
          case "CREATE" | "CREATED" => createAction(id, event, metrics)
          case "UPDATE" | "UPDATED" => updateAction(id, event, metrics)
        }
        if (!userData.isEmpty) {
          dataCache.setWithRetry(id, new Gson().toJson(mapAsJavaMap(userData)))
          metrics.incCounter(config.successCount)
          metrics.incCounter(config.userCacheHit)
        } else {
          metrics.incCounter(config.skipCount)
        }
      }).getOrElse(metrics.incCounter(config.skipCount))
    }).getOrElse(metrics.incCounter(config.skipCount))
  }

  def createAction(userId: String, event: Event, metrics: Metrics): mutable.Map[String, AnyRef] = {
    val userData: mutable.Map[String, AnyRef] = mutable.Map[String, AnyRef]()
    Option(event.getUserSignInType(cDataType = "SignupType")).map(signInType => {
      if (config.userSelfSignedInTypeList.contains(signInType)) {
        userData.put(config.userSignInTypeKey, config.userSelfSignedKey)
      }
      if (config.userValidatedTypeList.contains(signInType)) {
        userData.put(config.userSignInTypeKey, config.userValidatedKey)
      }
    }).getOrElse(null)
    userData
  }

  def updateAction(userId: String, event: Event, metrics: Metrics): mutable.Map[String, AnyRef] = {
    val userCacheData: mutable.Map[String, AnyRef] = dataCache.getWithRetry(userId)

    Option(event.getUserSignInType("UserRole")).map(loginType => {
      userCacheData.put(config.userLoginTypeKey, loginType)
    })
    val userMetaDataList = event.userMetaData()
    if (!userMetaDataList.isEmpty() && userCacheData.contains(config.userSignInTypeKey) && ("Anonymous" != userCacheData.get(config.userSignInTypeKey))) {

      // Get the user details from the cassandra table
      val userDetails: mutable.Map[String, AnyRef] = userCacheData.++(extractUserMetaData(readFromCassandra(
        keyspace = config.keySpace,
        table = config.userTable,
        QueryBuilder.eq("id", userId),
        metrics)
      ))

      // Fetching the location details from the cassandra table
      val updatedUserDetails: mutable.Map[String, AnyRef] = userDetails.++(extractLocationMetaData(readFromCassandra(
        keyspace = config.keySpace,
        table = config.locationTable,
        clause = QueryBuilder.in("id", userDetails.get("locationids").getOrElse(new util.ArrayList()).asInstanceOf[util.ArrayList[String]]),
        metrics)
      ))
      logger.info(s"User details ( $userId ) are fetched from the db's and updating the redis now.")
      updatedUserDetails
    } else {
      logger.info(s"Skipping the event update from databases since event Does not have user properties or user sigin in type is Anonymous ")
      userCacheData
    }
  }

  def readFromCassandra(keyspace: String, table: String, clause: Clause, metrics: Metrics): util.List[Row] = {
    var rowSet: util.List[Row] = null
    val query = QueryBuilder.select.all.from(keyspace, table).where(clause).toString
    try rowSet = cassandraConnect.find(query)
    catch {
      case ex: DriverException =>
        cassandraConnect.reconnect()
        rowSet = cassandraConnect.find(query)
    }
    metrics.incCounter(config.dbHitCount)
    rowSet
  }

  def extractUserMetaData(userDetails: util.List[Row]): mutable.Map[String, AnyRef] = {
    val result: mutable.Map[String, AnyRef] = mutable.Map[String, AnyRef]()
    if (null != userDetails && !userDetails.isEmpty) {
      val row: Row = userDetails.get(0)
      val columnDefinitions = row.getColumnDefinitions()
      val columnCount = columnDefinitions.size
      for (i <- 0 until columnCount) {
        result.put(columnDefinitions.getName(i), row.getObject(i))
      }
    }
    result
  }

  def extractLocationMetaData(locationDetails: util.List[Row]): mutable.Map[String, AnyRef] = {
    val result: mutable.Map[String, AnyRef] = mutable.Map[String, AnyRef]()
    locationDetails.forEach((record: Row) => {
      def foo(record: Row): Any = {
        record.getString("type").toLowerCase match {
          case "state" => result.put("state", record.getString("name"))
          case "district" => result.put("district", record.getString("name"))
        }
      }

      foo(record)
    })

    result
  }

}
