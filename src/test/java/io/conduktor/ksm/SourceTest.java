package io.conduktor.ksm;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.*;
import com.sun.net.httpserver.*;
import com.typesafe.config.*;
import io.conduktor.ksm.parser.AclParserRegistry;
import io.conduktor.ksm.source.*;
import io.conduktor.ksm.source.security.GoogleIAM;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SourceTest {
  @TempDir Path temporary;
  final AclParserRegistry parsers = new AclParserRegistry(',');
  HttpServer server;
  ExecutorService executor;
  AtomicReference<String> body = new AtomicReference<>(ParserTest.HEADER + ParserTest.ROW);
  AtomicInteger status = new AtomicInteger(200);
  AtomicInteger requests = new AtomicInteger();
  AtomicReference<Headers> requestHeaders = new AtomicReference<>();
  AtomicReference<URI> requestUri = new AtomicReference<>();
  AtomicLong delay = new AtomicLong();

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext(
        "/",
        exchange -> {
          try {
            requests.incrementAndGet();
            requestHeaders.set(exchange.getRequestHeaders());
            requestUri.set(exchange.getRequestURI());
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("ETag", "\"v1\"");
            exchange.getResponseHeaders().set("Last-Modified", "Mon, 12 Oct 2026 00:00:00 GMT");
            exchange.getResponseHeaders().set("Location", base() + "/redirect-target");
            if (status.get() == 304) exchange.sendResponseHeaders(304, -1);
            else {
              exchange.sendResponseHeaders(status.get(), bytes.length);
              Thread.sleep(delay.get());
              exchange.getResponseBody().write(bytes);
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (java.io.IOException ignored) {
            /* Client timeout deliberately closes the connection. */
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
    executor.shutdownNow();
  }

  String base() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  Config defaults() {
    return ConfigFactory.parseString("max.bytes=1048576\nrequest.timeout.ms=2000");
  }

  HttpSourceAcl http(Config override) {
    var source = new HttpSourceAcl(parsers);
    source.configure(
        override
            .withFallback(
                ConfigFactory.parseMap(
                    Map.of("url", base(), "parser", "csv", "method", "GET", "auth.type", "NONE")))
            .withFallback(defaults()));
    return source;
  }

  @Test
  void httpCacheTokenRequiresValidatedContentAndCanRecover() throws Exception {
    try (var source = http(ConfigFactory.empty())) {
      body.set("<html>broken</html>");
      var bad = source.fetch().orElseThrow();
      assertThrows(Exception.class, () -> bad.parser().parse(bad.document()));
      body.set(ParserTest.HEADER + ParserTest.ROW);
      var good = source.fetch().orElseThrow();
      assertNull(requestHeaders.get().getFirst("If-None-Match"));
      assertEquals(Set.of(ParserTest.read("orders")), good.parser().parse(good.document()));
      good.accept().run();
      status.set(304);
      assertTrue(source.fetch().isEmpty());
      assertEquals("\"v1\"", requestHeaders.get().getFirst("If-None-Match"));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {301, 302, 304, 401, 403, 404, 500})
  void firstHttpErrorNeverMeansUnchanged(int code) {
    status.set(code);
    try (var source = http(ConfigFactory.empty())) {
      assertThrows(Exception.class, source::fetch);
    }
    assertEquals(1, requests.get());
  }

  @Test
  void responseSizeAndWholeBodyTimeoutAreBounded() {
    body.set("x".repeat(8192));
    try (var source = http(ConfigFactory.parseString("max.bytes=64"))) {
      assertThrows(Exception.class, source::fetch);
    }
    delay.set(2000);
    try (var source = http(ConfigFactory.parseString("request.timeout.ms=400"))) {
      assertTimeoutPreemptively(
          Duration.ofSeconds(2), () -> assertThrows(Exception.class, source::fetch));
    }
  }

  @Test
  void repositoryUrlsAndAuthenticationPreserveSpecialCharacters() throws Exception {
    String encoded =
        Base64.getEncoder().encodeToString(body.get().getBytes(StandardCharsets.UTF_8));
    body.set(
        new ObjectMapper().writeValueAsString(Map.of("content", encoded, "encoding", "base64")));
    try (var source = new GitHubSourceAcl(parsers)) {
      source.configure(
          ConfigFactory.parseMap(
                  Map.of(
                      "hostname",
                      base(),
                      "user",
                      "team",
                      "repo",
                      "acl repo",
                      "filepath",
                      "dir/a file.csv",
                      "branch",
                      "feature/a+b",
                      "auth.token",
                      "test-token"))
              .withFallback(defaults()));
      var snapshot = source.fetch().orElseThrow();
      assertEquals(Set.of(ParserTest.read("orders")), snapshot.parser().parse(snapshot.document()));
      assertEquals(
          "/repos/team/acl%20repo/contents/dir/a%20file.csv", requestUri.get().getRawPath());
      assertEquals("ref=feature%2Fa%2Bb", requestUri.get().getRawQuery());
      assertEquals("Bearer test-token", requestHeaders.get().getFirst("Authorization"));
    }
    try (var source = new GitLabSourceAcl(parsers)) {
      source.configure(
          ConfigFactory.parseMap(
                  Map.of(
                      "hostname",
                      base(),
                      "repoid",
                      "team/project",
                      "filepath",
                      "dir/a file.csv",
                      "branch",
                      "feature/a+b",
                      "accesstoken",
                      "test-token"))
              .withFallback(defaults()));
      source.fetch();
      assertEquals(
          "/api/v4/projects/team%2Fproject/repository/files/dir%2Fa%20file.csv",
          requestUri.get().getRawPath());
      assertEquals("test-token", requestHeaders.get().getFirst("PRIVATE-TOKEN"));
      status.set(403);
      assertThrows(Exception.class, source::fetch);
    }
  }

  @Test
  void bitbucketUsesAuthenticatedRawContentAndSelectedBranch() throws Exception {
    String basic =
        "Basic " + Base64.getEncoder().encodeToString("test:pw".getBytes(StandardCharsets.UTF_8));
    try (var source = new BitbucketServerSourceAcl(parsers)) {
      source.configure(
          ConfigFactory.parseMap(
                  Map.of(
                      "protocol",
                      "http",
                      "hostname",
                      "127.0.0.1",
                      "port",
                      "" + server.getAddress().getPort(),
                      "project",
                      "KAF",
                      "repo",
                      "repo",
                      "filepath",
                      "dir/a file.csv",
                      "branch",
                      "feature/a",
                      "auth.username",
                      "test",
                      "auth.password",
                      "pw"))
              .withFallback(defaults()));
      var snapshot = source.fetch().orElseThrow();
      assertEquals(Set.of(ParserTest.read("orders")), snapshot.parser().parse(snapshot.document()));
      assertEquals(
          "/rest/api/1.0/projects/KAF/repos/repo/raw/dir/a%20file.csv",
          requestUri.get().getRawPath());
      assertEquals("at=feature%2Fa", requestUri.get().getRawQuery());
      assertEquals(basic, requestHeaders.get().getFirst("Authorization"));
      snapshot.accept().run();
      status.set(304);
      assertTrue(source.fetch().isEmpty());
    }
    status.set(200);
    try (var source = new BitbucketCloudSourceAcl(parsers)) {
      source.configure(
          ConfigFactory.parseMap(
                  Map.of(
                      "api.url",
                      base(),
                      "organization",
                      "org",
                      "repo",
                      "repo",
                      "filepath",
                      "dir/a file.csv",
                      "auth.username",
                      "test",
                      "auth.password",
                      "pw"))
              .withFallback(defaults()));
      source.fetch();
      assertEquals(
          "/repositories/org/repo/src/master/dir/a%20file.csv", requestUri.get().getRawPath());
      assertEquals(basic, requestHeaders.get().getFirst("Authorization"));
    }
  }

  @Test
  void gitJsonWithoutContentAndInvalidBase64Fail() throws Exception {
    try (var source = new GitHubSourceAcl(parsers)) {
      source.configure(
          ConfigFactory.parseMap(
                  Map.of(
                      "hostname",
                      base(),
                      "user",
                      "u",
                      "repo",
                      "r",
                      "filepath",
                      "a.csv",
                      "branch",
                      "main"))
              .withFallback(defaults()));
      body.set("{}");
      assertThrows(Exception.class, source::fetch);
      body.set("{\"content\":\"%%%\"}");
      assertThrows(Exception.class, source::fetch);
    }
  }

  @Test
  void fileReplacementOlderTimestampDeletionAndMalformedUtf8() throws Exception {
    Path file = temporary.resolve("policy.csv");
    Files.writeString(file, ParserTest.HEADER + ParserTest.ROW);
    try (var source = new FileSourceAcl(parsers)) {
      source.configure(
          ConfigFactory.parseMap(Map.of("filename", file.toString())).withFallback(defaults()));
      assertTrue(source.fetch().isPresent());
      Files.writeString(file, ParserTest.HEADER);
      Files.setLastModifiedTime(file, FileTime.fromMillis(1));
      assertTrue(source.fetch().orElseThrow().parser().parse(Files.readString(file)).isEmpty());
      Files.write(file, new byte[] {(byte) 0xff});
      assertThrows(Exception.class, source::fetch);
      Files.delete(file);
      assertThrows(Exception.class, source::fetch);
    }
  }

  @Test
  void s3UsesOneClientAndOnlyAcknowledgesValidSnapshot() throws Exception {
    System.setProperty("aws.accessKeyId", "local-test-key");
    System.setProperty("aws.secretAccessKey", "local-test-secret");
    try (var source = new S3SourceAcl(parsers)) {
      source.configure(
          ConfigFactory.parseMap(
                  Map.of(
                      "region",
                      "us-east-1",
                      "bucketname",
                      "acl-bucket",
                      "objectkey",
                      "dir/a.csv",
                      "endpoint",
                      base()))
              .withFallback(defaults()));
      var first = source.fetch().orElseThrow();
      assertEquals(Set.of(ParserTest.read("orders")), first.parser().parse(first.document()));
      assertNull(requestHeaders.get().getFirst("If-None-Match"));
      assertTrue(requestHeaders.get().getFirst("Authorization").startsWith("AWS4-HMAC-SHA256"));
      first.accept().run();
      status.set(304);
      assertTrue(source.fetch().isEmpty());
      assertEquals("\"v1\"", requestHeaders.get().getFirst("If-None-Match"));
    } finally {
      System.clearProperty("aws.accessKeyId");
      System.clearProperty("aws.secretAccessKey");
    }
  }

  @Test
  void googleAcceptsAnyIdTokenProviderAndRefreshesItsCachedToken() throws Exception {
    AtomicInteger issued = new AtomicInteger();
    IdTokenProvider provider =
        (audience, options) -> {
          issued.incrementAndGet();
          assertEquals("test-audience", audience);
          String header =
              Base64.getUrlEncoder()
                  .withoutPadding()
                  .encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
          String payload =
              Base64.getUrlEncoder()
                  .withoutPadding()
                  .encodeToString(
                      ("{\"aud\":\"test-audience\",\"exp\":"
                              + (Instant.now().getEpochSecond() + 3600)
                              + ",\"iat\":"
                              + Instant.now().getEpochSecond()
                              + "}")
                          .getBytes(StandardCharsets.UTF_8));
          return IdToken.create(header + "." + payload + ".c2ln");
        };
    var credentials = GoogleIAM.credentials(provider, "test-audience");
    assertTrue(
        credentials.getRequestMetadata().get("Authorization").getFirst().startsWith("Bearer "));
    credentials.getRequestMetadata();
    assertEquals(1, issued.get());
    credentials.refresh();
    assertEquals(2, issued.get());
  }

  @Test
  void unknownAuthenticationDoesNotFallBackToAnonymous() {
    assertThrows(
        IllegalArgumentException.class, () -> http(ConfigFactory.parseString("auth.type=typo")));
  }
}
