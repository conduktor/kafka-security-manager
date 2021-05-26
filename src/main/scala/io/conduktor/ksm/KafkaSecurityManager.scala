package io.conduktor.ksm

import com.typesafe.config.ConfigFactory
import io.conduktor.ksm.parser.AclParserRegistry
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ExecutionException, Executors, ScheduledExecutorService, TimeUnit}

object KafkaSecurityManager extends App {

  val log = LoggerFactory.getLogger(KafkaSecurityManager.getClass)

  val config = ConfigFactory.load()
  val appConfig: AppConfig = new AppConfig(config)

  var isCancelled: AtomicBoolean = new AtomicBoolean(false)
  var aclSynchronizer: AclSynchronizer = _
  val parserRegistry: AclParserRegistry = new AclParserRegistry(appConfig)
  val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

  if (appConfig.KSM.extract) {
    val parser = parserRegistry.getParser(appConfig.KSM.extractFormat)
    new ExtractAcl(appConfig.Authorizer.authorizer, parser).extract()
  } else {
    aclSynchronizer = new AclSynchronizer(
      appConfig.Authorizer.authorizer,
      appConfig.Source.createSource(parserRegistry),
      appConfig.Notification.notification,
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
