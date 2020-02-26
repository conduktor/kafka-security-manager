package com.github.simplesteph.ksm.source

import com.github.simplesteph.ksm.parser.CsvParserException
import com.github.simplesteph.ksm.source.SourceAclResult.{ksmAcls, ParsingExceptions}
import kafka.security.auth.{Acl, Resource}

object SourceAclResult{
  type ksmAcls = Set[(Resource, Acl)]
  type ParsingExceptions = List[CsvParserException]
}

/**
  * Case Class that wraps a complicated result
  * @param result Set of successfully parsed ACLs, or exceptions
  */

case class SourceAclResult(result: Either[ParsingExceptions, ksmAcls])
