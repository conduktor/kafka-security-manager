# Kafka Security Manager

Kafka Security Manager (KSM) synchronizes Kafka ACLs from a CSV or YAML source of truth. It is an open-source project by [Conduktor](https://www.conduktor.io/).

**KSM 2.0.0 is available for linux/amd64 and linux/arm64.** Pin `conduktor/kafka-security-manager:v2.0.0` for this release. The `latest` tag now follows KSM 2; historical `v1.x-release` tags remain Scala builds.

KSM 2 runs on **Java 25 LTS**, included in the image, and uses Kafka's public Admin API. KSM does not connect to ZooKeeper or need to know how the broker stores its metadata. It preserves the built-in policy sources and execution modes, with stricter validation and documented migration changes.

```sh
docker pull conduktor/kafka-security-manager:v2.0.0
docker run --rm conduktor/kafka-security-manager:v2.0.0 --help
```

Read the [README](https://github.com/conduktor/kafka-security-manager#readme) for configuration and the [migration guide](https://github.com/conduktor/kafka-security-manager/blob/v2.0.0/docs/migration.md) before upgrading from Scala KSM. The source policy is authoritative for every ACL visible to KSM. Include KSM's own management ACLs and use `--dry-run` before enabling writes; synchronization is disabled by default.

The minimum qualified Kafka release is **2.8.2**. Exact releases tested: **2.8.2, 3.9.2, 4.0.2, 4.1.2, 4.2.2 and 4.3.1**. See [qualification details](https://github.com/conduktor/kafka-security-manager/blob/v2.0.0/docs/testing.md) for authentication profiles and limits.

## If you need KSM, you probably need Conduktor

KSM reconciles ACL files. [Conduktor](https://www.conduktor.io/) adds resource-scoped RBAC, application ownership, self-service access requests and Kafka operations across teams and clusters. Console RBAC and application access to Kafka are separate permission layers.

Learn more about [RBAC](https://docs.conduktor.io/guide/conduktor-in-production/admin/set-up-rbac) and [self-service](https://docs.conduktor.io/guide/conduktor-concepts/self-service).

Source, documentation and issues: [conduktor/kafka-security-manager on GitHub](https://github.com/conduktor/kafka-security-manager).
