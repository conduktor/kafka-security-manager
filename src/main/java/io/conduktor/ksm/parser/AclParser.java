package io.conduktor.ksm.parser;

import java.util.Set;
import org.apache.kafka.common.acl.AclBinding;

/** A parser must reject the whole document if any entry is invalid. */
public interface AclParser {
  Set<AclBinding> parse(String document);

  String format(Set<AclBinding> acls);
}
