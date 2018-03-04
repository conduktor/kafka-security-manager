package com.github.simplesteph.ksm

import com.github.simplesteph.ksm.notification.{ConsoleNotification, DummyNotification}
import com.github.simplesteph.ksm.source.DummySourceAcl
import kafka.security.auth._
import net.manub.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import org.scalatest.concurrent.Eventually
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.JavaConverters._
import scala.concurrent.duration._

class AclSynchronizerTest extends FlatSpec with EmbeddedKafka with Matchers with Eventually {

  import DummySourceAcl._

  val kafkaGroupedAcls: Map[Resource, Set[Acl]] = Map(
    res1 -> Set(acl1,acl2),
    res2 -> Set(acl3),
  )

  val kafkaFlattenedAcls = Set(res1 -> acl1, res1 -> acl2, res2 -> acl3)

  "flattenKafkaAcls" should "correctly flatten Acls" in {

    AclSynchronizer.flattenKafkaAcls(kafkaGroupedAcls) shouldBe kafkaFlattenedAcls
  }

  "regroupAcls" should "correctly group Acls" in {
    AclSynchronizer.regroupAcls(kafkaFlattenedAcls) shouldBe kafkaGroupedAcls
  }

  "regroupAcls and flattenKafkaAcls" should "compose to identity" in {
    AclSynchronizer.flattenKafkaAcls(AclSynchronizer.regroupAcls(kafkaFlattenedAcls)) shouldBe kafkaFlattenedAcls
    AclSynchronizer.regroupAcls(AclSynchronizer.flattenKafkaAcls(kafkaGroupedAcls)) shouldBe kafkaGroupedAcls
  }


  // modify dynamically?
  implicit val config: EmbeddedKafkaConfig =
    EmbeddedKafkaConfig()

  "applySourcesAcls" should "add and removed Acls" in {
    withRunningKafka {
      val simpleAclAuthorizer = new SimpleAclAuthorizer()

      val configs = Map(
        "zookeeper.connect" -> s"localhost:${EmbeddedKafkaConfig.defaultConfig.zooKeeperPort}",
      )
      simpleAclAuthorizer.configure(configs.asJava)

      val dummySourceAcl = new DummySourceAcl
      val dummyNotification = new DummyNotification

      val aclSynchronizer: AclSynchronizer = new AclSynchronizer(
        simpleAclAuthorizer,
        dummySourceAcl,
        dummyNotification,
      )


      // first iteration
      dummyNotification.reset()
      aclSynchronizer.run()
      dummyNotification.addedAcls.size shouldBe 3
      dummyNotification.removedAcls.size shouldBe 0
      eventually(timeout(3000 milliseconds), interval(200 milliseconds)){
        simpleAclAuthorizer.getAcls() shouldBe Map(res1 -> Set(acl1, acl2), res2 -> Set(acl3))
      }

      // second iteration
      dummyNotification.reset()
      aclSynchronizer.run()
      dummyNotification.addedAcls.size shouldBe 1
      dummyNotification.removedAcls.size shouldBe 1
      eventually(timeout(3000 milliseconds), interval(200 milliseconds)){
        simpleAclAuthorizer.getAcls() shouldBe Map(res1 -> Set(acl1), res2 -> Set(acl3), res3 -> Set(acl2))
      }

      // third iteration
      dummySourceAcl.setNoneNext()
      dummyNotification.reset()
      aclSynchronizer.run()
      dummyNotification.addedAcls.size shouldBe 0
      dummyNotification.removedAcls.size shouldBe 0
      eventually(timeout(3000 milliseconds), interval(200 milliseconds)){
        simpleAclAuthorizer.getAcls() shouldBe Map(res1 -> Set(acl1), res2 -> Set(acl3), res3 -> Set(acl2))
      }

      // fourth iteration
      dummyNotification.reset()
      aclSynchronizer.run()
      dummyNotification.addedAcls.size shouldBe 0
      dummyNotification.removedAcls.size shouldBe 3
      eventually(timeout(3000 milliseconds), interval(200 milliseconds)){
        simpleAclAuthorizer.getAcls() shouldBe Map()
      }

      aclSynchronizer.close()
    }
  }

  "applySourcesAcls" should "mitigate unwanted changes in Kafka ACLs" in {
    withRunningKafka {
      val simpleAclAuthorizer = new SimpleAclAuthorizer()

      val configs = Map(
        "zookeeper.connect" -> s"localhost:${EmbeddedKafkaConfig.defaultConfig.zooKeeperPort}",
      )
      simpleAclAuthorizer.configure(configs.asJava)

      val dummySourceAcl = new DummySourceAcl

      val aclSynchronizer: AclSynchronizer = new AclSynchronizer(
        simpleAclAuthorizer,
        dummySourceAcl,
        new ConsoleNotification
      )

      // first iteration
      aclSynchronizer.run()
      eventually(timeout(3000 milliseconds), interval(200 milliseconds)){
        simpleAclAuthorizer.getAcls() shouldBe Map(res1 -> Set(acl1, acl2), res2 -> Set(acl3))
      }


      // changes to the ACLs through Kafka directly
      simpleAclAuthorizer.addAcls(Set(acl3,acl1), res3) // we add bad Acls
      eventually(timeout(3000 milliseconds), interval(200 milliseconds)){
        // wait for the bad Acls to settle in Kafka
        simpleAclAuthorizer.getAcls() shouldBe Map(res1 -> Set(acl1, acl2), res2 -> Set(acl3), res3 -> Set(acl3, acl1))
      }
      // apply KSM and see if Acls come back to normal
      eventually(timeout(3000 milliseconds), interval(200 milliseconds)){
        dummySourceAcl.setNoneNext()
        aclSynchronizer.run()
        simpleAclAuthorizer.getAcls() shouldBe Map(res1 -> Set(acl1, acl2), res2 -> Set(acl3))
      }

      aclSynchronizer.close()

    }
  }

  // TODO: deal with error cases



}
