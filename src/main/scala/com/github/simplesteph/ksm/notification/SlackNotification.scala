package com.github.simplesteph.ksm.notification
import kafka.security.auth.{Acl, Resource}

import scala.util.Try

class SlackNotification extends Notification {
  override def notifyOne(action: String, acls: Set[(Resource, Acl)]): Unit = ???

  override def notifyErrors(errs: List[Try[Throwable]]): Unit = ???

  override def close(): Unit = ???

}
