package com.horizon.exchangeapi.route.business

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.stream.ActorMaterializer
import com.horizon.exchangeapi.ExchangeApiApp.{cpds, db, logger}
import com.horizon.exchangeapi.{ApiUtils, BusinessRoutes, ExchConfig, ExchangeApi, HttpCode, PostBusinessPolicySearchRequest, PostBusinessPolicySearchResponse, PostPutBusinessPolicyRequest, PostPutOrgRequest, PostPutServiceRequest, PostPutUsersRequest, PutAgbotsRequest, PutNodesRequest, Role}
import com.horizon.exchangeapi.tables.{BService, BServiceVersions, NodeHeartbeatIntervals, OneProperty, OneUserInputService, OneUserInputValue, Prop, RegService, ResourceChangesTQ, SearchOffsetPolicyTQ}
import com.mchange.v2.c3p0.ComboPooledDataSource
import org.json4s.jackson.JsonMethods.parse
import org.json4s.{DefaultFormats, Formats, JValue, JsonInput, jvalue2extractable, string2JsonInput}
import org.json4s.native.Serialization.write
import org.junit.runner.RunWith
import org.scalactic.Bad
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable.Map
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.parsing.json

class TestDBConnection {
  ExchConfig.load() // get config file, normally in /etc/horizon/exchange/config.json
  ExchConfig.getHostAndPort match {
    case (h, p) => ExchangeApi.serviceHost = h;
      ExchangeApi.servicePort = p
  }
  
  private var cpds: ComboPooledDataSource = new ComboPooledDataSource
  cpds.setDriverClass(ExchConfig.getString("api.db.driverClass")) //loads the jdbc driver
  cpds.setJdbcUrl(ExchConfig.getString("api.db.jdbcUrl"))
  cpds.setUser(ExchConfig.getString("api.db.user"))
  cpds.setPassword(ExchConfig.getString("api.db.password"))
  // the settings below are optional -- c3p0 can work with defaults
  cpds.setMinPoolSize(ExchConfig.getInt("api.db.minPoolSize"))
  cpds.setAcquireIncrement(ExchConfig.getInt("api.db.acquireIncrement"))
  cpds.setMaxPoolSize(ExchConfig.getInt("api.db.maxPoolSize"))
  
  private val maxConns: Int = ExchConfig.getInt("api.db.maxPoolSize")
  
  private val db: Database =
    if (cpds != null) {
      Database.forDataSource(cpds,
                             Some(maxConns),
                             AsyncExecutor("ExchangeExecutor", maxConns, maxConns, 1000, maxConns))
  }
  else
    null
  
  def getDb: Database = db
}


class TestPolicySearchPost extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {
  private val ACCEPT: (String, String) = ("Content-Type", "application/json")
  private val AGBOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode("TestPolicySearchPost/a1" + ":" + "a1tok"))
  private val CONTENT: (String, String) = ACCEPT
  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/" + "TestPolicySearchPost"
  private val USERAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode("TestPolicySearchPost/u1" + ":" + "u1pw"))
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  
  implicit private val formats: Formats = DefaultFormats.withLong
  
  
  override def beforeAll() {
    Http(URL).postData(write(PostPutOrgRequest(None, "TestPolicySearchPost", "desc", None, None))).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    Http(URL + "/users/" + "u1").postData(write(PostPutUsersRequest("u1pw", admin = false, "u1" + "@hotmail.com"))).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    Http(URL + "/agbots/" + "a1").postData(write(PutAgbotsRequest("a1tok", "a1" + "name", None, "AGBOTABC"))).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    Http(URL+"/services").postData(write(PostPutServiceRequest("svc1", None, public = false, None, "bluehorizon.network.sdr", "1.0.0", "amd64", "multiple", None, None, None, Some(""), Some(""), None, None, None))).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    Http(URL + "/business/policies/" + "pol1").postData(write(PostPutBusinessPolicyRequest("pol1", Some("desc"), BService("bluehorizon.network.sdr", "TestPolicySearchPost", "*", List(BServiceVersions("1.0.0", None, None)), None ), None, Some(List(OneProperty("purpose",None,"location"))), Some(List("a == b"))))).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
  }
  
  override def afterAll(): Unit = {
    Http(URL + "/orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1").method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    Http(URL + "/orgs/" + "TestPolicySearchPost" + "/services/" + "svc1").method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    Http(URL + "/orgs/" + "TestPolicySearchPost" + "/agbots/" + "a1").method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    Http(URL + "/orgs/" + "TestPolicySearchPost" + "/users/" + "u1").method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    Http(URL + "/orgs/" + "TestPolicySearchPost").method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
  
    Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.dropAllChanges()), 15.seconds)
    
    DBCONNECTION.getDb.close()
  }
  
  override def afterEach(): Unit = {
    Await.result(DBCONNECTION.getDb.run(SearchOffsetPolicyTQ.dropAllOffsets()), 15.seconds)
  }
  
  def withNode(test: Any) {
    try {
      Http(URL + "/nodes/" + "n1").postData(write(PutNodesRequest("mytok", "rpi" + "n1" + "-normal", None, "",
        Some(List(
          RegService("TestPolicySearchPost/bluehorizon.network.sdr",1,Some("active"),"{json policy for " + "n1" + "sdr}",List(
            Prop("arch","arm","string","in"),
            Prop("memory","300","int",">="),
            Prop("version","1.0.0","version","in"),
            Prop("agreementProtocols","ExchangeAutomatedTest","list","in"),
            Prop("dataVerification","true","boolean","="))),
          RegService("bluehorizon.network.netspeed",1,None,"{json policy for " + "n1" + " netspeed}",List(  // intentionally setting configState to None to make sure GET displays the default
            Prop("arch","arm","string","in"),
            Prop("agreementProtocols","ExchangeAutomatedTest","list","in"),
            Prop("version","1.0.0","version","in")))
        )),
        Some(List( OneUserInputService("TestPolicySearchPost", "bluehorizon.network.sdr", Some("amd64"), Some("[0.0.0,INFINITY)"), List( OneUserInputValue("UI_STRING","mystr - updated"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )) )),
        Some(""), Some(Map("horizon"->"3.2.1")), "NODEABC", Some("amd64"), Some(NodeHeartbeatIntervals(6,15,2))))).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    }
    finally Http(URL + "/orgs/" + "TestPolicySearchPost" + "/nodes/" + "n1").method("delete").headers(ACCEPT).headers(USERAUTH).asString
  }
  
  test("POST /org/" + "TestPolicySearchPost" + "/business/policy/" + "pol1" + "/search -- 400 Bad request - changeSince < 0L") {
    val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(-1L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }
  
  test("POST /org/" + "TestPolicySearchPost" + "/business/policy/" + "pol1" + "/search -- 400 Bad request - numEntries < 0") {
    val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, Some(-1), 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }
  
  test("POST /org/" + "TestPolicySearchPost" + "/business/policy/" + "pol1" + "/search -- Initial API Call From Agbot - No Nodes") {
    val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    
    val responseBody: PostBusinessPolicySearchResponse = parse(response.body).extract[PostBusinessPolicySearchResponse]
    assert(responseBody.nodes.isEmpty)
    //assert(responseBody.offsetUpdated === false)
    
    val offset: Seq[(Option[String], Long)] = Await.result(DBCONNECTION.getDb.run(SearchOffsetPolicyTQ.getOffsetSession("TestPolicySearchPost/a1", "TestPolicySearchPost/pol1").result), 15.seconds)
    assert(offset.nonEmpty)
    assert(offset.head._1.isEmpty)
    assert(offset.head._2 === 0L)
  }
  
  test("POST /org/" + "TestPolicySearchPost" + "/business/policy/" + "pol1" + "/search -- 409 bad session - Agbot session desynchronization") {
    Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 1L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.ALREADY_EXISTS2.intValue)
  
    val responseBody: PostBusinessPolicySearchResponse = parse(response.body).extract[PostBusinessPolicySearchResponse]
    assert(responseBody.nodes.isEmpty)
    assert(responseBody.offsetUpdated === false)
  
    val offset: Seq[(Option[String], Long)] = Await.result(DBCONNECTION.getDb.run(SearchOffsetPolicyTQ.getOffsetSession("TestPolicySearchPost/a1", "TestPolicySearchPost/pol1").result), 15.seconds)
    assert(offset.nonEmpty)
    assert(offset.head._1.isEmpty)
    assert(offset.head._2 === 1L)
  }
}
