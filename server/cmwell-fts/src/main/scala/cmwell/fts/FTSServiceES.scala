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
import java.util
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{Executors, TimeUnit, TimeoutException}

import akka.NotUsed
import akka.stream.scaladsl.Source
import cmwell.common.formats.JsonSerializer
import cmwell.domain._
import cmwell.util.jmx._
import cmwell.common.exception._
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse
import org.elasticsearch.action.bulk.{BulkItemResponse, BulkResponse}
import org.elasticsearch.action.delete.DeleteResponse
import org.elasticsearch.action.deletebyquery.DeleteByQueryResponse
import org.elasticsearch.action.get.GetResponse
import org.elasticsearch.action.index.{IndexRequest, IndexResponse}
import org.elasticsearch.action.search.{SearchRequestBuilder, SearchResponse, SearchType}
import org.elasticsearch.action.update.UpdateRequest
import org.elasticsearch.action.{ActionListener, ActionRequest, WriteConsistencyLevel}
import org.elasticsearch.client.Client
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.netty.util.{HashedWheelTimer, Timeout, TimerTask}
import org.elasticsearch.common.settings.ImmutableSettings
import org.elasticsearch.common.transport.InetSocketTransportAddress
import org.elasticsearch.common.unit.TimeValue
import org.elasticsearch.index.VersionType
import org.elasticsearch.index.query.{BoolFilterBuilder, BoolQueryBuilder, QueryBuilder, QueryBuilders}
import org.elasticsearch.index.query.FilterBuilders._
import org.elasticsearch.index.query.QueryBuilders._
import org.elasticsearch.node.Node
import org.elasticsearch.node.NodeBuilder._
import org.elasticsearch.search.SearchHit
import org.elasticsearch.search.aggregations._
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram
import org.elasticsearch.search.aggregations.bucket.significant.InternalSignificantTerms
import org.elasticsearch.search.aggregations.bucket.terms.InternalTerms
import org.elasticsearch.search.aggregations.metrics.cardinality.InternalCardinality
import org.elasticsearch.search.aggregations.metrics.stats.InternalStats
import org.elasticsearch.search.sort.SortBuilders._
import org.elasticsearch.search.sort.SortOrder
import org.joda.time.DateTime

import scala.collection.JavaConverters._
import scala.compat.Platform._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util._
import scala.language.implicitConversions
/**
* User: Israel
* Date: 11/5/12
* Time: 10:19 AM
*/

object FTSServiceES {
  def getOne(classPathConfigFile: String, waitForGreen: Boolean = true) =
    new FTSServiceES(classPathConfigFile, waitForGreen)
}

object Settings {
  val config = ConfigFactory.load()
  val isTransportClient = config.getBoolean("ftsService.isTransportClient")
  val transportAddress = config.getString("ftsService.transportAddress")
  val transportPort = config.getInt("ftsService.transportPort")
  val defPartition = config.getString("ftsService.defaultPartition")
  val clusterName = config.getString("ftsService.clusterName")
  val scrollTTL = config.getLong("ftsService.scrollTTL")
  val scrollLength = config.getInt("ftsService.scrollLength")
  val dataCenter = config.getString("dataCenter.id")
}

class FTSServiceES private(classPathConfigFile: String, waitForGreen: Boolean)
  extends FTSServiceOps with LazyLogging with FTSServiceESMBean {

  import cmwell.fts.Settings._

  override val defaultPartition = defPartition
  override val defaultScrollTTL = scrollTTL

  var client:Client = _
  var node:Node = null

  @volatile var totalRequestedToIndex:Long = 0
  val totalIndexed  = new AtomicLong(0)
  val totalFailedToIndex = new AtomicLong(0)

  jmxRegister(this, "cmwell.indexer:type=FTSService")

  if(isTransportClient) {
    val esSettings = ImmutableSettings.settingsBuilder.put("cluster.name", clusterName).build
    // if(transportAddress=="localhost") InetAddress.getLocalHost.getHostName else
    val actualTransportAddress = transportAddress
    client = new TransportClient(esSettings).addTransportAddress(new InetSocketTransportAddress(actualTransportAddress, transportPort))
    logger.info(s"starting es transport client [/$actualTransportAddress:$transportPort]")
  } else {
    val esSettings = ImmutableSettings.settingsBuilder().loadFromClasspath(classPathConfigFile)
    node = nodeBuilder().settings(esSettings).node()
    client = node.client()
  }

  val localHostName = InetAddress.getLocalHost.getHostName
  logger.info(s"localhostname: $localHostName")

  val nodesInfo = client.admin().cluster().prepareNodesInfo().execute().actionGet()

  logger.info (s"nodesInfo: $nodesInfo")

  val localNodeId = client.admin().cluster().prepareNodesInfo().execute().actionGet().getNodesMap.asScala.filter{case (id, node) =>
    node.getHostname.equals(localHostName) && node.getNode.isDataNode
  }.map{_._1}.head
  
  val clients:Map[String, Client] = isTransportClient match {
    case true =>
      nodesInfo.getNodes.filterNot( n => ! n.getNode.dataNode()).map{ node =>
        val nodeId = node.getNode.getId
        val nodeHostName = node.getNode.getHostName

        val clint = nodeHostName.equalsIgnoreCase(localHostName) match {
          case true => client
          case false =>
            val transportAddress = node.getNode.getAddress
            val settings = ImmutableSettings.settingsBuilder.
              put("cluster.name", node.getSettings.get("cluster.name"))
              .put("transport.netty.worker_count", 3)
              .put("transport.connections_per_node.recovery", 1)
              .put("transport.connections_per_node.bulk", 1)
              .put("transport.connections_per_node.reg", 2)
              .put("transport.connections_per_node.state", 1)
              .put("transport.connections_per_node.ping", 1).build
            new TransportClient(settings).addTransportAddress(transportAddress)
        }
        (nodeId, clint)
      }.toMap
    
    case false =>
      Map(localNodeId -> client)
  }

  if(waitForGreen) {
      logger info "waiting for ES green status"
    // wait for green status
    client.admin().cluster()
      .prepareHealth()
      .setWaitForGreenStatus()
      .setTimeout(TimeValue.timeValueMinutes(5))
      .execute()
      .actionGet()

    logger info "got green light from ES"
  } else {
      logger info "waiting for ES yellow status"
    // wait for yellow status
    client.admin().cluster()
      .prepareHealth()
      .setWaitForYellowStatus()
      .setTimeout(TimeValue.timeValueMinutes(5))
      .execute()
      .actionGet()

    logger info "got yellow light from ES"
  }

  def getTotalRequestedToIndex(): Long = totalRequestedToIndex

  def getTotalIndexed(): Long = totalIndexed.get()

  def getTotalFailedToIndex(): Long = totalFailedToIndex.get()

  def close() {
    if (client != null)
      client.close()
    if (node != null && !node.isClosed)
      node.close()
    jmxUnRegister("cmwell.indexer:type=FTSService")

  }


  /**
   * Add given Infoton to Current index. If previous version of this Infoton passed, it will
   * be added to History index
    *
    * @param infoton
   * @param previousInfoton previous version of this infoton
   * @param partition logical name of partition. Used for targeting a specific index
   */
  def index(infoton: Infoton, previousInfoton: Option[Infoton] = None, partition: String = defaultPartition)
           (implicit executionContext: ExecutionContext): Future[Unit] = {
    logger debug ("indexing current: " + infoton.uuid)

    totalRequestedToIndex += 1

    val infotonToIndex = new String(JsonSerializer.encodeInfoton(infoton, toEs = true), "utf-8")
    val currentFuture = injectFuture[IndexResponse](client.prepareIndex(partition + "_current", "infoclone", infoton.uuid).setSource(JsonSerializer.encodeInfoton(infoton, toEs = true)).setConsistencyLevel(WriteConsistencyLevel.ALL).execute(_)).map{_ => }

    currentFuture.andThen {
      case Failure(t) => logger debug ("failed to index infoton uuid: " + infoton.uuid + "\n" + t.getLocalizedMessage + "\n" + t.getStackTrace().mkString("", EOL, EOL))
      case Success(v) => logger debug ("successfully indexed infoton uuid: " + infoton.uuid); totalIndexed.addAndGet(1)
    }

    if(previousInfoton.isDefined) {
      totalRequestedToIndex += 1
      val previousWriteFuture = injectFuture[IndexResponse](client.prepareIndex(partition + "_history", "infoclone", previousInfoton.get.uuid).setSource(JsonSerializer.encodeInfoton(previousInfoton.get, toEs = true)).setConsistencyLevel(WriteConsistencyLevel.ALL).execute(_)).map{_ => }
      val previousDeleteFuture = injectFuture[DeleteResponse](client.prepareDelete(partition + "_current", "infoclone", previousInfoton.get.uuid).setConsistencyLevel(WriteConsistencyLevel.ALL).execute(_)).map{ _.isFound }.andThen{case Failure(t) => logger debug ("failed to delete infoton uuid: " + infoton.uuid + "\n" + t.getLocalizedMessage + "\n" + t.getStackTrace().mkString("", EOL, EOL)); case Success(v) => logger debug ("successfully deleted infoton uuid: " + infoton.uuid)}.map{ _ => }
      currentFuture.flatMap( _ => previousWriteFuture).flatMap(_ => previousDeleteFuture)
    } else {
      currentFuture.map{ _ => }
    }

  }

  val scheduler = Executors.newSingleThreadScheduledExecutor()

  def extractSource(uuid: String, index: String)(implicit executionContext: ExecutionContext) = {
    injectFuture[GetResponse](client.prepareGet(index, "infoclone", uuid).execute(_)).map(_.getSourceAsString)
  }

  def executeBulkActionRequests(actionRequests: Iterable[ActionRequest[_ <: ActionRequest[_ <: AnyRef]]])
                               (implicit executionContext: ExecutionContext) = {
    val requestedToIndexSize = actionRequests.size
    totalRequestedToIndex += actionRequests.size
    val bulkRequest = client.prepareBulk()
    bulkRequest.request().add(actionRequests.asJava)

    val response = injectFuture[BulkResponse](bulkRequest.execute(_))
    response.onComplete{
      case Success(response) if !response.hasFailures =>
        logger debug (s"successfully indexed ${requestedToIndexSize} infotons")
        totalIndexed.addAndGet(actionRequests.size)
        response.getItems.foreach{r =>
          if( !r.getOpType.equalsIgnoreCase("delete") && r.getVersion > 1 ) {
            logger error (s"just wrote duplicate infoton: ${r.getId}")
          }
        }
      case Success(response) =>
        try {
          val failed = response.getItems.filter(_.isFailed).map(_.getId)
          // here we get if got response that has failures
          logger error (s"failed to index ${failed.size} out of $requestedToIndexSize infotons: ${failed.mkString(",")}")
          totalFailedToIndex.addAndGet(failed.size)
          totalIndexed.addAndGet(requestedToIndexSize - failed.size)
          response.getItems.foreach {
            r =>
              if (!r.getOpType.equalsIgnoreCase("delete") && r.getVersion > 1) {
                logger debug (s"just wrote duplicate infoton: ${r.getId}")
              }
              if (r.isFailed) {
                logger debug (s"failed infoton: ${r.getId}")
              }
          }
        }catch {
          case t:Throwable =>
            logger error (s"exception while handling ES response errors:\n ${getStackTrace(t)}")
        }
      case Failure(t) =>
        logger error (s"exception during bulk indexing\n${getStackTrace(t)}")
        totalFailedToIndex.addAndGet(requestedToIndexSize)
    }

    response

  }

  def executeBulkIndexRequests(indexRequests:Iterable[ESIndexRequest], numOfRetries: Int = 15,
                               waitBetweenRetries:Long = 3000)
                              (implicit executionContext:ExecutionContext) : Future[SuccessfulBulkIndexResult] = ???



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
    csf.map(_.getMetaData.iterator.asScala.filter(_.index().startsWith("cm")).map{imd =>
      val nested = Some(imd.mapping("infoclone").getSourceAsMap.get("properties"))
      val flds = nested.extract("fields").extract("properties")
      flds.extractOneValueBy[String]("type").map { case (k,v) => s"$k:$v" }
    }.flatten.toSet)
  }

  def bulkIndex(currentInfotons: Seq[Infoton], previousInfotons: Seq[Infoton] = Nil, partition: String = defaultPartition)
               (implicit executionContext: ExecutionContext) = {
    val bulkRequest = client.prepareBulk()
    currentInfotons.foreach{ current =>
      val indexSuffix = if(current.isInstanceOf[DeletedInfoton]) "_history" else "_current"
      bulkRequest.add(client.prepareIndex(partition + indexSuffix, "infoclone", current.uuid).setSource(JsonSerializer.encodeInfoton(current, toEs = true)))
    }
    previousInfotons.foreach{ previous =>
      bulkRequest.add(client.prepareIndex(partition + "_history", "infoclone", previous.uuid).setSource(JsonSerializer.encodeInfoton(previous, toEs = true)))
      bulkRequest.add(client.prepareDelete(partition + "_current", "infoclone", previous.uuid))
    }
    injectFuture[BulkResponse](bulkRequest.execute)
  }

  /**
   * Deletes given infoton from Current index, save it to the history index and put a tomb stone
   * to mark it is deleted
    *
    * @param deletedInfoton Deleted Infoton (tombstone) to index in history index
   * @param previousInfoton last version of this infoton to index in history and remove from current
   * @param partition logical name of partition. Used for targeting a specific index
   */
  def delete(deletedInfoton: Infoton, previousInfoton: Infoton, partition: String = defaultPartition)
            (implicit executionContext: ExecutionContext): Future[Boolean] = {

    for {
      // index previous Infoton in history index
      prev <- injectFuture[IndexResponse](client.prepareIndex(partition + "_history", "infoclone", previousInfoton.uuid).setSource(JsonSerializer.encodeInfoton(previousInfoton, toEs = true)).execute(_))

      // index tomb stone in history index
      tomb <- injectFuture[IndexResponse](client.prepareIndex(partition + "_history", "infoclone", deletedInfoton.uuid).setSource(JsonSerializer.encodeInfoton(deletedInfoton, toEs = true)).execute(_))

      // delete from current index
      deleted <- injectFuture[DeleteResponse](client.prepareDelete(partition + "_current", "infoclone", previousInfoton.uuid).execute(_))
    } yield true

  }

  /**
   * Completely erase !! infoton of given UUID from History index.
    *
    * @param uuid
   * @param partition logical name of partition. Used for targeting a specific index
   */
  def purge(uuid: String,  partition: String = defaultPartition)
           (implicit executionContext: ExecutionContext): Future[Boolean] = {
    injectFuture[DeleteResponse](client.prepareDelete(partition + "_history", "infoclone", uuid).execute(_)).map(x=> true)
  }

  def purgeByUuids(historyUuids: Seq[String], currentUuid: Option[String], partition: String = defaultPartition)
                  (implicit executionContext: ExecutionContext): Future[BulkResponse] = {

    // empty request -> empty response
    if(historyUuids.isEmpty && currentUuid.isEmpty)
      Future.successful(new BulkResponse(Array[BulkItemResponse](), 0))
    else {
      val currentIndices = getIndicesNamesByType("current", partition)
      val historyIndices = getIndicesNamesByType("history", partition)
      val bulkRequest = client.prepareBulk()

      for (uuid <- historyUuids; index <- historyIndices) {
        bulkRequest.add(client
          .prepareDelete(index, "infoclone", uuid)
          .setVersionType(VersionType.FORCE)
          .setVersion(1L)
        )
      }

      for (uuid <- currentUuid; index <- currentIndices) {
        bulkRequest.add(client
          .prepareDelete(index, "infoclone", uuid)
          .setVersionType(VersionType.FORCE)
          .setVersion(1L)
        )
      }

      injectFuture[BulkResponse](bulkRequest.execute(_))
    }
  }

  def purgeByUuidsAndIndexes(uuidsAtIndexes: Vector[(String,String)], partition: String = defaultPartition)
                            (implicit executionContext: ExecutionContext): Future[BulkResponse] = {
    // empty request -> empty response
    if(uuidsAtIndexes.isEmpty)
      Future.successful(new BulkResponse(Array[BulkItemResponse](), 0))
    else {
      val bulkRequest = client.prepareBulk()
      uuidsAtIndexes.foreach { case (uuid,index) =>
        bulkRequest.add(client
          .prepareDelete(index, "infoclone", uuid)
          .setVersionType(VersionType.FORCE)
          .setVersion(1L)
        )
      }
      injectFuture[BulkResponse](bulkRequest.execute(_))
    }
  }

  def purgeByUuidsFromAllIndexes(uuids: Vector[String], partition: String = defaultPartition)
                                (implicit executionContext: ExecutionContext): Future[BulkResponse] = {

    // empty request -> empty response
    if(uuids.isEmpty)
      Future.successful(new BulkResponse(Array[BulkItemResponse](), 0))
    else {
      val currentIndices = getIndicesNamesByType("current", partition)
      val historyIndices = getIndicesNamesByType("history", partition)
      val bulkRequest = client.prepareBulk()

      for (uuid <- uuids; index <- historyIndices) {
        bulkRequest.add(client
          .prepareDelete(index, "infoclone", uuid)
          .setVersionType(VersionType.FORCE)
          .setVersion(1L)
        )
      }

      for (uuid <- uuids; index <- currentIndices) {
        bulkRequest.add(client
          .prepareDelete(index, "infoclone", uuid)
          .setVersionType(VersionType.FORCE)
          .setVersion(1L)
        )
      }

      injectFuture[BulkResponse](bulkRequest.execute(_))
    }
  }


  /**
   *
   * ONLY USE THIS IF YOU HAVE NO CHOICE, IF YOU CAN SOMEHOW GET UUIDS - PURGING BY UUIDS HAS BETTER PERFORMANCE
   *
   * Completely erase all versions of infoton with given path
    *
    * @param path
   * @param isRecursive whether to erase all children of this infoton of all versions (found by path)
   * @param partition logical name of partition. Used for targeting a specific index
   */
  def purgeAll(path: String, isRecursive: Boolean = true, partition: String = defaultPartition)
              (implicit executionContext: ExecutionContext): Future[Boolean] = {
    val indices = getIndicesNamesByType("history") ++ getIndicesNamesByType("current")
    injectFuture[DeleteByQueryResponse](client.prepareDeleteByQuery((indices):_*)
      .setTypes("infoclone").setQuery( termQuery("path" , path) ).execute(_)).map(x =>  true)
  }

  /**
   *
   * ONLY USE THIS IF YOU HAVE NO CHOICE, IF YOU CAN SOMEHOW GET UUIDS - PURGING BY UUIDS HAS BETTER PERFORMANCE
   *
   *
   * Completely erase the current one, but none of historical versions of infoton with given path
   * This makes no sense unless you re-index the current one right away. Currently the only usage is by x-fix-dc
    *
    * @param path
   * @param isRecursive whether to erase all children of this infoton of all versions (found by path)
   * @param partition logical name of partition. Used for targeting a specific index
   */
  def purgeCurrent(path: String, isRecursive: Boolean = true, partition: String = defaultPartition)
                  (implicit executionContext: ExecutionContext): Future[Boolean] = {
    val indices = getIndicesNamesByType("current")
    injectFuture[DeleteByQueryResponse](client.prepareDeleteByQuery((indices):_*)
      .setTypes("infoclone").setQuery( termQuery("path" , path) ).execute(_)).map(x =>  true)
  }

  /**
   *
   * ONLY USE THIS IF YOU HAVE NO CHOICE, IF YOU CAN SOMEHOW GET UUIDS - PURGING BY UUIDS HAS BETTER PERFORMANCE
   *
   *
   * Completely erase all historical versions of infoton with given path, but not the current one
    *
    * @param path
   * @param isRecursive whether to erase all children of this infoton of all versions (found by path)
   * @param partition logical name of partition. Used for targeting a specific index
   */
  def purgeHistory(path: String, isRecursive: Boolean = true, partition: String = defaultPartition)
                  (implicit executionContext: ExecutionContext): Future[Boolean] = {
    val indices = getIndicesNamesByType("history")
    injectFuture[DeleteByQueryResponse](client.prepareDeleteByQuery((indices):_*)
      .setTypes("infoclone").setQuery( termQuery("path" , path) ).execute(_)).map(x =>  true)
  }

  def getIndicesNamesByType(suffix : String , partition:String = defaultPartition) = {
    val currentAliasRes = client.admin.indices().prepareGetAliases(s"${partition}_${suffix}").execute().actionGet()
    val indices = currentAliasRes.getAliases.keysIt().asScala.toSeq
    indices
  }

  private def esResponseToThinInfotons(esResponse: org.elasticsearch.action.search.SearchResponse, includeScore: Boolean)
                                      (implicit executionContext: ExecutionContext): Seq[FTSThinInfoton] = {
    if (esResponse.getHits().hits().nonEmpty) {
      val hits = esResponse.getHits().hits()
      hits.map { hit =>
        val path = hit.field("system.path").value.asInstanceOf[String]
        val uuid = hit.field("system.uuid").value.asInstanceOf[String]
        val lastModified = hit.field("system.lastModified").value.asInstanceOf[String]
        val indexTime = hit.field("system.indexTime").value.asInstanceOf[Long]
        val score = if(includeScore) Some(hit.score()) else None
        FTSThinInfoton(path, uuid, lastModified, indexTime, score)
      }.toSeq
    } else {
      Seq.empty
    }
  }

  private def esResponseToInfotons(esResponse: org.elasticsearch.action.search.SearchResponse, includeScore: Boolean):Vector[Infoton] = {

    def getValueAs[T](hit: SearchHit, fieldName: String): Try[T] = {
      Try[T](hit.field(fieldName).getValue[T])
    }

    def tryLongThenInt[V](hit: SearchHit, fieldName: String, f: Long => V, default: V, uuid: String, pathForLog: String): V = try {
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

    def tryInt[V](hit: SearchHit, fieldName: String, f: Long => V, default: V, uuid: String): V = try {
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


    if (esResponse.getHits().hits().nonEmpty) {
      val hits = esResponse.getHits().hits()
      hits.map{ hit =>
        val path = hit.field("system.path").getValue.asInstanceOf[String]
        val lastModified = new DateTime(hit.field("system.lastModified").getValue.asInstanceOf[String])
        val id = hit.field("system.uuid").getValue.asInstanceOf[String]
        val dc = Try(hit.field("system.dc").getValue.asInstanceOf[String]).getOrElse(Settings.dataCenter)

        val indexTime = tryLongThenInt[Option[Long]](hit,"system.indexTime",Some.apply[Long],None,id,path)
        val score: Option[Map[String, Set[FieldValue]]] = if(includeScore) Some(Map("$score" -> Set(FExtra(hit.score(), sysQuad)))) else None

        hit.field("type").getValue.asInstanceOf[String] match {
          case "ObjectInfoton" =>
            new ObjectInfoton(path, dc, indexTime, lastModified,score){
              override def uuid = id
              override def kind = "ObjectInfoton"
            }
          case "FileInfoton" =>

            val contentLength = tryLongThenInt[Long](hit,"content.length",identity,-1L,id,path)

            new FileInfoton(path, dc, indexTime, lastModified, score, Some(FileContent(hit.field("content.mimeType").getValue.asInstanceOf[String],contentLength))) {
              override def uuid = id
              override def kind = "FileInfoton"
            }
          case "LinkInfoton" =>
            new LinkInfoton(path, dc, indexTime, lastModified, score, hit.field("linkTo").getValue.asInstanceOf[String], hit.field("linkType").getValue[Int]) {
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

  /**
   * Get Infoton's current version children
    *
    * @param path
   * @param partition logical name of partition. Used for targeting a specific index
   * @return list of found infotons
   */
  def listChildren(path: String, offset: Int = 0, length: Int = 20, descendants: Boolean = false,
                   partition:String = defaultPartition)
                  (implicit executionContext:ExecutionContext): Future[FTSSearchResponse] = {

    search(pathFilter=Some(PathFilter(path, descendants)), paginationParams = PaginationParams(offset, length))
  }

  trait FieldType {
    def unmapped:String
  }
  case object DateType extends FieldType {
    override def unmapped = "date"
  }
  case object IntType extends FieldType {
    override def unmapped = "integer"
  }
  case object LongType extends FieldType {
    override def unmapped = "long"
  }
  case object FloatType extends FieldType {
    override def unmapped = "float"
  }
  case object DoubleType extends FieldType {
    override def unmapped = "double"
  }
  case object BooleanType extends FieldType {
    override def unmapped = "boolean"
  }
  case object StringType extends FieldType {
    override def unmapped = "string"
  }

  private def fieldType(fieldName:String) = {
    fieldName match {
      case "system.lastModified" => DateType
      case "type" | "system.parent" | "system.path" | "system.uuid" | "system.dc" | "system.quad" | "content.data" |
           "content.base64-data" | "content.mimeType" => StringType
      case "content.length" | "system.indexTime" => LongType
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

  private def applyFiltersToRequest(request: SearchRequestBuilder, pathFilter: Option[PathFilter] = None,
                                    fieldFilterOpt: Option[FieldFilter] = None,
                                    datesFilter: Option[DatesFilter] = None,
                                    withHistory: Boolean = false,
                                    withDeleted: Boolean = false,
                                    preferFilter: Boolean = false) = {

    val boolFilterBuilder:BoolFilterBuilder = boolFilter()

    pathFilter.foreach{ pf =>
      if(pf.path.equals("/")) {
        if(!pf.descendants) {
          boolFilterBuilder.must(termFilter("parent", "/"))
        }
      } else {
        boolFilterBuilder.must( termFilter(if(pf.descendants)"parent_hierarchy" else "parent", pf.path))
      }
    }

    datesFilter.foreach { df =>
      boolFilterBuilder.must(rangeFilter("lastModified")
        .from(df.from.map[Any](_.getMillis).orNull)
        .to(df.to.map[Any](_.getMillis).orNull))
    }

    val fieldsOuterQueryBuilder = boolQuery()

    fieldFilterOpt.foreach { ff =>
      applyFieldFilter(ff, fieldsOuterQueryBuilder)
    }

    def applyFieldFilter(fieldFilter: FieldFilter, outerQueryBuilder: BoolQueryBuilder): Unit = {
      fieldFilter match {
        case SingleFieldFilter(fieldOperator, valueOperator, name, valueOpt) =>
          if (valueOpt.isDefined) {
            val value = valueOpt.get
            val exactFieldName = fieldType(name) match {
              case StringType if (!name.startsWith("system")) => s"fields.${name}.%exact"
              case _ => name
            }
            val valueQuery = valueOperator match {
              case Contains => matchPhraseQuery(name, value)
              case Equals => termQuery(exactFieldName, value)
              case GreaterThan => rangeQuery(exactFieldName).gt(value)
              case GreaterThanOrEquals => rangeQuery(exactFieldName).gte(value)
              case LessThan => rangeQuery(exactFieldName).lt(value)
              case LessThanOrEquals => rangeQuery(exactFieldName).lte(value)
              case Like => fuzzyLikeThisFieldQuery(name).likeText(value)
            }
            fieldOperator match {
              case Must => outerQueryBuilder.must(valueQuery)
              case MustNot => outerQueryBuilder.mustNot(valueQuery)
              case Should => outerQueryBuilder.should(valueQuery)
            }
          } else {
            fieldOperator match {
              case Must => outerQueryBuilder.must(filteredQuery(matchAllQuery(), existsFilter(name)))
              case MustNot => outerQueryBuilder.must(filteredQuery(matchAllQuery(), missingFilter(name)))
              case _ => outerQueryBuilder.should(filteredQuery(matchAllQuery(), existsFilter(name)))
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



//    val preferFilter = false
//
//    (fieldFilterOpt.nonEmpty, pathFilter.isDefined || datesFilter.isDefined, preferFilter) match {
//      case (true,_, false) =>
//        val query = filteredQuery(fieldsOuterQueryBuilder, boolFilterBuilder)
//        request.setQuery(query)
//      case (true, _, true) =>
//        val query = filteredQuery(matchAllQuery(), andFilter(queryFilter(fieldsOuterQueryBuilder), boolFilterBuilder))
//        request.setQuery(query)
//      case (false, true, _) =>
//        request.setQuery(filteredQuery(matchAllQuery(), boolFilterBuilder))
//      case (false, false, _) => // this option is not possible due to the validation at the beginning of the method
//    }

    val query = (fieldsOuterQueryBuilder.hasClauses, boolFilterBuilder.hasClauses) match {
      case (true, true) =>
        filteredQuery(fieldsOuterQueryBuilder, boolFilterBuilder)
      case (false, true) =>
        filteredQuery(matchAllQuery(), boolFilterBuilder)
      case (true, false) =>
        if(preferFilter)
          filteredQuery(matchAllQuery(), queryFilter(fieldsOuterQueryBuilder))
        else
          fieldsOuterQueryBuilder
      case _ => matchAllQuery()
    }

    request.setQuery(query)

  }

  implicit def sortOrder2SortOrder(fieldSortOrder:FieldSortOrder):SortOrder = {
    fieldSortOrder match {
      case Desc => SortOrder.DESC
      case Asc => SortOrder.ASC
    }
  }


  def aggregate(pathFilter: Option[PathFilter] = None, fieldFilter: Option[FieldFilter],
                datesFilter: Option[DatesFilter] = None, paginationParams: PaginationParams = DefaultPaginationParams,
                aggregationFilters: Seq[AggregationFilter], withHistory: Boolean = false,
                partition: String = defaultPartition, debugInfo: Boolean = false)
               (implicit executionContext: ExecutionContext): Future[AggregationsResponse] = {

    val indices = (partition + "_current") :: (withHistory match{
      case true => partition + "_history" :: Nil
      case false => Nil
    })

    val request = client.prepareSearch(indices:_*).setTypes("infoclone").setFrom(paginationParams.offset).setSize(paginationParams.length).setSearchType(SearchType.COUNT)

    if(pathFilter.isDefined || fieldFilter.nonEmpty || datesFilter.isDefined) {
      applyFiltersToRequest(request, pathFilter, fieldFilter, datesFilter)
    }

    var counter = 0
    val filtersMap:collection.mutable.Map[String,AggregationFilter] = collection.mutable.Map.empty

    def filterToBuilder(filter: AggregationFilter): AbstractAggregationBuilder = {

      implicit def fieldValueToValue(fieldValue:Field) = fieldValue.operator match {
        case AnalyzedField => fieldValue.value
        case NonAnalyzedField => s"infoclone.fields.${fieldValue.value}.%exact"
      }

      val name = filter.name + "_" + counter
      counter += 1
      filtersMap.put(name, filter)

      val aggBuilder = filter match {
        case TermAggregationFilter(_, field, size, _) =>
          AggregationBuilders.terms(name).field(field).size(size)
        case StatsAggregationFilter(_, field) =>
          AggregationBuilders.stats(name).field(field)
        case HistogramAggregationFilter(_, field, interval, minDocCount, extMin, extMax, _) =>
          val eMin:java.lang.Long = extMin.getOrElse(null).asInstanceOf[java.lang.Long]
          val eMax:java.lang.Long = extMax.getOrElse(null).asInstanceOf[java.lang.Long]
          AggregationBuilders.histogram(name).field(field).interval(interval).minDocCount(minDocCount).extendedBounds(eMin, eMax)
        case SignificantTermsAggregationFilter(_, field, backGroundTermOpt, minDocCount, size, _) =>
          val sigTermsBuilder = AggregationBuilders.significantTerms(name).field(field).minDocCount(minDocCount).size(size)
          backGroundTermOpt.foreach{ backGroundTerm =>
            sigTermsBuilder.backgroundFilter(termFilter(backGroundTerm._1, backGroundTerm._2))
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
          aggBuilder.asInstanceOf[AggregationBuilder[_ <: AggregationBuilder[_ <: Any]]].subAggregation(filterToBuilder(subFilter))
        }
      }
      aggBuilder
    }

    aggregationFilters.foreach{ filter => request.addAggregation(filterToBuilder(filter)) }

    val searchQueryStr = if(debugInfo) Some(request.toString) else None

    val resFuture = injectFuture[SearchResponse](request.execute(_))

    def esAggsToOurAggs(aggregations: Aggregations, debugInfo: Option[String] = None): AggregationsResponse = {
      AggregationsResponse(
        aggregations.asScala.map {
            case ta: InternalTerms =>
              TermsAggregationResponse(
                filtersMap.get(ta.getName).get.asInstanceOf[TermAggregationFilter],
                ta.getBuckets.asScala.map { b =>
                  val subAggregations:Option[AggregationsResponse] = b.asInstanceOf[HasAggregations].getAggregations match {
                    case null => None
                    case subAggs => if(subAggs.asList().size()>0) Some(esAggsToOurAggs(subAggs)) else None
                  }
                  Bucket(FieldValue(b.getKey), b.getDocCount, subAggregations)
                }.toSeq
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
                  Bucket(FieldValue(b.getKeyAsNumber.longValue()), b.getDocCount, subAggregations)
                }.toSeq
              )
            case sta:InternalSignificantTerms =>
              val buckets = sta.getBuckets.asScala.toSeq
              SignificantTermsAggregationResponse(
                filtersMap.get(sta.getName).get.asInstanceOf[SignificantTermsAggregationFilter],
                if(!buckets.isEmpty) buckets(0).getSubsetSize else 0,
                buckets.map { b =>
                  val subAggregations:Option[AggregationsResponse] = b.asInstanceOf[HasAggregations].getAggregations match {
                    case null => None
                    case subAggs => Some(esAggsToOurAggs(subAggs))
                  }
                  SignificantTermsBucket(FieldValue(b.getKey), b.getDocCount, b.getSignificanceScore, b.getSubsetDf, subAggregations)
                }.toSeq
              )
            case _ => ???

        }.toSeq
        ,debugInfo)

    }

    resFuture.map{searchResponse => esAggsToOurAggs(searchResponse.getAggregations, searchQueryStr)}
  }

  def search(pathFilter: Option[PathFilter] = None, fieldsFilter: Option[FieldFilter] = None,
             datesFilter: Option[DatesFilter] = None, paginationParams: PaginationParams = DefaultPaginationParams,
             sortParams: SortParam = SortParam.empty, withHistory: Boolean = false, withDeleted: Boolean = false,
             partition: String = defaultPartition, debugInfo: Boolean = false, timeout: Option[Duration] = None)
            (implicit executionContext: ExecutionContext): Future[FTSSearchResponse] = {

    logger.debug(s"Search request: $pathFilter, $fieldsFilter, $datesFilter, $paginationParams, $sortParams, $withHistory, $partition, $debugInfo")

    if (pathFilter.isEmpty && fieldsFilter.isEmpty && datesFilter.isEmpty) {
      throw new IllegalArgumentException("at least one of the filters is needed in order to search")
    }


    val indices = (partition + "_current") :: (if(withHistory) partition + "_history" :: Nil else Nil )

    val fields = "type" :: "system.path" :: "system.uuid" :: "system.lastModified" :: "content.length" ::
      "content.mimeType" :: "linkTo" :: "linkType" :: "system.dc" :: "system.indexTime" :: "system.quad" :: Nil

    val request = client.prepareSearch(indices:_*).setTypes("infoclone").addFields(fields:_*).setFrom(paginationParams.offset).setSize(paginationParams.length)

    sortParams match {
      case NullSortParam => // don't sort.
      case FieldSortParams(fsp) if fsp.isEmpty => request.addSort("system.lastModified", SortOrder.DESC)
      case FieldSortParams(fsp) => fsp.foreach {
        case (name,order) => {
          val unmapped = name match {
            // newly added sys fields should be stated explicitly since not existing in old indices
            case "system.indexTime" => "long"
            case "system.dc"        => "string"
            case "system.quad"      => "string"
            case _ => {
              if (name.startsWith("system.") || name.startsWith("content.")) null
              else name.take(2) match {
                case "d$" => "date"
                case "i$" => "integer"
                case "l$" => "long"
                case "f$" => "float"
                case "w$" => "double"
                case "b$" => "boolean"
                case _    => "string"
              }
            }
          }
          val uname = if(unmapped == "string" && name != "type") s"fields.${name}.%exact" else name
          request.addSort(fieldSort(uname).order(order).unmappedType(unmapped))
        }
      }  
    }
    
    applyFiltersToRequest(request, pathFilter, fieldsFilter, datesFilter, withHistory, withDeleted)

    val searchQueryStr = if(debugInfo) Some(request.toString) else None
    logger.debug (s"^^^^^^^(**********************\n\n request: ${request.toString}\n\n")
    val resFuture = timeout match {
      case Some(t) => injectFuture[SearchResponse](request.execute, t)
      case None => injectFuture[SearchResponse](request.execute)
    }

    resFuture.map { response =>
      FTSSearchResponse(response.getHits.getTotalHits, paginationParams.offset, response.getHits.getHits.size,
        esResponseToInfotons(response, sortParams eq NullSortParam), searchQueryStr)
    }
  }


  def thinSearch(pathFilter: Option[PathFilter] = None, fieldsFilter: Option[FieldFilter] = None,
                 datesFilter: Option[DatesFilter] = None, paginationParams: PaginationParams = DefaultPaginationParams,
                 sortParams: SortParam = SortParam.empty, withHistory: Boolean = false, withDeleted: Boolean,
                 partition: String = defaultPartition, debugInfo:Boolean = false,
                 timeout: Option[Duration] = None)
                (implicit executionContext: ExecutionContext) : Future[FTSThinSearchResponse] = {

    logger.debug(s"Search request: $pathFilter, $fieldsFilter, $datesFilter, $paginationParams, $sortParams, $withHistory, $partition, $debugInfo")

    if (pathFilter.isEmpty && fieldsFilter.isEmpty && datesFilter.isEmpty) {
      throw new IllegalArgumentException("at least one of the filters is needed in order to search")
    }

    val indices = (partition + "_current") :: (withHistory match{
      case true => partition + "_history" :: Nil
      case false => Nil
    })

    val fields = "system.path" :: "system.uuid" :: "system.lastModified" ::"system.indexTime" :: Nil

    val request = client.prepareSearch(indices:_*).setTypes("infoclone").addFields(fields:_*).setFrom(paginationParams.offset).setSize(paginationParams.length)

    sortParams match {
      case NullSortParam => // don't sort.
      case FieldSortParams(fsp) if fsp.isEmpty => request.addSort("system.lastModified", SortOrder.DESC)
      case FieldSortParams(fsp) => fsp.foreach {
        case (name,order) => {
          val unmapped = name match {
            // newly added sys fields should be stated explicitly since not existing in old indices
            case "system.indexTime" => "long"
            case "system.dc"        => "string"
            case "system.quad"      => "string"
            case _ => {
              if (name.startsWith("system.") || name.startsWith("content.")) null
              else name.take(2) match {
                case "d$" => "date"
                case "i$" => "integer"
                case "l$" => "long"
                case "f$" => "float"
                case "w$" => "double"
                case "b$" => "boolean"
                case _    => "string"
              }
            }
          }
          request.addSort(fieldSort(name).order(order).unmappedType(unmapped))
        }
      }
    }
    
    applyFiltersToRequest(request, pathFilter, fieldsFilter, datesFilter, withHistory, withDeleted)

    var oldTimestamp = 0L
    if(debugInfo) {
      oldTimestamp = System.currentTimeMillis()
      logger.debug(s"thinSearch debugInfo request ($oldTimestamp): ${request.toString}")
    }

    val resFuture = timeout match {
      case Some(t) => injectFuture[SearchResponse](request.execute, t)
      case None => injectFuture[SearchResponse](request.execute)
    }

    val searchQueryStr = if(debugInfo) Some(request.toString) else None

    resFuture.map{ response =>

      if(debugInfo) logger.debug(s"thinSearch debugInfo response: ($oldTimestamp - ${System.currentTimeMillis()}): ${response.toString}")

      FTSThinSearchResponse(response.getHits.getTotalHits, paginationParams.offset, response.getHits.getHits.size,
        esResponseToThinInfotons(response, sortParams eq NullSortParam), searchQueryStr = searchQueryStr)
    }
  }

  def getLastIndexTimeFor(dc: String, partition: String = defaultPartition)
                         (implicit executionContext: ExecutionContext) : Future[Option[Long]] = {

    val request = client
      .prepareSearch(partition + "_current", partition + "_history")
      .setTypes("infoclone")
      .addFields("system.indexTime")
      .setSize(1)
      .addSort("system.indexTime", SortOrder.DESC)

    applyFiltersToRequest(
      request,
      None,
      Some(MultiFieldFilter(Must, Seq(
        SingleFieldFilter(Must, Equals, "system.dc", Some(dc)),                                 //ONLY DC
        SingleFieldFilter(MustNot, Contains, "system.parent.parent_hierarchy", Some("/meta/")), //NO META
        SingleFieldFilter(Must, GreaterThan, "system.lastModified", Some("1970"))
      ))),
      None
    )

    injectFuture[SearchResponse](request.execute).map{ sr =>
      val hits = sr.getHits().hits()
      if(hits.length < 1) None
      else {
        hits.headOption.map(_.field("system.indexTime").getValue.asInstanceOf[Long])
      }
    }
  }



  private def startShardScroll(pathFilter: Option[PathFilter] = None, fieldsFilter: Option[FieldFilter] = None,
                       datesFilter: Option[DatesFilter] = None, withHistory: Boolean, withDeleted: Boolean,
                       offset: Int, length: Int, scrollTTL:Long = scrollTTL,
                       index: String, nodeId: String, shard: Int)
                              (implicit executionContext: ExecutionContext) : Future[FTSStartScrollResponse] = {



    val fields = "type" :: "system.path" :: "system.uuid" :: "system.lastModified" :: "content.length" ::
      "content.mimeType" :: "linkTo" :: "linkType" :: "system.dc" :: "system.indexTime" :: "system.quad" :: Nil

    val request = clients.get(nodeId).getOrElse(client).prepareSearch(index)
      .setTypes("infoclone")
      .addFields(fields:_*)
      .setSearchType(SearchType.SCAN)
      .setScroll(TimeValue.timeValueSeconds(scrollTTL))
      .setSize(length)
      .setFrom(offset)
      .setPreference(s"_shards:$shard;_only_node:$nodeId")

    if (!pathFilter.isDefined && fieldsFilter.isEmpty && !datesFilter.isDefined) {
      request.setPostFilter(matchAllFilter())
    } else {
      applyFiltersToRequest(request, pathFilter, fieldsFilter, datesFilter, withHistory, withDeleted)
    }

    val scrollResponseFuture = injectFuture[SearchResponse](request.execute(_))

    scrollResponseFuture.map{ scrollResponse => FTSStartScrollResponse(scrollResponse.getHits.totalHits, scrollResponse.getScrollId, Some(nodeId))  }

  }


def startSuperScroll(pathFilter: Option[PathFilter] = None, fieldsFilter: Option[FieldFilter] = None,
                     datesFilter: Option[DatesFilter] = None,
                     paginationParams: PaginationParams = DefaultPaginationParams, scrollTTL:Long = scrollTTL,
                     withHistory: Boolean, withDeleted: Boolean)
                    (implicit executionContext: ExecutionContext) : Seq[Future[FTSStartScrollResponse]] = {

    val aliases = if(withHistory) List("cmwell_current", "cmwell_history") else List("cmwell_current")
    val ssr = client.admin().cluster().prepareSearchShards(aliases :_*).setTypes("infoclone").execute().actionGet()

    val targetedShards = ssr.getGroups.flatMap{ shardGroup =>
      shardGroup.getShards.filter(_.primary()).map{ shard =>
        (shard.index(), shard.currentNodeId(), shard.id())
      }
    }

    targetedShards.map{ case (index, node, shard) =>
      startShardScroll(pathFilter, fieldsFilter, datesFilter, withHistory, withDeleted, paginationParams.offset,
        paginationParams.length, scrollTTL, index, node, shard)
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
  def startScroll(pathFilter: Option[PathFilter] = None, fieldsFilter: Option[FieldFilter] = None,
                  datesFilter: Option[DatesFilter] = None, paginationParams: PaginationParams = DefaultPaginationParams,
                  scrollTTL: Long = scrollTTL, withHistory: Boolean = false, withDeleted: Boolean, indexNames: Seq[String] = Seq.empty,
                  onlyNode: Option[String] = None, partition: String, debugInfo: Boolean)
                 (implicit executionContext: ExecutionContext) : Future[FTSStartScrollResponse] = {
    logger.debug(s"StartScroll request: $pathFilter, $fieldsFilter, $datesFilter, $paginationParams, $withHistory")
    if(partition != defaultPartition) {
      logger.warn("old implementation ignores partition parameter")
    }

    val indices = {
      if (indexNames.nonEmpty) indexNames
      else "cmwell_current" :: (withHistory match {
        case true => "cmwell_history" :: Nil
        case false => Nil
      })
    }

    val fields = "type" :: "system.path" :: "system.uuid" :: "system.lastModified" :: "content.length" ::
      "content.mimeType" :: "linkTo" :: "linkType" :: "system.dc" :: "system.indexTime" :: "system.quad" :: Nil

    // since in ES scroll API, size is per shard, we need to convert our paginationParams.length parameter to be per shard
    // We need to find how many shards are relevant for this query. For that we'll issue a fake search request
    val fakeRequest = client.prepareSearch(indices:_*).setTypes("infoclone").addFields(fields:_*)

    if (pathFilter.isEmpty && fieldsFilter.isEmpty && datesFilter.isEmpty) {
      fakeRequest.setPostFilter(matchAllFilter())
    } else {
      applyFiltersToRequest(fakeRequest, pathFilter, fieldsFilter, datesFilter)
    }
    val fakeResponse = fakeRequest.execute().get()

    val relevantShards = fakeResponse.getSuccessfulShards

    // rounded to lowest multiplacations of shardsperindex or to mimimum of 1
    val infotonsPerShard = (paginationParams.length/relevantShards) max 1

    val request = client.prepareSearch(indices:_*)
      .setTypes("infoclone")
      .addFields(fields:_*)
      .setSearchType(SearchType.SCAN)
      .setScroll(TimeValue.timeValueSeconds(scrollTTL))
      .setSize(infotonsPerShard)
      .setFrom(paginationParams.offset)

    if(onlyNode.isDefined) {
      request.setPreference(s"_only_node_primary:${onlyNode.map{case "local" => localNodeId; case n => n}.get}")
    }

    if (pathFilter.isEmpty && fieldsFilter.isEmpty && datesFilter.isEmpty) {
      request.setPostFilter(matchAllFilter())
    } else {
      applyFiltersToRequest(request, pathFilter, fieldsFilter, datesFilter, withHistory, withDeleted)
    }

    val scrollResponseFuture = injectFuture[SearchResponse](request.execute(_))

    val searchQueryStr = if(debugInfo) Some(request.toString) else None

    scrollResponseFuture.map{ scrollResponse => FTSStartScrollResponse(scrollResponse.getHits.totalHits, scrollResponse.getScrollId, searchQueryStr = searchQueryStr)  }

  }


  def startSuperMultiScroll(pathFilter: Option[PathFilter] = None, fieldsFilter: Option[FieldFilter] = None,
                            datesFilter: Option[DatesFilter] = None,
                            paginationParams: PaginationParams = DefaultPaginationParams, scrollTTL:Long = scrollTTL,
                            withHistory: Boolean = false, withDeleted:Boolean, partition: String)
                           (implicit executionContext:ExecutionContext) : Seq[Future[FTSStartScrollResponse]] = {

    logger.debug(s"StartMultiScroll request: $pathFilter, $fieldsFilter, $datesFilter, $paginationParams, $withHistory")
    if(partition != defaultPartition) {
      logger.warn("old implementation ignores partition parameter")
    }
    def indicesNames(indexName: String): Seq[String] = {
      val currentAliasRes = client.admin.indices().prepareGetAliases(indexName).execute().actionGet()
      val indices = currentAliasRes.getAliases.keysIt().asScala.toSeq
      indices
    }

    def dataNodeIDs = {
      client.admin().cluster().prepareNodesInfo().execute().actionGet().getNodesMap.asScala.filter{case (id, node) =>
        node.getNode.isDataNode
      }.map{_._1}.toSeq
    }

    val indices = indicesNames("cmwell_current") ++ (if(withHistory) indicesNames("_history") else Nil )

    indices.flatMap { indexName =>
      dataNodeIDs.map{ nodeId =>
        startScroll(pathFilter, fieldsFilter, datesFilter, paginationParams, scrollTTL, withHistory, withDeleted,
          Seq(indexName), Some(nodeId))
      }
    }
  }

  def startMultiScroll(pathFilter: Option[PathFilter] = None, fieldsFilter: Option[FieldFilter] = None,
                       datesFilter: Option[DatesFilter] = None,
                       paginationParams: PaginationParams = DefaultPaginationParams,
                       scrollTTL: Long = scrollTTL, withHistory: Boolean = false, withDeleted: Boolean, partition: String)
                      (implicit executionContext:ExecutionContext): Seq[Future[FTSStartScrollResponse]] = {
    logger.debug(s"StartMultiScroll request: $pathFilter, $fieldsFilter, $datesFilter, $paginationParams, $withHistory")
    if(partition != defaultPartition) {
      logger.warn("old implementation ignores partition parameter")
    }
    def indicesNames(indexName: String): Seq[String] = {
      val currentAliasRes = client.admin.indices().prepareGetAliases(indexName).execute().actionGet()
      val indices = currentAliasRes.getAliases.keysIt().asScala.toSeq
      indices
    }

    val indices = indicesNames("cmwell_current") ++ (if(withHistory) indicesNames("_history") else Nil)

    indices.map { indexName =>
      startScroll(pathFilter, fieldsFilter, datesFilter, paginationParams, scrollTTL, withHistory, withDeleted,
        Seq(indexName))
    }
  }

  def scroll(scrollId: String, scrollTTL: Long= scrollTTL, nodeId: Option[String]=None)
            (implicit executionContext: ExecutionContext): Future[FTSScrollResponse] = {
    logger.debug(s"Scroll request: $scrollId, $scrollTTL")

    val clint = nodeId.map{clients(_)}.getOrElse(client)
    val scrollResponseFuture = injectFuture[SearchResponse](
      clint.prepareSearchScroll(scrollId).setScroll(TimeValue.timeValueSeconds(scrollTTL)).execute(_)
    )

    scrollResponseFuture.map{ scrollResponse => FTSScrollResponse(scrollResponse.getHits.getTotalHits, scrollResponse.getScrollId, esResponseToInfotons(scrollResponse,includeScore = false))}
  }

  def rInfo(path: String, scrollTTL: Long= scrollTTL, paginationParams: PaginationParams = DefaultPaginationParams, withHistory: Boolean = false, partition: String = defaultPartition)
           (implicit executionContext:ExecutionContext): Future[Source[Vector[(Long, String,String)],NotUsed]] = {

    val indices = (partition + "_current") :: (if (withHistory) partition + "_history" :: Nil else Nil)

    val fields = "system.uuid" :: "system.lastModified" :: Nil // "system.indexTime" :: Nil // TODO: fix should add indexTime, so why not pull it now?

    // since in ES scroll API, size is per shard, we need to convert our paginationParams.length parameter to be per shard
    // We need to find how many shards are relevant for this query. For that we'll issue a fake search request
    val fakeRequest = client.prepareSearch(indices: _*).setTypes("infoclone").addFields(fields: _*)

    fakeRequest.setQuery(QueryBuilders.matchQuery("path", path))

    injectFuture[SearchResponse](al => fakeRequest.execute(al)).flatMap { fakeResponse =>

      val relevantShards = fakeResponse.getSuccessfulShards

      // rounded to lowest multiplacations of shardsperindex or to mimimum of 1
      val infotonsPerShard = (paginationParams.length / relevantShards) max 1

      val request = client.prepareSearch(indices: _*)
        .setTypes("infoclone")
        .addFields(fields: _*)
        .setSearchType(SearchType.SCAN)
        .setScroll(TimeValue.timeValueSeconds(scrollTTL))
        .setSize(infotonsPerShard)
        .setQuery(QueryBuilders.matchQuery("path", path))

      val scrollResponseFuture = injectFuture[SearchResponse](al => request.execute(al))

      scrollResponseFuture.map { scrollResponse =>

        if(scrollResponse.getHits.totalHits == 0) Source.empty[Vector[(Long, String,String)]]
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


  override def latestIndexNameAndCount(prefix: String): Option[(String, Long)] = ???


  private def rExtractInfo(esResponse: org.elasticsearch.action.search.SearchResponse) : Vector[(Long, String, String)] = {
    val sHits = esResponse.getHits.hits()
    if (sHits.isEmpty) Vector.empty
    else {
      val hits = esResponse.getHits.hits()
      hits.map{ hit =>
        val uuid = hit.field("system.uuid").getValue.asInstanceOf[String]
        val lastModified = new DateTime(hit.field("system.lastModified").getValue.asInstanceOf[String]).getMillis
        val index = hit.getIndex
        (lastModified, uuid, index)
      }(collection.breakOut)
    }
  }

  def info(path: String , paginationParams: PaginationParams = DefaultPaginationParams, withHistory: Boolean = false,
           partition: String = defaultPartition)
          (implicit executionContext:ExecutionContext): Future[Vector[(String,String)]] = {

    val indices = (partition + "_current") :: (if(withHistory) partition + "_history" :: Nil else Nil)

//    val fields = "system.path" :: "system.uuid" :: "system.lastModified" :: Nil

    val request = client.prepareSearch(indices:_*).setTypes("infoclone").addFields("system.uuid").setFrom(paginationParams.offset).setSize(paginationParams.length)

    val qb : QueryBuilder = QueryBuilders.matchQuery("path", path)

    request.setQuery(qb)

    val resFuture = injectFuture[SearchResponse](request.execute)
    resFuture.map { response => extractInfo(response) }
  }

  private def extractInfo(esResponse: org.elasticsearch.action.search.SearchResponse) : Vector[(String , String )] = {
    if (esResponse.getHits.hits().nonEmpty) {
      val hits = esResponse.getHits.hits()
      hits.map{ hit =>
        val uuid = hit.field("system.uuid").getValue.asInstanceOf[String]
        val index = hit.getIndex
        ( uuid , index)
      }.toVector
    }
    else Vector.empty
  }

  private def injectFuture[A](f: ActionListener[A] => Unit, timeout : Duration = FiniteDuration(10, SECONDS))
                             (implicit executionContext: ExecutionContext)= {
    val p = Promise[A]()
    f(new ActionListener[A] {
      def onFailure(t: Throwable): Unit = {
        logger error ("Exception from ElasticSearch. %s\n%s".format(t.getLocalizedMessage, t.getStackTrace().mkString("", EOL, EOL)))

        if(!p.isCompleted) {
          p.failure(t)
        }

      }
      def onResponse(res: A): Unit =  {
        logger debug ("Response from ElasticSearch:\n%s".format(res.toString))
        p.success(res)
      }
    })
    TimeoutFuture.withTimeout(p.future, timeout)
  }

  def countSearchOpenContexts(): Array[(String,Long)] = {
    val response = client.admin().cluster().prepareNodesStats().setIndices(true).execute().get()
    response.getNodes.map{
      nodeStats =>
        nodeStats.getHostname -> nodeStats.getIndices.getSearch.getOpenContexts
    }.sortBy(_._1)
  }
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







