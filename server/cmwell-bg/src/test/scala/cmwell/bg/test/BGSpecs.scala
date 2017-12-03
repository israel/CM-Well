package cmwell.bg.test

import cmwell.util.build.BuildInfo.elasticsearchVersion
import com.typesafe.config.ConfigFactory
import org.scalatest._
import pl.allegro.tech.embeddedelasticsearch.{EmbeddedElastic, PopularProperties}

import scala.io.Source

object EmbeddedES {
  System.setProperty("es.set.netty.runtime.available.processors", "false")

  lazy val config = ConfigFactory.load()
  lazy val esClusterName = config.getString("ftsService.clusterName")
  lazy val indicesTemplate = Source.fromURL(this.getClass.getResource("/indices_template.json")).getLines.reduceLeft(_ + _)
  lazy val embeddedElastic:EmbeddedElastic = EmbeddedElastic.builder()
    .withElasticVersion(elasticsearchVersion)
    .withSetting(PopularProperties.CLUSTER_NAME, esClusterName)
    .withTemplate("indices_template", indicesTemplate)
    .withIndex("cm_well_p0_0")
    .build()
}

trait EmbeddedESSuite extends Suite with BeforeAndAfterAll { this: Suite =>


  override def beforeAll(): Unit = {
    if(nestedSuites.nonEmpty) {
      println("Starting EmbeddedElasticSearch before all test suites")
      EmbeddedES.embeddedElastic.start()
    } else {
      EmbeddedES.embeddedElastic.recreateIndices()
    }
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    if(nestedSuites.nonEmpty) {
      println("Stopping EmbeddedElasticSearch")
      EmbeddedES.embeddedElastic.stop()
    }
    super.afterAll()
  }
}

class BGSpecs extends Suites(
  new BGMergerSpec,
  new BGSeqSpecs
)

@DoNotDiscover
class BGSeqSpecs extends Suites (
  new CmwellBGSpec with EmbeddedESSuite,
  new BGResilienceSpec with EmbeddedESSuite,
  new BGSequentialSpec with EmbeddedESSuite
) with SequentialNestedSuiteExecution with EmbeddedESSuite
