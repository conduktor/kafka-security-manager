package io.conduktor.ksm.source

import io.conduktor.ksm.parser.csv.CsvAclParser
import io.conduktor.ksm.parser.{AclParser, AclParserRegistry}
import org.scalamock.scalatest.MockFactory
import org.scalatest.{FlatSpec, Matchers}

import java.io.BufferedReader
import java.util.UUID

class S3SourceAclTest extends FlatSpec with Matchers with MockFactory {

  val csvlAclParser = new CsvAclParser()
  val aclParserRegistryMock: AclParserRegistry = stub[AclParserRegistry]
  (aclParserRegistryMock.getParserByFilename _).when(*).returns(csvlAclParser)

  "S3SourceAcl" should "be able to read contents of a file in s3" in {

    //this starts the mock s3 api
    val s3SourceAcl = new DummyS3SourceAcl(aclParserRegistryMock)

    val bucket = "testbucket"
    val key = UUID.randomUUID().toString
    val region = "us-east-1"
    val content =
      """hello
        |world
        |""".stripMargin

    val client = s3SourceAcl.s3Client()

    client.createBucket(bucket)

    //put the test file
    client.putObject(bucket, key, content)

    s3SourceAcl.configure(bucket, key, region)

    val res = s3SourceAcl.refresh()

    res.head match {
      case ParsingContext(_, _: AclParser, x: BufferedReader, _) =>
        val read = Stream
          .continually(x.readLine())
          .takeWhile(Option(_).nonEmpty)
          .map(_.concat("\n"))
          .mkString
        content shouldBe read
      case _ => fail()
    }

    s3SourceAcl.api.shutdown // kills the underlying actor system. Use api.stop() to just unbind the port.

  }
}
