package io.conduktor.ksm.source

import com.typesafe.config.{Config, ConfigException}
import io.conduktor.ksm.parser.AclParserRegistry
import io.conduktor.ksm.source.security.{GoogleIAM, HttpAuthentication}
import org.apache.http.HttpHeaders.{CONTENT_LENGTH, CONTENT_TYPE, IF_MODIFIED_SINCE, LAST_MODIFIED}
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
  final val REQUIRE_CONTENT_LENGTH_HEADER = "contentlength.required"
  final val HEADERS = "headers"

  var lastModified: Option[String] = None

  var uri: String = _
  var parser: String = "csv"
  var httpMethod: Method = _
  var authentication: Option[HttpAuthentication] = _
  var requireContentLengthHeader: Boolean = _
  var headers: Map[String, String] = _

  def configure(url: String, parser: String, method: String): Unit = {
    configure(url, parser, method, None)
  }

  def configure(url: String, parser: String, method: String, authentication: Option[HttpAuthentication]): Unit = {
    configure(url, parser, method, authentication, requireContentLengthHeader = false)
  }

  def configure(url: String, parser: String, method: String, authentication: Option[HttpAuthentication], requireContentLengthHeader: Boolean): Unit = {
    configure(url, parser, method, authentication, requireContentLengthHeader, Map.empty)
  }

  def configure(url: String, parser: String, method: String,
                authentication: Option[HttpAuthentication],
                requireContentLengthHeader: Boolean, headers: Map[String, String]): Unit = {
    this.uri = url
    this.parser = parser
    this.httpMethod = new Method(method)
    this.authentication = authentication
    this.requireContentLengthHeader = requireContentLengthHeader
    this.headers = headers
  }

    /**
    * internal config definition for the module
    */
  override def configure(config: Config): Unit = {
    val uri = config.getString(URL)
    log.info("URL: {}", uri)

    val parser = config.getString(PARSER)
    log.info("PARSER: {}", parser)

    val httpMethod = config.getString(METHOD)
    log.info("HTTP Method: {}", httpMethod)

    val authentication = config.getString(AUTHENTICATION_TYPE) match {
      case "googleiam" => Some(new GoogleIAM(config.getConfig("auth.googleiam")))
      case _ => None
    }
    log.info("HTTP Authentication: {}", authentication)

    val requireContentLengthHeader: Boolean = getContentLengthHeaderRequiredConfiguration(config)
    log.info("HTTP Content-Length Header required: {}", requireContentLengthHeader)

    val headers: Map[String, String] = getHeaderConfiguration(config)
    log.info("Configured HTTP Headers: {}", headers)

    configure(uri, parser, httpMethod, authentication, requireContentLengthHeader, headers)
  }

  def getContentLengthHeaderRequiredConfiguration(config: Config): Boolean = {
    var requireContentLengthHeader = false
    if (config.hasPath(REQUIRE_CONTENT_LENGTH_HEADER)) {
      requireContentLengthHeader = config.getBoolean(REQUIRE_CONTENT_LENGTH_HEADER)
    }
    requireContentLengthHeader
  }

  def getHeaderConfiguration(config: Config): Map[String, String] = {
    var headers = Map.empty[String, String]
    if (!config.hasPath(HEADERS)) {
      return headers
    }
    val headerConfig = config.getString(HEADERS)
    headerConfig.split(",").foreach { header =>
      val headerKeyValue = header.split(":")
      if (headerKeyValue.length != 2) {
        throw new ConfigException.BadValue(CONFIG_PREFIX + "." + HEADERS, "Invalid header configuration. Expected format: 'name1:value1,name2:value2'")
      }
      val name = headerKeyValue(0).trim
      val value = headerKeyValue(1).trim
      headers += (name -> value)
    }
    headers
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
    request
      .enableThrowingIOException(true)
      .followRedirects(false)
      .connectTimeoutMillis(Int.MaxValue)
      .readTimeoutMillis(Int.MaxValue)

    authentication.map(authentication => request.header(authentication.authHeaderKey, authentication.authHeaderValue))

    // we use this header for the 304
    lastModified.foreach(header => request.header(IF_MODIFIED_SINCE, header))
    request.header(CONTENT_TYPE, "text/plain") // only type supported for now

    headers.foreach {case (name, value) => request.header(name, value)}

    val response: Response = HTTP.request(httpMethod, request)

    response.status match {
      case 200 =>
        lastModified = response.header(LAST_MODIFIED)

        // as skinny HTTP doesn't validate HTTP Header Content-Length
        validateBodyLength(response)

        Some(
          ParsingContext(
            parserRegistry.getParser(parser),
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
      if (requireContentLengthHeader) {
        val errorMessage = s"Response doesn't contain required $CONTENT_LENGTH header, only contained the following headers: ${response.headers.keySet}. Discarding response..."
        log.error(errorMessage)
        throw HTTPException(Some(errorMessage), response)
      }
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
