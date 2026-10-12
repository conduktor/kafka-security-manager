# Kafka Security Manager

[![Docker image](https://img.shields.io/badge/Docker-v2.0.0-2496ED?logo=docker&logoColor=white)](https://hub.docker.com/r/conduktor/kafka-security-manager)
[![Java CI](https://github.com/conduktor/kafka-security-manager/actions/workflows/ci.yml/badge.svg)](https://github.com/conduktor/kafka-security-manager/actions/workflows/ci.yml)

Keep Kafka permissions in a reviewed file and let Kafka Security Manager (KSM) apply them. KSM runs as a **Docker container**, reads your CSV or YAML policy, and keeps your cluster's access control lists (ACLs) in sync with it.

```sh
docker pull conduktor/kafka-security-manager:v2.0.0
```

Available for **amd64 and arm64**, with Java included. KSM is an open-source project by [Conduktor](https://www.conduktor.io/). Upgrading from KSM 1? Start with the [migration guide](docs/migration.md).

## Why use KSM?

Manually adding Kafka permissions is easy to start and hard to keep track of. With KSM, your policy becomes something you can review in a pull request, keep in version control and apply repeatedly.

KSM reads a policy from a local file, GitHub, GitLab, Bitbucket, HTTP or S3. It adds missing permissions and removes permissions absent from the policy. Run it once as part of a deployment, or leave it running to keep Kafka aligned with your policy. You can preview changes before enabling writes.

## Try it with Docker

With Git, Docker and Docker Compose installed, you can start a disposable Kafka cluster and KSM together. The demo includes example credentials and a policy that lets an application produce and consume messages.

```sh
git clone https://github.com/conduktor/kafka-security-manager.git
cd kafka-security-manager
docker compose up --no-build -d
docker compose logs -f ksm
```

Wait for `Reconciliation succeeded` in the logs. The demo application can now use the `orders` topic. See [the demo walkthrough](docs/demo.md) to send and read a message using its credentials.

Press Ctrl-C to leave the logs. When you're done, remove the demo:

```sh
docker compose down -v
```

The demo uses port `19092` on your machine; set `KSM_DEMO_PORT` if it's already in use. Its example credentials are for local testing only.

## Use it with your Kafka cluster

Start by exporting your existing permissions so you can review a complete policy. KSM needs a broker address reachable **from inside the container** and credentials allowed to manage ACLs. If Kafka runs on your host, `localhost` inside Docker refers to the container; use an address your Docker environment can reach, and check the broker's advertised listeners too.

Create a `ksm.env` file with your connection and source settings:

```dotenv
ADMIN_CLIENT_BOOTSTRAP_SERVERS=kafka.example.com:9092
SOURCE_CLASS=io.conduktor.ksm.source.FileSourceAcl
SOURCE_FILE_FILENAME=/config/acls.csv
```

Add your Kafka authentication and TLS settings using the [configuration guide](docs/configuration.md). The KSM identity needs Cluster Describe and Alter permissions on `kafka-cluster`.

### Export, edit and preview

```sh
mkdir -p ksm
docker run --rm --env-file ksm.env \
  -e KSM_EXTRACT_ENABLE=true \
  conduktor/kafka-security-manager:v2.0.0 > ksm/acls.csv
```

Check that the export succeeded, then edit `ksm/acls.csv`. The [policy format guide](docs/policies.md) includes CSV and YAML examples.

**Your policy controls every ACL visible to KSM.** Keep all permissions that should survive, including KSM's own management permissions. Preview additions and removals before applying the file:

```sh
docker run --rm --env-file ksm.env \
  -v "$PWD/ksm:/config:ro" \
  conduktor/kafka-security-manager:v2.0.0 --dry-run
```

The container runs as user `10001`; the mounted directory and policy must be readable by that user. Mount the whole directory so KSM sees new policy files when you replace them.

### Apply the reviewed policy

To synchronize once and exit:

```sh
docker run --rm --env-file ksm.env \
  -v "$PWD/ksm:/config:ro" \
  -e KSM_READONLY=false -e KSM_REFRESH_FREQUENCY_MS=0 \
  conduktor/kafka-security-manager:v2.0.0
```

For continuous synchronization, run one KSM instance for this ACL scope:

```sh
docker run -d --name ksm --restart unless-stopped --env-file ksm.env \
  -v "$PWD/ksm:/config:ro" \
  -e KSM_READONLY=false \
  conduktor/kafka-security-manager:v2.0.0
```

KSM checks the source every 10 seconds by default. Publish file changes by writing a complete temporary file and renaming it over `acls.csv`; editing the live file in place can expose an incomplete policy. An invalid or unavailable source aborts that attempt without treating it as an empty policy.

Synchronization is disabled by default (`KSM_READONLY=true`). Use `--dry-run` to read and preview, and `KSM_READONLY=false` to enable writes. The [configuration guide](docs/configuration.md) covers other sources, notifications and error handling.

Prefer to run without Docker? Download the executable JAR or ZIP/tar.gz from the [release](https://github.com/conduktor/kafka-security-manager/releases/tag/v2.0.0). You'll need Java 25; the archive launcher accepts `-Dconfig.file=/path/to/ksm.conf`.

## If you need KSM, you probably need Conduktor

KSM handles synchronization from an ACL policy. When several teams need to own applications, delegate administration and request access to data, [Conduktor](https://www.conduktor.io/) goes further.

Conduktor provides [resource-scoped RBAC](https://docs.conduktor.io/guide/conduktor-in-production/admin/set-up-rbac), [self-service access requests and application ownership](https://docs.conduktor.io/guide/conduktor-concepts/self-service), and Kafka operations across teams and clusters. Console RBAC governs what people can do in Console and its API; service-account permissions govern applications' access to Kafka.

## Documentation and contributing

- [Configuration](docs/configuration.md): authentication, policy sources, notifications and runtime settings.
- [Policy formats](docs/policies.md): CSV/YAML examples and permission helpers.
- [Migration from KSM 1](docs/migration.md): intentional changes and a rollout procedure.
- [Testing](docs/testing.md) and [contributing](CONTRIBUTING.md): local checks, real Kafka tests and project scope.

Licensed under [MIT](LICENSE.txt).

<details>
<summary>Compatibility and supported versions</summary>

- KSM 2 runs on **Java 25 LTS**, included in the Docker image. Your Kafka brokers can use a different Java runtime.
- Docker images support **linux/amd64 and linux/arm64**. Pin `conduktor/kafka-security-manager:v2.0.0` for this release. `latest` follows KSM 2; historical `v1.x-release` tags remain Scala builds.
- The minimum qualified Kafka release is **2.8.2**. Exact tested releases: **2.8.2, 3.9.2, 4.0.2, 4.1.2, 4.2.2 and 4.3.1**. Unlisted releases are not covered by this qualification.
- KSM uses Kafka's public Admin API (`describeAcls`, `createAcls`, `deleteAcls`). It does not connect to ZooKeeper or need a metadata-backend setting.
- Tests cover PLAINTEXT, SASL/PLAIN, mTLS, SASL/PLAIN over TLS and SCRAM-SHA-512 over TLS, with an enabled authorizer and a non-super-user KSM identity.
- Hosted Kafka providers, custom authorizers, Kerberos and OAuth handlers need their own qualification. Older tested brokers provide migration compatibility; they do not extend Apache's maintenance.

Kafka ACL batches are not transactional: a failed operation may have partially applied. KSM reads the broker state again on the next attempt and adds missing ACLs before removing obsolete ones. An intentionally empty policy is a CSV header with no rows or YAML `users: {}`.

See [testing and qualification](docs/testing.md) for the exact fixtures and limits, and [migration](docs/migration.md) before upgrading from Scala KSM or direct ZooKeeper access.

</details>
