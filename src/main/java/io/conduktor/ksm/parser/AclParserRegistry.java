package io.conduktor.ksm.parser;

import io.conduktor.ksm.parser.csv.CsvAclParser;
import io.conduktor.ksm.parser.yaml.YamlAclParser;
import java.util.Locale;

public final class AclParserRegistry {
  private final AclParser csv;
  private final AclParser yaml = new YamlAclParser();

  public AclParserRegistry(char delimiter) {
    csv = new CsvAclParser(delimiter);
  }

  public AclParser getParser(String name) {
    return switch (name.toLowerCase(Locale.ROOT)) {
      case "csv" -> csv;
      case "yaml", "yml" -> yaml;
      default -> throw new IllegalArgumentException("Unknown ACL format: " + name);
    };
  }

  public AclParser getParserByFilename(String file) {
    int dot = file.lastIndexOf('.');
    if (dot < 0) return csv;
    String extension = file.substring(dot + 1).toLowerCase(Locale.ROOT);
    return extension.equals("yml") || extension.equals("yaml") ? yaml : csv;
  }
}
