package io.conduktor.ksm;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.typesafe.config.ConfigFactory;
import io.conduktor.ksm.notification.SlackNotification;
import io.conduktor.ksm.parser.csv.CsvAclParser;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class NotificationTest {
  @Test
  void slackReceivesValidJsonForChangesAndFailureAndRejectsNonSuccess() throws Exception {
    var requests = new CopyOnWriteArrayList<String>();
    var status = new java.util.concurrent.atomic.AtomicInteger(200);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/fake-webhook",
        exchange -> {
          assertEquals("POST", exchange.getRequestMethod());
          assertEquals("application/json", exchange.getRequestHeaders().getFirst("Content-Type"));
          requests.add(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          exchange.sendResponseHeaders(status.get(), -1);
          exchange.close();
        });
    server.start();
    try (var notification = new SlackNotification()) {
      notification.configure(
          ConfigFactory.parseMap(
              Map.of(
                  "webhook", "http://127.0.0.1:" + server.getAddress().getPort() + "/fake-webhook",
                  "request.timeout.ms", 1000,
                  "username", "Kafka \"Security\" Manager",
                  "icon", "https://example.invalid/icon.png",
                  "channel", "acl-changes")));
      var parser = new CsvAclParser();
      var added =
          parser.parse(
              "KafkaPrincipal,ResourceType,ResourceName,Operation,PermissionType,Host\n"
                  + "\"User:CN=Alice,O=Example\",Topic,orders,Read,Allow,*\n");
      var removed =
          parser.parse(
              "KafkaPrincipal,ResourceType,ResourceName,Operation,PermissionType,Host\n"
                  + "User:bob,Group,old-reader,Read,Allow,*\n");
      notification.success(new ReconciliationPlan(added, removed));
      var json = new ObjectMapper().readTree(requests.getFirst());
      assertEquals("Kafka \"Security\" Manager", json.get("username").asText());
      assertEquals("acl-changes", json.get("channel").asText());
      assertTrue(json.get("text").asText().contains("ADDED"));
      assertTrue(json.get("text").asText().contains("REMOVED"));
      assertTrue(json.get("text").asText().contains("User:CN=Alice,O=Example"));
      notification.failure("Source failed\nTry again");
      assertEquals(
          "Source failed\nTry again",
          new ObjectMapper().readTree(requests.get(1)).get("text").asText());
      status.set(503);
      var error = assertThrows(java.io.IOException.class, () -> notification.failure("failure"));
      assertTrue(error.getMessage().contains("503"));
      assertFalse(error.getMessage().contains("fake-webhook"));
    } finally {
      server.stop(0);
    }
  }
}
