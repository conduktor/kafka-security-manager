package io.conduktor.ksm.parser.yaml

import io.conduktor.ksm.source.SourceAclResult
import kafka.security.auth._
import org.apache.kafka.common.resource.PatternType
import org.apache.kafka.common.security.auth.KafkaPrincipal
import org.apache.kafka.common.utils.SecurityUtils
import org.scalatest.{FlatSpec, Matchers}

import java.io.StringReader

class YamlAclParserTest extends FlatSpec with Matchers {

  val row = Map(
    "KafkaPrincipal" -> "User:alice",
    "ResourceType" -> "Topic",
    "PatternType" -> "LITERAL",
    "ResourceName" -> "test",
    "Operation" -> "Read",
    "PermissionType" -> "Allow",
    "Host" -> "*"
  )

  val resource: Resource = Resource(Topic, "test", PatternType.LITERAL)
  val acl: Acl =
    Acl(SecurityUtils.parseKafkaPrincipal("User:alice"), Allow, "*", Read)
  val yamlAclParser = new YamlAclParser()
  val yaml: String =
    """#Ignore comments
      |users:
      |  alice: # Ignore comments
      |    groups:
      |      mygroup-*:
      |        - Write,Deny,12.34.56.78
      |    topics:
      |      topic1: [ Consume, Produce ]
      |  bob:
      |    groups:
      |      group1: [ Read, Write ]
      |      group2:
      |        - Describe,Allow,*
      |        - Read
      |  peter:
      |    transactional_ids:
      |      'tr1*':
      |        - All
      |    clusters:
      |      '*':
      |        - Create,Allow,*
      |  tom:
      |    topics:
      |      '*':
      |        - All
      |""".stripMargin
  var res: SourceAclResult =
    yamlAclParser.aclsFromReader(new StringReader(yaml))
  val principalAlice = new KafkaPrincipal(KafkaPrincipal.USER_TYPE, "alice")
  val principalBob = new KafkaPrincipal(KafkaPrincipal.USER_TYPE, "bob")
  val principalPeter = new KafkaPrincipal(KafkaPrincipal.USER_TYPE, "peter")
  val principalTom = new KafkaPrincipal(KafkaPrincipal.USER_TYPE, "tom")

  "aclsFromYaml" should "handle (action, permission, host) formats and prefixed groups" in {

    res.result.right.get.contains(
      (
        Resource(Group, "mygroup-", PatternType.PREFIXED),
        Acl(principalAlice, Deny, "12.34.56.78", Write)
      )
    ) shouldBe true
  }

  it should "handle the Consume helper" in {

    res.result.right.get.contains(
      (
        Resource(Topic, "topic1", PatternType.LITERAL),
        Acl(principalAlice, Allow, "*", Read)
      )
    ) shouldBe true
    res.result.right.get.contains(
      (
        Resource(Topic, "topic1", PatternType.LITERAL),
        Acl(principalAlice, Allow, "*", Describe)
      )
    ) shouldBe true
  }

  it should "handle the Produce helper" in {

    res.result.right.get.contains(
      (
        Resource(Topic, "topic1", PatternType.LITERAL),
        Acl(principalAlice, Allow, "*", Write)
      )
    ) shouldBe true
    res.result.right.get.contains(
      (
        Resource(Topic, "topic1", PatternType.LITERAL),
        Acl(principalAlice, Allow, "*", Describe)
      )
    ) shouldBe true
    res.result.right.get.contains(
      (
        Resource(Topic, "topic1", PatternType.LITERAL),
        Acl(principalAlice, Allow, "*", Create)
      )
    ) shouldBe true
    res.result.right.get.contains(
      (
        Resource(Cluster, "*", PatternType.LITERAL),
        Acl(principalAlice, Allow, "*", Create)
      )
    ) shouldBe true
  }

  it should "handle normal groups" in {

    res.result.right.get.contains(
      (
        Resource(Group, "group1", PatternType.LITERAL),
        Acl(principalBob, Allow, "*", Read)
      )
    ) shouldBe true
  }

  it should "handle prefixed transactional ids" in {

    res.result.right.get.contains(
      (
        Resource(TransactionalId, "tr1", PatternType.PREFIXED),
        Acl(principalPeter, Allow, "*", All)
      )
    ) shouldBe true
  }

  it should "handle cluster permissions" in {

    res.result.right.get.contains(
      (
        Resource(Cluster, "*", PatternType.LITERAL),
        Acl(principalPeter, Allow, "*", Create)
      )
    ) shouldBe true
  }

  it should "catch all YAML errors" in {

    val yaml =
      """users:
        |  alice:
        |    groups:
        |      mygroup-*:
        |        - BadOperation,Deny,12.34.56.78
        |    topics:
        |      topic1: [ Consume, Produce ]
        |  bob:
        |    groups:
        |      group1: [ BadOperation2, Write ]
        |      group2:
        |        - Describe,Allow,*
        |        - Read
        |        - Write,Deny
        |        - Write,Deny,*,wrong
        |  peter:
        |    cluster:
        |      '*':
        |        - Create,Allow,*
        |  tom:
        |    topics:
        |      test:
        |       - All,Wrong,*
        |""".stripMargin
    res = yamlAclParser.aclsFromReader(new StringReader(yaml))

    res.result.left.get.foreach(e => println(e.asInstanceOf[YamlParserException].print()))

    res.result.left.get.exists(
      _.asInstanceOf[YamlParserException].print().matches(".*BadOperation.*?alice.*?mygroup.*")
    ) shouldBe true
    res.result.left.get.exists(
      _.asInstanceOf[YamlParserException]
        .print()
        .matches(".*BadOperation2.*?bob.*?group1.*")
    ) shouldBe true
    res.result.left.get.exists(
      _.asInstanceOf[YamlParserException]
        .print()
        .matches(".*Wrong.*?tom.*?test.*")
    ) shouldBe true
  }

  "asYaml" should "correctly format entire ACL" in {
    val csv =
      """KafkaPrincipal,ResourceType,PatternType,ResourceName,Operation,PermissionType,Host
        |User:alice,Topic,LITERAL,foo,Read,Allow,*
        |User:bob,Group,PREFIXED,bar,Write,Deny,12.34.56.78
        |User:peter,Cluster,LITERAL,kafka-cluster,Create,Allow,*
        |""".stripMargin

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
    val res2 = Resource(Group, "bar", PatternType.PREFIXED)
    val res3 = Resource(Cluster, "kafka-cluster", PatternType.LITERAL)

    val res =
      yamlAclParser.formatAcls(List((res1, acl1), (res2, acl2), (res3, acl3)))

    res shouldBe
      """alice:
        |  topics:
        |    foo:
        |    - Read,Allow,*
        |bob:
        |  groups:
        |    bar*:
        |    - Write,Deny,12.34.56.78
        |peter:
        |  clusters:
        |    kafka-cluster:
        |    - Create,Allow,*
        |""".stripMargin
  }
}
