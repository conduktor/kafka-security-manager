package com.github.simplesteph.ksm.source

import com.typesafe.config.Config
import kafka.security.auth._
import org.apache.kafka.common.utils.SecurityUtils

object DummySourceAcl {

  val acl1 = Acl(SecurityUtils.parseKafkaPrincipal("User:alice"), Allow, "*", Read)
  val acl2 = Acl(SecurityUtils.parseKafkaPrincipal("User:bob"), Deny, "12.34.56.78", Write)
  val acl3 = Acl(SecurityUtils.parseKafkaPrincipal("User:peter"), Allow, "*", Create)

  val res1 = Resource(Topic, "foo")
  val res2 = Resource(Group, "bar")
  val res3 = Resource(Cluster, "kafka-cluster")

}

class DummySourceAcl extends SourceAcl {

  import DummySourceAcl._

  var noneNext = false

  // initial state
  val sar1 = SourceAclResult(Set(
    res1 -> acl1,
    res1 -> acl2,
    res2 -> acl3,
  ), List())

  // one deletion, one add
  val sar2 = SourceAclResult(Set(
    res1 -> acl1,
    res2 -> acl3,
    res3 -> acl2
  ), List())

  // all gone
  val sar3 = SourceAclResult(Set(), List())

  // all state changes
  val sars: Iterator[SourceAclResult] = List(sar1, sar2, sar3).iterator

  override def refresh(): Option[SourceAclResult] = {
    if(noneNext){
      noneNext = false
      None
    } else Some(sars.next())
  }

  def setNoneNext(): Unit ={
    noneNext = true
  }

  override def close(): Unit = ()

  /**
    * Config Prefix for configuring this module
    */
  override val CONFIG_PREFIX: String = "dummy"

  /**
    * internal config definition for the module
    */
  override def configure(config: Config): Unit = ()
}
