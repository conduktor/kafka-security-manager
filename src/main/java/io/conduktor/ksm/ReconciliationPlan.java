package io.conduktor.ksm;

import java.util.*;
import org.apache.kafka.common.acl.AclBinding;

public record ReconciliationPlan(Set<AclBinding> added, Set<AclBinding> removed) {
  public ReconciliationPlan {
    added = Set.copyOf(added);
    removed = Set.copyOf(removed);
  }

  public static ReconciliationPlan between(Set<AclBinding> desired, Set<AclBinding> observed) {
    Set<AclBinding> added = new HashSet<>(desired), removed = new HashSet<>(observed);
    added.removeAll(observed);
    removed.removeAll(desired);
    return new ReconciliationPlan(added, removed);
  }

  public boolean isEmpty() {
    return added.isEmpty() && removed.isEmpty();
  }
}
