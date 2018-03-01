[![Build Status](https://travis-ci.org/simplesteph/kafka-security-manager.svg?branch=master)](https://travis-ci.org/simplesteph/kafka-security-manager)

# Kafka Security Manager

Kafka Security Manager (KSM) allows you to manage your Kafka ACLs at scale by leveraging an external source as the source of truth. Zookeeper just contains a copy of the ACLs instead of being the source.

There are several advantages to this:
- **Kafka administration is done outside of Kafka:** anyone with access to the external ACL source can manage Kafka Security
- **Prevents intruders:** if someone were to add ACLs to Kafka using the CLI, they would be reverted by KSM within 10 seconds. 
- **Full auditability:** KSM provides the guarantee that ACLs in Kafka are those in the external source. Additionally, if for example your external source is GitHub, then PRs, PR approvals and commit history will provide Audit the full log of who did what to the ACLs and when
- **Notifications**: KSM can notify external channels (such as Slack) in order to give feedback to admins when ACLs are changed. This is particularly useful to ensure that 1) ACL changes are correctly applied 2) ACL are not changed in Kafka directly.

Your role is to ensure that Kafka Security Manager is never down, as it is now a custodian of your ACL.

A sample CSV to manage ACL is:
```
KafkaPrincipal,ResourceType,ResourceName,Operation,PermissionType,Host
User:alice,Topic,foo,Read,Allow,*
User:bob,Group,bar,Write,Deny,12.34.56.78
User:peter,Cluster,kafka-cluster,Create,Allow,*
``` 

# Building

``` 
sbt test
sbt universal:stage
```

This is a Scala app and therefore should run on the JVM like any other application


# Configuration 

Make sure the app is using a property file and launch options similar to your broker so that it can 
1. Authenticate to Zookeeper using secure credentials (usually done with JAAS)
2. Apply Zookeeper ACL if enabled

*Kafka Security Manager does not connect to Kafka.* 

Sample run for a typical SASL Setup:
``` 
export JAVA_OPTS="TODO"
bin/kafka-security-manager -Djava.security.auth.login.config=conf/jaas.conf
```

# Running on Docker

TODO, PR welcome

# Compatibility

0.x: Kafka 1.0.0

# Contributing

You can break the API / configs as long as we haven't reached 1.0. Each API break would introduce a new version number. 

PRs are welcome, especially with the following:
- Code refactoring  / cleanup / renaming
- Docker image building
- External Sources for ACLs (JDBC, Microsoft AD, etc...)
- Notification Channels (Email, etc...) 

Please open an issue before opening a PR. 

# Used By...

PR to README.md to add your company