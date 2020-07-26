package com.github.simplesteph.ksm.source

import java.io.BufferedReader
import java.util.UUID

import org.scalatest.{FlatSpec, Matchers}

class S3SourceAclTest extends FlatSpec with Matchers {

  "S3SourceAcl" should "be able to read contents of a file in s3" in {


    //this starts the mock s3 api
    val s3SourceTopic = new DummyS3SourceAcl()

    val bucket = "testbucket"
    val key = UUID.randomUUID().toString
    val region = "us-east-1"
    val content = "hello"

    val client = s3SourceTopic.s3Client()

    client.createBucket(bucket)

    //put the test file
    client.putObject(bucket, key, content)

    s3SourceTopic.configure(bucket, key, region)

    val reader = s3SourceTopic.refresh()

    reader match {
      case None => fail() // didn't read
      case Some(x: BufferedReader) =>
        val read = Stream.continually(x.readLine()).takeWhile(Option(_).nonEmpty).mkString

        content shouldBe read
    }

    s3SourceTopic.api.shutdown // kills the underlying actor system. Use api.stop() to just unbind the port.

  }
}
