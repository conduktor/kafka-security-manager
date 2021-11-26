package io.conduktor.ksm.source.security

import com.google.auth.oauth2.{GoogleCredentials, IdTokenCredentials, IdTokenProvider, ServiceAccountCredentials}
import com.typesafe.config.Config
import org.slf4j.LoggerFactory

import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.{Collections, Date}

class GoogleIAM extends HttpAuthentication {
  def this(config: Config) {
    this()
    this.serviceAccountName = config.getString(SERVICE_ACCOUNT)
    log.info("Service Account: {}", this.serviceAccountName)

    this.targetAudience = config.getString(TARGET_AUDIENCE)
    log.info("Target Audience: {}", this.targetAudience)

    this.serviceAccountKey = config.getString(SERVICE_ACCOUNT_KEY)
  }

  def this(serviceAccountName: String, targetAudience: String, serviceAccountKey: String) {
    this()
    this.serviceAccountName = serviceAccountName
    this.targetAudience = targetAudience
    this.serviceAccountKey = serviceAccountKey
  }

  private val log = LoggerFactory.getLogger(classOf[GoogleIAM])

  private final val IAM_SCOPE = "https://www.googleapis.com/auth/iam"
  private final val AUTHENTICATION_HEADER_KEY = "Authorization"

  val CONFIG_PREFIX: String =  "googleiam"

  final val SERVICE_ACCOUNT = "service-account"
  final val SERVICE_ACCOUNT_KEY = "service-account-key"
  final val TARGET_AUDIENCE = "target-audience"

  private var tokenCache: IdTokenCredentials = _

  private var serviceAccountName: String = _
  private var targetAudience: String = _
  private var serviceAccountKey: String = _

  override def authHeaderKey: String = AUTHENTICATION_HEADER_KEY
  override def authHeaderValue: String = getIdToken()

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
      case credentials: ServiceAccountCredentials =>
      IdTokenCredentials.newBuilder
        .setIdTokenProvider(credentials)
        .setTargetAudience(this.targetAudience)
        .build
      case credentials: Any => throw new RuntimeException(
        s"Google Credentials: wrong type of token provider. Expected: IdTokenProvider, got: $credentials"
      )
    }
  }
}
