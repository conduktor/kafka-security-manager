package com.github.simplesteph.ksm.compat

import com.github.simplesteph.ksm.AclSynchronizer
import com.github.simplesteph.ksm.notification.DummyNotification
import com.github.simplesteph.ksm.parser.CsvAclParser
import com.github.simplesteph.ksm.source.DummySourceAcl
import net.manub.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import org.scalatest.{FlatSpec, Matchers}
import org.apache.kafka.clients.admin.AdminClientConfig

import scala.collection.JavaConverters._

trait JaasConfiguration {
  final val jaasPropertyName = "java.security.auth.login.config"
  def jaasConfig: String = getClass.getClassLoader.getResource("test-jaas.conf").getFile

  /** No way to configure zookeeper jaas in `embedded-kafka`, so have to patch system props */
  def withJaasSystemConfiguration[T](body: => T): T = {
    val originalPropValue: String = System.getProperty(jaasPropertyName)
    System.setProperty(jaasPropertyName, jaasConfig)
    try {
      body
    }
    finally {
      Option(originalPropValue).fold(
        System.clearProperty(jaasPropertyName)
      )(
        System.setProperty(jaasPropertyName, _)
      )
    }
  }
}

class AdminClientAuthorizerTest extends FlatSpec with EmbeddedKafka with Matchers with JaasConfiguration {

  implicit final val embeddedKafkaConfig: EmbeddedKafkaConfig = EmbeddedKafkaConfig(
    customBrokerProperties = Map(
      "super.users" -> "User:admin",
      "authorizer.class.name" -> "kafka.security.auth.SimpleAclAuthorizer",
      "security.protocol" -> "SASL_PLAINTEXT",
      "advertised.listeners" -> "SASL_PLAINTEXT://localhost:6001",
      "listeners" -> "SASL_PLAINTEXT://localhost:6001",
      "sasl.enabled.mechanisms" -> "PLAIN",
      "inter.broker.listener.name" -> "SASL_PLAINTEXT",
      "sasl.mechanism.inter.broker.protocol" -> "PLAIN"
    )
  )

  val adminClientConfig: Map[String, Object] = Map(
    // configuring params similarly like in net.manub.embeddedkafka.ops.AdminOps#createCustomTopic
    AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG -> s"localhost:${embeddedKafkaConfig.kafkaPort}",
    AdminClientConfig.CLIENT_ID_CONFIG -> "ksm-admin-client",
    AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG -> zkSessionTimeoutMs.toString,
    AdminClientConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG -> zkConnectionTimeoutMs.toString,
    // enabling sasl
    AdminClientConfig.SECURITY_PROTOCOL_CONFIG -> "SASL_PLAINTEXT",
    "sasl.mechanism" -> "PLAIN",
    "sasl.jaas.config" -> List(
        "org.apache.kafka.common.security.plain.PlainLoginModule", "required",
         """username="admin"""",
         """password="admin-secret"""",
      ).mkString("", " ", ";")
    )

  val dummySourceAcl = new DummySourceAcl

  // can't be a val because depends on current jaas configuration mutated in global context
  def newSynchronizer: AclSynchronizer = {
    val authorizer = new AdminClientAuthorizer()
    authorizer.configure(adminClientConfig.asJava)
    new AclSynchronizer(authorizer, dummySourceAcl, new DummyNotification, new CsvAclParser)
  }

  "syncronizer with AdminClient based authorizer" should "synchronize acls properly" in {
    withJaasSystemConfiguration {
      withRunningKafka {
        val synchronizer = newSynchronizer
        synchronizer.getKafkaAcls shouldBe Set.empty
        // transition to next state happens each time `.refresh(...)` called internally per synchronizer.run
        dummySourceAcl.sars.foreach(
          sourceAclResult => {
            synchronizer.run()
            synchronizer.getKafkaAcls shouldBe sourceAclResult.acls
          }
        )
        synchronizer.close()
      }
    }
  }
}
