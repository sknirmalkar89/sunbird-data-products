package org.sunbird.analytics.job.report

import java.io.File
import java.time.{ZoneOffset, ZonedDateTime}

import cats.syntax.either._
import ing.wbaa.druid._
import ing.wbaa.druid.client.DruidClient
import io.circe._
import io.circe.parser._
import org.apache.spark.sql.functions._
import scala.collection.JavaConverters._
import org.apache.spark.sql.types.{ArrayType, MapType, StringType, StructType}
import org.apache.spark.sql.{DataFrame, Encoder, Encoders, SparkSession}
import org.ekstep.analytics.framework.util.{HadoopFileUtil, JSONUtils}
import org.ekstep.analytics.framework.{DruidQueryModel, FrameworkContext, JobConfig, StorageConfig}
import org.scalamock.scalatest.MockFactory
import org.sunbird.analytics.util.UserData

import scala.collection.mutable
import scala.concurrent.Future

class TestCourseMetricsJobV2 extends BaseReportSpec with MockFactory with BaseReportsJob {
  var spark: SparkSession = _
  var courseBatchDF: DataFrame = _
  var userCoursesDF: DataFrame = _
  var userDF: DataFrame = _
  var locationDF: DataFrame = _
  var orgDF: DataFrame = _
  var userOrgDF: DataFrame = _
  var externalIdentityDF: DataFrame = _
  var systemSettingDF: DataFrame = _
  var reporterMock: ReportGeneratorV2 = mock[ReportGeneratorV2]
  val sunbirdCoursesKeyspace = "sunbird_courses"
  val sunbirdKeyspace = "sunbird"

  override def beforeAll(): Unit = {

    super.beforeAll()
    spark = getSparkSession()

    courseBatchDF = spark.read.format("com.databricks.spark.csv").option("header", "true")
      .load("src/test/resources/course-metrics-updaterv2/course_batch_data.csv").cache()

    externalIdentityDF = spark.read.format("com.databricks.spark.csv").option("header", "true")
      .load("src/test/resources/course-metrics-updaterv2/user_external_data.csv").cache()

    userCoursesDF = spark.read.format("com.databricks.spark.csv").option("header", "true")
      .load("src/test/resources/course-metrics-updaterv2/user_courses_data.csv").cache()

    userDF = spark.read.json("src/test/resources/course-metrics-updaterv2/user_data.json").cache()

    systemSettingDF = spark.read.format("com.databricks.spark.csv").option("header", "true")
      .load("src/test/resources/course-metrics-updaterv2/system_settings.csv").cache()
  }

  "TestUpdateCourseMetricsV2" should "generate reports for batches and validate all scenarios" in {
    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "course_batch", "keyspace" -> sunbirdCoursesKeyspace),"org.apache.spark.sql.cassandra", new StructType())
      .returning(courseBatchDF)

    val schema = Encoders.product[UserData].schema
    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "user","infer.schema" -> "true", "key.column"-> "userid"),"org.apache.spark.sql.redis", schema)
      .anyNumberOfTimes()
      .returning(userDF)

    CourseMetricsJobV2.loadData(spark, Map("table" -> "user", "keyspace" -> "sunbird"),"org.apache.spark.sql.cassandra", new StructType())


    val convertMethod = udf((value: mutable.WrappedArray[String]) => {
      if(null != value && value.nonEmpty)
        value.toList.map(str => JSONUtils.deserialize(str)(manifest[Map[String, String]])).toArray
      else null
    }, new ArrayType(MapType(StringType, StringType), true))

    val alteredUserCourseDf = userCoursesDF.withColumn("certificates", convertMethod(split(userCoursesDF.col("certificates"), ",").cast("array<string>")) )
    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "user_enrolments", "keyspace" -> sunbirdCoursesKeyspace),"org.apache.spark.sql.cassandra", new StructType())
      .anyNumberOfTimes()
      .returning(alteredUserCourseDf)

    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "user", "keyspace" -> sunbirdKeyspace),"org.apache.spark.sql.cassandra", new StructType())
      .anyNumberOfTimes()
      .returning(userDF)

    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "usr_external_identity", "keyspace" -> sunbirdKeyspace),"org.apache.spark.sql.cassandra", new StructType())
      .anyNumberOfTimes()
      .returning(externalIdentityDF)

    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "system_settings", "keyspace" -> sunbirdKeyspace),"org.apache.spark.sql.cassandra", new StructType())
      .anyNumberOfTimes()
      .returning(systemSettingDF)

    val outputLocation = "/tmp/course-metrics"
    val outputDir = "course-progress-reports"
    val storageConfig = StorageConfig("local", "", outputLocation)

    implicit val mockFc: FrameworkContext = mock[FrameworkContext]
    val strConfig= """{"search":{"type":"none"},"model":"org.sunbird.analytics.job.report.CourseMetricsJobV2","modelParams":{"batchFilters":["TPD"],"druidConfig":{"queryType":"groupBy","dataSource":"content-model-snapshot","intervals":"LastDay","granularity":"all","aggregations":[{"name":"count","type":"count","fieldName":"count"}],"dimensions":[{"fieldName":"identifier","aliasName":"identifier"},{"fieldName":"channel","aliasName":"channel"}],"filters":[{"type":"equals","dimension":"contentType","value":"Course"}],"descending":"false"},"fromDate":"$(date --date yesterday '+%Y-%m-%d')","toDate":"$(date --date yesterday '+%Y-%m-%d')","sparkCassandraConnectionHost":"'$sunbirdPlatformCassandraHost'","sparkElasticsearchConnectionHost":"'$sunbirdPlatformElasticsearchHost'"},"output":[{"to":"console","params":{"printEvent":false}}],"parallelization":8,"appName":"Course Dashboard Metrics","deviceMapping":false}"""
    val config = JSONUtils.deserialize[JobConfig](strConfig)
    val druidConfig = JSONUtils.deserialize[DruidQueryModel](JSONUtils.serialize(config.modelParams.get("druidConfig")))
    //mocking for DruidDataFetcher
    import scala.concurrent.ExecutionContext.Implicits.global
    val json: String =
      """
        |{
        |  "identifier": "do_1130264512015646721166",
        |  "channel": "01274266675936460840172"
        |}
      """.stripMargin

    val doc: Json = parse(json).getOrElse(Json.Null)
    val results = List(DruidResult.apply(ZonedDateTime.of(2020, 1, 23, 17, 10, 3, 0, ZoneOffset.UTC), doc));
    val druidResponse = DruidResponse.apply(results, QueryType.GroupBy)

    implicit val mockDruidConfig: DruidConfig = DruidConfig.DefaultConfig

    val mockDruidClient = mock[DruidClient]
    (mockDruidClient.doQuery(_: DruidQuery)(_: DruidConfig)).expects(*, mockDruidConfig).returns(Future(druidResponse)).anyNumberOfTimes()
    (mockFc.getDruidClient _).expects().returns(mockDruidClient).anyNumberOfTimes()

    CourseMetricsJobV2.prepareReport(spark, storageConfig, reporterMock.loadData, config, List())

    implicit val sc = spark

    val batchInfo = List(CourseBatch("01303150537737011211","2020-05-29","2030-06-30","b00bc992ef25f1a9a8d63291e20efc8d"), CourseBatch("0130334873750159361","2020-06-11","2030-06-30","013016492159606784174"))
    batchInfo.map(batches => {
      val reportDf = CourseMetricsJobV2.getReportDF(batches,userDF,reporterMock.loadData)
      CourseMetricsJobV2.saveReportToBlobStore(batches, reportDf, storageConfig, reportDf.count())
    })

    implicit val batchReportEncoder: Encoder[BatchReportOutput] = Encoders.product[BatchReportOutput]
    val batch1 = "01303150537737011211"
    val batch2 = "0130334873750159361"

    val batchReportsCount = Option(new File(s"$outputLocation/$outputDir").list)
      .map(_.count(_.endsWith(".csv"))).getOrElse(0)

    batchReportsCount should be (2)

    val batch1Results = spark.read.format("csv").option("header", "true")
      .load(s"$outputLocation/$outputDir/report-$batch1.csv").as[BatchReportOutput].collectAsList().asScala
    batch1Results.map {res => res.`User ID`}.toList should contain theSameElementsAs List("c7ef3848-bbdb-4219-8344-817d5b8103fa")
    batch1Results.map {res => res.`External ID`}.toList should contain theSameElementsAs List(null)
    batch1Results.map {res => res.`School UDISE Code`}.toList should contain theSameElementsAs List(null)
    batch1Results.map {res => res.`School Name`}.toList should contain theSameElementsAs List(null)
    batch1Results.map {res => res.`Block Name`}.toList should contain theSameElementsAs List(null)

    val batch2Results = spark.read.format("csv").option("header", "true")
      .load(s"$outputLocation/$outputDir/report-$batch2.csv").as[BatchReportOutput].collectAsList().asScala
    batch2Results.map {res => res.`User ID`}.toList should contain theSameElementsAs List("f3dd58a4-a56f-4c1d-95cf-3231927a28e9")
    batch2Results.map {res => res.`External ID`}.toList should contain theSameElementsAs List(null)
    batch2Results.map {res => res.`School UDISE Code`}.toList should contain theSameElementsAs List(null)
    batch2Results.map {res => res.`School Name`}.toList should contain theSameElementsAs List(null)
    batch2Results.map {res => res.`Block Name`}.toList should contain theSameElementsAs List(null)
    /*
    // TODO: Add assertions here
    EmbeddedES.getAllDocuments("cbatchstats-08-07-2018-16-30").foreach(f => {
      f.contains("lastUpdatedOn") should be (true)
    })
    EmbeddedES.getAllDocuments("cbatch").foreach(f => {
      f.contains("reportUpdatedOn") should be (true)
    })
    */

    /*
    val esOutput = JSONUtils.deserialize[ESOutput](EmbeddedES.getAllDocuments("cbatchstats-08-07-2018-16-30").head)
    esOutput.name should be ("Rajesh Kapoor")
    esOutput.id should be ("user012:1002")
    esOutput.batchId should be ("1002")
    esOutput.courseId should be ("do_112726725832507392141")
    esOutput.rootOrgName should be ("MPPS BAYYARAM")
    esOutput.subOrgUDISECode should be ("")
    esOutput.blockName should be ("")
    esOutput.maskedEmail should be ("*****@gmail.com")

  val esOutput_cbatch = JSONUtils.deserialize[ESOutputCBatch](EmbeddedES.getAllDocuments("cbatch").head)
    esOutput_cbatch.id should be ("1004")
    esOutput_cbatch.participantCount should be ("4")
    esOutput_cbatch.completedCount should be ("0")
    */

    new HadoopFileUtil().delete(spark.sparkContext.hadoopConfiguration, outputLocation)
  }

  it should "test redis and cassandra connections" in {
    implicit val fc = Option(mock[FrameworkContext])
    spark.sparkContext.stop()

    val strConfig = """{"search": {"type": "none"},"model": "org.sunbird.analytics.job.report.CourseMetricsJob","modelParams": {"batchFilters": ["TPD"],"fromDate": "$(date --date yesterday '+%Y-%m-%d')","toDate": "$(date --date yesterday '+%Y-%m-%d')","sparkCassandraConnectionHost": "127.0.0.0","sparkElasticsearchConnectionHost": "'$sunbirdPlatformElasticsearchHost'","sparkRedisConnectionHost": "'$sparkRedisConnectionHost'","sparkUserDbRedisIndex": "4"},"output": [{"to": "console","params": {"printEvent": false}}],"parallelization": 8,"appName": "Course Dashboard Metrics","deviceMapping": false}""".stripMargin
    getReportingSparkContext(JSONUtils.deserialize[JobConfig](strConfig))
    val conf = openSparkSession(JSONUtils.deserialize[JobConfig](strConfig))
    conf.sparkContext.stop()
    spark = getSparkSession()
  }


  it should "run for batch 01308027519495372821" in {
    spark.sparkContext.stop()

    val strConfig = """{"search": {"type": "none"},"model": "org.sunbird.analytics.job.report.CourseMetricsJob","modelParams": {"batchFilters": ["TPD"],"fromDate": "$(date --date yesterday '+%Y-%m-%d')","toDate": "$(date --date yesterday '+%Y-%m-%d')","sparkCassandraConnectionHost": "11.5.2.16","sparkElasticsearchConnectionHost": "'localhost","sparkRedisConnectionHost": "11.5.3.8","sparkUserDbRedisIndex": "12"},"output": [{"to": "console","params": {"printEvent": false}}],"parallelization": 8,"appName": "Course Dashboard Metrics","deviceMapping": false};01308027519495372821""".stripMargin

    CourseMetricsJobV2.main(strConfig)


    //    implicit val fc = new FrameworkContext
    //val conf = strConfig.split(";")
    //    val batchIds = if(conf.length > 1) {
    //      conf(1).split(",").toList
    //    } else List()
    //    val jobConfig = JSONUtils.deserialize[JobConfig](conf(0))
    //
    //    CourseMetricsJobV2.prepareReport(spark,StorageConfig("","","",Option("")),loadData,
    //      jobConfig,batchIds)

  }
}