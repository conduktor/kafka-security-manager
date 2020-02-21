package com.github.simplesteph.ksm.source

import cats.Monoid
import kafka.security.auth.{Acl, Resource}

import scala.util.Try

/**
  * Case Class that wraps a complicated result
  *
  * @param acls Set of successfully parsed ACLs
  * @param errs List of errors that were caught during processing
  */
case class SourceAclResult(acls: Set[(Resource, Acl)],
                           errs: List[Try[Throwable]])

object SourceAclResult {

  implicit val md = new Monoid[SourceAclResult] {

    override def empty: SourceAclResult = SourceAclResult(Set[(Resource, Acl)](), List())

    override def combine(x: SourceAclResult, y: SourceAclResult): SourceAclResult =
      SourceAclResult(x.acls ++ y.acls, x.errs ++ y.errs)
  }
}
