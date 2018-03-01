package com.github.simplesteph.ksm.parser

import java.io.Reader

import com.github.simplesteph.ksm.source.SourceAclResult
import com.github.tototoshi.csv.{ CSVFormat, CSVReader, QUOTE_MINIMAL, Quoting }
import kafka.security.auth._
import org.apache.kafka.common.utils.SecurityUtils

import scala.collection.immutable
import scala.util.{ Failure, Try }

object CsvParser {

  final val KAFKA_PRINCIPAL_COL = "KafkaPrincipal"
  final val RESOURCE_TYPE_COL = "ResourceType"
  final val RESOURCE_NAME_COL = "ResourceName"
  final val OPERATION_COL = "Operation"
  final val PERMISSION_TYPE_COL = "PermissionType"
  final val HOST_COL = "Host"

  final val EXPECTED_COLS = Set(
    KAFKA_PRINCIPAL_COL,
    RESOURCE_TYPE_COL, RESOURCE_NAME_COL, OPERATION_COL,
    PERMISSION_TYPE_COL, HOST_COL)

  implicit val csvFormat: CSVFormat = new CSVFormat {
    val delimiter: Char = ','
    val quoteChar: Char = '"'
    val escapeChar: Char = '"'
    val lineTerminator: String = "\r\n"
    val quoting: Quoting = QUOTE_MINIMAL
    val treatEmptyLineAsNil: Boolean = true
  }

  def parseRow(row: Map[String, String]): (Resource, Acl) = {
    val kafkaPrincipal = SecurityUtils.parseKafkaPrincipal(row(KAFKA_PRINCIPAL_COL))
    val resourceType = ResourceType.fromString(row(RESOURCE_TYPE_COL))
    val resourceName = row(RESOURCE_NAME_COL)
    val operation = Operation.fromString(row(OPERATION_COL))
    val permissionType = PermissionType.fromString(row(PERMISSION_TYPE_COL))
    val host = row(HOST_COL)

    val resource = Resource(resourceType, resourceName)
    val acl = Acl(kafkaPrincipal, permissionType, host, operation)

    (resource, acl)
  }

  def aclsFromCsv(reader: Reader): SourceAclResult = {
    val csv = CSVReader.open(reader).allWithHeaders().filter(_.nonEmpty)

    // parse the CSV
    val parsed: immutable.List[Try[(Resource, Acl)]] = csv.map(row => Try {
      parseRow(row)
    }.recoverWith[(Resource, Acl)] { case (t: Throwable) => Try(throw new CsvParserException(row, t)) })

    val acls = parsed.filter(_.isSuccess).map(_.get).toSet
    val errors = parsed.filter(_.isFailure).map(_.failed)
    SourceAclResult(acls, errors)
  }

}
