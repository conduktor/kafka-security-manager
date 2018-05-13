package com.github.simplesteph.ksm.grpc

import com.github.simplesteph.ksm.{ AclSynchronizer, KafkaSecurityManager }
import com.security.kafka.pb.ksm.{ KsmServiceGrpc, KsmServiceHandler }
import grpcgateway.server.{ GrpcGatewayServer, GrpcGatewayServerBuilder }
import io.grpc.protobuf.services.ProtoReflectionService
import io.grpc.{ ManagedChannelBuilder, Server, ServerBuilder }
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext

class KsmGrpcServer(
  aclSynchronizer: AclSynchronizer,
  port: Int,
  gatewayPort: Int,
  enabled: Boolean) {

  val log = LoggerFactory.getLogger(KsmServiceGrpc.getClass)

  private[this] var server: Server = _
  private[this] var gateway: GrpcGatewayServer = _

  private val ec = ExecutionContext.global

  def start(): Unit = {
    if (enabled) {
      log.info("Starting gRPC Server")
      server = ServerBuilder.forPort(port)
        .addService(ProtoReflectionService.newInstance())
        .addService(KsmServiceGrpc.bindService(new KsmServiceImpl(aclSynchronizer), ec))
        .build()
      server.start()
      log.info(s"gRPC Server started on port $port")

      log.info("Starting gRPC Gateway")

      // internal client for gateway
      val channel =
        ManagedChannelBuilder
          .forAddress("localhost", port)
          .usePlaintext()
          .build()

      // gateway (REST)
      gateway = GrpcGatewayServerBuilder
        .forPort(gatewayPort)
        .addService(new KsmServiceHandler(channel)(ec))
        .build()
      gateway.start()

      log.info(s"gRPC Server started on port $gatewayPort")
    }
  }

  def stop(): Unit = {
    if (enabled) {
      server.shutdown()
      gateway.shutdown()
    }
  }

}
