package io.conduktor.ksm.backend;

import java.util.Set;
import org.apache.kafka.common.acl.AclBinding;

public interface AclBackend extends AutoCloseable {
  Set<AclBinding> list() throws Exception;

  void create(Set<AclBinding> acls) throws Exception;

  void delete(Set<AclBinding> acls) throws Exception;

  @Override
  default void close() {}
}
