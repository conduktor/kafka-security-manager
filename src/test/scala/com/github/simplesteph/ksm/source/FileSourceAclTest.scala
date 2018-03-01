package com.github.simplesteph.ksm.source

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Paths }

import kafka.security.auth._
import org.apache.kafka.common.utils.SecurityUtils
import org.scalatest.{ FlatSpec, Matchers }

class FileSourceAclTest extends FlatSpec with Matchers {

  "fileSourceAcl Refresh" should "correctly parse a file" in {

    val file = File.createTempFile("ksm", "test")

    val content1 =
      """KafkaPrincipal,ResourceType,ResourceName,Operation,PermissionType,Host
        |User:alice,Topic,foo,Read,Allow,*
        |User:bob,Group,bar,Write,Deny,12.34.56.78
        |User:peter,Cluster,kafka-cluster,Create,Allow,*
        |""".stripMargin

    Files.write(Paths.get(file.toURI), content1.getBytes(StandardCharsets.UTF_8))

    val fileSourceAcl = new FileSourceAcl(file.getAbsolutePath)

    val acl1 = Acl(SecurityUtils.parseKafkaPrincipal("User:alice"), Allow, "*", Read)
    val acl2 = Acl(SecurityUtils.parseKafkaPrincipal("User:bob"), Deny, "12.34.56.78", Write)
    val acl3 = Acl(SecurityUtils.parseKafkaPrincipal("User:peter"), Allow, "*", Create)

    val res1 = Resource(Topic, "foo")
    val res2 = Resource(Group, "bar")
    val res3 = Resource(Cluster, "kafka-cluster")

    fileSourceAcl.refresh() shouldBe Some(SourceAclResult(Set(res1 -> acl1, res2 -> acl2, res3 -> acl3), List()))
  }

  "fileSourceAcl Refresh" should "correctly parse a file and then refresh after changes" in {

    val file = File.createTempFile("ksm", "test")

    val content1 =
      """KafkaPrincipal,ResourceType,ResourceName,Operation,PermissionType,Host
        |User:alice,Topic,foo,Read,Allow,*
        |User:bob,Group,bar,Write,Deny,12.34.56.78
        |User:peter,Cluster,kafka-cluster,Create,Allow,*
        |""".stripMargin

    Files.write(Paths.get(file.toURI), content1.getBytes(StandardCharsets.UTF_8))

    val fileSourceAcl = new FileSourceAcl(file.getAbsolutePath)

    val acl1 = Acl(SecurityUtils.parseKafkaPrincipal("User:alice"), Allow, "*", Read)
    val acl2 = Acl(SecurityUtils.parseKafkaPrincipal("User:bob"), Deny, "12.34.56.78", Write)
    val acl3 = Acl(SecurityUtils.parseKafkaPrincipal("User:peter"), Allow, "*", Create)

    val res1 = Resource(Topic, "foo")
    val res2 = Resource(Group, "bar")
    val res3 = Resource(Cluster, "kafka-cluster")

    fileSourceAcl.refresh() shouldBe Some(SourceAclResult(Set(res1 -> acl1, res2 -> acl2, res3 -> acl3), List()))

    val content2 =
      """KafkaPrincipal,ResourceType,ResourceName,Operation,PermissionType,Host
        |User:alice,Topic,foo,Read,Allow,*
        |""".stripMargin

    Files.write(Paths.get(file.toURI), content2.getBytes(StandardCharsets.UTF_8))
    // we force the modification of the time of the file so that the test passes
    file.setLastModified(System.currentTimeMillis() + 10000)

    fileSourceAcl.refresh() shouldBe Some(SourceAclResult(Set(res1 -> acl1), List()))

  }

}
