package com.github.simplesteph.ksm.notification
import com.typesafe.config.Config
import kafka.security.auth.{Acl, Resource}
import org.slf4j.{Logger, LoggerFactory}

import scala.util.Try

case class ConsoleNotification() extends Notification {

  val log: Logger =
    LoggerFactory.getLogger(classOf[ConsoleNotification].getSimpleName)

  /**
    * Config Prefix for configuring this module
    */
  override val CONFIG_PREFIX: String = "console"

  /**
    * internal config definition for the module
    */
  override def configure(config: Config): Unit = ()

  override def notifyErrors(errs: List[Try[Throwable]]): Unit = {
    NotificationUtils.errorsToString(errs).foreach(println)
  }

  override protected def notifyOne(action: String,
                                   acls: Set[(Resource, Acl)]): Unit = {
    if (acls.nonEmpty) {
      acls.foreach {
        case (resource, acl) =>
          val message = Notification.printAcl(acl, resource)
          log.info(s"$action $message")
      }
    }
  }

  override def close(): Unit = ()

}
