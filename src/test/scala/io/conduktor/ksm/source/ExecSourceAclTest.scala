package io.conduktor.ksm.source

import io.conduktor.ksm.parser.AclParserRegistry
import io.conduktor.ksm.parser.yaml.YamlAclParser
import io.conduktor.ksm.parser.{AclParserRegistry}

import java.io.{File}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import kafka.security.auth._
import org.scalamock.scalatest.MockFactory
import org.scalatest.{FlatSpec, Matchers}

class ExecSourceAclTest extends FlatSpec with Matchers with MockFactory {

  val yamlAclParser = new YamlAclParser()
  val aclParserRegistryMock: AclParserRegistry = stub[AclParserRegistry]
  (aclParserRegistryMock.getParserByFilename _).when(*).returns(yamlAclParser)

  "Test 1" should "successfully parse exec output" in {
    
    val yamlContent = 
      """
        |users:
        |  alice:
        |    topics:
        |      foo:
        |        - Read
        |      bar*:
        |        - Produce
        |  bob:
        |    groups:
        |      bar:
        |        - Write,Deny,12.34.56.78
        |      bob*:
        |        - All
        |    transactional_ids:
        |      bar-*:
        |        - All
        |  peter:
        |    clusters:
        |      kafka-cluster:
        |        - Create""".stripMargin

    val file = File.createTempFile("ksm", "test")
    val filepath = file.getAbsolutePath
    println(filepath)
    Files.write(
      Paths.get(file.toURI),
      yamlContent.getBytes(StandardCharsets.UTF_8)
    )

    val execSourceAcl = new ExecSourceAcl(aclParserRegistryMock)
    execSourceAcl.configure("/bin/cat", filepath, "|", "yaml")

    val parsingContext = execSourceAcl.refresh().get

    yamlAclParser.aclsFromReader(parsingContext.reader).result.isRight shouldBe true
  }

  "Test 2" should "retun None on non-zero exit status" in {

    val execSourceAcl = new ExecSourceAcl(aclParserRegistryMock)
    execSourceAcl.configure("/bin/false", "", "|")

    execSourceAcl.refresh() shouldBe None
    
  }



}
