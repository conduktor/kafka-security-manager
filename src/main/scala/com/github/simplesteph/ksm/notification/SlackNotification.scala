package com.github.simplesteph.ksm.notification
import com.typesafe.config.Config
import kafka.security.auth.{ Acl, Resource }

import scala.util.Try

class SlackNotification extends Notification {
  /**
   * Config Prefix for configuring this module
   */
  override val CONFIG_PREFIX: String = "slack"

  /**
   * internal config definition for the module
   */
  override def configure(config: Config): Unit = ???

  override def notifyOne(action: String, acls: Set[(Resource, Acl)]): Unit = ???

  override def notifyErrors(errs: List[Try[Throwable]]): Unit = ???

  override def close(): Unit = ???
}
