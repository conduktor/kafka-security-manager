package io.conduktor.ksm.source

import com.fasterxml.jackson.databind.ObjectMapper
import com.typesafe.config.Config
import io.conduktor.ksm.parser.AclParserRegistry
import io.conduktor.ksm.source.security.{GoogleIAM, HttpAuthentication}
import org.apache.http.HttpHeaders.CONTENT_LENGTH
import org.slf4j.LoggerFactory
import skinny.http._

import java.io._

class HttpSourceAcl(parserRegistry: AclParserRegistry)
  extends SourceAcl(parserRegistry) {

  private val log = LoggerFactory.getLogger(classOf[HttpSourceAcl])

  /**
    * Config Prefix for configuring this module
    */
  override val CONFIG_PREFIX: String = "http"

  final val URL = "url"
  final val PARSER = "parser"
  final val METHOD = "method"
  final val AUTHENTICATION_TYPE = "auth.type"

  var lastModified: Option[String] = None
  val objectMapper = new ObjectMapper()

  var uri: String = _
  var parser: String = "csv"
  var httpMethod: Method = _
  var authentication: Option[HttpAuthentication] = _

  def configure(url: String, parser: String, method: String, authentication: Option[HttpAuthentication]): Unit = {
    this.uri = url
    this.parser = parser
    this.httpMethod = new Method(method)
    this.authentication = authentication
  }
  def configure(url: String, parser: String, method: String): Unit = {
    this.uri = url
    this.parser = parser
    this.httpMethod = new Method(method)
    this.authentication = None
  }

    /**
    * internal config definition for the module
    */
  override def configure(config: Config): Unit = {
    this.uri = config.getString(URL)
    log.info("URL: {}", this.uri)

    this.parser = config.getString(PARSER)
    log.info("PARSER: {}", this.parser)

    this.httpMethod = new Method(config.getString(METHOD))
    log.info("HTTP Method: {}", this.httpMethod)

    this.authentication = config.getString(AUTHENTICATION_TYPE) match {
      case "googleiam" => Some(new GoogleIAM(config.getConfig("auth.googleiam")))
      case _ => None
    }
    log.info("HTTP Authentication: {}", this.authentication)
  }

  /**
    * Refresh the current view on the external source of truth for Acl
    * Ideally this function is smart and does not pull the entire external Acl at every iteration
    * Return `None` if the Source ACLs have not changed (usually using metadata).
    * Return `Some(x)` if the ACLs have changed. `x` represents the parsing and parsing errors if any
    * Note: the first call to this function should never return `None`.
    *
    * Kafka Security Manager will not update ACLs in Kafka until there are no errors in the result
    *
    * @return
    */
  override def refresh(): Option[ParsingContext] = {

    val request: Request = new Request(uri)
    // super important in order to properly fail in case a timeout happens for example
    request.enableThrowingIOException(true)
    request.followRedirects(false)
    request.connectTimeoutMillis(Int.MaxValue)
    request.readTimeoutMillis(Int.MaxValue)

    authentication.map(authentication => request.header(authentication.authHeaderKey, authentication.authHeaderValue))

    // we use this header for the 304
    lastModified.foreach(header => request.header("If-Modified-Since", header))
    request.header("Content-Type", "text/plain") // only type supported for now
    val response: Response = HTTP.request(httpMethod, request)

    response.status match {
      case 200 =>
        lastModified = response.header("Last-Modified")

        // as skinny HTTP doesn't validate HTTP Header Content-Length
        validateBodyLength(response)

        Some(
          ParsingContext(
            parserRegistry.getParser(this.parser),
            new BufferedReader(new StringReader(response.textBody))
          )
        )
      case 304 =>
        None
      case _ =>
        // we got an http error so we propagate it
        log.error("HTTP Status {} in HTTP Source request: {}", response.status, response.asString)
        throw HTTPException(Some(response.asString), response)
    }
  }

  /**
    * Validate HTTP Header Content-Length against response payload length.
    *
    * @param response HTTP response
    */
  private def validateBodyLength(response: Response): Unit = {
    val optContentLengthHeader = response.headers
      .find(h => CONTENT_LENGTH.equalsIgnoreCase(h._1))
      .map(h => h._2)
      .map(l => l.toInt)
    if (optContentLengthHeader.isEmpty) {
      log.warn(s"Response doesn't contain $CONTENT_LENGTH header, only contained the following headers: ${response.headers.keySet}. Skipping validation...")
      return
    }

    val contentLengthHeader = optContentLengthHeader.get
    val bodyLength = response.asBytes.length
    log.info(s"Validating body length ($bodyLength bytes) received from $uri against Content-Length header ($contentLengthHeader bytes) claimed in response")
    try {
      log.debug("There were {} lines in the response received from {}", response.textBody.linesIterator.length, uri)
    }
    catch {
      case e: Exception => log.warn(s"Failed to compute number of lines in the response received from $uri", e)
    }
    if (contentLengthHeader != bodyLength) {
      val errorMessage = s"Body length ($bodyLength bytes) inconsistent with Content-Length header ($contentLengthHeader bytes)"
      log.error(errorMessage)
      throw HTTPException(Some(errorMessage), response)
    }
  }

  /**
    * Close all the necessary underlying objects or connections belonging to this instance
    */
  override def close(): Unit = {

  }
}
