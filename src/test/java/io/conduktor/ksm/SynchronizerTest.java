package io.conduktor.ksm;

import static org.junit.jupiter.api.Assertions.*;

import com.typesafe.config.Config;
import io.conduktor.ksm.backend.AclBackend;
import io.conduktor.ksm.notification.Notification;
import io.conduktor.ksm.parser.csv.CsvAclParser;
import io.conduktor.ksm.source.*;
import java.io.IOException;
import java.util.*;
import org.apache.kafka.common.acl.AclBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SynchronizerTest {
  static class Backend implements AclBackend {
    Set<AclBinding> state = new HashSet<>();
    int reads, creates, deletes;
    boolean partialFailure, partialDeleteFailure, readFailure;

    public Set<AclBinding> list() throws Exception {
      reads++;
      if (readFailure) throw new IOException();
      return Set.copyOf(state);
    }

    public void create(Set<AclBinding> acls) throws Exception {
      creates += acls.size();
      for (var acl : acls) {
        state.add(acl);
        if (partialFailure) throw new IOException("Partial mutation");
      }
    }

    public void delete(Set<AclBinding> acls) throws Exception {
      deletes += acls.size();
      for (var acl : acls) {
        state.remove(acl);
        if (partialDeleteFailure) throw new IOException("Partial deletion");
      }
    }
  }

  static class Source implements SourceAcl {
    String document = ParserTest.HEADER + ParserTest.ROW;
    boolean unchanged, unavailable;
    int accepted;

    public String configPrefix() {
      return "test";
    }

    public void configure(Config ignored) {}

    public Optional<SourceSnapshot> fetch() throws Exception {
      if (unavailable) throw new IOException("Unavailable");
      return unchanged
          ? Optional.empty()
          : Optional.of(new SourceSnapshot(new CsvAclParser(), document, () -> accepted++));
    }
  }

  static class Notice implements Notification {
    int successes, failures;
    boolean broken;

    public String configPrefix() {
      return "test";
    }

    public void configure(Config ignored) {}

    public void success(ReconciliationPlan plan) throws Exception {
      successes++;
      if (broken) throw new IOException();
    }

    public void failure(String message) throws Exception {
      failures++;
      if (broken) throw new IOException();
    }
  }

  @Test
  void initialSyncIdempotenceDriftAndRemoval() throws Exception {
    var b = new Backend();
    var s = new Source();
    var n = new Notice();
    var engine = new AclSynchronizer(b, s, n, 1);
    engine.cycle(false);
    assertEquals(Set.of(ParserTest.read("orders")), b.state);
    assertTrue(engine.cycle(false).isEmpty());
    s.unchanged = true;
    b.state.add(ParserTest.read("intruder"));
    b.state.remove(ParserTest.read("orders"));
    engine.cycle(false);
    assertEquals(Set.of(ParserTest.read("orders")), b.state);
    s.unchanged = false;
    s.document = ParserTest.HEADER;
    engine.cycle(false);
    assertTrue(b.state.isEmpty());
    assertEquals(3, n.successes);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "<html>error</html>",
        "nonsense\n",
        "KafkaPrincipal,ResourceType\n",
        "users: {alice: {topic: {foo: [Read]}}}"
      })
  void badDocumentNeverReadsOrWritesKafkaOrAcceptsCacheToken(String document) {
    var b = new Backend();
    b.state.add(ParserTest.read("existing"));
    var s = new Source();
    s.document = document;
    var engine = new AclSynchronizer(b, s, new Notice(), 1);
    assertThrows(Exception.class, () -> engine.cycle(false));
    assertEquals(0, s.accepted);
    assertEquals(0, b.reads);
    assertEquals(0, b.creates + b.deletes);
    assertEquals(Set.of(ParserTest.read("existing")), b.state);
  }

  @Test
  void invalidThenCorrectedSnapshotRecovers() throws Exception {
    var b = new Backend();
    var s = new Source();
    var engine = new AclSynchronizer(b, s, new Notice(), 1);
    s.document = "";
    assertThrows(Exception.class, () -> engine.cycle(false));
    s.document = ParserTest.HEADER + ParserTest.ROW;
    engine.cycle(false);
    assertEquals(1, s.accepted);
    assertEquals(Set.of(ParserTest.read("orders")), b.state);
  }

  @Test
  void partialApplicationRetainsSnapshotAndRecomputesBeforeDeleting() throws Exception {
    var b = new Backend();
    b.state.add(ParserTest.read("old"));
    b.partialFailure = true;
    var s = new Source();
    var engine = new AclSynchronizer(b, s, new Notice(), 1);
    assertThrows(Exception.class, () -> engine.cycle(false));
    assertEquals(0, b.deletes);
    assertEquals(1, s.accepted);
    s.unchanged = true;
    b.partialFailure = false;
    engine.cycle(false);
    assertEquals(Set.of(ParserTest.read("orders")), b.state);
    assertEquals(2, b.reads);
  }

  @Test
  void partialDeletionReportsFailureAndNextCycleRecomputesFromKafka() throws Exception {
    var b = new Backend();
    b.state.addAll(Set.of(ParserTest.read("old-one"), ParserTest.read("old-two")));
    b.partialDeleteFailure = true;
    var s = new Source();
    var n = new Notice();
    var engine = new AclSynchronizer(b, s, n, 1);
    assertThrows(Exception.class, () -> engine.cycle(false));
    assertEquals(2, b.state.size());
    assertTrue(b.state.contains(ParserTest.read("orders")));
    assertEquals(0, n.successes);
    assertEquals(1, n.failures);
    b.partialDeleteFailure = false;
    s.unchanged = true;
    var retry = engine.cycle(false);
    assertTrue(retry.added().isEmpty());
    assertEquals(1, retry.removed().size());
    assertEquals(2, b.reads);
    assertEquals(Set.of(ParserTest.read("orders")), b.state);
    assertEquals(1, n.successes);
  }

  @Test
  void sourceFailureDoesNotApplyCachedPolicyAndNotificationThresholdResets() throws Exception {
    var b = new Backend();
    var s = new Source();
    var n = new Notice();
    var engine = new AclSynchronizer(b, s, n, 2);
    engine.cycle(false);
    b.state.clear();
    s.unavailable = true;
    assertThrows(Exception.class, () -> engine.cycle(false));
    assertEquals(0, n.failures);
    assertThrows(Exception.class, () -> engine.cycle(false));
    assertEquals(1, n.failures);
    assertEquals(1, b.reads);
    assertTrue(b.state.isEmpty());
    s.unavailable = false;
    engine.cycle(false);
    s.unavailable = true;
    assertThrows(Exception.class, () -> engine.cycle(false));
    assertEquals(1, n.failures);
  }

  @Test
  void dryRunNeverMutatesAndBrokenNotificationDoesNotFailAppliedCycle() throws Exception {
    var b = new Backend();
    var s = new Source();
    var n = new Notice();
    n.broken = true;
    var engine = new AclSynchronizer(b, s, n, 1);
    assertEquals(1, engine.cycle(true).added().size());
    assertEquals(0, b.creates + b.deletes);
    assertEquals(0, n.successes);
    assertDoesNotThrow(() -> engine.cycle(false));
    assertEquals(Set.of(ParserTest.read("orders")), b.state);
  }

  @Test
  void firstUnchangedAndReadFailureNeverDelete() {
    var b = new Backend();
    var s = new Source();
    s.unchanged = true;
    var engine = new AclSynchronizer(b, s, new Notice(), 1);
    assertThrows(Exception.class, () -> engine.cycle(false));
    assertEquals(0, b.reads);
    s.unchanged = false;
    b.readFailure = true;
    assertThrows(Exception.class, () -> engine.cycle(false));
    assertEquals(0, b.creates + b.deletes);
  }

  @Test
  void setPlanPropertiesAcrossManyPolicies() {
    Random random = new Random(4821);
    for (int iteration = 0; iteration < 300; iteration++) {
      Set<AclBinding> desired = new HashSet<>(), observed = new HashSet<>();
      for (int i = 0; i < 20; i++) {
        if (random.nextBoolean()) desired.add(ParserTest.read("t" + i));
        if (random.nextBoolean()) observed.add(ParserTest.read("t" + i));
      }
      var plan = ReconciliationPlan.between(desired, observed);
      Set<AclBinding> applied = new HashSet<>(observed);
      applied.addAll(plan.added());
      applied.removeAll(plan.removed());
      assertEquals(desired, applied);
      assertTrue(Collections.disjoint(plan.added(), plan.removed()));
      assertTrue(ReconciliationPlan.between(desired, applied).isEmpty());
    }
  }
}
