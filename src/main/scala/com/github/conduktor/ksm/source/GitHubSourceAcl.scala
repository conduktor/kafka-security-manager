package com.github.conduktor.ksm.source

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.conduktor.ksm.parser.AclParserRegistry
import com.typesafe.config.Config
import org.slf4j.LoggerFactory
import skinny.http.{HTTP, HTTPException, Request, Response}

import java.io.StringReader
import java.nio.charset.Charset
import java.util.Base64
import scala.util.Try

class GitHubSourceAcl(parserRegistry: AclParserRegistry)
    extends SourceAcl(parserRegistry) {

  private val log = LoggerFactory.getLogger(classOf[GitHubSourceAcl])

  override val CONFIG_PREFIX: String = "github"
  final val USER_CONFIG = "user"
  final val REPO_CONFIG = "repo"
  final val FILEPATH_CONFIG = "filepath"
  final val BRANCH_CONFIG = "branch"
  final val HOSTNAME_CONFIG = "hostname"
  final val AUTH_BASIC_CONFIG = "auth.basic"
  final val AUTH_TOKEN_CONFIG = "auth.token"

  var lastModified: Option[String] = None
  val objectMapper = new ObjectMapper()
  var user: String = _
  var repo: String = _
  var filepath: String = _
  var branch: String = _
  var hostname: String = _
  var basicOpt: Option[String] = _
  var tokenOpt: Option[String] = _

  /**
    * internal config definition for the module
    */
  override def configure(config: Config): Unit = {
    user = config.getString(USER_CONFIG)
    repo = config.getString(REPO_CONFIG)
    filepath = config.getString(FILEPATH_CONFIG)
    branch = config.getString(BRANCH_CONFIG)
    hostname = config.getString(HOSTNAME_CONFIG)
    basicOpt = Try(config.getString(AUTH_BASIC_CONFIG)).toOption
    tokenOpt = Try(config.getString(AUTH_TOKEN_CONFIG)).toOption
  }

  override def refresh(): Option[ParsingContext] = {
    val url =
      s"https://$hostname/repos/$user/$repo/contents/$filepath?ref=$branch"
    val request: Request = new Request(url)
    // super important in order to properly fail in case a timeout happens for example
    request.enableThrowingIOException(true)

    // authentication if present
    basicOpt.foreach(basic => {
      val basicB64 = Base64.getEncoder.encodeToString(basic.getBytes("UTF-8"))
      request.header("Authorization", s"Basic $basicB64")
    })
    tokenOpt.foreach(token => {
      request.header("Authorization", s"Token $token")
    })

    // we use this header for the 304
    lastModified.foreach(header => request.header("If-Modified-Since", header))
    val response: Response = HTTP.get(request)

    response.status match {
      case 200 =>
        lastModified = response.header("Last-Modified")
        val b64encodedContent =
          objectMapper.readTree(response.textBody).get("content").asText()
        val data = new String(
          Base64.getDecoder
            .decode(b64encodedContent.replace("\n", "").replace("\r", "")),
          Charset.forName("UTF-8")
        )
        // use the CSV Parser
        Some(
          ParsingContext(parserRegistry.getParserByFilename(filepath), new StringReader(data))
        )
      case 304 =>
        None
      case _ =>
        // we got an http error so we propagate it
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
