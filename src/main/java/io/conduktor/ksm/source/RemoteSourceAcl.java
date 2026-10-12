package io.conduktor.ksm.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import io.conduktor.ksm.parser.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Common conditional GET contract for HTTP and repository providers. */
public abstract class RemoteSourceAcl implements SourceAcl {
  protected final AclParserRegistry parsers;
  protected final Map<String, String> headers = new HashMap<>();
  protected URI uri;
  protected AclParser parser;
  protected String method = "GET";
  protected boolean base64Json;
  protected HttpTransport transport;
  private String etag, lastModified;
  private boolean accepted;
  private int maxBytes;

  protected RemoteSourceAcl(AclParserRegistry parsers) {
    this.parsers = parsers;
  }

  protected void initialize(Config config) {
    maxBytes = config.getInt("max.bytes");
    transport =
        new HttpTransport(Duration.ofMillis(config.getLong("request.timeout.ms")), maxBytes);
  }

  protected Map<String, String> requestHeaders() throws Exception {
    return new HashMap<>(headers);
  }

  public Optional<SourceSnapshot> fetch() throws Exception {
    Map<String, String> requestHeaders = requestHeaders();
    if (etag != null) requestHeaders.put("If-None-Match", etag);
    else if (lastModified != null) requestHeaders.put("If-Modified-Since", lastModified);
    var response = transport.send(uri, method, requestHeaders, null);
    if (response.statusCode() == 304) {
      if (!accepted)
        throw new java.io.IOException(
            "First source response cannot be 304 without a validated snapshot");
      return Optional.empty();
    }
    if (response.statusCode() != 200)
      throw new java.io.IOException("Source returned HTTP " + response.statusCode());
    byte[] content = response.body();
    if (base64Json) {
      var node = new ObjectMapper().readTree(content);
      if (node == null || !node.hasNonNull("content") || !node.get("content").isTextual())
        throw new java.io.IOException("Repository response has no file content");
      if (node.has("encoding") && !"base64".equals(node.get("encoding").asText()))
        throw new java.io.IOException("Repository file encoding must be base64");
      content =
          Base64.getDecoder()
              .decode(node.get("content").asText().replace("\n", "").replace("\r", ""));
    }
    if (content.length > maxBytes) throw new java.io.IOException("Source exceeds max.bytes");
    return Optional.of(
        new SourceSnapshot(
            parser,
            Content.utf8(content),
            () -> {
              etag = response.headers().firstValue("ETag").orElse(null);
              lastModified = response.headers().firstValue("Last-Modified").orElse(null);
              accepted = true;
            }));
  }

  public void close() {
    if (transport != null) transport.close();
  }

  protected static String encoded(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  protected static String path(String value) {
    return String.join(
        "/", Arrays.stream(value.split("/", -1)).map(RemoteSourceAcl::encoded).toList());
  }

  protected static String base(String hostname) {
    return (hostname.contains("://") ? hostname : "https://" + hostname).replaceAll("/+$", "");
  }

  protected void basic(String credentials) {
    headers.put(
        "Authorization",
        "Basic "
            + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
  }
}
