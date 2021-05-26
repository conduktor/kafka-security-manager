package io.conduktor.ksm.source

import io.conduktor.ksm.parser.AclParserRegistry

import java.io.Reader
import com.typesafe.config.Config
import io.conduktor.ksm.parser.{AclParser, AclParserRegistry}

case class ParsingContext(aclParser: AclParser, reader: Reader)

abstract class SourceAcl(val parserRegistry: AclParserRegistry) {

  /**
    * Config Prefix for configuring this module
    */
  val CONFIG_PREFIX: String

  /**
    * internal config definition for the module
    */
  def configure(config: Config)

  /**
    * Refresh the current view on the external source of truth for Acl
    * Ideally this function is smart and does not pull the entire external Acl at every iteration
    * Return `None` if the Source Acls have not changed (usually using metadata).
    * Return `Some(reader)` if some text is being returned and should be parsed next
    * Note: the first call to this function should never return `None`.
    *
    * Kafka Security Manager will not update Acls in Kafka until there are no errors in the result
    * @return
    */
  def refresh(): Option[ParsingContext]

  /**
    * Close all the necessary underlying objects or connections belonging to this instance
    */
  def close()
}
