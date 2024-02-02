package io.conduktor.ksm.source

import com.fasterxml.jackson.databind.ObjectMapper
import com.typesafe.config.Config
import io.conduktor.ksm.parser.AclParserRegistry
import io.conduktor.ksm.source
import org.slf4j.LoggerFactory
import skinny.http.{HTTP, HTTPException, Request, Response}

import java.io.StringReader
import java.nio.charset.Charset
import java.util.Base64

class BitbucketServerSourceAcl(parserRegistry: AclParserRegistry)
    extends SourceAcl(parserRegistry) {

  private val log = LoggerFactory.getLogger(classOf[BitbucketServerSourceAcl])

  override val CONFIG_PREFIX: String = "bitbucket-server"

  final val HOSTNAME_CONFIG = "hostname"
  final val PORT_CONFIG = "port"
  final val PROTOCOL_CONFIG = "protocol"
  final val PROJECT_CONFIG = "project"
  final val REPO_CONFIG = "repo"
  final val FILEPATH_CONFIG = "filepath"
  final val AUTH_USERNAME_CONFIG = "auth.username"
  final val AUTH_PASSWORD_CONFIG = "auth.password"
  final val BRANCH_CONFIG = "branch"

  var lastCommit: Option[String] = None
  val objectMapper = new ObjectMapper()
  var http: HTTP = HTTP

  var hostname: String = _
  var port: String = _
  var protocol: String = _
  var project: String = _
  var repo: String = _
  var filePath: String = _
  var username: String = _
  var password: String = _
  var branch: Option[String] = _

  /**
    * internal config definition for the module
    */
  override def configure(config: Config): Unit = {
    hostname = config.getString(HOSTNAME_CONFIG)
    port = config.getString(PORT_CONFIG)
    protocol = config.getString(PROTOCOL_CONFIG)
    project = config.getString(PROJECT_CONFIG)
    repo = config.getString(REPO_CONFIG)
    filePath = config.getString(FILEPATH_CONFIG)
    username = config.getString(AUTH_USERNAME_CONFIG)
    password = config.getString(AUTH_PASSWORD_CONFIG)
    branch = Option()
      .filter({ _ =>
        config.hasPath(BRANCH_CONFIG)
      })
      .map({ _ =>
        config.getString(BRANCH_CONFIG)
      })
  }

  override def refresh(): List[ParsingContext] = {
    // get changes since last commit
    val url =
      s"$protocol://$hostname:$port/rest/api/1.0/projects/$project/repos/$repo/commits"
    val request: Request = new Request(url)
    // super important in order to properly fail in case a timeout happens for example
    request.enableThrowingIOException(true)

    branch.foreach(it => request.queryParam("until", it))

    request.queryParam("path", filePath)
    // optionally add the last commit if available
    lastCommit.foreach(s => request.queryParam("since", s))

    // add authentication header
    val basicB64 = Base64.getEncoder.encodeToString(
      s"$username:$password".getBytes(Charset.forName("UTF-8"))
    )
    request.header("Authorization", s"Basic $basicB64")

    val response: Response = http.get(request)
    response.status match {
      case 200 =>
        // we receive a valid response
        val values = objectMapper.readTree(response.textBody).get("values")
        val hasNewCommits = values.size() > 0
        if (hasNewCommits) {

          val rawRetrieveUrl =
            s"$protocol://$hostname:$port/projects/$project/repos/$repo/browse/$filePath?raw"

          val fileRetrievalRequest = new Request(rawRetrieveUrl)
          branch.foreach(it => fileRetrievalRequest.queryParam("at", it))
          fileRetrievalRequest.header("Authorization", s"Basic $basicB64")
          val fileResponse = http.get(fileRetrievalRequest)
          fileResponse.status match {
            case 200 =>
              // update the last commit id
              lastCommit = Some(values.get(0).get("id").asText())
              val data = fileResponse.textBody
              List(
                ParsingContext(
                  filePath,
                  parserRegistry.getParserByFilename(filePath),
                  new StringReader(data)
                )
              )
            case _ =>
              // throw error as you can't retrieve the file
              log.warn(response.asString)
              throw HTTPException(Some(response.asString), response)
          }
        } else {
          List()
        }
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
