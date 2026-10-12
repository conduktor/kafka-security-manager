package io.conduktor.ksm;

import static org.junit.jupiter.api.Assertions.*;

import com.typesafe.config.Config;
import io.conduktor.ksm.parser.AclParserRegistry;
import io.conduktor.ksm.source.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessTest {
  @TempDir Path temp;
  int id;

  record Result(int code, String out, String err) {}

  record Run(Process process, Path out, Path err) {}

  Run start(
      String config, Map<String, String> environment, List<String> javaOptions, String... args)
      throws Exception {
    Path file = temp.resolve("config-" + (++id) + ".conf");
    Files.writeString(file, config);
    Path out = temp.resolve(id + ".out"), err = temp.resolve(id + ".err");
    List<String> command =
        new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString()));
    command.add("-Dconfig.file=" + file);
    command.addAll(javaOptions);
    command.addAll(
        List.of(
            "-cp",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            KafkaSecurityManager.class.getName()));
    command.addAll(List.of(args));
    var builder =
        new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile());
    builder
        .environment()
        .keySet()
        .removeIf(
            k ->
                k.startsWith("KSM_")
                    || k.startsWith("SOURCE_")
                    || k.startsWith("ADMIN_CLIENT_")
                    || k.startsWith("AUTHORIZER_")
                    || k.startsWith("NOTIFICATION_")
                    || k.equals("JAVA_TOOL_OPTIONS")
                    || k.equals("JDK_JAVA_OPTIONS"));
    builder.environment().putAll(environment);
    return new Run(builder.start(), out, err);
  }

  Result invoke(
      String config, Map<String, String> environment, List<String> javaOptions, String... args)
      throws Exception {
    var run = start(config, environment, javaOptions, args);
    var process = run.process();
    try {
      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Process did not exit");
      return new Result(
          process.exitValue(), Files.readString(run.out()), Files.readString(run.err()));
    } finally {
      if (process.isAlive()) process.destroyForcibly().waitFor();
    }
  }

  @Test
  void minimalExternalConfigFallsBackToDefaultsAndReadonlyDoesNotConnect() throws Exception {
    var result = invoke("ksm.refresh.frequency.ms=0", Map.of(), List.of());
    assertEquals(0, result.code);
    assertEquals("", result.out);
    assertTrue(result.err.contains("READONLY"));
  }

  @Test
  void environmentAndSystemPropertyPrecedence() throws Exception {
    var env = invoke("", Map.of("KSM_REFRESH_FREQUENCY_MS", "0"), List.of());
    assertEquals(0, env.code);
    var system =
        invoke(
            "ksm { readonly=false, refresh.frequency.ms=0 }",
            Map.of(),
            List.of("-Dksm.readonly=true"));
    assertEquals(0, system.code);
  }

  @Test
  void helpBadFlagsAndObsoleteEnvironmentHaveExplicitCodes() throws Exception {
    assertEquals(0, invoke("", Map.of(), List.of(), "--help").code);
    assertEquals(2, invoke("", Map.of(), List.of(), "--wrong").code);
    var obsolete = invoke("", Map.of("AUTHORIZER_ZOOKEEPER_CONNECT", "obsolete:2181"), List.of());
    assertEquals(2, obsolete.code);
    assertTrue(obsolete.err.contains("Kafka Admin API"));
    assertEquals(2, invoke("", Map.of("KSM_EXTRACT", "true"), List.of()).code);
    assertEquals(2, invoke("ksm.extract.enable=true", Map.of(), List.of(), "--dry-run").code);
  }

  @Test
  void failedRunOnceAndMissingSourceDoNotExitSuccessfully() throws Exception {
    var result =
        invoke(
            """
                ksm { readonly=false, refresh.frequency.ms=0 }
                authorizer.admin-client-config."bootstrap.servers"="127.0.0.1:1"
                source.class="io.conduktor.ksm.source.FileSourceAcl"
                source.file.filename="/file-that-does-not-exist-ksm-test"
                """,
            Map.of(),
            List.of());
    assertEquals(1, result.code);
    assertTrue(result.err.contains("reading source"));
  }

  @Test
  void invalidModeConfigurationFailsBeforeConnectingAndDoesNotEchoValues() throws Exception {
    var invalid = invoke("ksm.readonly=\"sensitive-invalid-value\"", Map.of(), List.of());
    assertEquals(2, invalid.code);
    assertFalse(invalid.err.contains("sensitive-invalid-value"));
    assertEquals(2, invoke("ksm.cycle.timeout.ms=0", Map.of(), List.of()).code);
  }

  @Test
  void cycleDeadlineBoundsCustomSourceAndStopsProcess() throws Exception {
    var result =
        invoke(
            """
                ksm { readonly=false, refresh.frequency.ms=10, cycle.timeout.ms=150 }
                authorizer.admin-client-config."bootstrap.servers"="127.0.0.1:1"
                source.class="io.conduktor.ksm.ProcessTest$SleepingSource"
                """,
            Map.of(),
            List.of());
    assertEquals(1, result.code);
    assertTrue(result.err.contains("Cycle exceeded"));
  }

  public static final class SleepingSource implements SourceAcl {
    public SleepingSource(AclParserRegistry ignored) {}

    public String configPrefix() {
      return "sleep";
    }

    public void configure(Config ignored) {}

    public Optional<SourceSnapshot> fetch() throws Exception {
      Thread.sleep(30000);
      return Optional.empty();
    }
  }

  @Test
  void sigtermWaitsForResourceCleanup() throws Exception {
    Path marker = temp.resolve("source");
    var run =
        start(
            """
        ksm { readonly=false, refresh.frequency.ms=10 }
        authorizer.admin-client-config."bootstrap.servers"="127.0.0.1:1"
        source.class="io.conduktor.ksm.ProcessTest$ClosingSource"
        """,
            Map.of(),
            List.of("-Dtest.marker=" + marker));
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (!Files.exists(Path.of(marker + ".ready")) && System.nanoTime() < deadline) {
        assertTrue(run.process().isAlive(), Files.readString(run.err()));
        Thread.sleep(10);
      }
      assertTrue(Files.exists(Path.of(marker + ".ready")));
      run.process().destroy();
      assertTrue(run.process().waitFor(10, TimeUnit.SECONDS), "Shutdown must be bounded");
      assertTrue(
          Files.exists(Path.of(marker + ".closed")),
          "Source cleanup was interrupted by JVM shutdown");
    } finally {
      if (run.process().isAlive()) run.process().destroyForcibly().waitFor();
    }
  }

  public static final class ClosingSource implements SourceAcl {
    public ClosingSource(AclParserRegistry ignored) {}

    public String configPrefix() {
      return "closing";
    }

    public void configure(Config ignored) {}

    public Optional<SourceSnapshot> fetch() throws Exception {
      Files.writeString(Path.of(System.getProperty("test.marker") + ".ready"), "ready");
      Thread.sleep(30000);
      return Optional.empty();
    }

    public void close() {
      try {
        Thread.interrupted();
        Thread.sleep(250);
        Files.writeString(Path.of(System.getProperty("test.marker") + ".closed"), "closed");
      } catch (Exception failure) {
        throw new IllegalStateException(failure);
      }
    }
  }
}
