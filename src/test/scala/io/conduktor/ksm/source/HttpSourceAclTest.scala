package io.conduktor.ksm.source

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, getRequestedFor, urlPathEqualTo}
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.matching.EqualToPattern
import io.conduktor.ksm.parser.AclParserRegistry
import io.conduktor.ksm.parser.csv.CsvAclParser
import io.conduktor.ksm.source.security.GoogleIAM
import org.apache.http.HttpHeaders.{CONTENT_LENGTH, CONTENT_TYPE}
import org.scalamock.scalatest.MockFactory
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

import java.io.BufferedReader

class HttpSourceAclTest extends FlatSpec with Matchers with MockFactory with BeforeAndAfterEach {

  val csvAclParser = new CsvAclParser()
  val aclParserRegistryMock: AclParserRegistry = stub[AclParserRegistry]
  (aclParserRegistryMock.getParserByFilename _).when(*).returns(csvAclParser)

  private val port = 8080
  // Run wiremock server on local machine with specified port.
  private val wireMockServer = new WireMockServer(wireMockConfig().port(port))

  val path = s"/acl"
  val content: String =
    """hello
      |world
      |""".stripMargin

  override def beforeEach: Unit = {
    wireMockServer.start()
  }

  override def afterEach: Unit = {
    wireMockServer.stop()
  }

  it should "be able to read contents of a HTTP Endpoint" in {
    wireMockServer.stubFor(
      WireMock.get(urlPathEqualTo(path))
        .willReturn(aResponse()
          .withHeader(CONTENT_TYPE, "text/plain")
          .withHeader(CONTENT_LENGTH, content.length.toString )
          .withBody(content)
          .withStatus(200)))

    val httpSourceAcl = new HttpSourceAcl(aclParserRegistryMock)

    httpSourceAcl.configure(wireMockServer.baseUrl() + path, csvAclParser.name, "GET")

    val reader = httpSourceAcl.refresh()

    wireMockServer.verify(
      getRequestedFor(urlPathEqualTo(path))
        .withHeader(CONTENT_TYPE, new EqualToPattern("text/plain"))
    )

    reader match {
      case Some(ParsingContext(_, x: BufferedReader)) =>
        val read = Stream.continually(x.readLine()).takeWhile(Option(_).nonEmpty).map(_.concat("\n")).mkString

        content shouldBe read
      case _ => fail() // didn't read
    }
  }

  it should "throw Exception when missing required Content-Length header" in {
    wireMockServer.stubFor(
      WireMock.get(urlPathEqualTo(path))
        .willReturn(aResponse()
          .withHeader(CONTENT_TYPE, "text/plain")
          .withBody(content)
          .withStatus(200)))

    val httpSourceAcl = new HttpSourceAcl(aclParserRegistryMock)

    httpSourceAcl.configure(wireMockServer.baseUrl() + path, csvAclParser.name, "GET", None, requireContentLengthHeader = true)

    assertThrows[Exception] {
      httpSourceAcl.refresh()
    }
  }

  it should "skip body length validation when missing optional Content-Length header" in {
    wireMockServer.stubFor(
      WireMock.get(urlPathEqualTo(path))
        .willReturn(aResponse()
          .withHeader(CONTENT_TYPE, "text/plain")
          .withBody(content)
          .withStatus(200)))

    val httpSourceAcl = new HttpSourceAcl(aclParserRegistryMock)

    httpSourceAcl.configure(wireMockServer.baseUrl() + path, csvAclParser.name, "GET")

    val reader = httpSourceAcl.refresh()

    wireMockServer.verify(
      getRequestedFor(urlPathEqualTo(path))
        .withoutHeader(CONTENT_LENGTH)
    )

    reader match {
      case Some(ParsingContext(_, x: BufferedReader)) =>
        val read = Stream.continually(x.readLine()).takeWhile(Option(_).nonEmpty).map(_.concat("\n")).mkString

        content shouldBe read
      case _ => fail() // didn't read
    }
  }

  it should "throw Exception in mismatch Content-Length" in {

    wireMockServer.stubFor(
      WireMock.get(urlPathEqualTo(path))
        .willReturn(aResponse()
          .withHeader(CONTENT_TYPE, "text/plain")
          .withHeader(CONTENT_LENGTH, "99999999")  // if < of payload.length will truncate to this value
          .withBody(content)
          .withStatus(200)))

    val httpSourceAcl = new HttpSourceAcl(aclParserRegistryMock)

    httpSourceAcl.configure(wireMockServer.baseUrl() + path, csvAclParser.name, "GET")

    assertThrows[Exception] {
      httpSourceAcl.refresh()
    }
  }

  it should "validate Google IAM authentication token when security is enabled" in {

    val mockTokenPath = s"/mock-token"
    val mockTokenContent = "{ id_token: 'eyJhbGciOiJSUzI1NiIsImtpZCI6IjAzMmIyZWYzZDJjMjgwNjE1N2Y4YTliOWY0ZWY3Nzk4MzRmODVhZGEiLCJ0eXAiOiJKV1QifQ.eyJhdWQiOiJodHRwczovL2Zha2UtYXVkaWVuY2UuY29tIiwiYXpwIjoic3ZjLWthZmthLXNlY3VyaXR5LW1hbmFnZXJAZGZleC1zdHJlYW0tcHJvZC5pYW0uZ3NlcnZpY2VhY2NvdW50LmNvbSIsImVtYWlsIjoic3ZjLWthZmthLXNlY3VyaXR5LW1hbmFnZXJAZGZleC1zdHJlYW0tcHJvZC5pYW0uZ3NlcnZpY2VhY2NvdW50LmNvbSIsImVtYWlsX3ZlcmlmaWVkIjp0cnVlLCJleHAiOjE2MzcyNTQ4ODcsImlhdCI6MTYzNzI1MTI4NywiaXNzIjoiaHR0cHM6Ly9hY2NvdW50cy5nb29nbGUuY29tIiwic3ViIjoiMTA1MDkxODM1NzMyNzk2MzUwNzUzIn0.QLTW9XlZ_lPH_45GG6SNvpExo4r7Kv9Mgpu-FScdIgOWFXrf1YsNUHLRByqkpBNGLuusKRxbM8fa3-5s_NzNGjVEstqXs2di3SA6mCZ0ofR0rTrGcWN1Bnc4DBgCNa5uKkqxY3Dbi-ckIKdrYpW8mqxQUdOhTmzMfEGUe4eWDc_CKu2xrV30NhlRQGWWjhuGuvVwekzvlfD3JmsFZh61smttPnnNUYiini23mLcbObJa1jnBCZI9RT7iUFPHf_SwSgxPJBEDNkUkGDh7snlItlT_ENz1vhNNQlG9AjeQjEi_F4FbCyAWSXE4qeaJn30FJzSLHfT620rxTit6SfVuNQ' }"

    // Google Auth Token Mock
    wireMockServer.stubFor(
      WireMock.post(urlPathEqualTo(mockTokenPath))
        .willReturn(aResponse()
          .withHeader(CONTENT_TYPE, "application/json")
          .withBody(mockTokenContent)
          .withStatus(200)))

    wireMockServer.stubFor(
      WireMock.get(urlPathEqualTo(path))
        .willReturn(aResponse()
          .withHeader(CONTENT_TYPE, "application/json")
          .withBody(content)
          .withStatus(200)))

    val httpSourceAcl = new HttpSourceAcl(aclParserRegistryMock)
    val googleIAM = new GoogleIAM(
      "fake-service-account@fake-project.iam.gserviceaccount.com",
      "https://fake-audience.com",
      "{ \"type\": \"service_account\",\n  \"private_key_id\": \"abc\",\n  \"private_key\": \"-----BEGIN PRIVATE KEY-----\\nMIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDY3E8o1NEFcjMM\\nHW/5ZfFJw29/8NEqpViNjQIx95Xx5KDtJ+nWn9+OW0uqsSqKlKGhAdAo+Q6bjx2c\\nuXVsXTu7XrZUY5Kltvj94DvUa1wjNXs606r/RxWTJ58bfdC+gLLxBfGnB6CwK0YQ\\nxnfpjNbkUfVVzO0MQD7UP0Hl5ZcY0Puvxd/yHuONQn/rIAieTHH1pqgW+zrH/y3c\\n59IGThC9PPtugI9ea8RSnVj3PWz1bX2UkCDpy9IRh9LzJLaYYX9RUd7++dULUlat\\nAaXBh1U6emUDzhrIsgApjDVtimOPbmQWmX1S60mqQikRpVYZ8u+NDD+LNw+/Eovn\\nxCj2Y3z1AgMBAAECggEAWDBzoqO1IvVXjBA2lqId10T6hXmN3j1ifyH+aAqK+FVl\\nGjyWjDj0xWQcJ9ync7bQ6fSeTeNGzP0M6kzDU1+w6FgyZqwdmXWI2VmEizRjwk+/\\n/uLQUcL7I55Dxn7KUoZs/rZPmQDxmGLoue60Gg6z3yLzVcKiDc7cnhzhdBgDc8vd\\nQorNAlqGPRnm3EqKQ6VQp6fyQmCAxrr45kspRXNLddat3AMsuqImDkqGKBmF3Q1y\\nxWGe81LphUiRqvqbyUlh6cdSZ8pLBpc9m0c3qWPKs9paqBIvgUPlvOZMqec6x4S6\\nChbdkkTRLnbsRr0Yg/nDeEPlkhRBhasXpxpMUBgPywKBgQDs2axNkFjbU94uXvd5\\nznUhDVxPFBuxyUHtsJNqW4p/ujLNimGet5E/YthCnQeC2P3Ym7c3fiz68amM6hiA\\nOnW7HYPZ+jKFnefpAtjyOOs46AkftEg07T9XjwWNPt8+8l0DYawPoJgbM5iE0L2O\\nx8TU1Vs4mXc+ql9F90GzI0x3VwKBgQDqZOOqWw3hTnNT07Ixqnmd3dugV9S7eW6o\\nU9OoUgJB4rYTpG+yFqNqbRT8bkx37iKBMEReppqonOqGm4wtuRR6LSLlgcIU9Iwx\\nyfH12UWqVmFSHsgZFqM/cK3wGev38h1WBIOx3/djKn7BdlKVh8kWyx6uC8bmV+E6\\nOoK0vJD6kwKBgHAySOnROBZlqzkiKW8c+uU2VATtzJSydrWm0J4wUPJifNBa/hVW\\ndcqmAzXC9xznt5AVa3wxHBOfyKaE+ig8CSsjNyNZ3vbmr0X04FoV1m91k2TeXNod\\njMTobkPThaNm4eLJMN2SQJuaHGTGERWC0l3T18t+/zrDMDCPiSLX1NAvAoGBAN1T\\nVLJYdjvIMxf1bm59VYcepbK7HLHFkRq6xMJMZbtG0ryraZjUzYvB4q4VjHk2UDiC\\nlhx13tXWDZH7MJtABzjyg+AI7XWSEQs2cBXACos0M4Myc6lU+eL+iA+OuoUOhmrh\\nqmT8YYGu76/IBWUSqWuvcpHPpwl7871i4Ga/I3qnAoGBANNkKAcMoeAbJQK7a/Rn\\nwPEJB+dPgNDIaboAsh1nZhVhN5cvdvCWuEYgOGCPQLYQF0zmTLcM+sVxOYgfy8mV\\nfbNgPgsP5xmu6dw2COBKdtozw0HrWSRjACd1N4yGu75+wPCcX/gQarcjRcXXZeEa\\nNtBLSfcqPULqD+h7br9lEJio\\n-----END PRIVATE KEY-----\\n\",\n  \"client_email\": \"123-abc@developer.gserviceaccount.com\",\n  \"client_id\": \"123-abc.apps.googleusercontent.com\",\n  \"auth_uri\": \"https://accounts.google.com/o/oauth2/auth\",\n  \"token_uri\": \"http://localhost:8080/mock-token\" }"
    )

    httpSourceAcl.configure(wireMockServer.baseUrl() + path,
      csvAclParser.name, "GET",
      Some(googleIAM)
    )

    val reader = httpSourceAcl.refresh()

    wireMockServer.verify(
      getRequestedFor(urlPathEqualTo(path))
        .withHeader("Content-Type", new EqualToPattern("text/plain"))
    )

    reader match {
      case Some(ParsingContext(_, x: BufferedReader)) =>
        val read = Stream.continually(x.readLine()).takeWhile(Option(_).nonEmpty).map(_.concat("\n")).mkString

        content shouldBe read
      case _ => fail() // didn't read
    }
  }

  it should "validate Google IAM authentication token when security is enabled with Config object" in {

    val mockTokenPath = s"/mock-token"
    val mockTokenContent = "{ id_token: 'eyJhbGciOiJSUzI1NiIsImtpZCI6IjAzMmIyZWYzZDJjMjgwNjE1N2Y4YTliOWY0ZWY3Nzk4MzRmODVhZGEiLCJ0eXAiOiJKV1QifQ.eyJhdWQiOiJodHRwczovL2Zha2UtYXVkaWVuY2UuY29tIiwiYXpwIjoic3ZjLWthZmthLXNlY3VyaXR5LW1hbmFnZXJAZGZleC1zdHJlYW0tcHJvZC5pYW0uZ3NlcnZpY2VhY2NvdW50LmNvbSIsImVtYWlsIjoic3ZjLWthZmthLXNlY3VyaXR5LW1hbmFnZXJAZGZleC1zdHJlYW0tcHJvZC5pYW0uZ3NlcnZpY2VhY2NvdW50LmNvbSIsImVtYWlsX3ZlcmlmaWVkIjp0cnVlLCJleHAiOjE2MzcyNTQ4ODcsImlhdCI6MTYzNzI1MTI4NywiaXNzIjoiaHR0cHM6Ly9hY2NvdW50cy5nb29nbGUuY29tIiwic3ViIjoiMTA1MDkxODM1NzMyNzk2MzUwNzUzIn0.QLTW9XlZ_lPH_45GG6SNvpExo4r7Kv9Mgpu-FScdIgOWFXrf1YsNUHLRByqkpBNGLuusKRxbM8fa3-5s_NzNGjVEstqXs2di3SA6mCZ0ofR0rTrGcWN1Bnc4DBgCNa5uKkqxY3Dbi-ckIKdrYpW8mqxQUdOhTmzMfEGUe4eWDc_CKu2xrV30NhlRQGWWjhuGuvVwekzvlfD3JmsFZh61smttPnnNUYiini23mLcbObJa1jnBCZI9RT7iUFPHf_SwSgxPJBEDNkUkGDh7snlItlT_ENz1vhNNQlG9AjeQjEi_F4FbCyAWSXE4qeaJn30FJzSLHfT620rxTit6SfVuNQ' }"

    // Google Auth Token Mock
    wireMockServer.stubFor(
      WireMock.post(urlPathEqualTo(mockTokenPath))
        .willReturn(aResponse()
          .withHeader(CONTENT_TYPE, "application/json")
          .withBody(mockTokenContent)
          .withStatus(200)))

    wireMockServer.stubFor(
      WireMock.get(urlPathEqualTo(path))
        .willReturn(aResponse()
          .withHeader(CONTENT_TYPE, "application/json")
          .withBody(content)
          .withStatus(200)))

    val httpSourceAcl = new HttpSourceAcl(aclParserRegistryMock)
    val googleIAM = new GoogleIAM(
      "fake-service-account@fake-project.iam.gserviceaccount.com",
      "https://fake-audience.com",
      "{ \"type\": \"service_account\",\n  \"private_key_id\": \"abc\",\n  \"private_key\": \"-----BEGIN PRIVATE KEY-----\\nMIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDY3E8o1NEFcjMM\\nHW/5ZfFJw29/8NEqpViNjQIx95Xx5KDtJ+nWn9+OW0uqsSqKlKGhAdAo+Q6bjx2c\\nuXVsXTu7XrZUY5Kltvj94DvUa1wjNXs606r/RxWTJ58bfdC+gLLxBfGnB6CwK0YQ\\nxnfpjNbkUfVVzO0MQD7UP0Hl5ZcY0Puvxd/yHuONQn/rIAieTHH1pqgW+zrH/y3c\\n59IGThC9PPtugI9ea8RSnVj3PWz1bX2UkCDpy9IRh9LzJLaYYX9RUd7++dULUlat\\nAaXBh1U6emUDzhrIsgApjDVtimOPbmQWmX1S60mqQikRpVYZ8u+NDD+LNw+/Eovn\\nxCj2Y3z1AgMBAAECggEAWDBzoqO1IvVXjBA2lqId10T6hXmN3j1ifyH+aAqK+FVl\\nGjyWjDj0xWQcJ9ync7bQ6fSeTeNGzP0M6kzDU1+w6FgyZqwdmXWI2VmEizRjwk+/\\n/uLQUcL7I55Dxn7KUoZs/rZPmQDxmGLoue60Gg6z3yLzVcKiDc7cnhzhdBgDc8vd\\nQorNAlqGPRnm3EqKQ6VQp6fyQmCAxrr45kspRXNLddat3AMsuqImDkqGKBmF3Q1y\\nxWGe81LphUiRqvqbyUlh6cdSZ8pLBpc9m0c3qWPKs9paqBIvgUPlvOZMqec6x4S6\\nChbdkkTRLnbsRr0Yg/nDeEPlkhRBhasXpxpMUBgPywKBgQDs2axNkFjbU94uXvd5\\nznUhDVxPFBuxyUHtsJNqW4p/ujLNimGet5E/YthCnQeC2P3Ym7c3fiz68amM6hiA\\nOnW7HYPZ+jKFnefpAtjyOOs46AkftEg07T9XjwWNPt8+8l0DYawPoJgbM5iE0L2O\\nx8TU1Vs4mXc+ql9F90GzI0x3VwKBgQDqZOOqWw3hTnNT07Ixqnmd3dugV9S7eW6o\\nU9OoUgJB4rYTpG+yFqNqbRT8bkx37iKBMEReppqonOqGm4wtuRR6LSLlgcIU9Iwx\\nyfH12UWqVmFSHsgZFqM/cK3wGev38h1WBIOx3/djKn7BdlKVh8kWyx6uC8bmV+E6\\nOoK0vJD6kwKBgHAySOnROBZlqzkiKW8c+uU2VATtzJSydrWm0J4wUPJifNBa/hVW\\ndcqmAzXC9xznt5AVa3wxHBOfyKaE+ig8CSsjNyNZ3vbmr0X04FoV1m91k2TeXNod\\njMTobkPThaNm4eLJMN2SQJuaHGTGERWC0l3T18t+/zrDMDCPiSLX1NAvAoGBAN1T\\nVLJYdjvIMxf1bm59VYcepbK7HLHFkRq6xMJMZbtG0ryraZjUzYvB4q4VjHk2UDiC\\nlhx13tXWDZH7MJtABzjyg+AI7XWSEQs2cBXACos0M4Myc6lU+eL+iA+OuoUOhmrh\\nqmT8YYGu76/IBWUSqWuvcpHPpwl7871i4Ga/I3qnAoGBANNkKAcMoeAbJQK7a/Rn\\nwPEJB+dPgNDIaboAsh1nZhVhN5cvdvCWuEYgOGCPQLYQF0zmTLcM+sVxOYgfy8mV\\nfbNgPgsP5xmu6dw2COBKdtozw0HrWSRjACd1N4yGu75+wPCcX/gQarcjRcXXZeEa\\nNtBLSfcqPULqD+h7br9lEJio\\n-----END PRIVATE KEY-----\\n\",\n  \"client_email\": \"123-abc@developer.gserviceaccount.com\",\n  \"client_id\": \"123-abc.apps.googleusercontent.com\",\n  \"auth_uri\": \"https://accounts.google.com/o/oauth2/auth\",\n  \"token_uri\": \"http://localhost:8080/mock-token\" }"
    )

    httpSourceAcl.configure(wireMockServer.baseUrl() + path,
      csvAclParser.name, "GET",
      Some(googleIAM)
    )

    val reader = httpSourceAcl.refresh()

    wireMockServer.verify(
      getRequestedFor(urlPathEqualTo(path))
        .withHeader("Content-Type", new EqualToPattern("text/plain"))
    )

    reader match {
      case Some(ParsingContext(_, x: BufferedReader)) =>
        val read = Stream.continually(x.readLine()).takeWhile(Option(_).nonEmpty).map(_.concat("\n")).mkString

        content shouldBe read
      case _ => fail() // didn't read
    }
  }
}
