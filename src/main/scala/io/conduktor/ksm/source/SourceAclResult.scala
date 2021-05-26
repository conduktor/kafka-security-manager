package io.conduktor.ksm.source

import cats.kernel.Monoid
import io.conduktor.ksm.source.SourceAclResult.{ParsingExceptions, ksmAcls}
import io.conduktor.ksm.parser.ParserException
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
      (x.result, y.result) match {
        case (Left(errX), Left(errY)) => SourceAclResult(Left(errX ++ errY))
        case (Right(resX), Right(resY)) => SourceAclResult(Right(resX ++ resY))
        case (Left(errX), Right(_)) => SourceAclResult(Left(errX))
        case (Right(_), Left(errY)) => SourceAclResult(Left(errY))
      }
    }
  }
}

/**
  * Case Class that wraps a complicated result
  * @param result Set of successfully parsed ACLs, or exceptions
  */
case class SourceAclResult(result: Either[ParsingExceptions, ksmAcls])
