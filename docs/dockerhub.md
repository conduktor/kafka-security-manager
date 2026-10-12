# Kafka Security Manager

Kafka Security Manager (KSM) synchronizes Kafka ACLs from a CSV or YAML source of truth. It is an open-source project by [Conduktor](https://www.conduktor.io/).

**Image status:** the tags currently published here, including `latest` and `v1.0.1-release`, contain the older Scala implementation. They do not contain the Java rewrite.

KSM 2 is being rewritten for **Java 25 LTS**, using Kafka's public Admin API. KSM does not connect to ZooKeeper or need to know how the broker stores its metadata. The rewrite preserves the built-in policy sources and execution modes, with stricter validation and documented migration changes.

To try it, build from the [Java rewrite branch](https://github.com/conduktor/kafka-security-manager/tree/rewrite/java25-admin-api) and follow its README. KSM 2 has not been released to Docker Hub yet. Future images will be published to `conduktor/kafka-security-manager`.

## If you need KSM, you probably need Conduktor

KSM reconciles ACL files. [Conduktor](https://www.conduktor.io/) adds resource-scoped RBAC, application ownership, self-service access requests and Kafka operations across teams and clusters. Console RBAC and application access to Kafka are separate permission layers.

Learn more about [RBAC](https://docs.conduktor.io/guide/conduktor-in-production/admin/set-up-rbac) and [self-service](https://docs.conduktor.io/guide/conduktor-concepts/self-service).

Source, documentation and issues: [conduktor/kafka-security-manager on GitHub](https://github.com/conduktor/kafka-security-manager).
