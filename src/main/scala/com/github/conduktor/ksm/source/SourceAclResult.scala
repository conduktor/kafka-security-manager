package com.github.conduktor.ksm.source

import cats.kernel.Monoid
import com.github.conduktor.ksm.parser.ParserException
import com.github.conduktor.ksm.source.SourceAclResult.{
  ParsingExceptions,
  ksmAcls
}
import kafka.security.auth.{Acl, Resource}

object SourceAclResult {
  type ksmAcls = Set[(Resource, Acl)]
  type ParsingExceptions = List[ParserException]

  implicit val monoid: Monoid[SourceAclResult] = new Monoid[SourceAclResult] {
    override def empty: SourceAclResult = SourceAclResult(Right(Set()))
    override def combine(
        x: SourceAclResult,
        y: SourceAclResult
    ): SourceAclResult = {
      if (x.result.isLeft || y.result.isLeft) {
        SourceAclResult(Left(x.result.left.get ++ y.result.left.get))
      } else {
        SourceAclResult(Right(x.result.right.get ++ y.result.right.get))
      }
    }
  }
}

/**
  * Case Class that wraps a complicated result
  * @param result Set of successfully parsed ACLs, or exceptions
  */
case class SourceAclResult(result: Either[ParsingExceptions, ksmAcls])
