package com.github.simplesteph.ksm.source

import kafka.security.auth.{Acl, Resource}

import scala.util.Try

case class SourceAclResult(acls: Set[(Resource, Acl)], errs: List[Try[Throwable]])