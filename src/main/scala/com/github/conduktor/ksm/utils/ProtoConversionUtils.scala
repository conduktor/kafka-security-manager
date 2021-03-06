package com.github.conduktor.ksm.utils

import com.security.kafka.pb.ksm._
import kafka.security.auth._
import org.apache.kafka.common.resource.PatternType
import org.apache.kafka.common.security.auth.KafkaPrincipal

object ProtoConversionUtils {

  def resourceToPb(resource: Resource): ResourcePb = {
    ResourcePb(
      name = resource.name,
      kafkaResourceType = resourceTypeToPb(resource.resourceType),
      patternType = patternTypeToPb(resource.patternType)
    )
  }

  def aclToPb(acl: Acl): AclPb = {
    AclPb(
      principal = Some(principalToPb(acl.principal)),
      permissionType = permissionTypeToPb(acl.permissionType),
      host = acl.host,
      operationType = operationToPb(acl.operation)
    )
  }

  def resourceTypeToPb(resourceType: ResourceType): ResourceTypePb = {
    resourceType match {
      case Topic           => ResourceTypePb.RESOURCE_TYPE_TOPIC
      case Group           => ResourceTypePb.RESOURCE_TYPE_GROUP
      case Cluster         => ResourceTypePb.RESOURCE_TYPE_CLUSTER
      case TransactionalId => ResourceTypePb.RESOURCE_TYPE_TRANSACTIONALID
      case DelegationToken => ResourceTypePb.RESOURCE_TYPE_DELEGATIONTOKEN
    }
  }

  def patternTypeToPb(patternType: PatternType): PatternTypePb = {
    patternType match {
      case PatternType.LITERAL  => PatternTypePb.PATTERN_TYPE_LITERAL
      case PatternType.PREFIXED => PatternTypePb.PATTERN_TYPE_PREFIXED
      case _                    => PatternTypePb.PATTERN_TYPE_INVALID
    }
  }

  def principalToPb(kafkaPrincipal: KafkaPrincipal): KafkaPrincipalPb = {
    KafkaPrincipalPb(
      name = kafkaPrincipal.getName,
      principalType = kafkaPrincipal.getPrincipalType
    )
  }

  def permissionTypeToPb(permissionType: PermissionType): PermissionTypePb = {
    permissionType match {
      case Allow => PermissionTypePb.PERMISSION_TYPE_ALLOW
      case Deny  => PermissionTypePb.PERMISSION_TYPE_DENY
    }
  }

  def operationToPb(operation: Operation): OperationTypePb = {
    operation match {
      case Read            => OperationTypePb.OPERATION_TYPE_READ
      case Write           => OperationTypePb.OPERATION_TYPE_WRITE
      case All             => OperationTypePb.OPERATION_TYPE_ALL
      case Alter           => OperationTypePb.OPERATION_TYPE_ALTER
      case AlterConfigs    => OperationTypePb.OPERATION_TYPE_ALTERCONFIGS
      case ClusterAction   => OperationTypePb.OPERATION_TYPE_CLUSTERACTION
      case Create          => OperationTypePb.OPERATION_TYPE_CREATE
      case Delete          => OperationTypePb.OPERATION_TYPE_DELETE
      case Describe        => OperationTypePb.OPERATION_TYPE_DESCRIBE
      case DescribeConfigs => OperationTypePb.OPERATION_TYPE_DESCRIBECONFIGS
      case IdempotentWrite => OperationTypePb.OPERATION_TYPE_IDEMPOTENTWRITE
    }
  }

}
