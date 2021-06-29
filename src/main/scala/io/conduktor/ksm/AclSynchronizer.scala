package io.conduktor.ksm

import cats.implicits._
import io.conduktor.ksm.notification.Notification
import io.conduktor.ksm.source.{ParsingContext, SourceAcl, SourceAclResult}
import kafka.security.auth.{Acl, Authorizer, Resource}
import org.slf4j.{Logger, LoggerFactory}

import scala.util.{Failure, Success, Try}

object AclSynchronizer {

  private val log: Logger =
    LoggerFactory.getLogger(classOf[AclSynchronizer].getSimpleName)

  // transform Kafka ACLs to make them more agreeable to deal with
  def flattenKafkaAcls(
      kafkaGroupedAcls: Map[Resource, Set[Acl]]
  ): Set[(Resource, Acl)] = {
    kafkaGroupedAcls.keySet.flatMap(resource =>
      kafkaGroupedAcls(resource).map((resource, _))
    )
  }

  // group the ACL by resource
  def regroupAcls(
      flattenedAcls: Set[(Resource, Acl)]
  ): Map[Resource, Set[Acl]] = {
    flattenedAcls
      .groupBy { case (r: Resource, _: Acl) => r }
      .mapValues(_.map((y: (Resource, Acl)) => y._2))
  }

  // apply changes to Zookeeper / Kafka security and store the results in Notification object
  def applySourceAcls(
      sourceAcls: Set[(Resource, Acl)],
      kafkaAcls: Set[(Resource, Acl)],
      notification: Notification,
      authZ: Authorizer
  ): Unit = {
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

class AclSynchronizer(
    authorizer: Authorizer,
    sourceAcl: SourceAcl,
    notification: Notification,
    numFailedRefreshesBeforeNotification: Int,
    readOnly: Boolean = false
) extends Runnable {

  import AclSynchronizer._

  private var sourceAclsCache: Map[String, Set[(Resource, Acl)]] = Map()
  private var failedRefreshes: Int = 0

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
    Try(sourceAcl.refresh()) match {
      case Success(result) =>
        failedRefreshes = 0
        result
          .map { context: ParsingContext =>
            context match {
              // in case there is an update
              case ParsingContext(resourceKey, aclParser, reader, true) =>
                val sourceAclResult = aclParser.aclsFromReader(reader)
                reader.close()
                // add to the cache if successful
                sourceAclResult.result match {
                  case Right(kafkaAcls) =>
                    sourceAclsCache += (resourceKey -> kafkaAcls)
                }
                sourceAclResult
              case ParsingContext(resourceKey, _, _, false) =>
                // no update necessary, fetch from cache
                SourceAclResult(
                  Right(
                    sourceAclsCache
                      .getOrElse(
                        resourceKey,
                        // this should never happen, sources should set shouldUpdate to true the first time
                        throw new RuntimeException(
                          s"The resource '$resourceKey' does not exist in the cache."
                        )
                      )
                  )
                )
            }
          }
          .combineAll
          .result match {
          // the source has changed
          case Right(ksmAcls) =>
            // we have a new result, so we cache it
            applySourceAcls(
              ksmAcls,
              getKafkaAcls,
              notification,
              authorizer
            )
          case Left(parsingExceptions: List[Exception]) =>
            // parsing exceptions we want to notify
            log.error(
              "Exceptions while refreshing ACL source:",
              parsingExceptions.map(e => e.toString).mkString("\n")
            )
            // ugly but for now this will do
            notification.notifyErrors(
              parsingExceptions.map(e => Failure(e))
            )
        }
      case Failure(e) =>
        // errors such as HTTP exceptions when refreshing
        failedRefreshes += 1
        try {
          log.error("Exceptions while refreshing ACL source:", e)
          if (failedRefreshes >= numFailedRefreshesBeforeNotification) {
            notification.notifyErrors(List(Try(e)))
            failedRefreshes = 0
          }
        } catch {
          case _: Throwable =>
            log.warn("Notifications module threw an exception, ignoring...")
        }
        log.error("Refreshing the source threw an unexpected exception", e)
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
