package com.github.simplesteph.ksm.source

import java.io._
import java.nio.charset.Charset
import java.util.Base64

import com.github.simplesteph.ksm.parser.AclParser
import com.typesafe.config.Config
import org.slf4j.LoggerFactory
import skinny.http.{HTTP, HTTPException, Request, Response}

import scala.util.Try

class BitbucketCloudSourceAcl extends SourceAcl {

  private val log = LoggerFactory.getLogger(classOf[BitbucketCloudSourceAcl])

  override val CONFIG_PREFIX: String = "bitbucket-cloud"

  final val API_URL_CONFIG = "api.url"
  final val ORGANIZATION_CONFIG = "organization"
  final val REPO_CONFIG = "repo"
  final val FILEPATH_CONFIG = "filepath"
  final val AUTH_USERNAME_CONFIG = "auth.username"
  final val AUTH_PASSWORD_CONFIG = "auth.password"
  final val HTTP_CONN_TIMEOUT_MS = "http.conn.timeout.ms"
  final val HTTP_READ_TIMEOUT_MS = "http.read.timeout.ms"

  var lastCommit: Option[String] = None

  var apiurl: String = _
  var organization: String = _
  var repo: String = _
  var filePath: String = _
  var username: String = _
  var password: String = _
  var connTimeout: Int = _
  var readTimeout: Int = _

  /**
    * internal config definition for the module
    */
  override def configure(config: Config): Unit = {
    apiurl = config.getString(API_URL_CONFIG)
    organization = config.getString(ORGANIZATION_CONFIG)
    repo = config.getString(REPO_CONFIG)
    filePath = config.getString(FILEPATH_CONFIG)
    username = config.getString(AUTH_USERNAME_CONFIG)
    password = config.getString(AUTH_PASSWORD_CONFIG)
    connTimeout = config.getInt(HTTP_CONN_TIMEOUT_MS)
    readTimeout = config.getInt(HTTP_READ_TIMEOUT_MS)
  }

  override def refresh(aclParser: AclParser): Option[SourceAclResult] = {
    // get the latest file
    val url = s"$apiurl/repositories/$organization/$repo/src/master/$filePath"
    val request: Request = new Request(url)
    request.enableThrowingIOException(true)
    request.connectTimeoutMillis(connTimeout)
    request.readTimeoutMillis(readTimeout)

    // add authentication header
    val basicB64 = Base64.getEncoder.encodeToString(s"$username:$password".getBytes(Charset.forName("UTF-8")))
    request.header("Authorization", s"Basic $basicB64")

    val response: Response = HTTP.get(request)
    response.status match {
      case 200 =>
        // we receive a valid response
        val reader = new BufferedReader(
          new StringReader(response.textBody))
        val res = aclParser.aclsFromReader(reader)
        reader.close()
        Some(res)

      case 400 =>
        // One of the supplied commit IDs or refs was invalid.
        throwError(response)
      case 401 =>
        // authentication error
        throw HTTPException(Some("Authentication exception"), response)
      case 403 =>
        // unauthorized
        throwError(response)
      case 404 =>
        // The repository does not exist.
        throwError(response)
      case _ =>
        // uncaught error
        throwError(response)
    }
  }

  def throwError(response: Response): Option[SourceAclResult] = {
    // we got an http error so we propagate it
    log.warn(response.asString)
    Some(
      SourceAclResult(
        Set(),
        List(Try(
          throw HTTPException(Some("Failure to fetch file"), response)))))
  }

  /**
    * Close all the necessary underlying objects or connections belonging to this instance
    */
  override def close(): Unit = {
    // HTTP
  }
}
