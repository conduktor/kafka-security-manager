package io.conduktor.ksm.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.slf4j.LoggerFactory

import java.net.InetSocketAddress
import java.util.concurrent.Executors

object Server {
  val responseMapper = new ObjectMapper()
}

class Server(port: Int, livenessProbes: List[Probe]) {
  private val log = LoggerFactory.getLogger(Server.getClass)
  private val server = HttpServer.create(new InetSocketAddress(port), 0)
  server.createContext("/api/probe/ready", new ProbeHandler(List()))
  server.createContext("/api/probe/alive", new ProbeHandler(livenessProbes))

  def start(): Unit = {
    log.info("Staring server on {}", port)
    server.setExecutor(Executors.newSingleThreadExecutor())
    server.start()
  }

  def stop(): Unit = {
    server.stop(0)
  }
}
