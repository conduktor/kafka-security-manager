package com.github.simplesteph.ksm.source

import com.github.simplesteph.ksm.TestFixtures._
import com.github.simplesteph.ksm.parser.AclParser
import com.typesafe.config.Config

import scala.util.Try

class DummySourceAcl extends SourceAcl {


  var noneNext = false
  var errorNext = false

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

  override def refresh(aclParser: AclParser): Option[SourceAclResult] = {
    if(noneNext){
      noneNext = false
      None
    } else if (errorNext) {
      errorNext = false
      Some(SourceAclResult(Set((res1, acl1)),
        List[Try[Throwable]](Try(new RuntimeException("triggered error")))))
    } else Some(sars.next())
  }

  def setNoneNext(): Unit ={
    noneNext = true
  }

  def setErrorNext(): Unit = {
    errorNext = true
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
