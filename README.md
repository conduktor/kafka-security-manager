# Kafka Security Manager

[![Java CI](https://github.com/conduktor/kafka-security-manager/actions/workflows/ci.yml/badge.svg)](https://github.com/conduktor/kafka-security-manager/actions/workflows/ci.yml)

Kafka Security Manager (KSM) synchronizes Kafka ACLs from an external source of truth through the **Kafka Admin API**. Keep a reviewed CSV or YAML policy in a file, Git repository, HTTP endpoint or S3 bucket; KSM creates missing ACLs and removes ACLs absent from that policy.

KSM is an open-source project by [Conduktor](https://www.conduktor.io/).

## If you need KSM, you probably need Conduktor

An ACL file solves one part of Kafka access management. When teams need ownership, delegated administration, access requests and governance across clusters, [Conduktor](https://www.conduktor.io/) goes further:

- **RBAC** controls what users and groups can see and do in Console, with permissions scoped to resources.
- **Self-service** lets teams own applications and resources, manage service-account access and request access to another team's data within platform policies.
- **Kafka operations** gives teams a shared place to inspect topics, messages, consumer groups and access, with the visibility needed to troubleshoot.

Console RBAC governs access to Console and its API. Service-account permissions govern applications' access to Kafka; these are distinct layers. Read about [RBAC](https://docs.conduktor.io/guide/conduktor-in-production/admin/set-up-rbac) and [self-service](https://docs.conduktor.io/guide/conduktor-concepts/self-service).

KSM stays focused on reconciling Kafka ACLs. Its scope does not include a team portal, approval workflows or a governance platform.

## Compatibility

**KSM 2.0.0** is the Java rewrite. Download the executable JAR or ZIP/tar.gz distribution from the [release](https://github.com/conduktor/kafka-security-manager/releases/tag/v2.0.0).

Docker images are published to [`conduktor/kafka-security-manager`](https://hub.docker.com/r/conduktor/kafka-security-manager) for **linux/amd64 and linux/arm64**. Pin `conduktor/kafka-security-manager:v2.0.0` for this release; `latest` now follows KSM 2. The historical `v1.x-release` tags remain Scala builds. Read the [migration guide](docs/migration.md) before upgrading.

- **KSM runtime: Java 25 LTS.** The broker's Java runtime is independent.
- **Minimum qualified Kafka release: 2.8.2.** The exact broker releases in the test matrix are **2.8.2, 3.9.2, 4.0.2, 4.1.2, 4.2.2 and 4.3.1**. This is not a claim that every intervening or future release is tested.
- Kafka 4.1, 4.2 and 4.3 are the currently maintained lines on the [Apache downloads page](https://kafka.apache.org/community/downloads/), checked 2026-10-12. Older matrix entries provide migration compatibility; they do not extend Apache's maintenance.
- KSM uses public `describeAcls`, `createAcls` and `deleteAcls` calls against broker listeners. **There is no metadata-backend setting in KSM.**
- Tests cover PLAINTEXT, SASL/PLAIN, mTLS, SASL/PLAIN over TLS and SCRAM-SHA-512 over TLS, with an enabled authorizer and a non-super-user KSM principal.

See [testing and qualification](docs/testing.md) for exact fixtures, commands and limits. Hosted Kafka providers, custom authorizers, Kerberos and OAuth handlers require their own qualification. See the [migration guide](docs/migration.md) before upgrading from Scala KSM or the direct ZooKeeper backend.

## How reconciliation works

1. Fetch and validate a complete source document.
2. Read the current ACLs through Kafka's Admin API.
3. Add missing ACLs, then remove ACLs absent from the source.
4. Report changes and repeat.

**The source is authoritative for every ACL visible to KSM.** There is no ownership filter. Include existing permissions that must survive, including KSM's own management ACLs, and run only one writer for that scope. KSM needs Cluster Describe and Alter on the literal `kafka-cluster` resource.

Invalid documents, authentication failures and unavailable sources abort the attempt. They never mean "delete all ACLs". An intentionally empty policy is a CSV header with no rows or YAML `users: {}`.

Kafka ACL batches are not transactional. A failed batch can have partially applied; the next attempt reads Kafka again and recomputes the delta. KSM repairs drift while it is running and able to reach both systems. It is not an intrusion-prevention boundary or an audit-log guarantee.

## Try it locally

Requires Java 25, Docker and Docker Compose. The example starts a disposable Kafka broker with authentication and authorization enabled.

```sh
./mvnw verify
docker compose up --build -d
docker compose logs -f ksm
```

The demo grants the `app` principal access to the `orders` topic and `orders-reader` group. Its policy is [example/demo-acls.csv](example/demo-acls.csv). Exercise those credentials:

```sh
printf 'hello from KSM\n' | docker compose exec -T kafka \
  /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka:9092 --topic orders \
  --producer.config /demo/demo-app.properties

docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:9092 --topic orders --group orders-reader \
  --consumer.config /demo/demo-app.properties --from-beginning \
  --max-messages 1 --timeout-ms 15000

docker compose down -v
```

The broker's host listener binds to `127.0.0.1:19092`; change `KSM_DEMO_PORT` if needed. The checked-in credentials are only for this local demo.

## Connect to your cluster

Download a [release](https://github.com/conduktor/kafka-security-manager/releases/tag/v2.0.0), or build with `./mvnw verify` to create the executable JAR and ZIP/tar.gz in `target/`. The examples below use the locally built JAR; adjust its path for a downloaded release. The ZIP launcher accepts `-Dconfig.file=/path/ksm.conf`; `JAVA_TOOL_OPTIONS` works with the JAR, launcher and container.

```sh
export ADMIN_CLIENT_BOOTSTRAP_SERVERS=localhost:9092
export SOURCE_CLASS=io.conduktor.ksm.source.FileSourceAcl
export SOURCE_FILE_FILENAME=/path/to/reviewed-acls.csv

# Validate the source and preview additions/removals. No writes.
java -jar target/kafka-security-manager-2.0.0.jar --dry-run

# Apply once, after reviewing the preview.
KSM_READONLY=false KSM_REFRESH_FREQUENCY_MS=0 \
  java -jar target/kafka-security-manager-2.0.0.jar
```

Supply your cluster's Kafka authentication properties as described in [configuration](docs/configuration.md). For continuous synchronization, set `KSM_READONLY=false` and a positive `KSM_REFRESH_FREQUENCY_MS` (default 10000).

**The default `KSM_READONLY=true` disables synchronization entirely.** Use `--dry-run` to actually read and preview. Once-mode failures exit nonzero. Continuous source/Kafka errors retry; a cycle exceeding its total deadline exits nonzero for a supervisor to restart.

Export current ACLs, including before migration:

```sh
KSM_EXTRACT_ENABLE=true KSM_EXTRACT_FORMAT=csv \
  java -jar target/kafka-security-manager-2.0.0.jar > current-acls.csv
```

Diagnostics go to stderr. CSV represents all supported resource and principal types. YAML export rejects ACL sets it cannot represent without loss.

For a local file source, write the complete new policy to a temporary file in the same directory, then atomically rename it over the configured file. An in-place write can expose a valid but incomplete policy and revoke permissions.

## Policy formats

CSV uses Kafka ACL fields. `PatternType` is optional for legacy documents and defaults to LITERAL. Quote values containing commas, such as certificate distinguished names.

CSV also supports `DelegationToken` and `User` resource ACLs. `User` resources require broker support (qualified on 3.9.2 and the listed 4.x releases, unavailable on 2.8.2); YAML has no representation for these two resource types.

```csv
KafkaPrincipal,ResourceType,PatternType,ResourceName,Operation,PermissionType,Host
User:manager,Cluster,LITERAL,kafka-cluster,Describe,Allow,*
User:manager,Cluster,LITERAL,kafka-cluster,Alter,Allow,*
User:app,Topic,PREFIXED,orders-,Write,Allow,*
User:app,Group,LITERAL,orders-reader,Read,Allow,*
```

YAML uses `User:` principals. A trailing `*` means PREFIXED; a single `*` means the literal wildcard.

```yaml
users:
  manager:
    clusters:
      kafka-cluster: [Describe, Alter]
  app:
    topics:
      orders-*: [Produce, Consume]
    groups:
      orders-reader: [Read]
    transactional_ids:
      orders-writer-*: [Write, Describe]
```

`Consume` expands to topic Describe/Read. `Produce` expands to topic Describe/Create/Write; it does not grant cluster-wide Create, IdempotentWrite or transactional permissions. Declare these explicitly when needed. The [migration guide](docs/migration.md) explains the correction to the old Produce helper.

Built-in sources: local file, HTTP (including Google IAM), GitHub, GitLab, Bitbucket Server, Bitbucket Cloud and S3. Notifications: console and Slack. Configuration names and Java extension interfaces are described in [configuration](docs/configuration.md) and [migration](docs/migration.md).

## Development

```sh
./mvnw verify                    # Java contracts, formatting, JAR and distributions
./mvnw spotless:apply            # Format Java
python3 scripts/broker-tests.py  # Real Kafka tests; Java 17 also needed for old brokers
```

The broker test runner downloads checksum-pinned Apache distributions, starts isolated local processes and exercises the packaged KSM JAR. [Testing](docs/testing.md) documents the full security, resilience and distribution checks. [Contributing](CONTRIBUTING.md) describes project scope and review expectations.

Licensed under [MIT](LICENSE.txt).
