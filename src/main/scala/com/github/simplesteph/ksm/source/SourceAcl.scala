package com.github.simplesteph.ksm.source

import kafka.security.auth.{Acl, Resource}

import scala.util.Try

trait SourceAcl {

  def refreshSourceAcl(): Option[SourceAclResult]

  def close()
}
