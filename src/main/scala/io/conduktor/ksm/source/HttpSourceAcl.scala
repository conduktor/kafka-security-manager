package io.conduktor.ksm.source

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.auth.oauth2.{GoogleCredentials, IdTokenCredentials, IdTokenProvider, ServiceAccountCredentials}
import com.typesafe.config.Config
import io.conduktor.ksm.parser.AclParserRegistry
import org.slf4j.LoggerFactory
import skinny.http.{HTTP, HTTPException, Request, Response}

import java.io.{StringReader, _}
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Collections

class HttpSourceAcl(parserRegistry: AclParserRegistry)
  extends SourceAcl(parserRegistry) {

  private val log = LoggerFactory.getLogger(classOf[HttpSourceAcl])

  private val jwtMap = collection.mutable.Map[String, String]()
  private val expirationMap = collection.mutable.Map[String, Instant]()

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
  var httpMethod: String = _
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
    this.httpMethod = method
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

    this.httpMethod = config.getString(METHOD)
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
    * Return `None` if the Source Acls have not changed (usually using metadata).
    * Return `Some(x)` if the Acls have changed. `x` represents the parsing and parsing errors if any
    * Note: the first call to this function should never return `None`.
    *
    * Kafka Security Manager will not update Acls in Kafka until there are no errors in the result
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
      val authorization = getIdToken()
      request.header("Authorization", authorization)
    }

    // we use this header for the 304
    lastModified.foreach(header => request.header("If-Modified-Since", header))
    val response: Response = HTTP.get(request)

    response.status match {
      case 200 =>
        lastModified = response.header("Last-Modified")

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

  private def getIdToken(): String = {
    val audience = this.targetAudience

    if (jwtMap.get(audience) != null
      && expirationMap.get(audience) != null
      && Instant
      .now()
      .isBefore(expirationMap.get(audience).getOrElse(Instant.MIN)))
      return jwtMap.get(audience).get

    log.info("Getting IdToken from Google Cloud")

    try {
      val jwt = this.getJwtFromGoogleIam
      // Add cache for 30 minutes
      log.info("Adding Token to cache for 30 minutes")
      jwtMap.put(audience, jwt)
      expirationMap.put(audience, Instant.now().plus(30, ChronoUnit.MINUTES))
      jwt
    } catch {
      case e: IOException =>
        throw new RuntimeException("Couldn't get IdToken", e)
    }
  }

  import java.io.IOException

  @throws[IOException]
  private def getJwtFromGoogleIam: String = {
    val credentials = if (serviceAccountKey == null || serviceAccountKey.isEmpty) {
      log.info("Getting Google Default Application Credentials...")
      GoogleCredentials.getApplicationDefault
    } else {
      log.info("Getting Google Credentials from Key File...")
      GoogleCredentials
        .fromStream(new ByteArrayInputStream(this.serviceAccountKey.getBytes))
        .createScoped(Collections.singleton(IAM_SCOPE))
    }

    if (!credentials.isInstanceOf[IdTokenProvider])
      throw new RuntimeException(
        s"Google Credentials: wrong type of token provider. Expected: IdTokenProvider, got: $credentials"
      )

    val idTokenCredentials = IdTokenCredentials.newBuilder
      .setIdTokenProvider(credentials.asInstanceOf[ServiceAccountCredentials])
      .setTargetAudience(this.targetAudience)
      .build

    idTokenCredentials.getRequestMetadata().get("Authorization").get(0)
  }

  /**
    * Close all the necessary underlying objects or connections belonging to this instance
    */
  override def close(): Unit = {

  }
}
