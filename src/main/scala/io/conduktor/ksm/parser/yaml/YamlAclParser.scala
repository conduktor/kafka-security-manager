package io.conduktor.ksm.parser.yaml

import cats.data.Validated
import cats.data.Validated.Valid
import cats.implicits._
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.yaml
import io.conduktor.ksm.parser.AclParser
import io.conduktor.ksm.source.SourceAclResult
import kafka.security.auth._
import org.apache.kafka.common.resource.PatternType
import org.apache.kafka.common.security.auth.KafkaPrincipal

import java.io.Reader
import scala.collection.immutable._
import scala.util.Try

/**
  * Parser that assumes that all ACLs are flattened
  * and live under a YML format.
  */
class YamlAclParser() extends AclParser {

  override val name: String = "yaml"

  case class AclResources(
      groups: Option[Map[String, List[String]]],
      topics: Option[Map[String, List[String]]],
      transactional_ids: Option[Map[String, List[String]]],
      clusters: Option[Map[String, List[String]]]
  )

  case class AclYaml(users: Map[String, AclResources])

  def getResource(
      resourceName: String,
      resourceType: ResourceType
  ): Resource = {

    val trimmedResourceName = resourceName.trim

    if (trimmedResourceName.endsWith("*") && trimmedResourceName != "*")
      new Resource(
        resourceType,
        trimmedResourceName.dropRight(1),
        PatternType.PREFIXED
      )
    else new Resource(resourceType, trimmedResourceName, PatternType.LITERAL)
  }

  def parseResource(
      principal: KafkaPrincipal,
      resourceType: ResourceType,
      resources: Map[String, List[String]]
  ): SourceAclResult = {

    val parsed = resources.toList.flatMap({
      case (resourceName, resourceAcls) =>
        val resource = getResource(resourceName, resourceType)
        resourceAcls.map(acl => parseAcl(principal, resource, acl))
    })

    val errors: List[YamlParserException] =
      parsed.filter(_.isInvalid).map(_.toEither.left.get)
    if (errors.nonEmpty) {
      // return all the parsing exceptions
      SourceAclResult(Left(errors))
    } else {
      // return all the successfully parsed rows
      val acls = parsed.filter(_.isValid).flatMap(_.getOrElse(List())).toSet
      SourceAclResult(Right(acls))
    }
  }

  val clusterWildcard: Resource = Resource(Cluster, "*", PatternType.LITERAL)

  def parseAcl(
      principal: KafkaPrincipal,
      resource: Resource,
      acl: String
  ): Validated[YamlParserException, List[(Resource, Acl)]] = {
    acl.split(",") match {
      case Array(operation, permissionType, host) =>
        Try {
          List(
            (
              resource,
              Acl(
                principal,
                PermissionType.fromString(permissionType),
                host,
                Operation.fromString(operation)
              )
            )
          )
        }.toEither
          .leftMap(error =>
            new YamlParserException(
              s"Could not parse ACL '$acl' for principal '$principal' and resource '$resource'",
              error
            )
          )
          .toValidated
      case Array(abbrev) =>
        (resource.resourceType, abbrev.toLowerCase()) match {
          case (Topic, "consume") =>
            Valid(
              List(
                (resource, Acl(principal, Allow, "*", Describe)),
                (resource, Acl(principal, Allow, "*", Read))
              )
            )
          case (Topic, "produce") =>
            Valid(
              List(
                (resource, Acl(principal, Allow, "*", Describe)),
                (resource, Acl(principal, Allow, "*", Create)),
                (resource, Acl(principal, Allow, "*", Write)),
                (clusterWildcard, Acl(principal, Allow, "*", Create))
              )
            )
          case (_, opStr) =>
            Try {
              Operation.fromString(opStr)
            }.toEither
              .leftMap(error =>
                new YamlParserException(
                  s"Could not parse ACL '$acl' for principal '$principal' and resource '$resource'",
                  error
                )
              )
              .toValidated
              .map(op => List((resource, Acl(principal, Allow, "*", op))))
        }
      case _ => Left(new YamlParserException(s"Could not parse ACL '$acl' for principal '$principal' and resource '$resource', pattern not supported", null)).toValidated
    }
  }

  /**
    * Parses all the ACLs as provided by the reader that wraps the YAML content
    *
    * @param reader we use the reader interface to use string and files interchangeably in the parser
    * @return sourceAclResult
    */
  override def aclsFromReader(reader: Reader): SourceAclResult = {

    val result = yaml.parser
      .parse(reader)
      .leftMap(error => new YamlParserException(s"Failed to parse Yaml", error))
      .flatMap(_.as[AclYaml])
      .valueOr(throw _)

    result.users.toList
      .map({
        case (userName, aclResources) =>
          val principal = new KafkaPrincipal("User", userName)

          List(
            parseResource(
              principal,
              Topic,
              aclResources.topics.getOrElse(Map())
            ),
            parseResource(
              principal,
              Group,
              aclResources.groups.getOrElse(Map())
            ),
            parseResource(
              principal,
              TransactionalId,
              aclResources.transactional_ids.getOrElse(Map())
            ),
            parseResource(
              principal,
              Cluster,
              aclResources.clusters.getOrElse(Map())
            )
          ).combineAll
      })
      .combineAll
  }

  override def formatAcls(acls: List[(Resource, Acl)]): String = {
    val yamlMap = acls
      .groupBy({ case (_, acl) => acl.principal.getName })
      .mapValues(userData =>
        userData
          .groupBy({
            case (resource, _) =>
              resource.resourceType.toString.toLowerCase + "s"
          })
          .mapValues(resourceData =>
            resourceData
              .groupBy({
                case (Resource(_, name, PatternType.LITERAL), _)  => name
                case (Resource(_, name, PatternType.PREFIXED), _) => name + "*"
              })
              .mapValues(aclData =>
                aclData.map({
                  case (_, acl) =>
                    s"${acl.operation},${acl.permissionType},${acl.host}"
                })
              )
          )
      )
    yaml.printer.print(yamlMap.asJson)
  }

  override def matchesExtension(extension: String): Boolean = extension.equalsIgnoreCase("yml") || extension.equalsIgnoreCase("yaml")
}
