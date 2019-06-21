package com.github.simplesteph.ksm

import com.github.simplesteph.ksm.notification.Notification
import com.github.simplesteph.ksm.source.SourceAcl
import com.typesafe.config.Config
import kafka.security.auth.Authorizer
import kafka.utils.CoreUtils

import scala.collection.JavaConverters._
import scala.util.Try

class AppConfig(config: Config) {

  object Authorizer {
    private val authorizerClass = config.getString("authorizer.class")
    val authorizer: Authorizer =
      CoreUtils.createObject[Authorizer](authorizerClass)

    private val authorizerConfig = config.getConfig(
      if (authorizer.isInstanceOf[compat.AdminClientAuthorizer]){
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
    val sourceAcl: SourceAcl = CoreUtils.createObject[SourceAcl](sourceAclClass)

    // here we get a dynamic config prefix given by the class.
    // this will allow multiple classes to co-exist in the same config and avoid collisions
    private val sourceAclConfig =
      config.getConfig(s"source.${sourceAcl.CONFIG_PREFIX}")
    sourceAcl.configure(sourceAclConfig)
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
    val extract: Boolean = ksmConfig.getBoolean("extract")
    val readOnly: Boolean = ksmConfig.getBoolean("readonly")
  }

  object Parser {
    private val aclParserConfig = config.getConfig("parser")
    val csvDelimiter: Char = aclParserConfig.getString("csv.delimiter").charAt(0)
  }

  object GRPC {
    private val grpcConfig = config.getConfig("grpc")
    val port: Int = grpcConfig.getInt("port")
    val gatewayPort: Int = grpcConfig.getInt("gateway.port")
  }

  object Feature {
    private val featureConfig = config.getConfig("feature")
    val grpc: Boolean = featureConfig.getBoolean("grpc")
  }

}
