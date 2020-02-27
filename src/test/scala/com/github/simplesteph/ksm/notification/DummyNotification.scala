package com.github.simplesteph.ksm.notification
import com.typesafe.config.Config
import kafka.security.auth.{Acl, Resource}

import scala.collection.mutable
import scala.util.Try

class DummyNotification extends Notification {

  /**
    * Config Prefix for configuring this module
    */
  override val CONFIG_PREFIX: String = "dummy"

  /**
    * internal config definition for the module
    */
  override def configure(config: Config): Unit = {}

  val addedAcls: mutable.Set[(Resource, Acl)] =
    mutable.Set.empty[(Resource, Acl)]
  val removedAcls: mutable.Set[(Resource, Acl)] =
    mutable.Set.empty[(Resource, Acl)]
  var errorCounter: Int = 0

  override protected def notifyOne(
      action: String,
      acls: Set[(Resource, Acl)]
  ): Unit = {
    action match {
      case "ADDED"   => acls.foreach(addedAcls.add)
      case "REMOVED" => acls.foreach(removedAcls.add)
      case _         => throw new RuntimeException(s"Not expected: $action")
    }
  }

  def reset(): Unit = {
    addedAcls.clear()
    removedAcls.clear()
    errorCounter = 0
  }

  /**
    * Notification logic in case of errors
    *
    * @param errs list of errors
    */
  override def notifyErrors(errs: List[Try[Throwable]]): Unit = {
    errorCounter += 1
  }

  /**
    * Closing any outstanding objects owned by this notification
    */
  override def close(): Unit = {}
}
