package io.conduktor.ksm.source;

import io.conduktor.ksm.parser.AclParser;
import java.util.Objects;

/** Accept the cache token only after parsing the entire document successfully. */
public record SourceSnapshot(AclParser parser, String document, Runnable accept) {
  public SourceSnapshot {
    Objects.requireNonNull(parser);
    Objects.requireNonNull(document);
    Objects.requireNonNull(accept);
  }
}
