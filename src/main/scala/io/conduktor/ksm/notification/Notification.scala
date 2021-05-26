package io.conduktor.ksm.notification

import com.typesafe.config.Config
import kafka.security.auth.{Acl, Resource}

import scala.util.Try

trait Notification {

  /**
    * Config Prefix for configuring this module
    */
  val CONFIG_PREFIX: String

  /**
    * internal config definition for the module
    */
  def configure(config: Config)

  /**
    * Function to be called by external accessors, but should not be implemented by subclasses
    * @param added ACLs that have been added
    * @param removed ACLs that have been removed
    */
  def notifySuccess(
      added: Set[(Resource, Acl)],
      removed: Set[(Resource, Acl)]
  ): Unit = {
    notifyOne("ADDED", added)
    notifyOne("REMOVED", removed)
  }

  /**
    *
    * @param action ADDED or REMOVED
    * @param acls List of corresponding ACLs
    */
  protected def notifyOne(action: String, acls: Set[(Resource, Acl)]): Unit

  /**
    * Notification logic in case of errors
    * @param errs list of errors
    */
  def notifyErrors(errs: List[Try[Throwable]]): Unit

  /**
    * Closing any outstanding objects owned by this notification
    */
  def close(): Unit

}

object Notification {

  // helper to pretty print a long ACL, as the toString provided by Kafka is not suitable here
  def printAcl(acl: Acl, resource: Resource): String = {
    s"${acl.principal}, $resource, Operation: ${acl.operation}, Permission: ${acl.permissionType}, Host: ${acl.host}"
  }
}
