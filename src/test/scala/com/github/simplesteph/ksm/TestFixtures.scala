package com.github.simplesteph.ksm

import kafka.security.auth._
import org.apache.kafka.common.resource.PatternType
import org.apache.kafka.common.utils.SecurityUtils

object TestFixtures {

  val acl1 =
    Acl(SecurityUtils.parseKafkaPrincipal("User:alice"), Allow, "*", Read)
  val acl2 = Acl(
    SecurityUtils.parseKafkaPrincipal("User:bob"),
    Deny,
    "12.34.56.78",
    Write
  )
  val acl3 =
    Acl(SecurityUtils.parseKafkaPrincipal("User:peter"), Allow, "*", Create)

  val res1 = Resource(Topic, "foo", PatternType.LITERAL)
  val res2 = Resource(Group, "bar", PatternType.LITERAL)
  val res3 = Resource(Cluster, "kafka-cluster", PatternType.LITERAL)
}
