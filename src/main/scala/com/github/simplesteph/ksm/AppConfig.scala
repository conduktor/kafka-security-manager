package com.github.simplesteph.ksm

import com.github.simplesteph.ksm.notification.Notification
import com.github.simplesteph.ksm.source.SourceAcl
import com.typesafe.config.Config
import kafka.security.auth.Authorizer
import kafka.utils.CoreUtils

import scala.collection.JavaConverters._

class AppConfig(config: Config) {

  object Authorizer {
    private val authorizerClass = config.getString("authorizer.class")
    val authZ: Authorizer = CoreUtils.createObject[Authorizer](authorizerClass)

    private val authorizerConfig = config.getConfig("authorizer.config")
    authZ.configure(Map("zookeeper.connect" -> "localhost:2181").asJava)
    //    authZ.configure(authorizerConfig)
  }

  object Source {
    private val sourceAclClass = config.getString("source.class")
    val sourceAcl: SourceAcl = CoreUtils.createObject[SourceAcl](sourceAclClass)

    // here we get a dynamic config prefix given by the class.
    // this will allow multiple classes to co-exist in the same config and avoid collisions
    private val sourceAclConfig = config.getConfig(s"source.${sourceAcl.CONFIG_PREFIX}")
    sourceAcl.configure(sourceAclConfig)
  }

  object Notification {
    private val notificationClass = config.getString("notification.class")
    val notification: Notification = CoreUtils.createObject[Notification](notificationClass)

    // here we get a dynamic config prefix given by the class.
    // this will allow multiple classes to co-exist in the same config and avoid collisions
    private val notificationConfig = config.getConfig(s"notification.${notification.CONFIG_PREFIX}")
    notification.configure(notificationConfig)
  }

}
