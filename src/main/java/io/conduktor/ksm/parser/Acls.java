package io.conduktor.ksm.parser;

import java.util.*;
import org.apache.kafka.common.acl.*;
import org.apache.kafka.common.resource.*;

public final class Acls {
  private Acls() {}

  public static final Comparator<AclBinding> ORDER = Comparator.comparing(AclBinding::toString);

  public static <E extends Enum<E>> E value(Class<E> type, String input) {
    String normalized = input.replace("_", "").toLowerCase(Locale.ROOT);
    return Arrays.stream(type.getEnumConstants())
        .filter(v -> v.name().replace("_", "").toLowerCase(Locale.ROOT).equals(normalized))
        .findFirst()
        .orElseThrow(
            () -> new IllegalArgumentException("Invalid " + type.getSimpleName() + ": " + input));
  }

  public static String display(Enum<?> value) {
    StringBuilder result = new StringBuilder();
    for (String word : value.name().split("_"))
      result.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
    return result.toString();
  }

  public static AclBinding binding(
      String principal,
      ResourceType type,
      String name,
      PatternType pattern,
      AclOperation operation,
      AclPermissionType permission,
      String host) {
    AclBinding binding =
        new AclBinding(
            new ResourcePattern(type, name, pattern),
            new AccessControlEntry(principal, host, operation, permission));
    validate(binding);
    return binding;
  }

  public static void validate(AclBinding a) {
    var r = a.pattern();
    var e = a.entry();
    if (!Set.of(
            ResourceType.TOPIC,
            ResourceType.GROUP,
            ResourceType.CLUSTER,
            ResourceType.TRANSACTIONAL_ID,
            ResourceType.DELEGATION_TOKEN,
            ResourceType.USER)
        .contains(r.resourceType()))
      throw new IllegalArgumentException("Unsupported ACL resource type: " + r.resourceType());
    if (r.patternType() != PatternType.LITERAL && r.patternType() != PatternType.PREFIXED)
      throw new IllegalArgumentException("ACL pattern must be LITERAL or PREFIXED");
    if (r.name().isEmpty()
        || e.host().isEmpty()
        || e.principal().indexOf(':') <= 0
        || e.principal().indexOf(':') == e.principal().length() - 1)
      throw new IllegalArgumentException("Empty resource, host or invalid principal");
    if (e.operation() == AclOperation.UNKNOWN
        || e.operation() == AclOperation.ANY
        || !Set.of(AclPermissionType.ALLOW, AclPermissionType.DENY).contains(e.permissionType()))
      throw new IllegalArgumentException("Invalid ACL operation or permission");
    if (r.resourceType() == ResourceType.CLUSTER
        && (!r.name().equals("kafka-cluster") || r.patternType() != PatternType.LITERAL))
      throw new IllegalArgumentException(
          "Cluster ACLs must use the literal resource kafka-cluster");
  }
}
