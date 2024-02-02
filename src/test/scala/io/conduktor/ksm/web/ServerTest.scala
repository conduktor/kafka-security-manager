package io.conduktor.ksm.web

import org.scalatest.{BeforeAndAfterAll, FlatSpec}
import skinny.http.{HTTP, Request}

class LivenessTestProbe extends Probe {
  var success = false;

  override def isSuccessful: Boolean = {
    success
  }
}

class ServerTest extends FlatSpec with BeforeAndAfterAll {
  private val livenessTestProbe1 = new LivenessTestProbe
  private val livenessTestProbe2 = new LivenessTestProbe
  private val testSubject =
    new Server(7777, List(livenessTestProbe1, livenessTestProbe2))
  private val testServerUrl = "http://localhost:7777";
  private val testReadyEndpoint = testServerUrl + "/api/probe/ready"
  private val testAliveEndpoint = testServerUrl + "/api/probe/alive"

  override protected def beforeAll(): Unit = {
    testSubject.start()
  }

  override protected def afterAll(): Unit = {
    testSubject.stop()
  }

  "get ready probe endpoint" should "return 200 with success true" in {
    val response = HTTP.get(Request(testReadyEndpoint))
    assert(response.status == 200)
    assert(new String(response.body) == "{\"success\":true}")
  }

  "get alive probe endpoint" should "return 200 with success true, if all probes are successful" in {
    livenessTestProbe1.success = true
    livenessTestProbe2.success = true
    val response = HTTP.get(Request(testAliveEndpoint))
    assert(response.status == 200)
    assert(new String(response.body) == "{\"success\":true}")
  }

  "get alive probe endpoint" should "return 500 with success false, if some probes are un-successful" in {
    livenessTestProbe1.success = true
    livenessTestProbe2.success = false
    val response = HTTP.get(Request(testAliveEndpoint))
    assert(response.status == 500)
    assert(new String(response.body) == "{\"success\":false}")
  }

  "get alive probe endpoint" should "return 500 with success false, if all probes are un-successful" in {
    livenessTestProbe1.success = false
    livenessTestProbe2.success = false
    val response = HTTP.get(Request(testAliveEndpoint))
    assert(response.status == 500)
    assert(new String(response.body) == "{\"success\":false}")
  }
}
