package com.github.simplesteph.ksm

import java.util.concurrent.atomic.AtomicBoolean

import com.github.simplesteph.ksm.parser.CsvAclParser
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

object KafkaSecurityManager extends App {

  val log = LoggerFactory.getLogger(KafkaSecurityManager.getClass)

  val config = ConfigFactory.load()
  val appConfig: AppConfig = new AppConfig(config)

  var isCancelled: AtomicBoolean = new AtomicBoolean(false)

  if (appConfig.KSM.extract) {
    new ExtractAcl(appConfig.Authorizer.authorizer, CsvAclParser).extract()
  } else {
    val aclSynchronizer = new AclSynchronizer(
      appConfig.Authorizer.authorizer,
      appConfig.Source.sourceAcl,
      appConfig.Notification.notification)

    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run(): Unit = {
        log.info("Received stop signal, Kafka Security Manager is shutting down...")
        isCancelled = new AtomicBoolean(true)
        aclSynchronizer.close()
      }
    })

    while (!isCancelled.get()) {
      aclSynchronizer.run()
      Thread.sleep(appConfig.KSM.refreshFrequencyMs)
    }

  }
}
