package com.github.simplesteph.ksm

import com.github.simplesteph.ksm.notification.{ ConsoleNotification, Notification }
import com.github.simplesteph.ksm.source.{ FileSourceAcl, SourceAcl, SourceAclResult }
import kafka.security.auth.{ Acl, Resource, SimpleAclAuthorizer }
import org.slf4j.{ Logger, LoggerFactory }

import scala.util.Try

// kafka-acls  --topic test --producer --authorizer-properties zookeeper.connect=localhost:2181 --add --allow-principal User:alice

object AclSynchronizer {

  private val log: Logger = LoggerFactory.getLogger(AclSynchronizer.getClass.getSimpleName)

  // transform Kafka ACLs to make them more agreeable to deal with
  def flattenKafkaAcls(kafkaGroupedAcls: Map[Resource, Set[Acl]]): Set[(Resource, Acl)] = {
    kafkaGroupedAcls.toSeq
      .flatMap {
        case (resource: Resource, acls: Set[Acl]) =>
          acls.toSeq.map(acl => (resource, acl))
      }.toSet
  }

  // group the ACL by resource
  def regroupAcls(flattenedAcls: Set[(Resource, Acl)]): Map[Resource, Set[Acl]] = {
    flattenedAcls.groupBy { case (r: Resource, _: Acl) => r }
      .mapValues(_.map((y: (Resource, Acl)) => y._2))
  }

  // apply changes to Zookeeper / Kafka security and store the results in Notification object
  def applySourceAcls(
    sourceAcls: Set[(Resource, Acl)],
    kafkaAcls: Set[(Resource, Acl)],
    notification: Notification,
    simpleAclAuthorizer: SimpleAclAuthorizer): Unit = {
    if (sourceAcls == kafkaAcls) {
      log.info("No ACL changes")
    } else {
      val added = sourceAcls -- kafkaAcls
      val removed = kafkaAcls -- sourceAcls

      regroupAcls(added).foreach { case (resource, acls) => simpleAclAuthorizer.addAcls(acls, resource) }
      regroupAcls(removed).foreach { case (resource, acls) => simpleAclAuthorizer.removeAcls(acls, resource) }

      notification.notifySuccess(added, removed)
    }
  }
}

class AclSynchronizer(
  simpleAclAuthorizer: SimpleAclAuthorizer,
  sourceAcl: SourceAcl,
  notification: Notification) extends Runnable {

  import AclSynchronizer._

  private var sourceAclsCache: SourceAclResult = _

  override def run(): Unit = {

    // flatten the Kafka ACL
    val kafkaAcls: Set[(Resource, Acl)] = flattenKafkaAcls(simpleAclAuthorizer.getAcls())

    // parse the source of the ACL
    sourceAcl.refresh() match {
      // the source has not changed
      case None =>
        if (sourceAclsCache.errs.isEmpty) {
          // the Kafka Acls may have changed so we check against the last known correct SourceAcl that we cached
          applySourceAcls(
            sourceAclsCache.acls,
            kafkaAcls,
            notification,
            simpleAclAuthorizer)
        }
      // the source has changed
      case Some(SourceAclResult(acls, errs)) =>
        // we have a new result, so we cache it
        sourceAclsCache = SourceAclResult(acls, errs)
        // normal execution
        if (errs.isEmpty) {
          applySourceAcls(
            sourceAclsCache.acls,
            kafkaAcls,
            notification,
            simpleAclAuthorizer)
        } else {
          log.error("Exceptions while parsing ACL source:")
          notification.notifyErrors(errs)
        }
    }

  }

  def close(): Unit = {
    simpleAclAuthorizer.close()
    sourceAcl.close()
    notification.close()
  }
}
