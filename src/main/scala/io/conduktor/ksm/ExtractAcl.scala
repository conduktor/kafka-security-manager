package io.conduktor.ksm

import io.conduktor.ksm.parser.AclParser
import kafka.security.auth.{Acl, Authorizer, Resource}
import org.slf4j.{Logger, LoggerFactory}

class ExtractAcl(authorizer: Authorizer, aclParser: AclParser) {

  val log: Logger = LoggerFactory.getLogger(classOf[ExtractAcl].getSimpleName)

  def extract(): Unit = {
    log.info("Running ACL Extraction mode")
    log.info("Getting ACLs from Kafka")
    val kafkaAcls = authorizer.getAcls()
    log.info("Closing Authorizer")
    authorizer.close()
    val acls: Set[(Resource, Acl)] = AclSynchronizer.flattenKafkaAcls(kafkaAcls)
    val extracted = aclParser.formatAcls(acls.toList)
    log.info("================ CURRENT ACLS ===============")
    println(extracted)
  }

}
