package com.github.simplesteph.ksm.source

import java.io.StringReader
import java.nio.charset.Charset
import java.util.Base64

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.simplesteph.ksm.parser.CsvAclParser
import com.typesafe.config.Config
import org.slf4j.LoggerFactory
import skinny.http.{ HTTP, HTTPException, Request, Response }

import scala.util.Try

class GitHubSourceAcl extends SourceAcl {

  private val log = LoggerFactory.getLogger(classOf[GitHubSourceAcl])

  override val CONFIG_PREFIX: String = "github"
  final val USER_CONFIG = "user"
  final val REPO_CONFIG = "repo"
  final val FILEPATH_CONFIG = "filepath"
  final val BRANCH_CONFIG = "branch"

  var lastModified: Option[String] = None
  val objectMapper = new ObjectMapper()
  var user: String = _
  var repo: String = _
  var filepath: String = _
  var branch: String = _

  /**
   * internal config definition for the module
   */
  override def configure(config: Config): Unit = {
    user = config.getString(USER_CONFIG)
    repo = config.getString(REPO_CONFIG)
    filepath = config.getString(FILEPATH_CONFIG)
    branch = config.getString(BRANCH_CONFIG)
  }

  override def refresh(): Option[SourceAclResult] = {
    val url = s"https://api.github.com/repos/$user/$repo/contents/$filepath?ref=$branch"
    val request: Request = new Request(url)
    // TODO: add optional auth

    // we use this header for the 304
    request.headers.put("If-Modified-Since", lastModified.getOrElse(""))
    val response: Response = HTTP.get(request)

    response.status match {
      case 200 =>
        lastModified = response.header("Last-Modified")
        val b64encodedContent = objectMapper.readTree(response.textBody).get("content").asText()
        val data = new String(Base64.getDecoder.decode(b64encodedContent), Charset.forName("UTF-8"))
        // use the CSV Parser
        Some(CsvAclParser.aclsFromReader(new StringReader(data)))
      case 304 =>
        None
      case _ =>
        // we got an http error so we propagate it
        Some(
          SourceAclResult(
            Set(),
            List(Try(throw HTTPException(Some("Failure to fetch file"), response)))))
    }
  }

  /**
   * Close all the necessary underlying objects or connections belonging to this instance
   */
  override def close(): Unit = {
    // HTTP
  }
}
