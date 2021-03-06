package com.github.conduktor.ksm.grpc

import com.github.conduktor.ksm.AclSynchronizer
import com.github.conduktor.ksm.utils.ProtoConversionUtils
import com.security.kafka.pb.ksm.KsmServiceGrpc.KsmService
import com.security.kafka.pb.ksm.{
  GetAllAclsRequest,
  GetAllAclsResponse,
  ResourceAndAclPb
}
import kafka.security.auth.{Acl, Resource}

import scala.concurrent.Future

class KsmServiceImpl(aclSynchronizer: AclSynchronizer) extends KsmService {

  override def getAllAcls(
      request: GetAllAclsRequest
  ): Future[GetAllAclsResponse] = {
    val aclsAndResources: Set[(Resource, Acl)] = aclSynchronizer.getKafkaAcls

    val response = GetAllAclsResponse(resourceAndAcls = aclsAndResources.map {
      case (resource: Resource, acl: Acl) =>
        ResourceAndAclPb(
          resource = Some(ProtoConversionUtils.resourceToPb(resource)),
          acl = Some(ProtoConversionUtils.aclToPb(acl))
        )
    }.toSeq)

    Future.successful(response)
  }

}
