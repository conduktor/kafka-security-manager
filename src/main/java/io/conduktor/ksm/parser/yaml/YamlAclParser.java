package io.conduktor.ksm.parser.yaml;

import io.conduktor.ksm.parser.*;
import java.util.*;
import org.apache.kafka.common.acl.*;
import org.apache.kafka.common.resource.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class YamlAclParser implements AclParser {
  private static final Map<String, ResourceType> TYPES =
      Map.of(
          "topics",
          ResourceType.TOPIC,
          "groups",
          ResourceType.GROUP,
          "clusters",
          ResourceType.CLUSTER,
          "transactional_ids",
          ResourceType.TRANSACTIONAL_ID);

  private Yaml reader() {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(20);
    options.setCodePointLimit(16 * 1024 * 1024);
    return new Yaml(new SafeConstructor(options));
  }

  private static Map<?, ?> mapping(Object value, String where) {
    if (!(value instanceof Map<?, ?> map))
      throw new IllegalArgumentException(where + " must be a mapping");
    return map;
  }

  private static String string(Object value, String where) {
    if (!(value instanceof String text) || text.isEmpty())
      throw new IllegalArgumentException(where + " must be a non-empty string");
    return text;
  }

  @Override
  public Set<AclBinding> parse(String document) {
    try {
      Map<?, ?> root = mapping(reader().load(document), "YAML root");
      if (!root.keySet().equals(Set.of("users")))
        throw new IllegalArgumentException("YAML root must contain only users");
      Set<AclBinding> result = new HashSet<>();
      mapping(root.get("users"), "users")
          .forEach(
              (user, definition) -> {
                String principal = "User:" + string(user, "User name");
                mapping(definition, "User resources")
                    .forEach(
                        (section, resources) -> {
                          ResourceType type = TYPES.get(section);
                          if (type == null)
                            throw new IllegalArgumentException(
                                "Unknown YAML resource section: " + section);
                          mapping(resources, "Resources")
                              .forEach(
                                  (key, permissions) -> {
                                    String name = string(key, "Resource name").trim();
                                    PatternType pattern =
                                        name.endsWith("*") && !name.equals("*")
                                            ? PatternType.PREFIXED
                                            : PatternType.LITERAL;
                                    String resource =
                                        pattern == PatternType.PREFIXED
                                            ? name.substring(0, name.length() - 1)
                                            : name;
                                    if (!(permissions instanceof List<?> list))
                                      throw new IllegalArgumentException(
                                          "Resource permissions must be a list");
                                    for (Object entry : list) {
                                      String[] parts = string(entry, "Permission").split(",", -1);
                                      if (parts.length == 1
                                          && type == ResourceType.TOPIC
                                          && parts[0].equalsIgnoreCase("Consume")) {
                                        for (AclOperation op :
                                            List.of(AclOperation.DESCRIBE, AclOperation.READ))
                                          result.add(
                                              Acls.binding(
                                                  principal,
                                                  type,
                                                  resource,
                                                  pattern,
                                                  op,
                                                  AclPermissionType.ALLOW,
                                                  "*"));
                                      } else if (parts.length == 1
                                          && type == ResourceType.TOPIC
                                          && parts[0].equalsIgnoreCase("Produce")) {
                                        // Topic CREATE is sufficient. Do not silently grant
                                        // cluster-wide topic creation.
                                        for (AclOperation op :
                                            List.of(
                                                AclOperation.DESCRIBE,
                                                AclOperation.CREATE,
                                                AclOperation.WRITE))
                                          result.add(
                                              Acls.binding(
                                                  principal,
                                                  type,
                                                  resource,
                                                  pattern,
                                                  op,
                                                  AclPermissionType.ALLOW,
                                                  "*"));
                                      } else if (parts.length == 1 || parts.length == 3) {
                                        result.add(
                                            Acls.binding(
                                                principal,
                                                type,
                                                resource,
                                                pattern,
                                                Acls.value(AclOperation.class, parts[0]),
                                                parts.length == 3
                                                    ? Acls.value(AclPermissionType.class, parts[1])
                                                    : AclPermissionType.ALLOW,
                                                parts.length == 3 ? parts[2] : "*"));
                                      } else
                                        throw new IllegalArgumentException(
                                            "Permission must be an operation or operation,permission,host");
                                    }
                                  });
                        });
              });
      return Set.copyOf(result);
    } catch (org.yaml.snakeyaml.error.YAMLException e) {
      throw new IllegalArgumentException("Malformed YAML document", e);
    }
  }

  @Override
  public String format(Set<AclBinding> acls) {
    Map<String, Map<String, Map<String, List<String>>>> users = new TreeMap<>();
    for (AclBinding a : acls.stream().sorted(Acls.ORDER).toList()) {
      Acls.validate(a);
      if (!a.entry().principal().startsWith("User:"))
        throw new IllegalArgumentException(
            "YAML only represents User principals; export CSV instead");
      String section =
          TYPES.entrySet().stream()
              .filter(e -> e.getValue() == a.pattern().resourceType())
              .map(Map.Entry::getKey)
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("Export this resource as CSV"));
      String resource =
          a.pattern().name() + (a.pattern().patternType() == PatternType.PREFIXED ? "*" : "");
      users
          .computeIfAbsent(a.entry().principal().substring(5), k -> new TreeMap<>())
          .computeIfAbsent(section, k -> new TreeMap<>())
          .computeIfAbsent(resource, k -> new ArrayList<>())
          .add(
              Acls.display(a.entry().operation())
                  + ","
                  + Acls.display(a.entry().permissionType())
                  + ","
                  + a.entry().host());
    }
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    String result = new Yaml(options).dump(Map.of("users", users));
    if (!parse(result).equals(acls))
      throw new IllegalArgumentException("ACLs cannot round-trip through YAML; export CSV instead");
    return result;
  }
}
