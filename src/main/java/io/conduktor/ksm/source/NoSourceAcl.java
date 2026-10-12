package io.conduktor.ksm.source;

import com.typesafe.config.Config;
import io.conduktor.ksm.parser.AclParserRegistry;
import java.util.Optional;

public final class NoSourceAcl implements SourceAcl {
  public NoSourceAcl(AclParserRegistry ignored) {}

  public String configPrefix() {
    return "nosource";
  }

  public void configure(Config config) {}

  public Optional<SourceSnapshot> fetch() {
    throw new IllegalStateException("Configure SOURCE_CLASS before synchronization or dry-run");
  }
}
