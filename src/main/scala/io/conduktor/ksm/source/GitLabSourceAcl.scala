package io.conduktor.ksm.source

import com.fasterxml.jackson.databind.ObjectMapper
import com.typesafe.config.Config
import io.conduktor.ksm.parser.AclParserRegistry
import org.slf4j.LoggerFactory
import skinny.http.{HTTP, HTTPException, Request, Response}

import java.io.StringReader
import java.nio.charset.Charset
import java.util.Base64

class GitLabSourceAcl(parserRegistry: AclParserRegistry)
    extends SourceAcl(parserRegistry) {

  private val log = LoggerFactory.getLogger(classOf[GitLabSourceAcl])

  override val CONFIG_PREFIX: String = "gitlab"
  final val REPOID_CONFIG = "repoid"
  final val FILEPATH_CONFIG = "filepath"
  final val BRANCH_CONFIG = "branch"
  final val HOSTNAME_CONFIG = "hostname"
  final val ACCESSTOKEN_CONFIG = "accesstoken"

  var lastModified: Option[String] = None
  val objectMapper = new ObjectMapper()
  var repoid: String = _
  var filepath: String = _
  var branch: String = _
  var hostname: String = _
  var accessToken: String = _

  /**
    * internal config definition for the module
    */
  override def configure(config: Config): Unit = {
    repoid = config.getString(REPOID_CONFIG)
    filepath = config.getString(FILEPATH_CONFIG)
    branch = config.getString(BRANCH_CONFIG)
    hostname = config.getString(HOSTNAME_CONFIG)
    accessToken = config.getString(ACCESSTOKEN_CONFIG)
  }

  override def refresh(): List[ParsingContext] = {
    val url =
      s"https://$hostname/api/v4/projects/$repoid/repository/files/$filepath?ref=$branch"
    val request: Request = new Request(url)
    // super important in order to properly fail in case a timeout happens for example
    request.enableThrowingIOException(true)

    // auth header
    request.header("PRIVATE-TOKEN", s" $accessToken")
    val metadata: Response = HTTP.head(request)
    val commitId = metadata.header("X-Gitlab-Commit-Id")

    log.debug(s"lastModified: ${lastModified}")
    log.debug(s"commitId from Head: ${commitId}")

    lastModified match {
      case `commitId` =>
        log.info(
          s"No changes were detected in the ACL file ${filepath}. Skipping .... "
        )
        List()
      case _ =>
        val response: Response = HTTP.get(request)
        response.status match {
          case 200 =>
            val responseJSON = objectMapper.readTree(response.textBody)
            lastModified = Some(responseJSON.get("commit_id").asText())
            val b64encodedContent = responseJSON.get("content").asText()
            val data = new String(
              Base64.getDecoder
                .decode(b64encodedContent.replace("\n", "").replace("\r", "")),
              Charset.forName("UTF-8")
            )
            // use the CSV Parser
            List(
              ParsingContext(
                filepath,
                parserRegistry.getParserByFilename(filepath),
                new StringReader(data)
              )
            )
          case _ =>
            // we got an http error so we propagate it
            log.warn(response.asString)
            throw new HTTPException(Some(response.asString), response)
        }
    }
  }

  /**
    * Close all the necessary underlying objects or connections belonging to this instance
    */
  override def close(): Unit = {
    // HTTP
  }
}
