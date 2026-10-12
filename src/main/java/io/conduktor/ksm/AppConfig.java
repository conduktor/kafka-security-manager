package io.conduktor.ksm;

import com.typesafe.config.*;
import io.conduktor.ksm.backend.*;
import io.conduktor.ksm.notification.Notification;
import io.conduktor.ksm.parser.AclParserRegistry;
import io.conduktor.ksm.source.SourceAcl;
import java.util.*;

public final class AppConfig {
  public final Config config;
  public final AclParserRegistry parsers;

  public AppConfig(Config config) {
    this.config = config;
    // Validate mode settings before opening any external resource.
    config.getBoolean("ksm.readonly");
    config.getBoolean("ksm.debug");
    config.getBoolean("ksm.extract.enable");
    config.getString("ksm.extract.format");
    config.getLong("ksm.refresh.frequency.ms");
    config.getInt("ksm.num.failed.refreshes.before.notification");
    if (config.getLong("ksm.cycle.timeout.ms") < 1)
      throw new IllegalArgumentException("ksm.cycle.timeout.ms must be positive");
    String delimiter = config.getString("parser.csv.delimiter");
    if (delimiter.length() != 1)
      throw new IllegalArgumentException("CSV delimiter must be one character");
    parsers = new AclParserRegistry(delimiter.charAt(0));
    String authorizer = config.getString("authorizer.class");
    if (!Set.of(
            "io.conduktor.ksm.compat.AdminClientAuthorizer",
            "io.conduktor.ksm.backend.KafkaAclBackend")
        .contains(authorizer))
      throw new IllegalArgumentException(
          "KSM 2 uses Kafka Admin API only. Remove AUTHORIZER_CLASS and configure ADMIN_CLIENT_BOOTSTRAP_SERVERS and Kafka credentials. See docs/migration.md.");
    if (config.hasPath("authorizer.config") && !config.getConfig("authorizer.config").isEmpty())
      throw new IllegalArgumentException(
          "authorizer.config is no longer used. Migrate to authorizer.admin-client-config; see docs/migration.md.");
  }

  public AclBackend backend() {
    Map<String, Object> properties =
        new HashMap<>(config.getConfig("authorizer.admin-client-config").root().unwrapped());
    if (!properties.containsKey("bootstrap.servers")
        || properties.get("bootstrap.servers").toString().isBlank())
      throw new IllegalArgumentException(
          "Set ADMIN_CLIENT_BOOTSTRAP_SERVERS or authorizer.admin-client-config.\"bootstrap.servers\"");
    return new KafkaAclBackend(properties);
  }

  public SourceAcl source() throws Exception {
    String name = config.getString("source.class");
    Class<? extends SourceAcl> type = Class.forName(name).asSubclass(SourceAcl.class);
    SourceAcl source = type.getConstructor(AclParserRegistry.class).newInstance(parsers);
    try {
      Config section =
          config.hasPath("source." + source.configPrefix())
              ? config.getConfig("source." + source.configPrefix())
              : ConfigFactory.empty();
      section = section.withFallback(config.getConfig("source.defaults"));
      validateLimits(section);
      source.configure(section);
      return source;
    } catch (Exception e) {
      source.close();
      throw e;
    }
  }

  public Notification notification() throws Exception {
    Notification notification =
        Class.forName(config.getString("notification.class"))
            .asSubclass(Notification.class)
            .getConstructor()
            .newInstance();
    try {
      notification.configure(
          config
              .getConfig("notification." + notification.configPrefix())
              .withFallback(config.getConfig("notification.defaults")));
      return notification;
    } catch (Exception e) {
      notification.close();
      throw e;
    }
  }

  private static void validateLimits(Config c) {
    int max = c.getInt("max.bytes");
    if (max < 1 || max > 64 * 1024 * 1024 || c.getLong("request.timeout.ms") < 1)
      throw new IllegalArgumentException(
          "Source limits require max.bytes in [1,67108864] and positive request.timeout.ms");
  }
}
