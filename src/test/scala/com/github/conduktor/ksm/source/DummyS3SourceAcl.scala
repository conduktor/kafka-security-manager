package com.github.conduktor.ksm.source

import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.github.conduktor.ksm.parser.AclParserRegistry
import io.findify.s3mock.S3Mock

class DummyS3SourceAcl(parserRegistry: AclParserRegistry)
    extends S3SourceAcl(parserRegistry) {

  val api: S3Mock =
    new S3Mock.Builder().withPort(8001).withInMemoryBackend.build
  api.start

  //override the s3 client getter functionality for testing.  we run a local s3 web app
  //and that is why we need to use a client built from a specific end point.
  override def s3Client(): AmazonS3 = {
    val endpoint = new AwsClientBuilder.EndpointConfiguration(
      "http://localhost:8001",
      region
    )

    AmazonS3ClientBuilder.standard
      .withPathStyleAccessEnabled(true)
      .withEndpointConfiguration(endpoint)
      .build
  }
}
