package com.github.simplesteph.ksm

import com.github.simplesteph.ksm.notification.Notification
import com.github.simplesteph.ksm.parser.AclParser
import com.github.simplesteph.ksm.source.{SourceAcl, SourceAclResult}
import kafka.security.auth.{Acl, Authorizer, Resource}
import org.slf4j.{Logger, LoggerFactory}

import scala.util.{Failure, Success, Try}

object AclSynchronizer {

  private val log: Logger =
    LoggerFactory.getLogger(classOf[AclSynchronizer].getSimpleName)

  // transform Kafka ACLs to make them more agreeable to deal with
  def flattenKafkaAcls(
      kafkaGroupedAcls: Map[Resource, Set[Acl]]): Set[(Resource, Acl)] = {
    kafkaGroupedAcls.keySet.flatMap(resource =>
      kafkaGroupedAcls(resource).map((resource, _)))
  }

  // group the ACL by resource
  def regroupAcls(
      flattenedAcls: Set[(Resource, Acl)]): Map[Resource, Set[Acl]] = {
    flattenedAcls
      .groupBy { case (r: Resource, _: Acl) => r }
      .mapValues(_.map((y: (Resource, Acl)) => y._2))
  }

  // apply changes to Zookeeper / Kafka security and store the results in Notification object
  def applySourceAcls(sourceAcls: Set[(Resource, Acl)],
                      kafkaAcls: Set[(Resource, Acl)],
                      notification: Notification,
                      authZ: Authorizer): Unit = {
    if (sourceAcls == kafkaAcls) {
      log.info("No ACL changes")
    } else {
      val added = sourceAcls -- kafkaAcls
      val removed = kafkaAcls -- sourceAcls

      regroupAcls(added).foreach {
        case (resource, acls) => authZ.addAcls(acls, resource)
      }
      regroupAcls(removed).foreach {
        case (resource, acls) => authZ.removeAcls(acls, resource)
      }

      notification.notifySuccess(added, removed)
    }
  }
}

class AclSynchronizer(authorizer: Authorizer,
                      sourceAcl: SourceAcl,
                      notification: Notification,
                      aclParser: AclParser,
                      readOnly: Boolean = false) extends Runnable {

  import AclSynchronizer._

  private var sourceAclsCache: Set[(Resource, Acl)] = _

  if (readOnly) {
    log.warn("""
        |=======================================================
        |==========   READ-ONLY mode is activated      =========
        |==========   To disable: KSM_READONLY=false   =========
        |=======================================================
      """.stripMargin)
  }

  def run(): Unit = if (!readOnly) {
    log.debug("Refreshing ACLs...")
    // parse the source of the ACL
    Try(sourceAcl.refresh(aclParser)) match {
      case Success(result) =>
        result match {
          // the source has not changed
          case None =>
            if (sourceAclsCache != null) {
              // the Kafka Acls may have changed so we check against the last known correct SourceAcl that we cached
              applySourceAcls(sourceAclsCache,
                              getKafkaAcls,
                              notification,
                              authorizer)
            }
          // the source has changed
          case Some(SourceAclResult(acls, errs)) =>
            // normal execution
            if (errs.isEmpty) {
              // we have a new result, so we cache it
              sourceAclsCache = acls
              applySourceAcls(sourceAclsCache,
                              getKafkaAcls,
                              notification,
                              authorizer)
            } else {
              try {
                log.error("Exceptions while refreshing ACL source:")
                notification.notifyErrors(errs)
              } catch {
                case _: Throwable => log.warn("Notifications module threw an exception, ignoring...")
              }
            }
        }
      case Failure(e) =>
        log.error("Refreshing the source failed", e)
    }
  }

  def getKafkaAcls: Set[(Resource, Acl)] =
    flattenKafkaAcls(authorizer.getAcls())

  def close(): Unit = {
    authorizer.close()
    sourceAcl.close()
    notification.close()
  }
}
