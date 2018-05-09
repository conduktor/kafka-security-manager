package com.github.simplesteph.ksm.grpc

import com.github.simplesteph.ksm.{AclSynchronizer, KafkaSecurityManager}
import com.security.kafka.pb.ksm.KsmServiceGrpc
import io.grpc.{Server, ServerBuilder}
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext

class KsmGrpcServer(
  aclSynchronizer: AclSynchronizer,
  port: Int) {

  val log = LoggerFactory.getLogger(KsmServiceGrpc.getClass)

  private[this] var server: Server = _

  def start(): Unit = {
    log.info("Starting gRPC Server")
    server = ServerBuilder.forPort(port)
      .addService(KsmServiceGrpc.bindService(new KsmServiceImpl(aclSynchronizer), ExecutionContext.global))
      // TODO: Add Reflection service .addService()
      .build()
    server.start()
    log.info("gRPC Server Started")
  }

  def stop(): Unit = {
    server.shutdown()
  }

}
