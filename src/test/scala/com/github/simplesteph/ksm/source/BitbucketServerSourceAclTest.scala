package com.github.simplesteph.ksm.source

import java.io.{BufferedReader, Reader}
import java.util.Base64
import java.util.stream.Collectors

import org.scalatest.{FlatSpec, Matchers}
import skinny.http.{HTTP, Request, Response}


class BitbucketServerSourceAclTest extends FlatSpec with Matchers {


  "Test" should "Successfully return body for specific branch" in {
    val bitbucketServerSoureAcl = new BitbucketServerSourceAcl()
    val dummyHttp = new DummyHttp(Response(200, body = DummyHttp.commitsContent.getBytes))
    dummyHttp.commitMatcher = req => {
      req.url.endsWith("commits") && req.queryParams.exists(q => q.name == "until" && q.value == "ref/feature-F1") && req.queryParams.length == 2
    }
    
    dummyHttp.browseMatcher = req => req.url.endsWith("browse/testFile?raw") && req.queryParams.exists(q => q.name == "at" && q.value == "ref/feature-F1") && req.queryParams.length == 1

    populateSourceAcl(bitbucketServerSoureAcl, branch = "ref/feature-F1")
    bitbucketServerSoureAcl.http = dummyHttp

    val response = bitbucketServerSoureAcl.refresh()


    response.isEmpty shouldBe false
    readAllLines(response.get) shouldBe DummyHttp.browseFile
  }

  "Test" should "Successfully return body for acl" in {
    val bitbucketServerSoureAcl = new BitbucketServerSourceAcl()
    val dummyHttp = new DummyHttp(Response(200, body = DummyHttp.commitsContent.getBytes))
    populateSourceAcl(bitbucketServerSoureAcl)
    bitbucketServerSoureAcl.http = dummyHttp
    
    val response = bitbucketServerSoureAcl.refresh()
    
    response.isEmpty shouldBe false
    readAllLines(response.get) shouldBe DummyHttp.browseFile
  }

  "Test" should "Pass base64 auth to bitbucket" in {
    val bitbucketServerSoureAcl = new BitbucketServerSourceAcl()
    val dummyHttp = new DummyHttp(Response(200, body = DummyHttp.commitsContent.getBytes))
    val expected = "Basic " + Base64.getEncoder.encodeToString("test:pwd".getBytes)
    populateSourceAcl(bitbucketServerSoureAcl)
    bitbucketServerSoureAcl.http = dummyHttp
    dummyHttp.commitMatcher = req => req.header("Authorization").get == expected && req.url.endsWith("commits")
    dummyHttp.browseMatcher = req => req.header("Authorization").get == expected && req.url.endsWith("browse/testFile?raw")


    val response = bitbucketServerSoureAcl.refresh()


    response.isEmpty shouldBe false
    readAllLines(response.get) shouldBe DummyHttp.browseFile
  }

  "Test" should "Successfully not return body if acl do not changed since last call" in {
    val bitbucketServerSoureAcl = new BitbucketServerSourceAcl()
    val dummyHttp = new DummyHttp(Response(200, body = DummyHttp.commitsContent.getBytes))
    populateSourceAcl(bitbucketServerSoureAcl)
    bitbucketServerSoureAcl.http = dummyHttp

    val firstResponse = bitbucketServerSoureAcl.refresh()
    dummyHttp.commitMatcher = req => req.queryParams.exists(q => q.value == "c22287a15f6bada0b3b121b838a13dc3fad613cc")
    dummyHttp.commitsResponse = Response(200, body = DummyHttp.commitsEmptyResponse.getBytes)
    dummyHttp.browseResponse = Response(500)
    val response = bitbucketServerSoureAcl.refresh()


    firstResponse.isEmpty shouldBe false
    response.isEmpty shouldBe true
  }

  "Test" should "Successfully return body if acl changed since last call" in {
    val bitbucketServerSoureAcl = new BitbucketServerSourceAcl()
    val dummyHttp = new DummyHttp(Response(200, body = DummyHttp.commitsContentFirst.getBytes))
    populateSourceAcl(bitbucketServerSoureAcl)
    bitbucketServerSoureAcl.http = dummyHttp
    val firstResponse = bitbucketServerSoureAcl.refresh()
    val firstCommit = bitbucketServerSoureAcl.lastCommit.get
    dummyHttp.commitMatcher = req => req.queryParams.exists(q => q.value == "somefirsthash")
    dummyHttp.commitsResponse = Response(200, body = DummyHttp.commitsContent.getBytes)


    val response = bitbucketServerSoureAcl.refresh()


    firstResponse.isEmpty shouldBe false
    response.isEmpty shouldBe false
    firstCommit shouldBe "somefirsthash"
    bitbucketServerSoureAcl.lastCommit.get shouldBe "c22287a15f6bada0b3b121b838a13dc3fad613cc"
  }

  def readAllLines(reader: Reader): String = {
    val buffReader = new BufferedReader(reader)
    buffReader.lines().collect(Collectors.joining("\n"))
  }

  def populateSourceAcl(source: BitbucketServerSourceAcl, filePath: String = "testFile", branch: String = null): Unit = {
    source.hostname = "example"
    source.port = "8888"
    source.protocol = "http"
    source.project = "PROJ"
    source.repo = "REPO"
    source.filePath = filePath
    source.username = "test"
    source.password = "pwd"
    source.branch = Option(branch)
  }
}


class DummyHttp(var commitsResponse: Response, var browseResponse: Response = Response(200, body = DummyHttp.browseFile.getBytes)) extends HTTP {

  var commitMatcher: Request => Boolean = req => {
    req.url.endsWith("commits") && req.queryParams.exists(q => q.name == "path"
      && q.value == "testFile") && !req.queryParams.exists(q => q.name == "until")
  }

  var browseMatcher: Request => Boolean = req => {
    req.url.endsWith("browse/testFile?raw") && !req.queryParams.exists(q => q.name == "at")
  }

  override def get(req: Request): Response = {
    if (commitMatcher.apply(req)) {
      commitsResponse
    } else if (browseMatcher.apply(req)) {
      browseResponse
    } else {
      Response(404)
    }
  }
}

object DummyHttp {
  val browseFile: String =
    """KafkaPrincipal,ResourceType,PatternType,ResourceName,Operation,PermissionType,Host
      |User:alice,Topic,LITERAL,foo,Read,Allow,*""".stripMargin

  val commitsContent: String =
    """{
  "values": [
    {
      "id": "c22287a15f6bada0b3b121b838a13dc3fad613cc",
      "displayId": "c22287a15f6",
      "authorTimestamp": 1584392899000,
      "committerTimestamp": 1584392899000,
      "message": "file edited online with Bitbucket",
      "parents": [
        {
          "id": "9a4e9734423a8bed1d945dbb8cc775a68ca7c0d7",
          "displayId": "9a4e973442"
        }
      ]
    },
    {
      "id": "8d06d666dc41e4b8542084e7ee11e218df7d4ac3",
      "displayId": "8d06d666dc4",
      "authorTimestamp": 1584389657000,
      "committerTimestamp": 1584389657000,
      "message": "file edited online with Bitbucket",
      "parents": [
        {
          "id": "ec4de8879d86e4005e6f3def1d671a257a46eb27",
          "displayId": "ec4de8879d8"
        }
      ]
    }
  ],
  "size": 2,
  "isLastPage": true,
  "start": 0,
  "limit": 25,
  "nextPageStart": null
}""".stripMargin

  val commitsContentFirst: String =
    """{
  "values": [
    {
      "id": "somefirsthash",
      "displayId": "somefirsthash",
      "authorTimestamp": 1584389657000,
      "committerTimestamp": 1584389657000,
      "message": "file edited online with Bitbucket",
      "parents": [
        {
          "id": "olderHash",
          "displayId": "olderHash"
        }
      ]
    }
  ],
  "size": 1,
  "isLastPage": true,
  "start": 0,
  "limit": 25,
  "nextPageStart": null
}""".stripMargin
  val commitsEmptyResponse: String =
    """{
  "values": [],
  "size": 0,
  "isLastPage": true,
  "start": 0,
  "limit": 25,
  "nextPageStart": null
}""".stripMargin

}
