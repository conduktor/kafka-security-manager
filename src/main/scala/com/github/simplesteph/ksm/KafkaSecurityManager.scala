package com.github.simplesteph.ksm

import java.util.concurrent.atomic.AtomicBoolean

import com.github.simplesteph.ksm.grpc.KsmGrpcServer
import com.github.simplesteph.ksm.parser.CsvAclParser
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success, Try}

object KafkaSecurityManager extends App {

  val log = LoggerFactory.getLogger(KafkaSecurityManager.getClass)

  val config = ConfigFactory.load()
  val appConfig: AppConfig = new AppConfig(config)

  var isCancelled: AtomicBoolean = new AtomicBoolean(false)
  var grpcServer: KsmGrpcServer = _
  var aclSynchronizer: AclSynchronizer = _
  val aclParser = new CsvAclParser(appConfig.Parser.csvDelimiter)

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
      while (!isCancelled.get()) {
        aclSynchronizer.run()
        Thread.sleep(appConfig.KSM.refreshFrequencyMs)
      }
    } catch {
      case e: Exception =>
        log.error("Unexpected exception...", e)
        shutdown()
    }

  }

  def shutdown(): Unit = {
    log.info("Kafka Security Manager is shutting down...")
    isCancelled = new AtomicBoolean(true)
    aclSynchronizer.close()
    grpcServer.stop()
  }
}
