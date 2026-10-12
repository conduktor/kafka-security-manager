package io.conduktor.ksm.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import io.conduktor.ksm.ReconciliationPlan;
import io.conduktor.ksm.parser.Acls;
import io.conduktor.ksm.source.HttpTransport;
import java.net.URI;
import java.time.Duration;
import java.util.*;

public final class SlackNotification implements Notification {
  private Config config;
  private URI webhook;
  private HttpTransport http;

  public String configPrefix() {
    return "slack";
  }

  public void configure(Config config) {
    this.config = config;
    webhook = URI.create(config.getString("webhook"));
    http = new HttpTransport(Duration.ofMillis(config.getLong("request.timeout.ms")), 65536);
  }

  public void success(ReconciliationPlan plan) throws Exception {
    List<String> lines = new ArrayList<>();
    plan.added().stream().sorted(Acls.ORDER).forEach(a -> lines.add("ADDED " + a));
    plan.removed().stream().sorted(Acls.ORDER).forEach(a -> lines.add("REMOVED " + a));
    for (int i = 0; i < lines.size(); i += 50)
      send(String.join("\n", lines.subList(i, Math.min(i + 50, lines.size()))));
  }

  public void failure(String description) throws Exception {
    send(description);
  }

  private void send(String text) throws Exception {
    var payload =
        new ObjectMapper()
            .createObjectNode()
            .put("text", text)
            .put("username", config.getString("username"))
            .put("channel", config.getString("channel"))
            .put("icon_url", config.getString("icon"));
    int status =
        http.send(
                webhook,
                "POST",
                Map.of("Content-Type", "application/json"),
                new ObjectMapper().writeValueAsBytes(payload))
            .statusCode();
    if (status != 200) throw new java.io.IOException("Slack returned HTTP " + status);
  }

  public void close() {
    if (http != null) http.close();
  }
}
