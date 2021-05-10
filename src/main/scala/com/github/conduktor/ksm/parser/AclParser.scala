package com.github.conduktor.ksm.parser

import java.io.Reader

import com.github.conduktor.ksm.source.SourceAclResult
import kafka.security.auth.{Acl, Resource}

trait AclParser {

  val name: String

  def aclsFromReader(reader: Reader): SourceAclResult

  def formatAcls(acls: List[(Resource, Acl)]): String

  def matchesExtension(extension: String): Boolean

}
