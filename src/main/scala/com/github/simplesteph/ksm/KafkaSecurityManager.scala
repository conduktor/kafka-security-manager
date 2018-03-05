package com.github.simplesteph.ksm

import java.util.concurrent.{ Executors, ScheduledFuture, TimeUnit }

import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

object KafkaSecurityManager extends App {

  val log = LoggerFactory.getLogger(KafkaSecurityManager.getClass)

  val config = ConfigFactory.load()
  val appConfig: AppConfig = new AppConfig(config)

  val aclSynchronizer = new AclSynchronizer(
    appConfig.Authorizer.authorizer,
    appConfig.Source.sourceAcl,
    appConfig.Notification.notification)

  val executor = Executors.newScheduledThreadPool(1)
  val f: ScheduledFuture[_] = executor.scheduleAtFixedRate(aclSynchronizer, 1,
    appConfig.KSM.refreshFrequencyMs, TimeUnit.MILLISECONDS)

  Runtime.getRuntime.addShutdownHook(new Thread() {
    override def run(): Unit = {
      aclSynchronizer.close()
      log.info("Kafka Security Manager is shutting down...")
      f.cancel(false)
      log.info("Waiting for thread to cleanly shutdown (10 seconds maximum)")
      executor.shutdown()
      executor.awaitTermination(10, TimeUnit.SECONDS)
      log.info("Kafka Security Manager has shut down...")
    }
  })
}
