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

  if (appConfig.KSM.extract) {
    new ExtractAcl(appConfig.Authorizer.authorizer, CsvAclParser).extract()
  } else {
    aclSynchronizer = new AclSynchronizer(appConfig.Authorizer.authorizer,
                                          appConfig.Source.sourceAcl,
                                          appConfig.Notification.notification)

    Try {
      grpcServer = new KsmGrpcServer(aclSynchronizer,
                                     appConfig.GRPC.port,
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

    while (!isCancelled.get()) {
      aclSynchronizer.run()
      Thread.sleep(appConfig.KSM.refreshFrequencyMs)
    }

  }

  def shutdown(): Unit = {
    log.info("Kafka Security Manager is shutting down...")
    isCancelled = new AtomicBoolean(true)
    aclSynchronizer.close()
    grpcServer.stop()
  }
}
