package io.conduktor.ksm;

import io.conduktor.ksm.backend.AclBackend;
import io.conduktor.ksm.notification.Notification;
import io.conduktor.ksm.parser.Acls;
import io.conduktor.ksm.source.SourceAcl;
import java.util.*;
import org.apache.kafka.common.acl.AclBinding;
import org.slf4j.*;

public final class AclSynchronizer {
  private static final Logger LOG = LoggerFactory.getLogger(AclSynchronizer.class);
  private final AclBackend backend;
  private final SourceAcl source;
  private final Notification notification;
  private final int notifyAfter;
  private Set<AclBinding> cached;
  private int failures;

  public AclSynchronizer(
      AclBackend backend, SourceAcl source, Notification notification, int notifyAfter) {
    this.backend = backend;
    this.source = source;
    this.notification = notification;
    this.notifyAfter = Math.max(1, notifyAfter);
  }

  /** A source failure never triggers reconciliation using stale cached state. */
  public ReconciliationPlan cycle(boolean dryRun) throws Exception {
    String phase = "reading source";
    try {
      var candidate = source.fetch();
      if (candidate.isPresent()) {
        phase = "validating source";
        var snapshot = candidate.get();
        Set<AclBinding> parsed = Set.copyOf(snapshot.parser().parse(snapshot.document()));
        parsed.forEach(Acls::validate);
        cached = parsed;
        snapshot.accept().run();
      }
      if (cached == null)
        throw new IllegalStateException("Source returned unchanged without a validated snapshot");
      phase = "reading Kafka ACLs";
      var observed = backend.list();
      observed.forEach(Acls::validate);
      ReconciliationPlan plan = ReconciliationPlan.between(cached, observed);
      LOG.debug(
          "Validated {} desired ACLs; observed {} Kafka ACLs", cached.size(), observed.size());
      if (!dryRun) {
        // Preserve add-before-remove semantics. Never delete after an unsuccessful create batch.
        phase = "creating Kafka ACLs";
        backend.create(plan.added());
        phase = "deleting Kafka ACLs";
        backend.delete(plan.removed());
      }
      failures = 0;
      if (!dryRun && !plan.isEmpty()) {
        try {
          notification.success(plan);
        } catch (Exception e) {
          LOG.warn(
              "Notification failed ({}) after successful ACL application",
              e.getClass().getSimpleName());
        }
      }
      LOG.info(
          "{}: {} additions, {} removals",
          dryRun ? "Dry-run" : "Reconciliation succeeded",
          plan.added().size(),
          plan.removed().size());
      return plan;
    } catch (Exception error) {
      failures++;
      if (failures >= notifyAfter) {
        try {
          notification.failure(
              "Reconciliation failed while "
                  + phase
                  + " ("
                  + error.getClass().getSimpleName()
                  + ")");
        } catch (Exception ignored) {
          LOG.warn("Failure notification could not be delivered");
        }
      }
      throw new CycleException(phase, error);
    }
  }

  public static final class CycleException extends Exception {
    CycleException(String phase, Exception cause) {
      super("Failure while " + phase + " (" + cause.getClass().getSimpleName() + ")", cause);
    }
  }
}
