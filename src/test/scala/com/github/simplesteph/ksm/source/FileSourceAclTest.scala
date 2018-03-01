package com.github.simplesteph.ksm.source

import java.io.File

import kafka.security.auth._
import org.apache.kafka.common.utils.SecurityUtils
import org.scalatest.{ FlatSpec, Matchers }

class FileSourceAclTest extends FlatSpec with Matchers {

  "fileSourceAcl Refresh" should "correctly parse a file" in {
    //    val file = File.createTempFile("ksm", "test")
    val file = new File("src/test/resources/test-acls.csv")
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
    // TODO:
    //    val file = File.createTempFile("ksm", "test")
    //    val file = new File("src/test/resources/test-acls.csv")
    //    val fileSourceAcl = new FileSourceAcl(file.getAbsolutePath)
    //
    //    val acl1 = Acl(SecurityUtils.parseKafkaPrincipal("User:alice"), Allow, "*", Read)
    //    val acl2 = Acl(SecurityUtils.parseKafkaPrincipal("User:bob"), Deny, "12.34.56.78", Write)
    //    val acl3 = Acl(SecurityUtils.parseKafkaPrincipal("User:peter"), Allow, "*", Create)
    //
    //    val res1 = Resource(Topic, "foo")
    //    val res2 = Resource(Group, "bar")
    //    val res3 = Resource(Cluster, "kafka-cluster")
    //
    //    fileSourceAcl.refresh() shouldBe Some(SourceAclResult(Set(res1 -> acl1, res2 -> acl2, res3 -> acl3), List()))
  }

}
