package io.conduktor.ksm;

import static org.junit.jupiter.api.Assertions.*;

import com.typesafe.config.*;
import io.conduktor.ksm.source.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConfigurationTest {
  @Test
  void defaultsSelectAdminApiAndNoSourceIsInstantiable() throws Exception {
    var app = new AppConfig(ConfigFactory.load());
    assertEquals(
        "io.conduktor.ksm.compat.AdminClientAuthorizer", app.config.getString("authorizer.class"));
    try (var source = app.source()) {
      assertInstanceOf(NoSourceAcl.class, source);
      assertThrows(Exception.class, source::fetch);
    }
  }

  @Test
  void legacyBackendAndMalformedLimitsAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AppConfig(
                ConfigFactory.parseString(
                        "authorizer.class=\"kafka.security.auth.SimpleAclAuthorizer\"")
                    .withFallback(ConfigFactory.load())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AppConfig(
                ConfigFactory.parseString(
                        "authorizer.config { \"zookeeper.connect\"=\"host:2181\" }")
                    .withFallback(ConfigFactory.load())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AppConfig(
                ConfigFactory.parseString("parser.csv.delimiter=\"ab\"")
                    .withFallback(ConfigFactory.load())));
  }

  @Test
  void preservedEnvironmentMappingsArePresentExceptRemovedBackend() throws Exception {
    String defaults = Files.readString(Path.of("src/main/resources/reference.conf"));
    for (String variable :
        List.of(
            "KSM_READONLY",
            "KSM_EXTRACT_ENABLE",
            "KSM_EXTRACT_FORMAT",
            "KSM_REFRESH_FREQUENCY_MS",
            "SOURCE_CLASS",
            "ADMIN_CLIENT_BOOTSTRAP_SERVERS",
            "SOURCE_GITHUB_TOKEN",
            "SOURCE_GITLAB_ACCESSTOKEN",
            "SOURCE_HTTP_AUTH_GOOGLEIAM_SERVICE_ACCOUNT_KEY",
            "NOTIFICATION_SLACK_WEBHOOK"))
      assertTrue(defaults.contains("$" + "{?" + variable + "}"), variable);
    assertFalse(defaults.contains("AUTHORIZER_ZOOKEEPER"));
  }

  @Test
  void customAdminPropertiesPassThroughIncludingQuotedDottedKeys() {
    var config =
        ConfigFactory.parseString(
                """
                authorizer.admin-client-config {
                  "bootstrap.servers"="localhost:9092"
                  "request.timeout.ms"=3210
                  "client.dns.lookup"="use_all_dns_ips"
                }
                """)
            .withFallback(ConfigFactory.load());
    var properties =
        new AppConfig(config).config.getConfig("authorizer.admin-client-config").root().unwrapped();
    assertEquals(3210, properties.get("request.timeout.ms"));
    assertEquals("use_all_dns_ips", properties.get("client.dns.lookup"));
  }
}
