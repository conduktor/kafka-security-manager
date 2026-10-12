package io.conduktor.ksm.parser.csv;

import io.conduktor.ksm.parser.*;
import java.io.*;
import java.util.*;
import org.apache.commons.csv.*;
import org.apache.kafka.common.acl.*;
import org.apache.kafka.common.resource.*;

public final class CsvAclParser implements AclParser {
  public static final List<String> HEADER =
      List.of(
          "KafkaPrincipal",
          "ResourceType",
          "PatternType",
          "ResourceName",
          "Operation",
          "PermissionType",
          "Host");
  private final CSVFormat format;

  public CsvAclParser() {
    this(',');
  }

  public CsvAclParser(char delimiter) {
    format = CSVFormat.RFC4180.builder().setDelimiter(delimiter).setIgnoreEmptyLines(true).get();
  }

  @Override
  public Set<AclBinding> parse(String document) {
    try (CSVParser csv = CSVParser.parse(document, format)) {
      var rows = csv.iterator();
      if (!rows.hasNext())
        throw new IllegalArgumentException(
            "CSV requires a header; empty input is not an empty ACL list");
      List<String> header = rows.next().toList();
      Set<String> expected = new HashSet<>(HEADER);
      if (!header.contains("PatternType")) expected.remove("PatternType");
      if (header.size() != expected.size() || !new HashSet<>(header).equals(expected))
        throw new IllegalArgumentException(
            "CSV header must contain "
                + String.join(",", HEADER)
                + " (PatternType is optional for legacy files)");
      Set<AclBinding> result = new HashSet<>();
      while (rows.hasNext()) {
        CSVRecord row = rows.next();
        if (row.size() != header.size())
          throw new IllegalArgumentException(
              "CSV row " + row.getRecordNumber() + ": wrong column count");
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < header.size(); i++) values.put(header.get(i), row.get(i));
        try {
          result.add(
              Acls.binding(
                  values.get("KafkaPrincipal"),
                  Acls.value(ResourceType.class, values.get("ResourceType")),
                  values.get("ResourceName"),
                  Acls.value(PatternType.class, values.getOrDefault("PatternType", "LITERAL")),
                  Acls.value(AclOperation.class, values.get("Operation")),
                  Acls.value(AclPermissionType.class, values.get("PermissionType")),
                  values.get("Host")));
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException(
              "CSV row " + row.getRecordNumber() + ": " + e.getMessage(), e);
        }
      }
      return Set.copyOf(result);
    } catch (IOException | UncheckedIOException e) {
      throw new IllegalArgumentException("Malformed CSV document", e);
    }
  }

  @Override
  public String format(Set<AclBinding> acls) {
    StringWriter output = new StringWriter();
    try (CSVPrinter printer = new CSVPrinter(output, format)) {
      printer.printRecord(HEADER);
      for (var a : acls.stream().sorted(Acls.ORDER).toList()) {
        Acls.validate(a);
        printer.printRecord(
            a.entry().principal(),
            Acls.display(a.pattern().resourceType()),
            a.pattern().patternType(),
            a.pattern().name(),
            Acls.display(a.entry().operation()),
            Acls.display(a.entry().permissionType()),
            a.entry().host());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return output.toString();
  }
}
