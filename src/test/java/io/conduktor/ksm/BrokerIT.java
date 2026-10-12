package io.conduktor.ksm;

import static org.junit.jupiter.api.Assertions.*;

import com.typesafe.config.*;
import com.typesafe.config.Config;
import io.conduktor.ksm.parser.csv.CsvAclParser;
import io.conduktor.ksm.parser.yaml.YamlAclParser;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.acl.*;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.resource.*;
import org.junit.jupiter.api.Test;

/** Runs the packaged KSM in child JVMs, against a broker owned by scripts/broker-tests.py. */
class BrokerIT {
  final Path directory =
      Path.of(
          Objects.requireNonNull(
              System.getProperty("ksm.it.directory"), "Use scripts/broker-tests.py"));
  final Path jar =
      Path.of(System.getProperty("ksm.it.jar", "target/kafka-security-manager-2.0.0.jar"))
          .toAbsolutePath();
  final CsvAclParser csv = new CsvAclParser();
  int invocation;

  Properties client(String user) throws Exception {
    Properties p = new Properties();
    try (var input = Files.newInputStream(directory.resolve(user + ".properties"))) {
      p.load(input);
    }
    return p;
  }

  Set<AclBinding> list(Admin admin) throws Exception {
    return Set.copyOf(admin.describeAcls(AclBindingFilter.ANY).values().get(15, TimeUnit.SECONDS));
  }

  interface Attempt {
    void run() throws Exception;
  }

  void eventually(Attempt attempt) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    Throwable last = null;
    while (System.nanoTime() < end) {
      try {
        attempt.run();
        return;
      } catch (Exception | AssertionError e) {
        last = e;
        Thread.sleep(100);
      }
    }
    throw new AssertionError("Condition not met before deadline", last);
  }

  Config settings(Path source, Properties properties) {
    Map<String, Object> values = new HashMap<>();
    properties.forEach((k, v) -> values.put(k.toString(), v));
    return ConfigFactory.load()
        .withValue("authorizer.admin-client-config", ConfigValueFactory.fromMap(values))
        .withValue("ksm.readonly", ConfigValueFactory.fromAnyRef(false))
        .withValue("ksm.refresh.frequency.ms", ConfigValueFactory.fromAnyRef(0))
        .withValue(
            "source.class", ConfigValueFactory.fromAnyRef("io.conduktor.ksm.source.FileSourceAcl"))
        .withValue("source.file.filename", ConfigValueFactory.fromAnyRef(source.toString()));
  }

  record Run(Process process, Path stdout, Path stderr) {}

  Run start(Config config, String... arguments) throws Exception {
    Path conf = directory.resolve("ksm-" + (++invocation) + ".conf");
    Files.writeString(conf, config.root().render(ConfigRenderOptions.concise()));
    List<String> command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Dconfig.file=" + conf,
                "-jar",
                jar.toString()));
    command.addAll(List.of(arguments));
    Path stdout = directory.resolve("ksm-" + invocation + ".stdout"),
        stderr = directory.resolve("ksm-" + invocation + ".stderr");
    var builder =
        new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
    builder
        .environment()
        .keySet()
        .removeIf(
            k ->
                k.startsWith("KSM_")
                    || k.startsWith("SOURCE_")
                    || k.startsWith("AUTHORIZER_")
                    || k.startsWith("ADMIN_CLIENT_")
                    || k.startsWith("NOTIFICATION_")
                    || k.equals("JAVA_TOOL_OPTIONS")
                    || k.equals("JDK_JAVA_OPTIONS"));
    return new Run(builder.start(), stdout, stderr);
  }

  Run run(Config config, int expected, String... arguments) throws Exception {
    Run result = start(config, arguments);
    try {
      assertTrue(result.process.waitFor(30, TimeUnit.SECONDS), "KSM did not terminate within 30s");
      assertEquals(expected, result.process.exitValue(), Files.readString(result.stderr));
      return result;
    } finally {
      if (result.process.isAlive()) result.process.destroyForcibly().waitFor();
    }
  }

  void write(Path path, Set<AclBinding> acls) throws Exception {
    Path candidate = path.resolveSibling(path.getFileName() + ".next");
    Files.writeString(candidate, csv.format(acls));
    Files.move(
        candidate, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  AclBinding allow(
      String user, ResourceType type, String name, PatternType pattern, AclOperation operation) {
    return ParserTest.acl(
        "User:" + user, type, name, pattern, operation, AclPermissionType.ALLOW, "*");
  }

  void produce(String topic, boolean transactional) throws Exception {
    Properties p = client("app");
    p.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
    p.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
    p.put("enable.idempotence", transactional ? "true" : "false");
    p.put("max.block.ms", "5000");
    p.put("delivery.timeout.ms", "6000");
    p.put("request.timeout.ms", "3000");
    if (transactional) p.put("transactional.id", "ksm-tx-one");
    try (var producer = new KafkaProducer<String, String>(p)) {
      if (transactional) {
        producer.initTransactions();
        producer.beginTransaction();
      }
      producer.send(new ProducerRecord<>(topic, "key", "message")).get(8, TimeUnit.SECONDS);
      if (transactional) producer.commitTransaction();
    }
  }

  void consume(String group) throws Exception {
    Properties p = client("app");
    p.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    p.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    p.put("group.id", group);
    p.put("group.protocol", "classic");
    p.put("auto.offset.reset", "earliest");
    p.put("enable.auto.commit", "false");
    try (var consumer = new KafkaConsumer<String, String>(p)) {
      consumer.subscribe(List.of("ksm-orders"));
      long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      while (System.nanoTime() < end) {
        if (!consumer.poll(Duration.ofMillis(200)).isEmpty()) {
          consumer.commitSync(Duration.ofSeconds(5));
          return;
        }
      }
      fail("No message consumed");
    }
  }

  void denied(Attempt attempt) throws Exception {
    try {
      attempt.run();
    } catch (Exception e) {
      for (Throwable t = e; t != null; t = t.getCause())
        if (t instanceof AuthorizationException) return;
      throw e;
    }
    fail("Operation unexpectedly authorized");
  }

  @Test
  void packagedKsmReconcilesAndEnforcesItsDeclaredPolicy() throws Exception {
    assertTrue(Files.isRegularFile(jar), "Package KSM before running the broker matrix");
    Properties manager = client("manager");
    String principal =
        manager.getProperty("security.protocol").equals("PLAINTEXT") ? "ANONYMOUS" : "manager";
    Set<AclBinding> management =
        Set.of(
            allow(
                principal,
                ResourceType.CLUSTER,
                "kafka-cluster",
                PatternType.LITERAL,
                AclOperation.ALTER),
            allow(
                principal,
                ResourceType.CLUSTER,
                "kafka-cluster",
                PatternType.LITERAL,
                AclOperation.DESCRIBE));
    Set<AclBinding> desired = new HashSet<>(management);
    desired.addAll(
        List.of(
            allow("app", ResourceType.TOPIC, "ksm-orders", PatternType.LITERAL, AclOperation.WRITE),
            allow("app", ResourceType.TOPIC, "ksm-", PatternType.PREFIXED, AclOperation.READ),
            allow("app", ResourceType.TOPIC, "*", PatternType.LITERAL, AclOperation.DESCRIBE),
            allow("app", ResourceType.GROUP, "ksm-group", PatternType.LITERAL, AclOperation.READ),
            allow(
                "app",
                ResourceType.TRANSACTIONAL_ID,
                "ksm-tx-",
                PatternType.PREFIXED,
                AclOperation.WRITE),
            allow(
                "app",
                ResourceType.CLUSTER,
                "kafka-cluster",
                PatternType.LITERAL,
                AclOperation.IDEMPOTENT_WRITE)));
    Path policy = directory.resolve("acls.csv");
    write(policy, desired);
    Config config = settings(policy, manager);
    try (Admin root = Admin.create(client("root"));
        Admin app = Admin.create(client("app"));
        Admin outsider = Admin.create(client("outsider"))) {
      root.createAcls(management).all().get(15, TimeUnit.SECONDS);
      root.createTopics(List.of(new NewTopic("ksm-orders", 1, (short) 1)))
          .all()
          .get(15, TimeUnit.SECONDS);
      eventually(() -> denied(() -> produce("ksm-orders", false)));
      run(config, 0, "--dry-run");
      assertEquals(management, list(root));
      run(config.withValue("ksm.readonly", ConfigValueFactory.fromAnyRef(true)), 0);
      assertEquals(management, list(root));
      run(config, 0);
      eventually(() -> assertEquals(desired, list(root)));
      Run noChange = run(config, 0);
      assertTrue(Files.readString(noChange.stderr).contains("0 additions, 0 removals"));
      eventually(() -> produce("ksm-orders", false));
      eventually(() -> produce("ksm-orders", true));
      eventually(() -> consume("ksm-group"));
      eventually(() -> denied(() -> consume("other-group")));
      denied(() -> outsider.describeAcls(AclBindingFilter.ANY).values().get(10, TimeUnit.SECONDS));
      denied(
          () ->
              outsider
                  .createAcls(List.of(ParserTest.read("outsider-topic")))
                  .all()
                  .get(10, TimeUnit.SECONDS));
      denied(
          () ->
              outsider
                  .deleteAcls(List.of(desired.iterator().next().toFilter()))
                  .all()
                  .get(10, TimeUnit.SECONDS));
      var rogue = ParserTest.read("rogue");
      root.createAcls(List.of(rogue)).all().get(15, TimeUnit.SECONDS);
      eventually(() -> assertTrue(list(root).contains(rogue)));
      run(config, 0);
      eventually(() -> assertEquals(desired, list(root)));
      Run export =
          run(config.withValue("ksm.extract.enable", ConfigValueFactory.fromAnyRef(true)), 0);
      assertEquals(desired, csv.parse(Files.readString(export.stdout)));
      Run yaml =
          run(
              config
                  .withValue("ksm.extract.enable", ConfigValueFactory.fromAnyRef(true))
                  .withValue("ksm.extract.format", ConfigValueFactory.fromAnyRef("yaml")),
              0);
      assertEquals(desired, new YamlAclParser().parse(Files.readString(yaml.stdout)));
      for (String bad : List.of("", "<html>upstream failed</html>", "broken,header\n")) {
        Files.writeString(policy, bad);
        run(config, 1);
        assertEquals(desired, list(root));
      }
      write(policy, desired);
      run(settings(policy, client("outsider")), 1);
      assertEquals(desired, list(root));
      if (manager.getProperty("security.protocol").startsWith("SASL")) {
        Properties wrong = new Properties();
        wrong.putAll(manager);
        wrong.put(
            "sasl.jaas.config",
            manager.getProperty("sasl.jaas.config").replace("local-probe", "wrong-password"));
        Run failure = run(settings(policy, wrong), 1);
        assertFalse(Files.readString(failure.stderr).contains("wrong-password"));
      }
      if (manager.getProperty("security.protocol").contains("SSL")) {
        Properties wrong = new Properties();
        wrong.putAll(manager);
        wrong.put("ssl.truststore.location", directory.resolve("bad-trust.p12").toString());
        run(settings(policy, wrong), 1);
        wrong.putAll(manager);
        wrong.put(
            "bootstrap.servers",
            manager.getProperty("bootstrap.servers").replace("127.0.0.1", "localhost"));
        run(settings(policy, wrong), 1);
      }
      var deny =
          ParserTest.acl(
              "User:app",
              ResourceType.TOPIC,
              "ksm-orders",
              PatternType.LITERAL,
              AclOperation.WRITE,
              AclPermissionType.DENY,
              "*");
      desired.add(deny);
      write(policy, desired);
      run(config, 0);
      eventually(() -> denied(() -> produce("ksm-orders", false)));
      desired.remove(deny);
      write(policy, desired);
      run(config, 0);
      eventually(() -> produce("ksm-orders", false));
      Path yamlPolicy = directory.resolve("acls.yaml");
      Files.writeString(
          yamlPolicy,
          "users:\n  "
              + principal
              + ":\n    clusters:\n      kafka-cluster: [Alter, Describe]\n  app:\n    topics:\n      ksm-helper: [Produce]\n");
      Config yamlConfig = settings(yamlPolicy, manager);
      run(yamlConfig, 0);
      eventually(
          () ->
              app.createTopics(List.of(new NewTopic("ksm-helper", 1, (short) 1)))
                  .all()
                  .get(10, TimeUnit.SECONDS));
      eventually(() -> produce("ksm-helper", false));
      denied(
          () ->
              app.createTopics(List.of(new NewTopic("not-declared", 1, (short) 1)))
                  .all()
                  .get(10, TimeUnit.SECONDS));
      Files.writeString(yamlPolicy, "users: {app: {topic: {ksm-helper: [Read]}}}");
      Set<AclBinding> beforeInvalid = list(root);
      run(yamlConfig, 1);
      assertEquals(beforeInvalid, list(root));
      Files.writeString(policy, "bad header");
      Run continuous =
          start(config.withValue("ksm.refresh.frequency.ms", ConfigValueFactory.fromAnyRef(100)));
      try {
        eventually(() -> assertTrue(Files.readString(continuous.stderr).contains("Cycle failed")));
        assertTrue(continuous.process.isAlive());
        write(policy, desired);
        eventually(() -> assertEquals(desired, list(root)));
      } finally {
        continuous.process.destroy();
        if (!continuous.process.waitFor(10, TimeUnit.SECONDS)) {
          continuous.process.destroyForcibly().waitFor();
          fail("KSM did not stop after SIGTERM");
        }
      }
      var token =
          allow(
              "app",
              ResourceType.DELEGATION_TOKEN,
              "token",
              PatternType.LITERAL,
              AclOperation.DESCRIBE);
      desired.add(token);
      String kafkaVersion =
          Objects.requireNonNull(
              System.getProperty("ksm.it.kafka.version"), "Missing fixture version");
      if (!kafkaVersion.startsWith("2.")) {
        for (var operation : List.of(AclOperation.CREATE_TOKENS, AclOperation.DESCRIBE_TOKENS)) {
          desired.add(
              allow("app", ResourceType.USER, "User:owner", PatternType.LITERAL, operation));
        }
      }
      write(policy, desired);
      run(config, 0);
      eventually(() -> assertEquals(desired, list(root)));
      Run tokenExport =
          run(config.withValue("ksm.extract.enable", ConfigValueFactory.fromAnyRef(true)), 0);
      assertEquals(desired, csv.parse(Files.readString(tokenExport.stdout)));
      Run failedExport =
          run(
              config
                  .withValue("ksm.extract.enable", ConfigValueFactory.fromAnyRef(true))
                  .withValue("ksm.extract.format", ConfigValueFactory.fromAnyRef("yaml")),
              1);
      assertEquals("", Files.readString(failedExport.stdout));
      write(policy, management);
      run(config, 0);
      eventually(() -> assertEquals(management, list(root)));
      eventually(() -> denied(() -> produce("ksm-orders", false)));
      root.deleteAcls(management.stream().map(AclBinding::toFilter).toList())
          .all()
          .get(15, TimeUnit.SECONDS);
    }
  }
}
