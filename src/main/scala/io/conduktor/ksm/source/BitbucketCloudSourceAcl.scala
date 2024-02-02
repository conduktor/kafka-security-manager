package io.conduktor.ksm.source

import com.typesafe.config.Config
import io.conduktor.ksm.parser.AclParserRegistry
import io.conduktor.ksm.source
import org.slf4j.LoggerFactory
import skinny.http.{HTTP, HTTPException, Request, Response}

import java.io._
import java.nio.charset.Charset
import java.util.Base64

class BitbucketCloudSourceAcl(parserRegistry: AclParserRegistry)
    extends SourceAcl(parserRegistry) {

  private val log = LoggerFactory.getLogger(classOf[BitbucketCloudSourceAcl])

  override val CONFIG_PREFIX: String = "bitbucket-cloud"

  final val API_URL_CONFIG = "api.url"
  final val ORGANIZATION_CONFIG = "organization"
  final val REPO_CONFIG = "repo"
  final val FILEPATH_CONFIG = "filepath"
  final val AUTH_USERNAME_CONFIG = "auth.username"
  final val AUTH_PASSWORD_CONFIG = "auth.password"

  var lastCommit: Option[String] = None

  var apiurl: String = _
  var organization: String = _
  var repo: String = _
  var filePath: String = _
  var username: String = _
  var password: String = _

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
  }

  override def refresh(): List[ParsingContext] = {
    // get the latest file
    val url = s"$apiurl/repositories/$organization/$repo/src/master/$filePath"
    val request: Request = new Request(url)
    // super important in order to properly fail in case a timeout happens for example
    request.enableThrowingIOException(true)

    // add authentication header
    val basicB64 = Base64.getEncoder.encodeToString(
      s"$username:$password".getBytes(Charset.forName("UTF-8"))
    )
    request.header("Authorization", s"Basic $basicB64")

    val response: Response = HTTP.get(request)
    response.status match {
      case 200 =>
        // we receive a valid response
        val reader = new BufferedReader(new StringReader(response.textBody))
        List(
          ParsingContext(
            filePath,
            parserRegistry.getParserByFilename(filePath),
            reader
          )
        )
      case _ =>
        // uncaught error
        log.warn(response.asString)
        throw HTTPException(Some(response.asString), response)
    }
  }

  /**
    * Close all the necessary underlying objects or connections belonging to this instance
    */
  override def close(): Unit = {
    // HTTP
  }
}
