package com.github.simplesteph.ksm

import java.util

import com.github.simplesteph.ksm.TestFixtures._
import kafka.network.RequestChannel
import kafka.security.auth.{ Acl, Authorizer, Operation, Resource }
import org.apache.kafka.common.security.auth.KafkaPrincipal

class DummyAuthorizer() extends Authorizer {

  override def authorize(session: RequestChannel.Session, operation: Operation, resource: Resource): Boolean = ???

  override def addAcls(acls: Set[Acl], resource: Resource): Unit = ???

  override def removeAcls(acls: Set[Acl], resource: Resource): Boolean = ???

  override def removeAcls(resource: Resource): Boolean = ???

  override def getAcls(resource: Resource): Set[Acl] = ???

  override def getAcls(principal: KafkaPrincipal): Map[Resource, Set[Acl]] = ???

  override def getAcls(): Map[Resource, Set[Acl]] = {
    Map(res1 -> Set(acl1))
  }

  override def close(): Unit = ???

  override def configure(configs: util.Map[String, _]): Unit = ???
}
