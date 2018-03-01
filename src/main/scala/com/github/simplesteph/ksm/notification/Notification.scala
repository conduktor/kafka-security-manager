package com.github.simplesteph.ksm.notification

import kafka.security.auth.{Acl, Resource}

import scala.util.Try

trait Notification {

  def notifySuccess(added: Set[(Resource, Acl)], removed: Set[(Resource, Acl)]): Unit = {
    notifyOne("ADDED", added)
    notifyOne("REMOVED", removed)
  }

  protected def notifyOne (action: String, acls: Set[(Resource, Acl)]): Unit

  def notifyErrors(errs: List[Try[Throwable]]): Unit

  def close(): Unit

}

object Notification {
  def printAcl(acl: Acl, resource: Resource): String = {
    s"${acl.principal}, $resource, ${acl.operation}, ${acl.permissionType}, ${acl.host}"
  }
}
