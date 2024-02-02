package io.conduktor.ksm.web

import com.sun.net.httpserver.{HttpExchange, HttpHandler}

trait Probe {
  // implementation should be non blocking
  def isSuccessful: Boolean
}

class ProbeHandler(probes: List[Probe]) extends HttpHandler {
  override def handle(exc: HttpExchange): Unit = {
    val checkup = probes.forall(p => p.isSuccessful)
    val payload = Server.responseMapper
      .createObjectNode()
      .put("success", checkup)
    val response = Server.responseMapper.writeValueAsString(payload)
    val responseCode = if (checkup) 200 else 500
    exc.sendResponseHeaders(responseCode, response.length())
    val os = exc.getResponseBody
    os.write(response.getBytes)
    os.close()
  }
}
