package io.conduktor.ksm.source;

import com.typesafe.config.Config;
import java.util.Optional;

/** External sources return complete snapshots. Empty Optional means unchanged, never failure. */
public interface SourceAcl extends AutoCloseable {
  String configPrefix();

  void configure(Config config);

  Optional<SourceSnapshot> fetch() throws Exception;

  @Override
  default void close() {}
}
