/**
  * Copyright 2015 Thomson Reuters
  *
  * Licensed under the Apache License, Version 2.0 (the “License”); you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *   http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
  * an “AS IS” BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  *
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */


package cmwell.fts

import java.net.InetAddress
import java.util.concurrent.{TimeUnit, TimeoutException}

import akka.NotUsed
import akka.stream.scaladsl.Source
import cmwell.common.formats.NsSplitter
import cmwell.domain._
import cmwell.util.concurrent.SimpleScheduler
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.Logger
import io.netty.util.{HashedWheelTimer, Timeout, TimerTask}
import org.elasticsearch.action.DocWriteResponse.Result
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesResponse
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse
import org.elasticsearch.action.bulk.{BulkItemResponse, BulkResponse}
import org.elasticsearch.action.delete.DeleteResponse
import org.elasticsearch.action.get.GetResponse
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.action.search.{SearchRequestBuilder, SearchResponse}
import org.elasticsearch.action.support.WriteRequest
import org.elasticsearch.action.update.UpdateRequest
import org.elasticsearch.action.{ActionListener, DocWriteRequest}
import org.elasticsearch.client.Client
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.transport.TransportAddress
import org.elasticsearch.common.unit.TimeValue
import org.elasticsearch.index.query.QueryBuilders._
import org.elasticsearch.index.query.{BoolQueryBuilder, QueryBuilder, QueryBuilders}
import org.elasticsearch.search.SearchHit
import org.elasticsearch.search.aggregations._
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram
import org.elasticsearch.search.aggregations.bucket.significant.InternalSignificantTerms
import org.elasticsearch.search.aggregations.bucket.terms.InternalTerms
import org.elasticsearch.search.aggregations.metrics.cardinality.InternalCardinality
import org.elasticsearch.search.aggregations.metrics.stats.InternalStats
import org.elasticsearch.search.sort.SortBuilders._
import org.elasticsearch.search.sort.SortOrder
import org.elasticsearch.transport.client.PreBuiltTransportClient
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.language.implicitConversions
import scala.util._


/**
  * Created by israel on 30/06/2016.
  */

object FTSService {
  def apply() = new FTSService(ConfigFactory.load)
  def apply(configFile:String) = new FTSService(ConfigFactory.load(configFile))
  def apply(config:Config) = new FTSService(config)
}

class FTSService(config: Config) extends NsSplitter{

  val loger = Logger(LoggerFactory.getLogger(getClass.getName))

  val clusterName = config.getString("ftsService.clusterName")
  val transportAddress = config.getString("ftsService.transportAddress")
  val transportPort = config.getInt("ftsService.transportPort")
  val scrollLength = config.getInt("ftsService.scrollLength")
  val dataCenter = config.getString("dataCenter.id")
  val waitForGreen = config.getBoolean("ftsService.waitForGreen")
  val transportSniff = config.getBoolean("ftsService.sniff")
  val defaultScrollTTL = config.getLong("ftsService.scrollTTL")
  val defaultPartition = config.getString("ftsService.defaultPartitionNew")
  val sysQuad = Some("cmwell://meta/sys")

  var client:Client = _

    val esSettings = Settings.builder().put("cluster.name", clusterName).put("client.transport.sniff", transportSniff).build()
    val actualTransportAddress = transportAddress
    client = new PreBuiltTransportClient(esSettings).addTransportAddress(new TransportAddress(InetAddress.getByName(actualTransportAddress), transportPort))
    loger.info(s"starting es transport client [/$actualTransportAddress:$transportPort]")

  val localHostName = InetAddress.getLocalHost.getHostName

  val nodesInfo = client.admin().cluster().prepareNodesInfo().execute().actionGet()

  lazy val clients:Map[String, Client] =
      nodesInfo.getNodes.asScala.filterNot( n => ! n.getNode.isDataNode).map{ node =>
        val nodeId = node.getNode.getId
        val nodeHostName = node.getNode.getHostName

        val clint = nodeHostName.equalsIgnoreCase(localHostName) match {
          case true => client
          case false =>
            val transportAddress = node.getNode.getAddress
            val settings = Settings.builder().
              put("cluster.name", node.getSettings.get("cluster.name"))
              .put("transport.netty.worker_count", 3)
              .put("transport.connections_per_node.recovery", 1)
              .put("transport.connections_per_node.bulk", 1)
              .put("transport.connections_per_node.reg", 2)
              .put("transport.connections_per_node.state", 1)
              .put("transport.connections_per_node.ping", 1).build
            new PreBuiltTransportClient(settings).addTransportAddress(transportAddress)
        }
        (nodeId, clint)
      }.toMap


  if(waitForGreen) {
    loger.info("waiting for ES green status")
    // wait for green status
    client.admin().cluster()
      .prepareHealth()
      .setWaitForGreenStatus()
      .setTimeout(TimeValue.timeValueMinutes(5))
      .execute()
      .actionGet()

    loger.info("got green light from ES")
  } else {
    loger.info("waiting for ES yellow status")
    // wait for yellow status
    client.admin().cluster()
      .prepareHealth()
      .setWaitForYellowStatus()
      .setTimeout(TimeValue.timeValueMinutes(5))
      .execute()
      .actionGet()

    loger.info("got yellow light from ES")
  }

  def shutdown() {
    if (client != null)
      client.close()
  }

  def nodesHttpAddresses() = {
    nodesInfo.getNodes.asScala.map{ node =>
      val inetAddress = node.getHttp.getAddress.publishAddress().address()
      s"${inetAddress.getHostString}:${inetAddress.getPort}"
    }
  }

  def latestIndexNameAndCount(prefix:String): Option[(String, Long)] = {
    val indices = client.admin().indices().prepareStats(prefix).clear().setDocs(true).execute().actionGet().getIndices
    if(indices.size() > 0) {
      val lastIndexName = indices.keySet().asScala.maxBy{ k => k.substring(k.lastIndexOf('_')+1).toInt}
      val lastIndexCount = indices.get(lastIndexName).getTotal.docs.getCount
      Some((lastIndexName -> lastIndexCount))
    } else {
      None
    }
  }

  def numOfShardsForIndex(indexName:String):Int = {
    val recoveries = client.admin().indices().prepareRecoveries(indexName).get()
    recoveries.shardRecoveryStates().get(indexName).asScala.filter(_.getPrimary).size
  }

  def createIndex(indexName:String)(implicit executionContext:ExecutionContext):Future[CreateIndexResponse] =
    injectFuture[CreateIndexResponse](client.admin.indices().prepareCreate(indexName).execute(_))

  def updateAllAlias()(implicit executionContext:ExecutionContext):Future[IndicesAliasesResponse] =
    injectFuture[IndicesAliasesResponse](client.admin().indices().prepareAliases()
    .addAlias("cm_well_*", "cm_well_all")
    .execute(_))

  def listChildren(path: String, offset: Int, length: Int, descendants: Boolean = false,
                   partition: String = defaultPartition)
                  (implicit executionContext:ExecutionContext) : Future[FTSSearchResponse] = {
    search(
      pathFilter = Some(PathFilter(path, descendants)),
      fieldsFilter = None,
      datesFilter = None,
      paginationParams = PaginationParams(offset, length))
  }

  def search(pathFilter: Option[PathFilter], fieldsFilter: Option[FieldFilter], datesFilter: Option[DatesFilter],
             paginationParams: PaginationParams, sortParams: SortParam = SortParam.empty,
             withHistory: Boolean = false, withDeleted: Boolean = false, partition:String = defaultPartition,
             debugInfo:Boolean = false, timeout : Option[Duration] = None)
            (implicit executionContext:ExecutionContext, logger:Logger = loger): Future[FTSSearchResponse] = {

      logger.debug(s"Search request: $pathFilter, $fieldsFilter, $datesFilter, $paginationParams, $sortParams, $withHistory, $partition, $debugInfo")

    if (pathFilter.isEmpty && fieldsFilter.isEmpty && datesFilter.isEmpty) {
      throw new IllegalArgumentException("at least one of the filters is needed in order to search")
    }

    val fields = "system.kind" :: "system.path" :: "system.uuid" :: "system.lastModified" :: "content.length" ::
      "content.mimeType" :: "link.to" :: "link.kind" :: "system.dc" :: "system.indexTime" :: "system.quad" :: "system.current" :: Nil

    val request = client.prepareSearch(s"${partition}_all").setTypes("infoclone").storedFields(fields:_*).setFrom(paginationParams.offset).setSize(paginationParams.length)

    applySortToRequest(sortParams,request)

    applyFiltersToRequest(request, pathFilter, fieldsFilter, datesFilter, withHistory, withDeleted)

    val searchQueryStr = if(debugInfo) Some(request.toString) else None
    logger.debug(s"^^^^^^^(**********************\n\n request: ${request.toString}\n\n")
    val resFuture = timeout match {
      case Some(t) => injectFuture[SearchResponse](request.execute, t)
      case None => injectFuture[SearchResponse](request.execute)
    }

    resFuture.map{ response =>
      FTSSearchResponse(response.getHits.getTotalHits, paginationParams.offset, response.getHits.getHits.size,
        esResponseToInfotons(response, sortParams eq NullSortParam), searchQueryStr)
    }
  }

  trait FieldType {
    def asString:String
  }
  case object DateType extends FieldType {
    override def asString = "date"
  }
  case object IntType extends FieldType {
    override def asString = "integer"
  }
  case object LongType extends FieldType {
    override def asString = "long"
  }
  case object FloatType extends FieldType {
    override def asString = "float"
  }
  case object DoubleType extends FieldType {
    override def asString = "double"
  }
  case object BooleanType extends FieldType {
    override def asString = "boolean"
  }
  case object StringType extends FieldType {
    override def asString = "string"
  }

  private def fieldType(fieldName:String) = {
    fieldName match {
      case "system.lastModified" => DateType
      case "system.parent" | "system.path" | "system.kind" | "system.uuid" | "system.dc" | "system.quad"
           | "content.data" | "content.mimeType" | "link.to" => StringType
      case "content.length" | "system.indexTime" => LongType
      case "link.kind" => IntType
      case "system.current" => BooleanType
      case other => other.take(2) match {
        case "d$" => DateType
        case "i$" => IntType
        case "l$" => LongType
        case "f$" => FloatType
        case "w$" => DoubleType
        case "b$" => BooleanType
        case _ => StringType
      }
    }
  }

  private def applySortToRequest(sortParams: SortParam, request: SearchRequestBuilder): Unit = sortParams match {
    case NullSortParam => // don't sort.
    case FieldSortParams(fsp) if fsp.isEmpty => request.addSort("system.lastModified", SortOrder.DESC)
    case FieldSortParams(fsp) => fsp.foreach {
      case (name, order) => {
        val fType = fieldType(name)
        var reversed = reverseNsTypedField(name)
        if(fType eq StringType) reversed = reversed + ".%exact"
        request.addSort(fieldSort(reversed).order(order).unmappedType(fType.asString))
      }
    }
  }

  def thinSearch(pathFilter: Option[PathFilter], fieldsFilter: Option[FieldFilter],
                 datesFilter: Option[DatesFilter], paginationParams: PaginationParams,
                 sortParams: SortParam = SortParam.empty, withHistory: Boolean = false, withDeleted: Boolean = false,
                 partition:String = defaultPartition, debugInfo:Boolean = false,
                 timeout : Option[Duration] = None)
                (implicit executionContext:ExecutionContext, logger:Logger = loger) : Future[FTSThinSearchResponse] = {

    logger.debug(s"Search request: $pathFilter, $fieldsFilter, $datesFilter, $paginationParams, $sortParams, $withHistory, $partition, $debugInfo")

    if (pathFilter.isEmpty && fieldsFilter.isEmpty && datesFilter.isEmpty) {
      throw new IllegalArgumentException("at least one of the filters is needed in order to search")
    }

    val fields = "system.path" :: "system.uuid" :: "system.lastModified" ::"system.indexTime" :: Nil

    val request = client.prepareSearch(s"${partition}_all").setTypes("infoclone").storedFields(fields:_*).setFrom(paginationParams.offset).setSize(paginationParams.length)

    applySortToRequest(sortParams,request)

    applyFiltersToRequest(request, pathFilter, fieldsFilter, datesFilter, withDeleted)

    val resFuture = timeout match {
      case Some(t) => injectFuture[SearchResponse](request.execute, t)
      case None => injectFuture[SearchResponse](request.execute)
    }

    lazy val oldTimestamp = System.currentTimeMillis()
    lazy val debugInfoIdentifier = resFuture.##
    if(debugInfo) {
      logger.info(s"[!$debugInfoIdentifier] thinSearch debugInfo request ($oldTimestamp): ${request.toString}")
    }

    val searchQueryStr = if(debugInfo) Some(request.toString) else None

    resFuture.map{ response =>
      if(debugInfo) {
        logger.info(s"[!$debugInfoIdentifier] thinSearch debugInfo response: ($oldTimestamp - ${System.currentTimeMillis()}): ${response.toString}")
      }
      FTSThinSearchResponse(response.getHits.getTotalHits, paginationParams.offset, response.getHits.getHits.size,
        esResponseToThinInfotons(response, sortParams eq NullSortParam), searchQueryStr = searchQueryStr)
    }
  }

  def executeIndexRequests(indexRequests:Iterable[ESIndexRequest], forceRefresh:Boolean = false)
                          (implicit executionContext:ExecutionContext, logger:Logger = loger):Future[BulkIndexResult] = {

      logger debug s"executing index requests: $indexRequests"
    val promise = Promise[BulkIndexResult]
    val bulkRequest = client.prepareBulk()
    bulkRequest.request().add(indexRequests.map{_.esAction}.asJava)
    if(forceRefresh)
      bulkRequest.setRefreshPolicy(WriteRequest.RefreshPolicy.WAIT_UNTIL)

    logger debug s"bulkRequest: \n${bulkRequest.request().toString}"

    val esResponse = injectFuture[BulkResponse](bulkRequest.execute(_))

    esResponse.onComplete {
      case Success(bulkResponse) =>
          logger debug s"successful es response: ${bulkResponse.hasFailures}"
        if(!bulkResponse.hasFailures){
            logger debug s"no failures in es response"
          promise.success(SuccessfulBulkIndexResult.fromSuccessful(indexRequests, bulkResponse.getItems))
        } else {
            logger debug s"failures in es response"
          promise.success(SuccessfulBulkIndexResult(indexRequests, bulkResponse))
        }
      case err@Failure(exception) =>
        logger.warn(s"[!${err.##}] Elasticsearch rejected execution of current bulk",exception)
        promise.success(RejectedBulkIndexResult(exception.getLocalizedMessage))
    }

    promise.future
  }

  /**
    * execute bulk index requests
    * @param indexRequests
    * @param numOfRetries
    * @param waitBetweenRetries
    * @param executionContext
    * @return
    */
  def executeBulkIndexRequests(indexRequests:Iterable[ESIndexRequest], numOfRetries: Int = 15, waitBetweenRetries:Long = 500)
                              (implicit executionContext:ExecutionContext, logger:Logger = loger) : Future[SuccessfulBulkIndexResult] = {

    logger debug s"indexRequests:$indexRequests"

    val promise = Promise[SuccessfulBulkIndexResult]
    val bulkRequest = client.prepareBulk()
    bulkRequest.request().add(indexRequests.map{_.esAction}.asJava)

    val esResponse = injectFuture[BulkResponse](bulkRequest.execute(_))

    esResponse.onComplete{
      case Success(bulkResponse) =>
        if(!bulkResponse.hasFailures){
          promise.success(SuccessfulBulkIndexResult.fromSuccessful(indexRequests, bulkResponse.getItems))
        } else {

          val indexedIndexRequests = indexRequests.toIndexedSeq
          val bulkSuccessfulResult = SuccessfulBulkIndexResult.fromSuccessful(indexRequests, bulkResponse.getItems.filter{!_.isFailed})
          val allFailures = bulkResponse.getItems.filter(_.isFailed).map{ itemResponse =>
            (itemResponse, indexedIndexRequests(itemResponse.getItemId))
          }

          val (recoverableFailures, nonRecoverableFailures) = allFailures.partition{case (itemResponse, _) =>
            itemResponse.getFailureMessage.contains("EsRejectedExecutionException")
          }

          // filter expected errors that can happen since since we're at least once and not exactly once
          val unexpectedErrors = nonRecoverableFailures.filterNot{case (bulkItemResponse, _) =>
            bulkItemResponse.getFailureMessage.startsWith("VersionConflictEngineException")
          }

          // log errors
          unexpectedErrors.foreach{ case (itemResponse, esIndexRequest) =>
            val infotonPath = infotonPathFromDocWriteRequest(esIndexRequest.esAction)
            logger.error(s"ElasticSearch non recoverable failure on doc id:${itemResponse.getId}, path: $infotonPath . due to: ${itemResponse.getFailureMessage}")
          }
          recoverableFailures.foreach{ case (itemResponse, esIndexRequest) =>
            val infotonPath = infotonPathFromDocWriteRequest(esIndexRequest.esAction)
            logger.error(s"ElasticSearch recoverable failure on doc id:${itemResponse.getId}, path: $infotonPath . due to: ${itemResponse.getFailureMessage}")
          }

          val nonRecoverableBulkIndexResults = SuccessfulBulkIndexResult.fromFailed(unexpectedErrors.map{_._1})

          if(recoverableFailures.length > 0) {
            if(numOfRetries > 0) {
              logger.warn(s"will retry recoverable failures after waiting for $waitBetweenRetries milliseconds")
              val updatedIndexRequests = updateIndexRequests(recoverableFailures.map{_._2}, new DateTime().getMillis)

              val reResponse = SimpleScheduler.scheduleFuture[SuccessfulBulkIndexResult](waitBetweenRetries.milliseconds)(
                {
                  executeBulkIndexRequests(updatedIndexRequests, numOfRetries - 1, (waitBetweenRetries * 1.1).toLong)
                }
              )
              promise.completeWith(
                reResponse.map{bulkSuccessfulResult ++ nonRecoverableBulkIndexResults ++ _}
              )
            } else {
              logger error s"exhausted all retries attempts. logging failures to RED_LOG and returning results "
              promise.success(SuccessfulBulkIndexResult(indexRequests, bulkResponse))
            }
          } else {
            promise.success(SuccessfulBulkIndexResult(indexRequests, bulkResponse))
          }


        }

      case err@Failure(exception) =>
        val errorId = err.##
        if(exception.getLocalizedMessage.contains("EsRejectedExecutionException")) {
          logger.warn(s"[!$errorId] Elasticsearch rejected execution of current bulk",exception)
          if(numOfRetries > 0) {
            logger.warn(s"[!$errorId] retrying rejected bulk after waiting for $waitBetweenRetries milliseconds")
            val f = SimpleScheduler.scheduleFuture(waitBetweenRetries.milliseconds)(
              {
                val updatedIndexRequests = updateIndexRequests(indexRequests, new DateTime().getMillis)
                executeBulkIndexRequests(updatedIndexRequests, numOfRetries -1, (waitBetweenRetries * 1.1).toLong)
              }
            )
            promise.completeWith(f)
          } else {
            logger.error(s"[!$errorId] exhausted all retries attempts. logging failures to RED_LOG ")
            promise.failure(exception)
          }
        } else {
          logger.error(s"[!$errorId] unexpected Exception from Elasticsearch.",exception)
          // TODO log to RED_LOG
          promise.failure(exception)
        }
    }

    promise.future


  }

  def delete(uuid:String, indexName:String)(implicit executionContext:ExecutionContext):Future[Boolean] = {
    injectFuture[DeleteResponse](client.prepareDelete(indexName, "infoclone", uuid).execute(_)).map{ res =>
      res.getResult == Result.DELETED
    }
  }

  def delete(uuid:String)(implicit executionContext:ExecutionContext):Future[Boolean] = {
    injectFuture[SearchResponse](client.prepareSearch().setQuery(idsQuery("infoclone").addIds(uuid)).execute(_)).collect[String]{
      case res if res.getHits.getHits.nonEmpty => res.getHits.getHits.head.getIndex
    }.flatMap{ indexName =>
      delete(uuid, indexName)
    }.recover{case _ => false}
  }

  private def startShardScroll(pathFilter: Option[PathFilter] = None, fieldsFilter: Option[FieldFilter] = None,
                               datesFilter: Option[DatesFilter] = None,
                               withHistory: Boolean, withDeleted: Boolean,
                               offset:Int, length:Int, scrollTTL:Long = defaultScrollTTL,
                               index:String, nodeId:String, shard:Int)
                              (implicit executionContext:ExecutionContext) : Future[FTSStartScrollResponse] = {

    val fields = "system.kind" :: "system.path" :: "system.uuid" :: "system.lastModified" :: "content.length" ::
      "content.mimeType" :: "link.to" :: "link.kind" :: "system.dc" :: "system.indexTime" :: "system.quad" :: "system.current" :: Nil

    val request = clients.getOrElse(nodeId,client).prepareSearch(index)
      .setTypes("infoclone")
      .storedFields(fields:_*)
      .setScroll(TimeValue.timeValueSeconds(scrollTTL))
      .setSize(length)
      .setFrom(offset)
      .setPreference(s"_shards:$shard;_only_node:$nodeId")

    if (pathFilter.isEmpty && fieldsFilter.isEmpty && datesFilter.isEmpty) {
      request.setQuery(matchAllQuery())
    } else {
      applyFiltersToRequest(request, pathFilter, fieldsFilter, datesFilter, withHistory, withDeleted)
    }

    val scrollResponseFuture = injectFuture[SearchResponse](request.execute(_))

    scrollResponseFuture.map{ scrollResponse => FTSStartScrollResponse(scrollResponse.getHits.totalHits, scrollResponse.getScrollId, Some(nodeId))  }
  }

  def startSuperScroll(pathFilter: Option[PathFilter], fieldsFilter: Option[FieldFilter],
                       datesFilter: Option[DatesFilter],
                       paginationParams: PaginationParams, scrollTTL: Long,
                       withHistory: Boolean, withDeleted: Boolean)
                      (implicit executionContext:ExecutionContext): Seq[Future[FTSStartScrollResponse]] = {

    val ssr = client.admin().cluster().prepareSearchShards(s"${defaultPartition}_all").execute().actionGet()

    val targetedShards = ssr.getGroups.flatMap(_.getShards.collect {
      case shard if shard.primary() =>
      (shard.index(), shard.currentNodeId(), shard.id())
    })

    targetedShards.map{ case (index, node, shard) =>
      startShardScroll(pathFilter, fieldsFilter, datesFilter, withHistory, withDeleted, paginationParams.offset,
        paginationParams.length, scrollTTL, index.getName, node, shard)
    }
  }

  /**
    *
    * @param pathFilter
    * @param fieldsFilter
    * @param datesFilter
    * @param paginationParams
    * @param scrollTTL
    * @param withHistory
    * @param indexNames indices to search on, empty means all.
    * @param onlyNode ES NodeID to restrict search to ("local" means local node), or None for no restriction
    * @return
    */
  def startScroll(pathFilter: Option[PathFilter],
                  fieldsFilter: Option[FieldFilter],
                  datesFilter: Option[DatesFilter],
                  paginationParams: PaginationParams,
                  scrollTTL :Long = defaultScrollTTL,
                  withHistory: Boolean = false,
                  withDeleted: Boolean = false,
                  indexNames: Seq[String] = Seq.empty,
                  onlyNode: Option[String] = None,
                  partition: String = defaultPartition,
                  debugInfo: Boolean = false)
                 (implicit executionContext:ExecutionContext, logger:Logger = loger) : Future[FTSStartScrollResponse] = {
    logger.debug(s"StartScroll request: $pathFilter, $fieldsFilter, $datesFilter, $paginationParams, $withHistory")

    val fields = "system.kind" :: "system.path" :: "system.uuid" :: "system.lastModified" :: "content.length" ::
      "content.mimeType" :: "link.to" :: "link.kind" :: "system.dc" :: "system.indexTime" :: "system.quad" :: Nil

    val indices = if(indexNames.nonEmpty) indexNames else Seq(s"${partition}_all")

    // since in ES scroll API, size is per shard, we need to convert our paginationParams.length parameter to be per shard
    // We need to find how many shards are relevant for this query. For that we'll issue a fake search request
    val fakeRequest = client.prepareSearch(indices:_*).setTypes("infoclone").storedFields(fields:_*)

    if (pathFilter.isEmpty && fieldsFilter.isEmpty && datesFilter.isEmpty) {
      fakeRequest.setQuery(matchAllQuery())
    } else {
      applyFiltersToRequest(fakeRequest, pathFilter, fieldsFilter, datesFilter, withHistory, withDeleted)
    }

    injectFuture[SearchResponse](fakeRequest.execute(_)).flatMap { fakeResponse =>

      val relevantShards = fakeResponse.getSuccessfulShards

      // rounded to lowest multiplacations of shardsperindex or to mimimum of 1
      val infotonsPerShard = (paginationParams.length / relevantShards) max 1

      val request = client.prepareSearch(indices:_*)
        .setTypes("infoclone")
        .storedFields(fields: _*)
        .setScroll(TimeValue.timeValueSeconds(scrollTTL))
        .setSize(infotonsPerShard)
        .setFrom(paginationParams.offset)

      if (pathFilter.isEmpty && fieldsFilter.isEmpty && datesFilter.isEmpty) {
        request.setQuery(matchAllQuery())
      } else {
        applyFiltersToRequest(request, pathFilter, fieldsFilter, datesFilter, withHistory, withDeleted)
      }

      val scrollResponseFuture = injectFuture[SearchResponse](request.execute(_))

      val searchQueryStr = if (debugInfo) Some(request.toString) else None

      scrollResponseFuture.map { scrollResponse => FTSStartScrollResponse(scrollResponse.getHits.totalHits, scrollResponse.getScrollId, searchQueryStr = searchQueryStr) }
    }
  }


  def startSuperMultiScroll(pathFilter: Option[PathFilter], fieldsFilter: Option[FieldFilter],
                            datesFilter: Option[DatesFilter],
                            paginationParams: PaginationParams, scrollTTL: Long = defaultScrollTTL,
                            withHistory: Boolean = false, withDeleted:Boolean = false,
                            partition: String = defaultPartition)
                           (implicit executionContext:ExecutionContext, logger:Logger = loger): Seq[Future[FTSStartScrollResponse]] = {

    logger.debug(s"StartMultiScroll request: $pathFilter, $fieldsFilter, $datesFilter, $paginationParams, $withHistory")
    def indicesNames(indexName: String): Seq[String] = {
      val currentAliasRes = client.admin.indices().prepareGetAliases(indexName).execute().actionGet()
      val indices = currentAliasRes.getAliases.keysIt().asScala.toSeq
      indices
    }

    def dataNodeIDs = {
      client.admin().cluster().prepareNodesInfo().execute().actionGet().getNodesMap.asScala.collect {
        case (id, n) if n.getNode.isDataNode => id
      }.toSeq
    }

    val indices = indicesNames(s"${partition}_all")

    indices.flatMap { indexName =>
      dataNodeIDs.map{ nodeId =>
        startScroll(pathFilter, fieldsFilter, datesFilter, paginationParams, scrollTTL, withHistory, withDeleted,
          Seq(indexName), Some(nodeId))
      }
    }
  }

  def startMultiScroll(pathFilter: Option[PathFilter], fieldsFilter: Option[FieldFilter],
                       datesFilter: Option[DatesFilter],
                       paginationParams: PaginationParams,
                       scrollTTL: Long = defaultScrollTTL,
                       withHistory: Boolean = false,
                       withDeleted: Boolean = false,
                       partition: String = defaultPartition)
                      (implicit executionContext:ExecutionContext, logger:Logger = loger) : Seq[Future[FTSStartScrollResponse]] = {

    logger.debug(s"StartMultiScroll request: $pathFilter, $fieldsFilter, $datesFilter, $paginationParams, $withHistory")

    val indices = {
      val currentAliasRes = client.admin.indices().prepareGetAliases(s"${partition}_all").execute().actionGet()
      currentAliasRes.getAliases.keysIt().asScala.toSeq
    }

    indices.map { indexName =>
      startScroll(pathFilter, fieldsFilter, datesFilter, paginationParams, scrollTTL, withHistory, withDeleted,
        Seq(indexName), None, partition)
    }
  }

  def scroll(scrollId: String, scrollTTL: Long = defaultScrollTTL, nodeId: Option[String] = None)
            (implicit executionContext:ExecutionContext, logger:Logger = loger): Future[FTSScrollResponse] = {

    logger.debug(s"Scroll request: $scrollId, $scrollTTL")

    val clint = nodeId.map{clients(_)}.getOrElse(client)
    val scrollResponseFuture = injectFuture[SearchResponse](
      clint.prepareSearchScroll(scrollId).setScroll(TimeValue.timeValueSeconds(scrollTTL)).execute(_)
    )

    val p = Promise[FTSScrollResponse]()
    scrollResponseFuture.onComplete {
      case Failure(exception) => p.failure(exception)
      case Success(scrollResponse) => {
        val status = scrollResponse.status().getStatus
        if (status >= 400) p.failure(new Exception(s"bad scroll response: $scrollResponse"))
        else {
          if(status != 200)
            logger.warn(s"scroll($scrollId, $scrollTTL, $nodeId) resulted with status[$status] != 200: $scrollResponse")

          p.complete(Try(esResponseToInfotons(scrollResponse, includeScore = false)).map { infotons =>
            FTSScrollResponse(scrollResponse.getHits.getTotalHits, scrollResponse.getScrollId, infotons)
          })
        }
      }
    }
    p.future
  }

  implicit def sortOrder2SortOrder(fieldSortOrder:FieldSortOrder):SortOrder = {
    fieldSortOrder match {
      case Desc => SortOrder.DESC
      case Asc => SortOrder.ASC
    }
  }

  private def applyFiltersToRequest(request:SearchRequestBuilder, pathFilter: Option[PathFilter] = None,
                                    fieldFilterOpt:Option[FieldFilter] = None,
                                    datesFilter: Option[DatesFilter] = None,
                                    withHistory:Boolean = false,
                                    withDeleted:Boolean = false,
                                    preferFilter: Boolean = false) = {

    val boolQueryBuilder = boolQuery()

    if(!withHistory)
      boolQueryBuilder.must(termQuery("system.current", true))

    if(!withHistory && !withDeleted)
      boolQueryBuilder.mustNot(termQuery("system.kind", "DeletedInfoton"))

    pathFilter.foreach{ pf =>
      if(pf.path.equals("/")) {
        if(!pf.descendants) {
          boolQueryBuilder.must(termQuery("system.parent", "/"))
        }
      } else {
        boolQueryBuilder.must( termQuery(if(pf.descendants)"system.parent.parent_hierarchy" else "system.parent", pf.path))
      }
    }

    datesFilter.foreach {
      case DatesFilter(Some(from), Some(to)) => boolQueryBuilder.must(rangeQuery("lastModified").from(from.getMillis).to(to.getMillis))
      case DatesFilter(None, Some(to)) => boolQueryBuilder.must(rangeQuery("lastModified").to(to.getMillis))
      case DatesFilter(Some(from), None) => boolQueryBuilder.must(rangeQuery("lastModified").from(from.getMillis))
      case _ => //do nothing, don't add date filter
    }

    fieldFilterOpt.foreach { ff =>
      applyFieldFilter(ff, boolQueryBuilder)
    }

    def applyFieldFilter(fieldFilter:FieldFilter, outerQueryBuilder:BoolQueryBuilder):Unit = {
      fieldFilter match {
        case SingleFieldFilter(fieldOperator, valueOperator, reversedNsTypedField, valueOpt) =>
          val name = {
            if (reversedNsTypedField.startsWith("system.") || reversedNsTypedField.startsWith("content.") || reversedNsTypedField.startsWith("link.")) reversedNsTypedField
            else reverseNsTypedField(reversedNsTypedField)
          }

          if (valueOpt.isDefined) {
            val value = valueOpt.get
            val (_, fieldName) = splitNamespaceField(reversedNsTypedField)
            val exactFieldName = fieldType(fieldName) match {
              case StringType if(!name.startsWith("system.") && !name.startsWith("content.") && !name.startsWith("link.")) => s"$name.%exact"
              case _ => name
            }
            val valueQuery = valueOperator match {
              case Contains => matchPhraseQuery(name, value)
              case Equals => termQuery(exactFieldName, value)
              case GreaterThan => rangeQuery(exactFieldName).gt(value)
              case GreaterThanOrEquals => rangeQuery(exactFieldName).gte(value)
              case LessThan => rangeQuery(exactFieldName).lt(value)
              case LessThanOrEquals => rangeQuery(exactFieldName).lte(value)
              case Like => fuzzyQuery(name,value)
            }
            fieldOperator match {
              case Must => outerQueryBuilder.must(valueQuery)
              case MustNot => outerQueryBuilder.mustNot(valueQuery)
              case Should => outerQueryBuilder.should(valueQuery)
            }
          } else {
            fieldOperator match {
              case Must => outerQueryBuilder.must(existsQuery(name))
              case MustNot => outerQueryBuilder.mustNot(existsQuery(name))
              case _ => outerQueryBuilder.should(existsQuery(name))
            }
          }
        case MultiFieldFilter(fieldOperator, filters) =>
          val innerQueryBuilder = boolQuery()
          filters.foreach{ ff =>
            applyFieldFilter(ff, innerQueryBuilder)
          }
          fieldOperator match {
            case Must => outerQueryBuilder.must(innerQueryBuilder)
            case MustNot => outerQueryBuilder.mustNot(innerQueryBuilder)
            case Should => outerQueryBuilder.should(innerQueryBuilder)
          }
      }
    }

    val query = if(boolQueryBuilder.hasClauses) boolQueryBuilder else matchAllQuery()

    request.setQuery(query)

  }

  def aggregate(pathFilter: Option[PathFilter], fieldFilter: Option[FieldFilter],
                datesFilter: Option[DatesFilter] = None, paginationParams: PaginationParams,
                aggregationFilters: Seq[AggregationFilter], withHistory: Boolean = false,
                partition: String = defaultPartition, debugInfo: Boolean = false)
               (implicit executionContext:ExecutionContext) : Future[AggregationsResponse] = {

    val request = client.prepareSearch(s"${partition}_all").setTypes("infoclone").setFrom(paginationParams.offset).setSize(paginationParams.length)

    if(pathFilter.isDefined || fieldFilter.nonEmpty || datesFilter.isDefined) {
      applyFiltersToRequest(request, pathFilter, fieldFilter, datesFilter)
    }

    var counter = 0
    val filtersMap:collection.mutable.Map[String, AggregationFilter] = collection.mutable.Map.empty

    def filterToBuilder(filter:AggregationFilter):AggregationBuilder = {

      implicit def fieldValueToValue(fieldValue: Field): String = fieldValue.operator match {
        case AnalyzedField => reverseNsTypedField(fieldValue.value)
        case NonAnalyzedField => s"infoclone.${reverseNsTypedField(fieldValue.value)}.%exact"
      }

      val name = filter.name + "_" + counter
      counter += 1
      filtersMap.put(name, filter)

      val aggBuilder:AggregationBuilder = filter match {
        case TermAggregationFilter(_, field, size, _) =>
          AggregationBuilders.terms(name).field(field).size(size)
        case StatsAggregationFilter(_, field) =>
          AggregationBuilders.stats(name).field(field)
        case HistogramAggregationFilter(_, field, interval, minDocCount, extMin, extMax, _) =>
          val eMin = extMin.asInstanceOf[Option[java.lang.Double]].orNull
          val eMax = extMax.asInstanceOf[Option[java.lang.Double]].orNull
          AggregationBuilders.histogram(name).field(field).interval(interval).minDocCount(minDocCount).extendedBounds(eMin, eMax)
        case SignificantTermsAggregationFilter(_, field, backGroundTermOpt, minDocCount, size, _) =>
          val sigTermsBuilder = AggregationBuilders.significantTerms(name).field(field).minDocCount(minDocCount).size(size)
          backGroundTermOpt.foreach{ backGroundTerm =>
            sigTermsBuilder.backgroundFilter(termQuery(backGroundTerm._1, backGroundTerm._2))
          }
          sigTermsBuilder
        case CardinalityAggregationFilter(_, field, precisionThresholdOpt) =>
          val cardinalityAggBuilder = AggregationBuilders.cardinality(name).field(field)
          precisionThresholdOpt.foreach{ precisionThreshold =>
            cardinalityAggBuilder.precisionThreshold(precisionThreshold)
          }
          cardinalityAggBuilder
      }



      if(filter.isInstanceOf[BucketAggregationFilter]) {
        filter.asInstanceOf[BucketAggregationFilter].subFilters.foreach{ subFilter =>
          aggBuilder.asInstanceOf[AggregationBuilder].subAggregation(filterToBuilder(subFilter))
        }
      }
      aggBuilder
    }

    aggregationFilters.foreach{ filter => request.addAggregation(filterToBuilder(filter)) }

    val searchQueryStr = if(debugInfo) Some(request.toString) else None

    val resFuture = injectFuture[SearchResponse](request.execute(_))

    def esAggsToOurAggs(aggregations:Aggregations, debugInfo:Option[String] = None):AggregationsResponse = {
      AggregationsResponse(
        aggregations.asScala.map {
          case ta: InternalTerms[_,_] =>
            TermsAggregationResponse(
              filtersMap.get(ta.getName).get.asInstanceOf[TermAggregationFilter],
              ta.getBuckets.asScala.map { b =>
                val subAggregations:Option[AggregationsResponse] = b.asInstanceOf[HasAggregations].getAggregations match {
                  case null => None
                  case subAggs => if(subAggs.asList().size()>0) Some(esAggsToOurAggs(subAggs)) else None
                }
                Bucket(FieldValue(b.getKeyAsString), b.getDocCount, subAggregations)
              }
            )
          case sa: InternalStats =>
            StatsAggregationResponse(
              filtersMap.get(sa.getName).get.asInstanceOf[StatsAggregationFilter],
              sa.getCount, sa.getMin, sa.getMax, sa.getAvg, sa.getSum
            )
          case ca:InternalCardinality =>
            CardinalityAggregationResponse(filtersMap.get(ca.getName).get.asInstanceOf[CardinalityAggregationFilter], ca.getValue)
          case ha: Histogram =>
            HistogramAggregationResponse(
              filtersMap.get(ha.getName).get.asInstanceOf[HistogramAggregationFilter],
              ha.getBuckets.asScala.map { b =>
                val subAggregations:Option[AggregationsResponse] = b.asInstanceOf[HasAggregations].getAggregations match {
                  case null => None
                  case subAggs => Some(esAggsToOurAggs(subAggs))
                }
                Bucket(FieldValue(b.getKeyAsString), b.getDocCount, subAggregations)
              }
            )
          case sta:InternalSignificantTerms[_,_] =>
            val buckets = sta.getBuckets.asScala
            SignificantTermsAggregationResponse(
              filtersMap.get(sta.getName).get.asInstanceOf[SignificantTermsAggregationFilter],
              buckets.headOption.fold(0L)(_.getSubsetSize),
              buckets.map { b =>
                val subAggregations:Option[AggregationsResponse] = b.asInstanceOf[HasAggregations].getAggregations match {
                  case null => None
                  case subAggs => Some(esAggsToOurAggs(subAggs))
                }
                SignificantTermsBucket(FieldValue(b.getKeyAsString), b.getDocCount, b.getSignificanceScore, b.getSubsetDf, subAggregations)
              }
            )
          case _ => ???

        }.toSeq
        ,debugInfo)

    }

    resFuture.map{searchResponse => esAggsToOurAggs(searchResponse.getAggregations, searchQueryStr)}
  }

  def rInfo(path: String, scrollTTL: Long, paginationParams: PaginationParams = DefaultPaginationParams,
            withHistory: Boolean = false, partition: String = defaultPartition)
           (implicit executionContext:ExecutionContext): Future[Source[Vector[(Long,String,String)],NotUsed]] = {

    val alias = partition + "_all"

    // since in ES scroll API, size is per shard, we need to convert our paginationParams.length parameter to be per shard
    // We need to find how many shards are relevant for this query. For that we'll issue a fake search request
    val fakeRequest = client.prepareSearch(alias).setTypes("infoclone").storedFields("system.uuid","system.lastModified") // TODO: fix should add indexTime, so why not pull it now?

    fakeRequest.setQuery(QueryBuilders.matchQuery("path", path))

    injectFuture[SearchResponse](al => fakeRequest.execute(al)).flatMap { fakeResponse =>

      val relevantShards = fakeResponse.getSuccessfulShards

      // rounded to lowest multiplacations of shardsperindex or to mimimum of 1
      val infotonsPerShard = (paginationParams.length / relevantShards) max 1

      val request = client.prepareSearch(alias)
        .setTypes("infoclone")
        .storedFields("system.uuid","system.lastModified")
        .setScroll(TimeValue.timeValueSeconds(scrollTTL))
        .setSize(infotonsPerShard)
        .setQuery(QueryBuilders.matchQuery("path", path))

      val scrollResponseFuture = injectFuture[SearchResponse](al => request.execute(al))

      scrollResponseFuture.map { scrollResponse =>

        if(scrollResponse.getHits.totalHits == 0) Source.empty[Vector[(Long,String,String)]]
        else Source.unfoldAsync(scrollResponse.getScrollId) { scrollID =>
          injectFuture[SearchResponse]({ al =>
            client
              .prepareSearchScroll(scrollID)
              .setScroll(TimeValue.timeValueSeconds(scrollTTL))
              .execute(al)
          }, FiniteDuration(30, SECONDS)).map { scrollResponse =>
            val info = rExtractInfo(scrollResponse)
            if (info.isEmpty) None
            else Some(scrollResponse.getScrollId -> info)
          }
        }
      }
    }
  }

  private def rExtractInfo(esResponse: org.elasticsearch.action.search.SearchResponse) : Vector[(Long, String, String)] = {
    val sHits = esResponse.getHits.getHits()
    if (sHits.isEmpty) Vector.empty
    else {
      sHits.map{ hit =>
        val uuid = hit.field("system.uuid").getValue[String]
        val lastModified = new DateTime(hit.field("system.lastModified").getValue[String]).getMillis
        val index = hit.getIndex
        (lastModified, uuid, index)
      }(collection.breakOut)
    }
  }

  def info(path: String, paginationParams: PaginationParams, withHistory: Boolean, partition: String)
          (implicit executionContext:ExecutionContext) : Future[Vector[(String, String)]] = {

    val request = client.prepareSearch(s"${partition}_all").setTypes("infoclone").storedFields("system.uuid").setFrom(paginationParams.offset).setSize(paginationParams.length)

    val qb : QueryBuilder = QueryBuilders.matchQuery("path", path)

    request.setQuery(qb)

    val resFuture = injectFuture[SearchResponse](request.execute)
    resFuture.map { response => extractInfo(response) }
  }

  val bo = collection.breakOut[Array[SearchHit],(String,Long,String),Vector[(String,Long,String)]]

  def uinfo(uuid: String, partition: String)
           (implicit executionContext:ExecutionContext) : Future[Vector[(String, Long, String)]] = {

    val request = client.prepareSearch(s"${partition}_all").setTypes("infoclone").setFetchSource(true).setVersion(true)
    val qb : QueryBuilder = QueryBuilders.matchQuery("uuid", uuid)
    request.setQuery(qb)

    injectFuture[SearchResponse](request.execute).map { response =>
      val hits = response.getHits.getHits()
      hits.map { hit =>
        (hit.getIndex, hit.getVersion, hit.getSourceAsString)
      }(bo)
    }
  }

  private def extractInfo(esResponse: org.elasticsearch.action.search.SearchResponse) : Vector[(String , String )] = {
    if (esResponse.getHits.getHits().nonEmpty) {
      val hits = esResponse.getHits.getHits()
      hits.map{ hit =>
        val uuid = hit.field("system.uuid").getValue[String]
        val index = hit.getIndex
        ( uuid , index)
      }.toVector
    }
    else {
      Vector.empty
    }
  }

  def getLastIndexTimeFor(dc: String, partition: String = defaultPartition, fieldFilters: Option[FieldFilter])
                         (implicit executionContext:ExecutionContext): Future[Option[Long]] = {

    val request = client
      .prepareSearch(s"${partition}_all")
      .setTypes("infoclone")
      .storedFields("system.indexTime")
      .setSize(1)
      .addSort("system.indexTime", SortOrder.DESC)

    val filtersSeq:List[FieldFilter] = List(
      SingleFieldFilter(Must, Equals, "system.dc", Some(dc)),                                 //ONLY DC
      SingleFieldFilter(MustNot, Contains, "system.parent.parent_hierarchy", Some("/meta/")) //NO META
    )
    applyFiltersToRequest(
      request,
      None,
      Some(MultiFieldFilter(Must, fieldFilters.fold(filtersSeq)(filtersSeq.::))),
      None
    )

    injectFuture[SearchResponse](request.execute).map{ sr =>
      val hits = sr.getHits.getHits()
      if(hits.length < 1) None
      else {
        hits.headOption.map(_.field("system.indexTime").getValue[Long])
      }
    }
  }

  private def getValueAs[T](hit: SearchHit, fieldName: String): Try[T] = {
    Try[T](hit.field(fieldName).getValue[T])
  }

  private def tryLongThenInt[V](hit: SearchHit, fieldName: String, f: Long => V, default: V, uuid: String, pathForLog: String)(implicit logger:Logger = loger): V = try {
    getValueAs[Long](hit, fieldName) match {
      case Success(l) => f(l)
      case Failure(e) => {
        e.setStackTrace(Array.empty) // no need to fill the logs with redundant stack trace
        logger.trace(s"$fieldName not Long (outer), uuid = $uuid, path = $pathForLog", e)
        tryInt(hit,fieldName,f,default,uuid)
      }
    }
  } catch {
    case e: Throwable => {
      logger.trace(s"$fieldName not Long (inner), uuid = $uuid", e)
      tryInt(hit,fieldName,f,default,uuid)
    }
  }

  private def tryInt[V](hit: SearchHit, fieldName: String, f: Long => V, default: V, uuid: String)(implicit logger:Logger = loger): V = try {
    getValueAs[Int](hit, fieldName) match {
      case Success(i) => f(i.toLong)
      case Failure(e) => {
        logger.error(s"$fieldName not Int (outer), uuid = $uuid", e)
        default
      }
    }
  } catch {
    case e: Throwable => {
      logger.error(s"$fieldName not Int (inner), uuid = $uuid", e)
      default
    }
  }

  private val memoizedBreakoutForEsResponseToThinInfotons = scala.collection.breakOut[Array[SearchHit],FTSThinInfoton,Vector[FTSThinInfoton]]
  private def esResponseToThinInfotons(esResponse: org.elasticsearch.action.search.SearchResponse, includeScore: Boolean): Seq[FTSThinInfoton] = {
    esResponse.getHits.getHits().map { hit =>
      val path = hit.field("system.path").getValue[String]
      val uuid = hit.field("system.uuid").getValue[String]
      val lastModified = hit.field("system.lastModified").getValue[String]
      val indexTime = tryLongThenInt[Long](hit,"system.indexTime",identity,-1L,uuid,path)
      val score = if(includeScore) Some(hit.getScore()) else None
      FTSThinInfoton(path, uuid, lastModified, indexTime, score)
    }(memoizedBreakoutForEsResponseToThinInfotons)
  }

  private def esResponseToInfotons(esResponse: org.elasticsearch.action.search.SearchResponse, includeScore: Boolean): Vector[Infoton] = {

    if (esResponse.getHits.getHits().nonEmpty) {
      val hits = esResponse.getHits.getHits()
      hits.map{ hit =>
        val path = hit.field("system.path").getValue[String]
        val x = hit.field("system.lastModified")
        x.getValue
        val lastModified = new DateTime(hit.field("system.lastModified").getValue[String])
        val id = hit.field("system.uuid").getValue[String]
        val dc = Try(hit.field("system.dc").getValue[String]).getOrElse(dataCenter)
        val indexTime = tryLongThenInt[Option[Long]](hit,"system.indexTime",Some.apply[Long],None,id,path)
        val score: Option[Map[String, Set[FieldValue]]] = if(includeScore) Some(Map("$score" -> Set(FExtra(hit.getScore(), sysQuad)))) else None

        hit.field("system.kind").getValue[String] match {
          case "ObjectInfoton" =>
            new ObjectInfoton(path, dc, indexTime, lastModified,score){
              override def uuid = id
              override def kind = "ObjectInfoton"
            }
          case "FileInfoton" =>

            val contentLength = tryLongThenInt[Long](hit,"content.length",identity,-1L,id,path)

            new FileInfoton(path, dc, indexTime, lastModified, score, Some(FileContent(hit.field("content.mimeType").getValue[String],contentLength))) {
              override def uuid = id
              override def kind = "FileInfoton"
            }
          case "LinkInfoton" =>
            new LinkInfoton(path, dc, indexTime, lastModified, score, hit.field("link.to").getValue[String], hit.field("link.kind").getValue[Int]) {
              override def uuid = id
              override def kind = "LinkInfoton"
            }
          case "DeletedInfoton" =>
            new DeletedInfoton(path, dc, indexTime, lastModified) {
              override def uuid = id
              override def kind = "DeletedInfoton"
            }
          case unknown => throw new IllegalArgumentException(s"content returned from elasticsearch is illegal [$unknown]") // TODO change to our appropriate exception
        }
      }.toVector
    } else {
      Vector.empty
    }
  }

  def getIndicesNames(partition:String = defaultPartition):Iterable[String] = {
    val currentAliasRes = client.admin.indices().prepareGetAliases(s"${partition}_all").execute().actionGet()
    currentAliasRes.getAliases.keysIt().asScala.toIterable
  }


  /**
    * updates indexTime in ESIndexRequets if needed (by checking if 'newIndexTime' field is non empty
    * @param esIndexRequests
    * @param currentTime
    * @return
    */
  private def updateIndexRequests(esIndexRequests:Iterable[ESIndexRequest], currentTime:Long) : Iterable[ESIndexRequest] = {

    def updateIndexRequest(indexRequest:IndexRequest) :IndexRequest = {
      val source = indexRequest.sourceAsMap()
      val system = source.get("system").asInstanceOf[java.util.HashMap[String, Object]]
      system.replace("indexTime", new java.lang.Long(currentTime))
      indexRequest.source(source)
    }

    esIndexRequests.map{
      case esir@ESIndexRequest(indexRequest:IndexRequest, Some(_)) =>
        esir.copy(esAction = updateIndexRequest(indexRequest))
      case x => x
    }
  }

  private def infotonPathFromDocWriteRequest(actionRequest:DocWriteRequest[_])(implicit logger:Logger = loger) = {
    if(!actionRequest.isInstanceOf[IndexRequest] && !actionRequest.isInstanceOf[UpdateRequest]) {
      logger error s"got an actionRequest:$actionRequest which is non of the supported: IndexRequest or UpdateRequest"
      "Couldn't resolve infoton's path from DocWriteRequest"
    } else{
      val indexRequest =
        if(actionRequest.isInstanceOf[IndexRequest])
          actionRequest.asInstanceOf[IndexRequest]
        else
          actionRequest.asInstanceOf[UpdateRequest].doc()
      //      indexRequest.sourceAsMap().asScala.get("system").asInstanceOf[util.HashMap[String, Any]].get("path").asInstanceOf[String]
      Try{indexRequest.sourceAsMap().get("system").asInstanceOf[java.util.HashMap[String, Any]].get("path").asInstanceOf[java.lang.String]}.getOrElse("Not available")
    }
  }

  private def injectFuture[A](f: ActionListener[A] => Unit, timeout : Duration = FiniteDuration(10, SECONDS))
                             (implicit executionContext:ExecutionContext, logger:Logger = loger) = {
    val p = Promise[A]()
    f(new ActionListener[A] {
      def onFailure(e: Exception): Unit = {
        logger.error("Exception from ElasticSearch.",e)
        if(!p.isCompleted) {
          p.failure(e)
        }
      }
      def onResponse(res: A): Unit =  {
        logger.debug("Response from ElasticSearch:\n%s".format(res.toString))
        p.success(res)
      }
    })
    TimeoutFuture.withTimeout(p.future, timeout)
  }

  object TimeoutScheduler {
    val timer = new HashedWheelTimer(10, TimeUnit.MILLISECONDS)
    def scheduleTimeout(promise:Promise[_], after:Duration) = {
      timer.newTimeout(new TimerTask {
        override def run(timeout:Timeout) = {
          promise.failure(new TimeoutException("Operation timed out after " + after.toMillis + " millis"))
        }
      }, after.toNanos, TimeUnit.NANOSECONDS)
    }
  }

  object TimeoutFuture {
    def withTimeout[T](fut:Future[T], after:Duration)
                      (implicit executionContext: ExecutionContext, logger:Logger = loger) = {
      val prom = Promise[T]()
      val timeout = TimeoutScheduler.scheduleTimeout(prom, after)
      val combinedFut = Future.firstCompletedOf(List(fut, prom.future))
      fut onComplete {
        case _ : Success[T] => timeout.cancel()
        case Failure(error) => {
          logger.warn(s"TimeoutFuture",error)
          timeout.cancel()
        }
      }
      combinedFut
    }
  }

  def extractSource[T : EsSourceExtractor](uuid: String, index: String)
                            (implicit executionContext:ExecutionContext) : Future[(T,Long)] = {
    injectFuture[GetResponse](client.prepareGet(index, "infoclone", uuid).execute(_)).map{ hit =>
      implicitly[EsSourceExtractor[T]].extract(hit) -> hit.getVersion
    }
  }

//  def getMappings(withHistory: Boolean, partition: String)
//                          (implicit executionContext:ExecutionContext) : Future[Set[String]] = {
//    import org.elasticsearch.cluster.ClusterState
//
//    implicit class AsLinkedHashMap[K](lhm: Option[AnyRef]) {
//      def extract(k: K) = lhm match {
//        case Some(m) => Option(m.asInstanceOf[java.util.Map[K,AnyRef]].get(k))
//        case None => None
//      }
//      def extractKeys: Set[K] = lhm.map(_.asInstanceOf[java.util.Map[K,Any]].keySet().asScala.toSet).getOrElse(Set.empty[K])
//      def extractOneValueBy[V](selector: K): Map[K,V] = lhm.map(_.asInstanceOf[java.util.Map[K,Any]].asScala.map{ case (k,vs) => k -> vs.asInstanceOf[java.util.Map[K,V]].get(selector) }.toMap).getOrElse(Map[K,V]())
//    }
//
//    val req = client.admin().cluster().prepareState()
//    val f = injectFuture[ClusterStateResponse](req.execute)
//    val csf: Future[ClusterState] = f.map(_.getState)
//    csf.map { cs =>
//      val b = Set.newBuilder[String]
//      cs.getMetaData.iterator.asScala.filter{ x => x.getIndex.getName.startsWith("cm")}.foreach { imd =>
//        val nested = imd.mapping("infoclone").getSourceAsMap.get("properties").asInstanceOf[java.util.Map[String, Object]]
//        val nsHash = nested.get("fields").asInstanceOf[java.util.Map[String, Object]].get("properties").asInstanceOf[java.util.Map[String, Object]]
//        val fields = nsHash.keySet().asScala.foreach { h =>
//          val inner = nsHash.get(h).asInstanceOf[java.util.Map[String, Object]].get("properties").asInstanceOf[java.util.Map[String, Object]]
//          b ++= inner.extractOneValueBy[String]("type").map {
//            case (k, v) if h == "nn" => s"$k:$v"
//            case (k, v) => s"$k.$h:$v"
//          }
//        }
//      }
//      b.result()
//    }
//  }

    def getMappings(withHistory: Boolean = false, partition: String = defaultPartition)
                   (implicit executionContext: ExecutionContext): Future[Set[String]] = {
      import org.elasticsearch.cluster.ClusterState

      implicit class AsLinkedHashMap[K](lhm: Option[AnyRef]) {
        def extract(k: K) = lhm match {
          case Some(m) => Option(m.asInstanceOf[java.util.LinkedHashMap[K,AnyRef]].get(k))
          case None => None
        }
        def extractKeys: Set[K] = lhm.map(_.asInstanceOf[java.util.LinkedHashMap[K,Any]].keySet().asScala.toSet).getOrElse(Set.empty[K])
        def extractOneValueBy[V](selector: K): Map[K,V] = lhm.map(_.asInstanceOf[java.util.LinkedHashMap[K,Any]].asScala.map{ case (k,vs) => k -> vs.asInstanceOf[java.util.LinkedHashMap[K,V]].get(selector) }.toMap).getOrElse(Map[K,V]())
      }

      val req = client.admin().cluster().prepareState()
      val f = injectFuture[ClusterStateResponse](req.execute)
      val csf: Future[ClusterState] = f.map(_.getState)
      csf.map{ _.getMetaData.asScala.filter( _.getIndex().getName.startsWith("cm")).flatMap{imd =>
        val nested = Some(imd.mapping("infoclone").getSourceAsMap.get("properties"))
        val flds = nested.extract("fields").extract("properties")
        flds.extractOneValueBy[String]("type").map { case (k,v) => s"$k:$v" }
      }.toSet}
    }

  def purgeByUuidsAndIndexes(uuidsAtIndexes: Vector[(String, String)], partition: String)
                                     (implicit executionContext: ExecutionContext): Future[BulkResponse] = {

    // empty request -> empty response
    if(uuidsAtIndexes.isEmpty)
      Future.successful(new BulkResponse(Array[BulkItemResponse](), 0))
    else {
      val bulkRequest = client.prepareBulk()

      uuidsAtIndexes.foreach { case (uuid, index) =>
        bulkRequest.add(client
          .prepareDelete(index, "infoclone", uuid)
          .setVersion(1L)
        )
      }

      injectFuture[BulkResponse](bulkRequest.execute(_))
    }
  }

  def deleteInfotons(infotons:Seq[Infoton])(implicit executionContext: ExecutionContext):Future[Boolean] = {
    assert(infotons.size > 0, "infotons to delete must not be empty")
    val bulkRequest = client.prepareBulk()
    infotons.foreach{ infoton =>
      bulkRequest.add(
        client.prepareDelete(infoton.indexName, "infoclone", infoton.uuid)
      )
    }
    injectFuture[BulkResponse](bulkRequest.execute(_)).map(_ => true)
  }

}

import scala.language.existentials
case class ESIndexRequest(esAction:DocWriteRequest[_], newIndexTime:Option[Long]) {
  override def toString() = {
    if(esAction.isInstanceOf[UpdateRequest]) {
      val updateRequest = esAction.asInstanceOf[UpdateRequest]
      s"UpdateRequest: ${updateRequest.doc.index(updateRequest.index).id(updateRequest.id).`type`(updateRequest.`type`())}"
    } else {
      esAction.toString
    }
  }
}

sealed trait IndexResult {
  val uuid:String
}

case class SuccessfulIndexResult( uuid:String, newIndexTime:Option[Long]) extends IndexResult
case class FailedIndexResult( uuid:String, reason:String, idx:Int) extends IndexResult

trait BulkIndexResult {
  def successful:Iterable[SuccessfulIndexResult] = Nil
  def failed:Iterable[FailedIndexResult] = Nil
}

case class SuccessfulBulkIndexResult(override val successful:Iterable[SuccessfulIndexResult],
                                     override val failed:Iterable[FailedIndexResult] = Nil) extends BulkIndexResult{
  def ++ (other:SuccessfulBulkIndexResult) = SuccessfulBulkIndexResult(successful ++ other.successful, failed ++ other.failed)
}

case class RejectedBulkIndexResult(reason:String) extends BulkIndexResult

object SuccessfulBulkIndexResult {

  def apply(indexRequests:Iterable[ESIndexRequest], esResponse:BulkResponse):SuccessfulBulkIndexResult = {
    val indexedIndexRequests = indexRequests.toIndexedSeq
    val successfulResults = esResponse.getItems.collect{
      case esItemResponse:BulkItemResponse if !esItemResponse.isFailed =>
        SuccessfulIndexResult(esItemResponse.getId, indexedIndexRequests(esItemResponse.getItemId).newIndexTime)
    }
    val failedResults = esResponse.hasFailures match {
      case false => Iterable.empty[FailedIndexResult]
      case true => esResponse.getItems.collect {
        case esItemResponse:BulkItemResponse if esItemResponse.isFailed =>
          FailedIndexResult(esItemResponse.getId, esItemResponse.getFailureMessage, esItemResponse.getItemId)
      }.toIterable
    }
    SuccessfulBulkIndexResult(successfulResults, failedResults)
  }

  def fromSuccessful(indexRequests:Iterable[ESIndexRequest],
                     successfulItemResponses:Iterable[BulkItemResponse]):SuccessfulBulkIndexResult = {
    val indexedIndexRequests = indexRequests.toIndexedSeq
    SuccessfulBulkIndexResult(
      successfulItemResponses.map{ sir =>
        SuccessfulIndexResult(sir.getId, indexedIndexRequests(sir.getItemId).newIndexTime)
      }
    )
  }

  def fromFailed(failedItemResponses:Iterable[BulkItemResponse]) = {
    val failedIndexResults = failedItemResponses.map{ fit => FailedIndexResult(fit.getId, fit.getFailureMessage, fit.getItemId) }
    SuccessfulBulkIndexResult(Nil, failedIndexResults)
  }

}

/**
  * Result of executing bulk actions
  * @param successful iterable of (uuid, indexTime)
  * @param failed iterable of (uuid, failure message)
  */
case class BulkResultWithIndexTime(successful:Iterable[(String, Long)], failed:Iterable[(String, String)] = Iterable.empty[(String, String)]) {
  def ++ (other:BulkResultWithIndexTime) = BulkResultWithIndexTime(successful ++ other.successful, failed ++ other.failed)
}

object BulkResultWithIndexTime {
  def apply(failed:Iterable[(String, String)]) = new BulkResultWithIndexTime(Iterable.empty[(String, Long)], failed)
}

/**
  * Result of executing bulk actions
  * @param successful iterable of (uuid, indexTime)
  * @param failed iterable of (uuid, failure message)
  */
case class BulkResult(successful:Iterable[String], failed:Iterable[(String, String)] = Iterable.empty[(String, String)]) {
  def ++ (other:BulkResult) = BulkResult(successful ++ other.successful, failed ++ other.failed)
}

object BulkResult {
  def apply(failed:Iterable[(String, String)]) = new BulkResult(Iterable.empty[String], failed)
}


object TimeoutScheduler{
  val timer = new HashedWheelTimer(10, TimeUnit.MILLISECONDS)
  def scheduleTimeout(promise: Promise[_], after: Duration) = {
    timer.newTimeout(new TimerTask {
      override def run(timeout:Timeout) = {
        promise.failure(new TimeoutException("Operation timed out after " + after.toMillis + " millis"))
      }
    }, after.toNanos, TimeUnit.NANOSECONDS)
  }
}

object TimeoutFuture {
  def withTimeout[T](fut: Future[T], after: Duration) (implicit executionContext:ExecutionContext) = {
    val prom = Promise[T]()
    val timeout = TimeoutScheduler.scheduleTimeout(prom, after)
    val combinedFut = Future.firstCompletedOf(List(fut, prom.future))
    fut onComplete{case result => timeout.cancel()}
    combinedFut
  }
}


sealed abstract class InfotonToIndex(val infoton: Infoton)
case class CurrentInfotonToIndex(override val infoton: Infoton) extends InfotonToIndex(infoton)
case class PreviousInfotonToIndex(override val infoton: Infoton) extends InfotonToIndex(infoton)
case class DeletedInfotonToIndex(override val infoton: Infoton) extends InfotonToIndex(infoton)


sealed abstract class FieldSortOrder
case object Desc extends FieldSortOrder
case object Asc extends FieldSortOrder

sealed abstract class SortParam
object SortParam {
  type FieldSortParam = (String, FieldSortOrder)
  val empty = FieldSortParams(Nil)
  def apply(sortParam: (String, FieldSortOrder)*) = new FieldSortParams(sortParam.toList)
}
case class FieldSortParams(fieldSortParams: List[SortParam.FieldSortParam]) extends SortParam
case object NullSortParam extends SortParam

sealed abstract class FieldOperator {
  def applyTo(softBoolean: SoftBoolean): SoftBoolean
}
case object Must extends FieldOperator {
  override def applyTo(softBoolean: SoftBoolean): SoftBoolean = softBoolean match {
    case SoftFalse => False
    case unsoftVal => unsoftVal
  }
}
case object Should extends FieldOperator {
  override def applyTo(softBoolean: SoftBoolean): SoftBoolean = softBoolean match {
    case False => SoftFalse
    case softOrTrue => softOrTrue
  }
}
case object MustNot extends FieldOperator {
  override def applyTo(softBoolean: SoftBoolean): SoftBoolean = softBoolean match {
    case True => False
    case _ => True
  }
}

sealed abstract class ValueOperator
case object Contains extends ValueOperator
case object Equals extends ValueOperator
case object GreaterThan extends ValueOperator
case object GreaterThanOrEquals extends ValueOperator
case object LessThan extends ValueOperator
case object LessThanOrEquals extends ValueOperator
case object Like extends ValueOperator
case class PathFilter(path: String, descendants: Boolean)

sealed trait FieldFilter {
  def fieldOperator: FieldOperator
  def filter(i: Infoton): SoftBoolean
}

case class SingleFieldFilter(override val fieldOperator: FieldOperator = Must, valueOperator: ValueOperator,
                             name: String, value: Option[String]) extends FieldFilter {
  def filter(i: Infoton): SoftBoolean = {
    require(valueOperator == Contains || valueOperator == Equals,s"unsupported ValueOperator: $valueOperator")

    val valOp: (String,String) => Boolean = valueOperator match {
      case Contains => (infotonValue,inputValue) => infotonValue.contains(inputValue)
      case Equals => (infotonValue,inputValue) => infotonValue == inputValue
      case _ => ???
    }

    fieldOperator match {
      case Must => i.fields.flatMap(_.get(name).map(_.exists(fv => value.forall(v => valOp(fv.value.toString,v))))).fold[SoftBoolean](False)(SoftBoolean.hard)
      case Should => i.fields.flatMap(_.get(name).map(_.exists(fv => value.forall(v => valOp(fv.value.toString,v))))).fold[SoftBoolean](SoftFalse)(SoftBoolean.soft)
      case MustNot => i.fields.flatMap(_.get(name).map(_.forall(fv => !value.exists(v => valOp(fv.value.toString,v))))).fold[SoftBoolean](True)(SoftBoolean.hard)
    }
  }
}

/**
  * SoftBoolean is a 3-state "boolean" where we need a 2-way mapping
  * between regular booleans and this 3-state booleans.
  *
  * `true` is mapped to `True`
  * `false` is mapped to either `False` or `SoftFalse`, depending on business logic.
  *
  * `True` is mapped to `true`
  * `False` & `SoftFalse` are both mapped to `false`.
  *
  * You may think of `SoftFalse` as an un-commited false,
  * where we don't "fail fast" an expression upon `SoftFalse`,
  * and may still succeed with `True` up ahead.
  */
object SoftBoolean {
  def hard(b: Boolean): SoftBoolean = if(b) True else False
  def soft(b: Boolean): SoftBoolean = if(b) True else SoftFalse
  def zero: SoftBoolean = SoftFalse
}
sealed trait SoftBoolean {
  def value: Boolean
  def combine(that: SoftBoolean): SoftBoolean = this match {
    case False => this
    case SoftFalse => that
    case True => that match {
      case False => that
      case _ => this
    }
  }
}

case object True extends SoftBoolean { override val value = true }
case object False extends SoftBoolean { override val value = false }
case object SoftFalse extends SoftBoolean { override val value = false }

case class MultiFieldFilter(override val fieldOperator: FieldOperator = Must,
                            filters: Seq[FieldFilter]) extends FieldFilter {
  def filter(i: Infoton): SoftBoolean = {
    fieldOperator.applyTo(
      filters.foldLeft(SoftBoolean.zero){
        case (b,f) => b.combine(f.filter(i))
      })
  }
}

object FieldFilter {
  def apply(fieldOperator: FieldOperator, valueOperator: ValueOperator, name: String, value: String) =
    new SingleFieldFilter(fieldOperator, valueOperator, name, Some(value))
}

case class DatesFilter(from: Option[DateTime], to:Option[DateTime])
case class PaginationParams(offset: Int, length: Int)
object DefaultPaginationParams extends PaginationParams(0, 100)

case class FTSSearchResponse(total: Long, offset: Long, length: Long, infotons: Seq[Infoton], searchQueryStr: Option[String] = None)
case class FTSStartScrollResponse(total: Long, scrollId: String, nodeId: Option[String] = None, searchQueryStr: Option[String] = None)
case class FTSScrollResponse(total: Long, scrollId: String, infotons: Seq[Infoton], nodeId: Option[String] = None)
case class FTSScrollThinResponse(total: Long, scrollId: String, thinInfotons: Seq[FTSThinInfoton], nodeId: Option[String] = None)
case object FTSTimeout

case class FTSThinInfoton(path: String, uuid: String, lastModified: String, indexTime: Long, score: Option[Float])
case class FTSThinSearchResponse(total: Long, offset: Long, length: Long, thinInfotons: Seq[FTSThinInfoton], searchQueryStr: Option[String] = None)