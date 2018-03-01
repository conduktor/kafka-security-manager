package com.github.simplesteph.ksm.source

import kafka.security.auth.{ Acl, Resource }

import scala.util.Try

trait SourceAcl {

  def refresh(): Option[SourceAclResult]

  def close()
}
