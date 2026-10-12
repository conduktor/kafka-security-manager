package io.conduktor.ksm.source;

import com.typesafe.config.Config;
import io.conduktor.ksm.parser.AclParserRegistry;
import java.nio.file.*;
import java.util.Optional;

public final class FileSourceAcl implements SourceAcl {
  private final AclParserRegistry parsers;
  private Path file;
  private int maxBytes;

  public FileSourceAcl(AclParserRegistry parsers) {
    this.parsers = parsers;
  }

  public String configPrefix() {
    return "file";
  }

  public void configure(Config config) {
    file = Path.of(config.getString("filename"));
    maxBytes = config.getInt("max.bytes");
    parsers.getParserByFilename(file.toString());
  }

  public Optional<SourceSnapshot> fetch() throws Exception {
    // Read each time: mtime alone misses atomic replacement, older timestamps and deletion.
    try (var input = Files.newInputStream(file)) {
      return Optional.of(
          new SourceSnapshot(
              parsers.getParserByFilename(file.toString()),
              Content.utf8(Content.read(input, maxBytes)),
              () -> {}));
    }
  }
}
