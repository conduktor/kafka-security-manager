package com.github.simplesteph.ksm.source

import java.io._
import java.util.Date

import com.google.cloud.secretmanager.v1._
import com.typesafe.config.Config
import org.slf4j.LoggerFactory

import scala.collection.mutable.ListBuffer

class GoogleSecretManagerAcl extends SourceAcl {

  private val log = LoggerFactory.getLogger(classOf[GoogleSecretManagerAcl])

  /**
    * Config Prefix for configuring this module
    */
  override val CONFIG_PREFIX: String = "gcp-secret-manager"

  final val PROJECT = "projectid"
  final val LABEL_FILTER = "label_filter"

  var lastModified: Date = new Date(0)
  var projectId: String = _
  var labelFilter: String = _

  /**
    * internal config definition for the module
    */
  override def configure(config: Config): Unit = {
    projectId = config.getString(PROJECT)
    log.info("Google Project Id: " + projectId)

    labelFilter = config.getString(LABEL_FILTER)
    log.info("Label filter: " + labelFilter)
  }

  private final val HEADER =
    "KafkaPrincipal,ResourceType,PatternType,ResourceName,Operation,PermissionType,Host\n"

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
  override def refresh(): Option[Reader] = {

    val secretManagerServiceClient = SecretManagerServiceClient.create

    val parent = ProjectName.of(projectId)
    val request = ListSecretsRequest.newBuilder.setParent(parent.toString).build

    var response = new StringBuilder(HEADER)

    val filter: Map[String, String] = labelFilter
      .split(",")
      .filter(_ != "")
      .map({ pair =>
        {
          val Array(k, v) = pair.split("=")
          k.trim -> v.trim
        }
      })
      .toMap

    var secrets = new ListBuffer[Secret]()

    // Filter secrets by lastModified and Labels
    // For the moment (2020-June) Google SDK doesn't have filter on API level,
    // so we need to iterate over all secrets
    secretManagerServiceClient
      .listSecrets(request)
      .iterateAll
      .forEach(secret => {
        // check there are any label filter in config
        if (filter.nonEmpty) {
          var counter = 0
          secret.getLabelsMap.forEach((key, value) => {
            if (filter.exists(x => x._1 == key && x._2 == value)) {
              counter += 1
            }
          })
          if (counter == filter.size) {
            secrets += secret
          }
        } else {
          secrets += secret
        }
      })

    log.info("Found " + secrets.size + " Secrets to add.")

    // Get latest version of secret
    secrets.toList.foreach(secret =>
      response ++= secretManagerServiceClient
        .accessSecretVersion(
          SecretVersionName.parse(secret.getName + "/versions/latest").toString
        )
        .getPayload
        .getData
        .toStringUtf8
    )

    log.debug("ACL Contents:")
    log.debug(response.toString())

    secretManagerServiceClient.close()
    secrets.clear()

    Some(new StringReader(response.toString()))

  }

  /**
    * Close all the necessary underlying objects or connections belonging to this instance
    */
  override def close(): Unit = {
    // GCP SDK closed at refresh
  }
}
