package com.github.conduktor.ksm.parser

import com.github.conduktor.ksm.source.SourceAclResult
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
  val yaml =
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
      |        - Admin
      |""".stripMargin
  val res: SourceAclResult =
    yamlAclParser.aclsFromReader(new StringReader(yaml))
  val principalAlice = new KafkaPrincipal(KafkaPrincipal.USER_TYPE, "alice")
  val principalBob = new KafkaPrincipal(KafkaPrincipal.USER_TYPE, "bob")
  val principalPeter = new KafkaPrincipal(KafkaPrincipal.USER_TYPE, "peter")
  val principalTom = new KafkaPrincipal(KafkaPrincipal.USER_TYPE, "tom")

  "aclsFromYaml" should "handle prefixed groups" in {

    res.result.right.get.contains(
      (
        Resource(Group, "mygroup-", PatternType.PREFIXED),
        Acl(principalAlice, Deny, "12.34.56.78", Write)
      )
    ) shouldBe true
  }

  "aclsFromYaml" should "correctly parse a valid YAML" in {

    /*
    res.acls.map(_.toString()) shouldBe Set(
      "(TransactionalId:PREFIXED:tr1,User:peter has Allow permission for operations: All from hosts: *)",
      "(Cluster:LITERAL:*,User:peter has Allow permission for operations: Create from hosts: *)",
      "(Cluster:LITERAL:*,User:alice has Allow permission for operations: Create from hosts: *)",
      "(Topic:LITERAL:topic1,User:alice has Allow permission for operations: Read from hosts: *)",
      "(Topic:LITERAL:topic1,User:alice has Allow permission for operations: Write from hosts: *)",
      "(Topic:LITERAL:topic1,User:alice has Allow permission for operations: Describe from hosts: *)",
      "(Topic:LITERAL:topic1,User:alice has Allow permission for operations: Create from hosts: *)",
      "(Group:PREFIXED:mygroup-,User:alice has Deny permission for operations: Write from hosts: 12.34.56.78)",
      "(Topic:LITERAL:*,User:tom has Allow permission for operations: Describe from hosts: *)",
      "(Cluster:LITERAL:*,User:tom has Allow permission for operations: Create from hosts: *)",
      "(Topic:LITERAL:*,User:tom has Allow permission for operations: Write from hosts: *)",
      "(Topic:LITERAL:*,User:tom has Allow permission for operations: Delete from hosts: *)",
      "(Topic:LITERAL:*,User:tom has Allow permission for operations: Read from hosts: *)",
      "(Topic:LITERAL:*,User:tom has Allow permission for operations: Create from hosts: *)",
      "(Group:LITERAL:group1,User:bob has Allow permission for operations: Write from hosts: *)",
      "(Group:LITERAL:group1,User:bob has Allow permission for operations: Read from hosts: *)",
      "(Group:LITERAL:group2,User:bob has Allow permission for operations: Read from hosts: *)",
      "(Group:LITERAL:group2,User:bob has Allow permission for operations: Describe from hosts: *)")

   */
  }

  "aclsFromYaml" should "catch all YAML errors" in {

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
        |  peter:
        |    cluster:
        |      '*':
        |        - Create,Allow,*
        |  tom:
        |    topics:
        |      '*':
        |       - Admin
        |""".stripMargin

    val res = yamlAclParser.aclsFromReader(new StringReader(yaml))

    res.result.left.get.map(_.toString()).foreach(str => println(str))

    res.result.left.get.map(_.toString()) shouldBe List(
      "Could not parse ACL 'BadOperation,Deny,12.34.56.78' for principal 'User:alice' and resource 'Group:PREFIXED:mygroup-'",
      "Could not parse ACL 'BadOperation2' for principal 'User:bob' and resource 'Group:LITERAL:group1'"
    )
  }

  "aclsFromYaml" should "catch YAML syntax errors" in {

    val yaml =
      """users:
        |  alice:
        |    topics:
        |      topic1: [ Consume, Produce ]
        |  peter:
        |    cluster:
        |      '*':
        |        - Create,Allow,*
        |  tom:
        |    topics:
        |      '*': Admin # <-- ERROR HERE
        |""".stripMargin

    val res = yamlAclParser.aclsFromReader(new StringReader(yaml))

    res.result.left.get.map(_.toString()) shouldBe List(
      "DecodingFailure at .users.tom.topics.*: C[A]"
    )
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
