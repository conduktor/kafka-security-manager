package com.github.simplesteph.ksm.source

import kafka.security.auth.{ Acl, Resource }

import scala.util.Try

/**
 * Case Class that wraps a complicated result
 * @param acls Set of successfully parsed ACLs
 * @param errs List of errors that were caught during processing
 */
case class SourceAclResult(acls: Set[(Resource, Acl)], errs: List[Try[Throwable]])