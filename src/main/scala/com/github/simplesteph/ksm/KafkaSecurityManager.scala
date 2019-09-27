package com.github.simplesteph.ksm

import java.util.concurrent.atomic.AtomicBoolean

import com.github.simplesteph.ksm.grpc.KsmGrpcServer
import com.github.simplesteph.ksm.parser.CsvAclParser
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success, Try}
import java.util.concurrent.{ExecutionException, Executors, ScheduledExecutorService, TimeUnit}

object KafkaSecurityManager extends App {

  val log = LoggerFactory.getLogger(KafkaSecurityManager.getClass)

  val config = ConfigFactory.load()
  val appConfig: AppConfig = new AppConfig(config)

  var isCancelled: AtomicBoolean = new AtomicBoolean(false)
  var grpcServer: KsmGrpcServer = _
  var aclSynchronizer: AclSynchronizer = _
  val aclParser = new CsvAclParser(appConfig.Parser.csvDelimiter)
  val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

  if (appConfig.KSM.extract) {
    new ExtractAcl(appConfig.Authorizer.authorizer, aclParser).extract()
  } else {
    aclSynchronizer = new AclSynchronizer(appConfig.Authorizer.authorizer,
                                          appConfig.Source.sourceAcl,
                                          appConfig.Notification.notification,
                                          aclParser,
                                          appConfig.KSM.readOnly)

    Try {
      grpcServer = new KsmGrpcServer(aclSynchronizer,
                                     appConfig.GRPC.port,
                                     appConfig.GRPC.gatewayPort,
                                     appConfig.Feature.grpc)
      grpcServer.start()
    } match {
      case Success(_) =>
      case Failure(e) =>
        log.error("gRPC Server failed to start", e)
        shutdown()
    }

    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run(): Unit = {
        log.info("Received stop signal")
        shutdown()
      }
    })
    
    try {
      //if appConfig.KSM.refreshFrequencyMs is equal to -1 the aclSyngronizer is run just once.
      if(appConfig.KSM.refreshFrequencyMs == -1){
        aclSynchronizer.run()
      } else {
        val handle = scheduler.scheduleAtFixedRate(aclSynchronizer, 0, appConfig.KSM.refreshFrequencyMs, TimeUnit.MILLISECONDS)
        handle.get
      }
    }
    catch {
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
    grpcServer.stop()
    scheduler.shutdownNow()
  }
}
