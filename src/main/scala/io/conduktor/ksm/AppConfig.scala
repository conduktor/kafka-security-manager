package io.conduktor.ksm

import com.typesafe.config.Config
import io.conduktor.ksm.notification.Notification
import io.conduktor.ksm.parser.AclParserRegistry
import io.conduktor.ksm.source.SourceAcl
import kafka.security.auth.Authorizer
import kafka.utils.CoreUtils

import scala.collection.JavaConverters._

class AppConfig(config: Config) {

  object Authorizer {
    private val authorizerClass = config.getString("authorizer.class")
    val authorizer: Authorizer =
      CoreUtils.createObject[Authorizer](authorizerClass)

    private val authorizerConfig = config.getConfig(
      if (authorizer.isInstanceOf[compat.AdminClientAuthorizer]) {
        "authorizer.admin-client-config"
      } else {
        "authorizer.config"
      }
    )
    private val configMap = authorizerConfig.root().unwrapped().asScala.map {
      case (s, a) => (s, a.toString)
    }
    authorizer.configure(configMap.asJava)
  }

  object Source {
    private val sourceAclClass = config.getString("source.class")
    def createSource(parserRegistry: AclParserRegistry):SourceAcl = {
      val sourceAcl: SourceAcl = CoreUtils.createObject[SourceAcl](sourceAclClass, parserRegistry)

      // here we get a dynamic config prefix given by the class.
      // this will allow multiple classes to co-exist in the same config and avoid collisions
      val sourceAclConfig =
      config.getConfig(s"source.${sourceAcl.CONFIG_PREFIX}")
      sourceAcl.configure(sourceAclConfig)
      sourceAcl
    }
  }

  object Notification {
    private val notificationClass = config.getString("notification.class")
    val notification: Notification =
      CoreUtils.createObject[Notification](notificationClass)

    // here we get a dynamic config prefix given by the class.
    // this will allow multiple classes to co-exist in the same config and avoid collisions
    private val notificationConfig =
      config.getConfig(s"notification.${notification.CONFIG_PREFIX}")
    notification.configure(notificationConfig)
  }

  object KSM {
    private val ksmConfig = config.getConfig("ksm")
    val refreshFrequencyMs: Int = ksmConfig.getInt("refresh.frequency.ms")
    val numFailedRefreshesBeforeNotification: Int = ksmConfig.getInt("num.failed.refreshes.before.notification")
    val extract: Boolean = ksmConfig.getBoolean("extract.enable")
    val extractFormat: String = ksmConfig.getString("extract.format")
    val readOnly: Boolean = ksmConfig.getBoolean("readonly")
  }

  object Server {
    private val serverConfig = config.getConfig("server")
    val port: Int = serverConfig.getInt("port")
  }

  object Parser {
    private val aclParserConfig = config.getConfig("parser")
    val csvDelimiter: Char =
      aclParserConfig.getString("csv.delimiter").charAt(0)
  }

  object Feature {
    private val featureConfig = config.getConfig("feature")
  }

}
