package io.conduktor.ksm.source

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.auth.oauth2.{GoogleCredentials, IdTokenCredentials, IdTokenProvider, ServiceAccountCredentials}
import com.typesafe.config.Config
import io.conduktor.ksm.parser.AclParserRegistry
import org.apache.http.HttpHeaders.CONTENT_LENGTH
import org.slf4j.LoggerFactory
import skinny.http._

import java.io._
import java.time.Instant
import java.util.{Collections, Date}

class HttpSourceAcl(parserRegistry: AclParserRegistry)
  extends SourceAcl(parserRegistry) {

  private val log = LoggerFactory.getLogger(classOf[HttpSourceAcl])

  private var tokenCache: IdTokenCredentials = _

  private final val IAM_SCOPE = "https://www.googleapis.com/auth/iam"

  /**
    * Config Prefix for configuring this module
    */
  override val CONFIG_PREFIX: String = "http"

  final val URL = "url"
  final val PARSER = "parser"
  final val METHOD = "method"
  final val ENABLE_AUTH = "enable_auth"
  final val SERVICE_ACCOUNT = "service_account"
  final val SERVICE_ACCOUNT_KEY = "service_account_key"
  final val TARGET_AUDIENCE = "target_audience"

  var lastModified: Option[String] = None
  val objectMapper = new ObjectMapper()

  var uri: String = _
  var parser: String = "csv"
  var httpMethod: Method = _
  var enableAuth: Boolean = false
  var serviceAccountName: String = _
  var targetAudience: String = _
  var serviceAccountKey: String = _

  def configure(
                 url: String,
                 parser: String,
                 method: String,
                 enableAuth: Boolean,
                 serviceAccount: String,
                 targetAudience: String,
                 serviceAccountKey: String
               ): Unit = {
    this.uri = url
    this.parser = parser
    this.httpMethod = new Method(method)
    this.enableAuth = enableAuth
    this.serviceAccountName = serviceAccount
    this.targetAudience = targetAudience
    this.serviceAccountKey = serviceAccountKey
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

    this.enableAuth = config.getBoolean(ENABLE_AUTH)
    log.info("Enable Auth: {}", this.enableAuth)

    this.serviceAccountName = config.getString(SERVICE_ACCOUNT)
    log.info("Service Account: {}", this.serviceAccountName)

    this.targetAudience = config.getString(TARGET_AUDIENCE)
    log.info("Target Audience: {}", this.targetAudience)

    this.serviceAccountKey = config.getString(SERVICE_ACCOUNT_KEY)
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

    if (enableAuth) {
      request.header("Authorization", getIdToken())
    }

    // we use this header for the 304
    lastModified.foreach(header => request.header("If-Modified-Since", header))
    request.header("Content-Type", "text/plain") // only type supported for now
    val response: Response = HTTP.request(httpMethod, request)

    response.status match {
      case 200 =>
        lastModified = response.header("Last-Modified")

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
      val reader = new BufferedReader(new StringReader(response.textBody))
      val aclLineCount = reader.lines.count
      log.info("There were {} lines in the response received from {}", aclLineCount, uri)
      reader.close()
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

  private def getIdToken(): String = {
    if (tokenCache == null
      || Date.from(Instant.now()).after(tokenCache.getIdToken.getExpirationTime)) {
      try {
        log.info("Getting IdToken from Google Cloud")
        tokenCache = getJwtFromGoogleIam
      } catch {
        case e: Exception =>
          throw new RuntimeException("Couldn't get IdToken", e)
      }
    }
    tokenCache.getRequestMetadata().get("Authorization").get(0) // Bearer Token
  }

  import java.io.IOException

  @throws[IOException]
  private def getJwtFromGoogleIam: IdTokenCredentials = {
    val credentials = if (serviceAccountKey == null || serviceAccountKey.isEmpty) {
      log.info("Getting Google Default Application Credentials...")
      GoogleCredentials.getApplicationDefault
    } else {
      log.info("Getting Google Credentials from Key File...")
      GoogleCredentials
        .fromStream(new ByteArrayInputStream(this.serviceAccountKey.getBytes))
        .createScoped(Collections.singleton(IAM_SCOPE))
    }

    credentials match {
      case credentials: IdTokenProvider =>
        IdTokenCredentials.newBuilder
          .setIdTokenProvider(credentials.asInstanceOf[ServiceAccountCredentials])
          .setTargetAudience(this.targetAudience)
          .build
      case credentials: Any =>  throw new RuntimeException(
        s"Google Credentials: wrong type of token provider. Expected: IdTokenProvider, got: $credentials"
      )
    }

  }

  /**
    * Close all the necessary underlying objects or connections belonging to this instance
    */
  override def close(): Unit = {

  }
}
