package io.conduktor.ksm;

import com.typesafe.config.*;
import io.conduktor.ksm.parser.Acls;
import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.*;

public final class KafkaSecurityManager {
  private static final Logger LOG = LoggerFactory.getLogger(KafkaSecurityManager.class);

  private KafkaSecurityManager() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  public static int run(String[] args, PrintStream out, PrintStream err) {
    if (Arrays.equals(args, new String[] {"--help"})) {
      out.println(
          "Usage: kafka-security-manager [--dry-run]\nConfigure with environment variables or -Dconfig.file=...; see README.md.\n--dry-run validates the source and prints the ACL delta without modifying Kafka.");
      return 0;
    }
    boolean dryRun = Arrays.equals(args, new String[] {"--dry-run"});
    if (args.length != 0 && !dryRun) {
      err.println("Unknown arguments; use --help");
      return 2;
    }
    if (System.getenv("KSM_EXTRACT") != null) {
      err.println("KSM_EXTRACT was renamed to KSM_EXTRACT_ENABLE");
      return 2;
    }
    for (String variable :
        List.of(
            "AUTHORIZER_ZOOKEEPER_CONNECT",
            "AUTHORIZER_ZOOKEEPER_SET_ACL",
            "AUTHORIZER_CONNECTION_TIMEOUT_MS",
            "AUTHORIZER_SESSION_TIMEOUT_MS")) {
      if (System.getenv(variable) != null) {
        err.println(variable + " is obsolete; configure Kafka Admin API. See docs/migration.md.");
        return 2;
      }
    }
    AppConfig app;
    try {
      app = new AppConfig(ConfigFactory.load());
    } catch (ConfigException e) {
      err.println(
          "Invalid configuration ("
              + e.getClass().getSimpleName()
              + "); check the reference configuration");
      return 2;
    } catch (IllegalArgumentException e) {
      err.println(e.getMessage());
      return 2;
    }
    Config c = app.config;
    if (c.getBoolean("ksm.debug")) {
      ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("io.conduktor.ksm"))
          .setLevel(ch.qos.logback.classic.Level.DEBUG);
    }
    boolean extract = c.getBoolean("ksm.extract.enable");
    if (dryRun && extract) {
      err.println("--dry-run cannot be combined with extraction");
      return 2;
    }
    if (!dryRun && !extract && c.getBoolean("ksm.readonly")) {
      LOG.info("READONLY: synchronization disabled. Use --dry-run to validate and preview.");
      try {
        if (c.getLong("ksm.refresh.frequency.ms") > 0) new CountDownLatch(1).await();
        return 0;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return 0;
      }
    }
    if (extract) {
      try (var backend = app.backend()) {
        String output =
            app.parsers.getParser(c.getString("ksm.extract.format")).format(backend.list());
        out.print(output);
        return 0;
      } catch (Exception e) {
        err.println("Extraction failed (" + e.getClass().getSimpleName() + ")");
        return 1;
      }
    }
    AtomicBoolean stopping = new AtomicBoolean();
    CountDownLatch cleanupFinished = new CountDownLatch(1);
    Thread main = Thread.currentThread();
    ExecutorService worker =
        Executors.newSingleThreadExecutor(Thread.ofVirtual().name("ksm-cycle").factory());
    Thread shutdown =
        new Thread(
            () -> {
              stopping.set(true);
              worker.shutdownNow();
              main.interrupt();
              try {
                if (!cleanupFinished.await(7, TimeUnit.SECONDS))
                  LOG.warn("Shutdown cleanup did not finish within seven seconds");
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            },
            "ksm-stop");
    Runtime.getRuntime().addShutdownHook(shutdown);
    try (var source = app.source();
        var notification = app.notification();
        var backend = app.backend()) {
      var synchronizer =
          new AclSynchronizer(
              backend,
              source,
              notification,
              c.getInt("ksm.num.failed.refreshes.before.notification"));
      long frequency = c.getLong("ksm.refresh.frequency.ms");
      long cycleTimeout = c.getLong("ksm.cycle.timeout.ms");
      if (cycleTimeout < 1)
        throw new IllegalArgumentException("ksm.cycle.timeout.ms must be positive");
      do {
        Future<ReconciliationPlan> cycle = worker.submit(() -> synchronizer.cycle(dryRun));
        try {
          ReconciliationPlan plan = cycle.get(cycleTimeout, TimeUnit.MILLISECONDS);
          if (dryRun) {
            plan.added().stream().sorted(Acls.ORDER).forEach(a -> out.println("ADD " + a));
            plan.removed().stream().sorted(Acls.ORDER).forEach(a -> out.println("REMOVE " + a));
          }
        } catch (ExecutionException e) {
          LOG.error(
              "Cycle failed: {}; next attempt re-reads source and Kafka",
              e.getCause().getMessage());
          if (frequency <= 0 || dryRun) return 1;
        } catch (TimeoutException e) {
          cycle.cancel(true);
          LOG.error(
              "Cycle exceeded ksm.cycle.timeout.ms; exiting to prevent overlapping or abandoned work");
          return 1;
        }
        if (frequency <= 0 || dryRun) return 0;
        Thread.sleep(frequency);
      } while (!stopping.get());
      return 0;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return stopping.get() ? 0 : 1;
    } catch (Exception e) {
      LOG.error(
          "KSM failed ({}); check source, notification and Kafka configuration",
          e.getClass().getSimpleName());
      return 1;
    } finally {
      worker.shutdownNow();
      cleanupFinished.countDown();
      try {
        Runtime.getRuntime().removeShutdownHook(shutdown);
      } catch (IllegalStateException ignored) {
      }
    }
  }
}
