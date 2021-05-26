package io.conduktor.ksm.compat

import java.util

import kafka.network.RequestChannel
import kafka.security.authorizer.AuthorizerWrapper
import kafka.security.auth.{
  Acl,
  Authorizer,
  Operation,
  PermissionType,
  Resource,
  ResourceType
}
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.common.utils.{SecurityUtils => JavaSecurityUtils}
import org.apache.kafka.common.acl.{AccessControlEntry, AclBindingFilter}
import org.apache.kafka.common.resource.ResourcePattern
import org.apache.kafka.common.security.auth.KafkaPrincipal

import scala.collection.JavaConverters._

object JavaAclConversions {
  def aclFromEntry(entry: AccessControlEntry): Acl = Acl(
    JavaSecurityUtils.parseKafkaPrincipal(entry.principal()),
    PermissionType.fromJava(entry.permissionType()),
    entry.host(),
    Operation.fromJava(entry.operation())
  )
  def resourceFromPattern(pattern: ResourcePattern): Resource = Resource(
    ResourceType.fromJava(pattern.resourceType),
    pattern.name(),
    pattern.patternType()
  )
}

trait AdminClientAuthorizerBase extends Authorizer {

  protected def client: AdminClient

  override def addAcls(acls: Set[Acl], resource: Resource): Unit =
    client
      .createAcls(
        acls.map(acl => AuthorizerWrapper.convertToAclBinding(resource, acl)).asJava
      )
      .all()
      .get()

  override def removeAcls(acls: Set[Acl], resource: Resource): Boolean =
    !client
      .deleteAcls(
        acls
          .map(acl => AuthorizerWrapper.convertToAclBinding(resource, acl).toFilter)
          .asJava
      )
      .all()
      .get()
      .isEmpty

  override def getAcls(): Map[Resource, Set[Acl]] =
    client
      .describeAcls(AclBindingFilter.ANY)
      .values()
      .get()
      .asScala
      .groupBy(aclBinding =>
        JavaAclConversions.resourceFromPattern(aclBinding.pattern())
      )
      .mapValues(
        _.map(aclBinding => JavaAclConversions.aclFromEntry(aclBinding.entry())).toSet
      )

  //<editor-fold desc="Interface methods not used by kafka-security-manager">

  override def getAcls(resource: Resource): Set[Acl] = ???

  override def removeAcls(resource: Resource): Boolean = ???

  override def getAcls(principal: KafkaPrincipal): Map[Resource, Set[Acl]] = ???

  override def authorize(
      session: RequestChannel.Session,
      operation: Operation,
      resource: Resource
  ): Boolean = ???
  //</editor-fold>
}

/** No-argument constructor implementation, admin client initialized by `configure` */
class AdminClientAuthorizer() extends AdminClientAuthorizerBase {

  private var clientInstance: Option[AdminClient] = None

  override protected def client: AdminClient = clientInstance.getOrElse(
    throw new IllegalStateException("AdminClient is not yet configured")
  )

  override def close(): Unit = clientInstance.foreach(_.close())

  override def configure(map: util.Map[String, _]): Unit = {
    synchronized {
      clientInstance.foreach(_.close())
      clientInstance = Some(
        AdminClient.create(
          // The only use in security manager provides util.Map[String, String], so it's safe to cast
          map.asInstanceOf[util.Map[String, Object]]
        )
      )
    }
  }
}
