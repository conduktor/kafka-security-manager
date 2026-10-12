package io.conduktor.ksm;

import static org.junit.jupiter.api.Assertions.*;

import io.conduktor.ksm.parser.*;
import io.conduktor.ksm.parser.csv.CsvAclParser;
import io.conduktor.ksm.parser.yaml.YamlAclParser;
import java.nio.file.*;
import java.util.*;
import org.apache.kafka.common.acl.*;
import org.apache.kafka.common.resource.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class ParserTest {
  static final String HEADER = String.join(",", CsvAclParser.HEADER) + "\n";
  static final String ROW = "User:alice,Topic,LITERAL,orders,Read,Allow,*\n";

  static AclBinding acl(
      String principal,
      ResourceType type,
      String name,
      PatternType pattern,
      AclOperation op,
      AclPermissionType permission,
      String host) {
    return Acls.binding(principal, type, name, pattern, op, permission, host);
  }

  static AclBinding read(String name) {
    return acl(
        "User:alice",
        ResourceType.TOPIC,
        name,
        PatternType.LITERAL,
        AclOperation.READ,
        AclPermissionType.ALLOW,
        "*");
  }

  @Test
  void legacyCsvAndCustomDelimiter() {
    var parser = new CsvAclParser();
    assertEquals(Set.of(read("orders")), parser.parse(HEADER + ROW + "\n" + ROW));
    assertEquals(
        Set.of(read("orders")),
        parser.parse((HEADER + ROW).replace("PatternType,", "").replace("LITERAL,", "")));
    assertEquals(
        Set.of(read("orders")), new CsvAclParser('?').parse((HEADER + ROW).replace(',', '?')));
    assertEquals(
        Set.of(read("orders")),
        parser.parse((HEADER + ROW).replace("Topic", "tOpIc").replace("Read", "rEaD")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "\n",
        "<html>failed</html>",
        "foo,bar\n",
        "KafkaPrincipal,KafkaPrincipal\n",
        "KafkaPrincipal,ResourceType,PatternType,ResourceName,Operation,PermissionType,Host,Extra\n"
      })
  void rejectsDangerousEmptyCsv(String text) {
    assertThrows(IllegalArgumentException.class, () -> new CsvAclParser().parse(text));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "User:alice,Topic,LITERAL,orders,Read,Allow\n",
        "User:alice,Topic,MATCH,orders,Read,Allow,*\n",
        "User:alice,Topic,UNKNOWN,orders,Read,Allow,*\n",
        "User:alice,Topic,LITERAL,orders,ANY,Allow,*\n",
        "User:alice,Cluster,LITERAL,*,Create,Allow,*\n",
        "alice,Topic,LITERAL,orders,Read,Allow,*\n",
        "\"unclosed\n"
      })
  void oneBadRowRejectsWholeCsv(String row) {
    assertThrows(RuntimeException.class, () -> new CsvAclParser().parse(HEADER + ROW + row));
  }

  @Test
  void explicitEmptyAndHeaderOnlyAreValid() {
    assertEquals(Set.of(), new CsvAclParser().parse(HEADER));
    assertEquals(Set.of(), new YamlAclParser().parse("users: {}"));
  }

  @Test
  void csvPreservesUserResourceAclsForDelegationTokenAdministration() {
    var parser = new CsvAclParser();
    var acls =
        parser.parse(
            HEADER
                + "User:manager,User,LITERAL,User:owner,CreateTokens,Allow,*\n"
                + "User:manager,User,LITERAL,User:owner,DescribeTokens,Allow,*\n");
    assertEquals(2, acls.size());
    assertEquals(acls, parser.parse(parser.format(acls)));
    assertThrows(IllegalArgumentException.class, () -> new YamlAclParser().format(acls));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "users: {alice: {topic: {foo: [Read]}}}",
        "users: {}\nother: true",
        "users: {}\nusers: {}",
        "users: {alice: {topics: {foo: [Wrong]}}}",
        "users: {alice: {topics: {foo: [Read,Deny]}}}",
        "users: {alice: {topics: {foo: null}}}",
        "users: {alice: {topics: {foo: ['Read,Wrong,*']}}}",
        "users: null",
        "users: {alice: {clusters: {'*': [Create]}}}",
        "!!java.net.URL ['https://example.com']"
      })
  void invalidYamlIsNeverAnEmptyPolicy(String text) {
    assertThrows(RuntimeException.class, () -> new YamlAclParser().parse(text));
  }

  @Test
  void yamlHelpersAndResourceTypes() {
    var result =
        new YamlAclParser()
            .parse(
                """
                users:
                  alice:
                    topics:
                      orders: [Consume, Produce]
                      '*': [Describe]
                    groups:
                      orders-*: ['Read,Deny,127.0.0.1']
                    transactional_ids:
                      tx-*: [All]
                    clusters:
                      kafka-cluster: [IdempotentWrite]
                """);
    assertEquals(8, result.size());
    assertTrue(result.contains(read("orders")));
    assertTrue(
        result.contains(
            acl(
                "User:alice",
                ResourceType.GROUP,
                "orders-",
                PatternType.PREFIXED,
                AclOperation.READ,
                AclPermissionType.DENY,
                "127.0.0.1")));
    assertFalse(
        result.stream()
            .anyMatch(
                a ->
                    a.pattern().resourceType() == ResourceType.CLUSTER
                        && a.entry().operation() == AclOperation.CREATE));
  }

  @Test
  void csvPreservesQuotedDnAndEverySupportedResource() {
    Set<AclBinding> expected = new HashSet<>();
    for (var type :
        List.of(
            ResourceType.TOPIC,
            ResourceType.GROUP,
            ResourceType.CLUSTER,
            ResourceType.TRANSACTIONAL_ID,
            ResourceType.DELEGATION_TOKEN))
      expected.add(
          acl(
              "Service:CN=\"é\",OU=eng",
              type,
              type == ResourceType.CLUSTER ? "kafka-cluster" : "a,b",
              PatternType.LITERAL,
              AclOperation.DESCRIBE,
              AclPermissionType.DENY,
              "127.0.0.1"));
    var parser = new CsvAclParser();
    assertEquals(expected, parser.parse(parser.format(expected)));
  }

  @Test
  void yamlRoundTripAndLossyExportRefusal() {
    var parser = new YamlAclParser();
    var expected =
        parser.parse("users: {alice: {transactional_ids: {'tx-*': [All]}, groups: {g: [Read]}}}");
    assertEquals(expected, parser.parse(parser.format(expected)));
    assertThrows(IllegalArgumentException.class, () -> parser.format(Set.of(read("literal*"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            parser.format(
                Set.of(
                    acl(
                        "Service:alice",
                        ResourceType.TOPIC,
                        "orders",
                        PatternType.LITERAL,
                        AclOperation.READ,
                        AclPermissionType.ALLOW,
                        "*"))));
  }

  @Test
  void checkedInExamplesAreValidAndRoundTrip() throws Exception {
    for (String file : List.of("example/acls.csv", "example/acls.yml")) {
      var parser = new AclParserRegistry(',').getParserByFilename(file);
      var acls = parser.parse(Files.readString(Path.of(file)));
      assertFalse(acls.isEmpty());
      assertEquals(acls, parser.parse(parser.format(acls)));
    }
  }

  @Test
  void extensionlessSourcesStillUseCsvAndPrincipalNamesMayContainColons() {
    var registry = new AclParserRegistry(',');
    assertEquals(
        Set.of(read("orders")), registry.getParserByFilename("mounted-secret").parse(HEADER + ROW));
    var principal =
        acl(
            "User:alice:",
            ResourceType.TOPIC,
            "orders",
            PatternType.LITERAL,
            AclOperation.READ,
            AclPermissionType.ALLOW,
            "*");
    var parser = new CsvAclParser();
    assertEquals(Set.of(principal), parser.parse(parser.format(Set.of(principal))));
  }
}
