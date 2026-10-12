package io.conduktor.ksm.source;

import com.typesafe.config.Config;
import io.conduktor.ksm.parser.AclParserRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

public final class S3SourceAcl implements SourceAcl {
  private final AclParserRegistry parsers;
  private S3Client client;
  private String bucket, key, etag;
  private int maxBytes;
  private boolean accepted;

  public S3SourceAcl(AclParserRegistry parsers) {
    this.parsers = parsers;
  }

  public String configPrefix() {
    return "s3";
  }

  public void configure(Config config) {
    bucket = config.getString("bucketname");
    key = config.getString("objectkey");
    parsers.getParserByFilename(key);
    maxBytes = config.getInt("max.bytes");
    Duration timeout = Duration.ofMillis(config.getLong("request.timeout.ms"));
    var builder =
        S3Client.builder()
            .region(Region.of(config.getString("region")))
            .httpClientBuilder(
                UrlConnectionHttpClient.builder().connectionTimeout(timeout).socketTimeout(timeout))
            .overrideConfiguration(c -> c.apiCallTimeout(timeout).apiCallAttemptTimeout(timeout));
    if (config.hasPath("endpoint"))
      builder.endpointOverride(URI.create(config.getString("endpoint"))).forcePathStyle(true);
    client = builder.build();
  }

  public Optional<SourceSnapshot> fetch() throws Exception {
    var request = GetObjectRequest.builder().bucket(bucket).key(key);
    if (etag != null) request.ifNoneMatch(etag);
    try (var response = client.getObject(request.build())) {
      String candidate = response.response().eTag();
      String content = Content.utf8(Content.read(response, maxBytes));
      return Optional.of(
          new SourceSnapshot(
              parsers.getParserByFilename(key),
              content,
              () -> {
                etag = candidate;
                accepted = true;
              }));
    } catch (S3Exception e) {
      if (e.statusCode() == 304 && accepted) return Optional.empty();
      throw new java.io.IOException("S3 source returned HTTP " + e.statusCode());
    }
  }

  public void close() {
    if (client != null) client.close();
  }
}
