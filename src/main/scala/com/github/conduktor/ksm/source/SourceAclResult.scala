package com.github.conduktor.ksm.source

import SourceAclResult.{ParsingExceptions, ksmAcls}
import com.github.conduktor.ksm.parser.CsvParserException
import kafka.security.auth.{Acl, Resource}

object SourceAclResult {
  type ksmAcls = Set[(Resource, Acl)]
  type ParsingExceptions = List[CsvParserException]
}

/**
  * Case Class that wraps a complicated result
  * @param result Set of successfully parsed ACLs, or exceptions
  */
case class SourceAclResult(result: Either[ParsingExceptions, ksmAcls])
