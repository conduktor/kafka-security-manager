package com.github.simplesteph.ksm.source

import com.amazonaws.regions.Regions
import com.amazonaws.services.s3._
import com.amazonaws.services.s3.model._
import java.io._
import com.github.simplesteph.ksm.parser.CsvAclParser
import org.slf4j.LoggerFactory
import com.typesafe.config.Config

import java.util.Date

class S3SourceAcl extends SourceAcl {

  private val log = LoggerFactory.getLogger(classOf[S3SourceAcl])

  /**
    * Config Prefix for configuring this module
    */
  override val CONFIG_PREFIX: String = "s3"

  final val BUCKET_NAME = "bucketname"
  final val BUCKET_KEY  = "objectkey"
  final val REGION      = "region"

  var lastModified: Date = new Date(0)
  var bucket: String = _
  var key: String = _
  var region: String = _

  /**
    * internal config definition for the module
    */
  override def configure(config: Config): Unit = {
    bucket = config.getString(BUCKET_NAME)
    key    = config.getString(BUCKET_KEY)
    region = config.getString(REGION)
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
  override def refresh(): Option[SourceAclResult] = {
    val s3Client = AmazonS3ClientBuilder.standard.withRegion(Regions.fromName(region)).build
    val s3object = s3Client.getObject(new GetObjectRequest(bucket, key).withModifiedSinceConstraint(lastModified))
    // Null is returned when S3 responds with 304 Not Modified
    if (s3object != null) {
      val reader = new BufferedReader(new InputStreamReader(s3object.getObjectContent))
      lastModified = s3object.getObjectMetadata.getLastModified
      val res = CsvAclParser.aclsFromReader(reader)
      reader.close()
      s3object.close()
      Some(res)
    } else {
      None
    }
  }

  /**
    * Close all the necessary underlying objects or connections belonging to this instance
    */
  override def close(): Unit = {
    // S3 (HTTP)
  }
}