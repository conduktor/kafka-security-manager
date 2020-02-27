package com.github.simplesteph.ksm.source

import java.io.{File, Reader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import com.github.simplesteph.ksm.parser.CsvAclParser
import kafka.security.auth._
import org.apache.kafka.common.resource.PatternType
import org.apache.kafka.common.utils.SecurityUtils
import org.scalatest.{FlatSpec, Matchers}

class FileSourceAclTest extends FlatSpec with Matchers {

  val csvlAclParser = new CsvAclParser()

  "fileSourceAcl Refresh" should "correctly parse a file" in {

    val file = File.createTempFile("ksm", "test")

    val content1 =
      """KafkaPrincipal,ResourceType,PatternType,ResourceName,Operation,PermissionType,Host
        |User:alice,Topic,LITERAL,foo,Read,Allow,*
        |User:bob,Group,PREFIXED,bar,Write,Deny,12.34.56.78
        |User:peter,Cluster,LITERAL,kafka-cluster,Create,Allow,*
        |""".stripMargin

    Files.write(
      Paths.get(file.toURI),
      content1.getBytes(StandardCharsets.UTF_8)
    )

    val fileSourceAcl = new FileSourceAcl
    fileSourceAcl.filename = file.getAbsolutePath

    val acl1 =
      Acl(SecurityUtils.parseKafkaPrincipal("User:alice"), Allow, "*", Read)
    val acl2 = Acl(
      SecurityUtils.parseKafkaPrincipal("User:bob"),
      Deny,
      "12.34.56.78",
      Write
    )
    val acl3 =
      Acl(SecurityUtils.parseKafkaPrincipal("User:peter"), Allow, "*", Create)

    val res1 = Resource(Topic, "foo", PatternType.LITERAL)
    val res2 = Resource(Group, "bar", PatternType.PREFIXED)
    val res3 = Resource(Cluster, "kafka-cluster", PatternType.LITERAL)

    val reader = fileSourceAcl.refresh().get
    csvlAclParser.aclsFromReader(reader).result shouldBe Right(
      Set(res1 -> acl1, res2 -> acl2, res3 -> acl3)
    )

  }

  "fileSourceAcl Refresh" should "correctly parse a file and then refresh after changes" in {

    val file = File.createTempFile("ksm", "test")

    val content1 =
      """KafkaPrincipal,ResourceType,PatternType,ResourceName,Operation,PermissionType,Host
        |User:alice,Topic,LITERAL,foo,Read,Allow,*
        |User:bob,Group,PREFIXED,bar,Write,Deny,12.34.56.78
        |User:peter,Cluster,LITERAL,kafka-cluster,Create,Allow,*
        |""".stripMargin

    Files.write(
      Paths.get(file.toURI),
      content1.getBytes(StandardCharsets.UTF_8)
    )

    val fileSourceAcl = new FileSourceAcl
    fileSourceAcl.filename = file.getAbsolutePath

    val acl1 =
      Acl(SecurityUtils.parseKafkaPrincipal("User:alice"), Allow, "*", Read)
    val acl2 = Acl(
      SecurityUtils.parseKafkaPrincipal("User:bob"),
      Deny,
      "12.34.56.78",
      Write
    )
    val acl3 =
      Acl(SecurityUtils.parseKafkaPrincipal("User:peter"), Allow, "*", Create)

    val res1 = Resource(Topic, "foo", PatternType.LITERAL)
    val res2 = Resource(Group, "bar", PatternType.PREFIXED)
    val res3 = Resource(Cluster, "kafka-cluster", PatternType.LITERAL)

    val reader1: Reader = fileSourceAcl.refresh().get
    csvlAclParser.aclsFromReader(reader1).result shouldBe Right(
      Set(res1 -> acl1, res2 -> acl2, res3 -> acl3)
    )
    reader1.close()

    val content2 =
      """KafkaPrincipal,ResourceType,PatternType,ResourceName,Operation,PermissionType,Host
        |User:alice,Topic,LITERAL,foo,Read,Allow,*
        |""".stripMargin

    Files.write(
      Paths.get(file.toURI),
      content2.getBytes(StandardCharsets.UTF_8)
    )
    // we force the modification of the time of the file so that the test passes
    file.setLastModified(System.currentTimeMillis() + 10000)

    val reader2 = fileSourceAcl.refresh().get
    csvlAclParser.aclsFromReader(reader2).result shouldBe Right(
      Set(res1 -> acl1)
    )
    reader2.close()
  }

}
