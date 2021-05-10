package com.github.conduktor.ksm

import java.util.concurrent.atomic.AtomicBoolean

import com.github.conduktor.ksm.parser.CsvAclParser
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success, Try}
import java.util.concurrent.{
  ExecutionException,
  Executors,
  ScheduledExecutorService,
  TimeUnit
}

object KafkaSecurityManager extends App {

  val log = LoggerFactory.getLogger(KafkaSecurityManager.getClass)

  val config = ConfigFactory.load()
  val appConfig: AppConfig = new AppConfig(config)

  var isCancelled: AtomicBoolean = new AtomicBoolean(false)
  var aclSynchronizer: AclSynchronizer = _
  val aclParser = new CsvAclParser(appConfig.Parser.csvDelimiter)
  val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

  if (appConfig.KSM.extract) {
    new ExtractAcl(appConfig.Authorizer.authorizer, aclParser).extract()
  } else {
    aclSynchronizer = new AclSynchronizer(
      appConfig.Authorizer.authorizer,
      appConfig.Source.sourceAcl,
      appConfig.Notification.notification,
      aclParser,
      appConfig.KSM.numFailedRefreshesBeforeNotification,
      appConfig.KSM.readOnly
    )

    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run(): Unit = {
        log.info("Received stop signal")
        shutdown()
      }
    })

    try {
      //if appConfig.KSM.refreshFrequencyMs is equal or less than 0 the aclSyngronizer is run just once.
      if (appConfig.KSM.refreshFrequencyMs <= 0) {
        log.info("Single run mode: ACL will be synchornized once.")
        aclSynchronizer.run()
      } else {
        log.info(
          "Continuous mode: ACL will be synchronized every " + appConfig.KSM.refreshFrequencyMs + " ms."
        )
        val handle = scheduler.scheduleAtFixedRate(
          aclSynchronizer,
          0,
          appConfig.KSM.refreshFrequencyMs,
          TimeUnit.MILLISECONDS
        )
        handle.get
      }
    } catch {
      case e: ExecutionException =>
        log.error("unexpected exception", e)
    } finally {
      shutdown()
    }

  }

  def shutdown(): Unit = {
    log.info("Kafka Security Manager is shutting down...")
    isCancelled = new AtomicBoolean(true)
    aclSynchronizer.close()
    scheduler.shutdownNow()
  }
}
