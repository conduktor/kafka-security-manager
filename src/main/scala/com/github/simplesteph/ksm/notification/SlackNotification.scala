package com.github.simplesteph.ksm.notification
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.simplesteph.ksm.parser.CsvParserException
import com.typesafe.config.Config
import kafka.security.auth.{ Acl, Resource }
import org.slf4j.LoggerFactory
import skinny.http.HTTP

import scala.util.Try

class SlackNotification extends Notification {
  /**
   * Config Prefix for configuring this module
   */
  override val CONFIG_PREFIX: String = "slack"

  private val log = LoggerFactory.getLogger(classOf[SlackNotification])

  final val WEBHOOK_CONFIG = "webhook"
  final val USERNAME_CONFIG = "username"
  final val ICON_CONFIG = "icon"
  final val CHANNEL_CONFIG = "channel"

  val objectMapper: ObjectMapper = new ObjectMapper()
  var webhook: String = _
  var username: String = _
  var icon: String = _
  var channel: String = _

  /**
   * internal config definition for the module
   */
  override def configure(config: Config): Unit = {
    webhook = config.getString(WEBHOOK_CONFIG)
    username = config.getString(USERNAME_CONFIG)
    icon = config.getString(ICON_CONFIG)
    channel = config.getString(CHANNEL_CONFIG)
  }

  override def notifyOne(action: String, acls: Set[(Resource, Acl)]): Unit = {
    if (acls.nonEmpty) {
      val messages = acls.map {
        case (resource, acl) =>
          val message = Notification.printAcl(acl, resource)
          s"$action $message"
      }.toList

      sendToSlack(messages)
    }
  }

  def sendToSlack(messages: List[String], retries: Int = 5): Unit = {
    if (retries > 0) {
      messages.grouped(50).foreach(msgChunks => {
        val text =
          s"""```
             |${msgChunks.mkString("\n")}
             |```
           """.stripMargin

        val payload = objectMapper.createObjectNode()
          .put("text", text)
          .put("username", username)
          .put("icon_url", icon)
          .put("channel", channel)

        val response = HTTP.post(webhook, payload.toString)

        response.status match {
          case 200 => ()
          case _ =>
            log.warn(response.asString)
            if (retries > 1) log.warn("Retrying...")
            Thread.sleep(300)
            sendToSlack(msgChunks, retries - 1)
        }
      })
    } else {
      log.error("Can't send notification to Slack after retries")
    }
  }

  override def notifyErrors(errs: List[Try[Throwable]]): Unit = {
    sendToSlack(NotificationUtils.errorsToString(errs))
  }

  override def close(): Unit = {

  }
}
