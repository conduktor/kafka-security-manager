//package io.conduktor.ksm.compat
//
//import java.security.Security
//import io.conduktor.ksm.{AclSynchronizer, TestFixtures}
//import io.conduktor.ksm.notification.DummyNotification
//import io.conduktor.ksm.parser.AclParserRegistry
//import io.conduktor.ksm.parser.csv.CsvAclParser
//import io.conduktor.ksm.source.DummySourceAcl
//import net.manub.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
//import org.scalatest.{FlatSpec, Matchers}
//import org.apache.kafka.clients.admin.AdminClientConfig
//import org.scalamock.scalatest.MockFactory
//import org.scalatest.concurrent.Eventually
//
//import scala.collection.JavaConverters._
//import scala.concurrent.duration._
//
//trait JaasConfiguration {
// final val jaasPropertyName = "java.security.auth.login.config"
// def jaasConfig: String =
//   getClass.getClassLoader.getResource("test-jaas.conf").getFile
//
// private def currentSecurityProviders: Set[String] =
//   Security.getProviders.map(_.getName).toSet
//
// /** No way to configure zookeeper jaas in `embedded-kafka`, so have to patch system props.
//   * Also, once Jaas is used, it affects list of Security providers, so we revert it */
// def withJaasSystemConfiguration[T](body: => T): T = {
//   val originalPropValue: String = System.getProperty(jaasPropertyName)
//   val originalProviders = currentSecurityProviders
//   System.setProperty(jaasPropertyName, jaasConfig)
//   try {
//     body
//   } finally {
//     Option(originalPropValue).fold(
//       System.clearProperty(jaasPropertyName)
//     )(
//       System.setProperty(jaasPropertyName, _)
//     )
//     currentSecurityProviders
//       .diff(originalProviders)
//       .foreach(
//         Security.removeProvider
//       )
//   }
// }
//}
//
//class AdminClientAuthorizerTest
//   extends FlatSpec
//   with EmbeddedKafka
//   with Matchers
//   with JaasConfiguration
//   with Eventually
//     with MockFactory {
//
// implicit final val embeddedKafkaConfig: EmbeddedKafkaConfig =
//   EmbeddedKafkaConfig(
//     customBrokerProperties = Map(
//       "super.users" -> "User:admin",
//       "authorizer.class.name" -> "kafka.security.auth.SimpleAclAuthorizer",
//       "security.protocol" -> "SASL_PLAINTEXT",
//       "advertised.listeners" -> "SASL_PLAINTEXT://localhost:6001",
//       "listeners" -> "SASL_PLAINTEXT://localhost:6001",
//       "sasl.enabled.mechanisms" -> "PLAIN",
//       "inter.broker.listener.name" -> "SASL_PLAINTEXT",
//       "sasl.mechanism.inter.broker.protocol" -> "PLAIN"
//     )
//   )
//
// val adminClientConfig: Map[String, Object] = Map(
//   // configuring params similarly like in net.manub.embeddedkafka.ops.AdminOps#createCustomTopic
//   AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG -> s"localhost:${embeddedKafkaConfig.kafkaPort}",
//   AdminClientConfig.CLIENT_ID_CONFIG -> "ksm-admin-client",
//   AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG -> zkSessionTimeoutMs.toString,
//   AdminClientConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG -> zkConnectionTimeoutMs.toString,
//   // enabling sasl
//   AdminClientConfig.SECURITY_PROTOCOL_CONFIG -> "SASL_PLAINTEXT",
//   "sasl.mechanism" -> "PLAIN",
//   "sasl.jaas.config" -> List(
//     "org.apache.kafka.common.security.plain.PlainLoginModule",
//     "required",
//     """username="admin"""",
//     """password="admin-secret""""
//   ).mkString("", " ", ";")
// )
//
//  val csvlAclParser = new CsvAclParser()
//  val aclParserRegistryMock: AclParserRegistry = stub[AclParserRegistry]
//  (aclParserRegistryMock.getParserByFilename _).when(*).returns(csvlAclParser)
//
//  val dummySourceAcl = new DummySourceAcl(aclParserRegistryMock)
//
// // can't be a val because depends on current jaas configuration mutated in global context
// def newSynchronizer: AclSynchronizer = {
//   val authorizer = new AdminClientAuthorizer()
//   authorizer.configure(adminClientConfig.asJava)
//   new AclSynchronizer(
//     authorizer,
//     dummySourceAcl,
//     new DummyNotification,
//     1
//   )
// }
//
// override implicit val patienceConfig: PatienceConfig = PatienceConfig(
//   timeout = 3000.milliseconds,
//   interval = 200 milliseconds
// )
//
// "synchronize with AdminClient based authorizer" should "synchronize acls properly" in {
//   withJaasSystemConfiguration {
//     withRunningKafka {
//       val synchronizer = newSynchronizer
//       eventually {
//         synchronizer.getKafkaAcls shouldBe Set.empty
//       }
//       // transition to next state happens each time `.refresh(...)` called internally per synchronizer.run
//       dummySourceAcl.sars.foreach(sourceAclResult => {
//         synchronizer.run()
//         eventually {
//           synchronizer.getKafkaAcls shouldBe sourceAclResult
//         }
//       })
//       synchronizer.close()
//     }
//   }
// }
//}
