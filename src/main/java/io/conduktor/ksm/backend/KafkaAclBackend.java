package io.conduktor.ksm.backend;

import io.conduktor.ksm.parser.Acls;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.acl.*;

/** Uses only public Kafka client APIs. The broker owns metadata storage. */
public final class KafkaAclBackend implements AclBackend {
  private final Admin admin;
  private final long timeoutMillis;

  public KafkaAclBackend(Map<String, Object> properties) {
    Map<String, Object> config = new HashMap<>(properties);
    config.putIfAbsent("default.api.timeout.ms", "15000");
    config.putIfAbsent("request.timeout.ms", "10000");
    timeoutMillis = Long.parseLong(config.get("default.api.timeout.ms").toString());
    if (timeoutMillis <= 0)
      throw new IllegalArgumentException("default.api.timeout.ms must be positive");
    admin = Admin.create(config);
  }

  public Set<AclBinding> list() throws Exception {
    Set<AclBinding> result =
        Set.copyOf(
            admin
                .describeAcls(AclBindingFilter.ANY)
                .values()
                .get(timeoutMillis, TimeUnit.MILLISECONDS));
    result.forEach(Acls::validate);
    return result;
  }

  public void create(Set<AclBinding> acls) throws Exception {
    if (acls.isEmpty()) return;
    acls.forEach(Acls::validate);
    collect("create", admin.createAcls(acls).values(), false);
  }

  public void delete(Set<AclBinding> acls) throws Exception {
    if (acls.isEmpty()) return;
    acls.forEach(Acls::validate);
    collect(
        "delete",
        admin.deleteAcls(acls.stream().map(AclBinding::toFilter).toList()).values(),
        true);
  }

  private void collect(String operation, Map<?, ? extends KafkaFuture<?>> futures, boolean deletion)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    List<String> failures = new ArrayList<>();
    for (var item : futures.entrySet()) {
      try {
        Object result =
            item.getValue().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        if (deletion) {
          for (var deleted : ((DeleteAclsResult.FilterResults) result).values())
            if (deleted.exception() != null)
              failures.add(
                  deleted.binding() + ": " + deleted.exception().getClass().getSimpleName());
        }
      } catch (ExecutionException | TimeoutException e) {
        failures.add(
            item.getKey()
                + ": "
                + (e.getCause() == null ? e : e.getCause()).getClass().getSimpleName());
      }
    }
    if (!failures.isEmpty())
      throw new IllegalStateException(
          "Kafka "
              + operation
              + " partially failed; re-read before retrying: "
              + String.join("; ", failures));
  }

  public void close() {
    admin.close(Duration.ofSeconds(5));
  }
}
