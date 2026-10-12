# Testing and qualification

Use Java 25 for KSM and Python 3.12 or newer for the fixtures. Java 17 is also needed to launch Kafka 2.x/3.x. Docker is only required for the distribution tests.

Run the [required static checks](../CONTRIBUTING.md#required-checks) before the broker suites. `./scripts/lint.sh` checks Python, shell, workflows and Markdown; `./mvnw verify` also enforces formatting, compiler warnings and SpotBugs.

## Commands

```sh
./mvnw verify
python3 -m unittest discover -s scripts -p 'test_*.py'

# Seven broker configurations; SASL/PLAIN transport by default.
python3 scripts/broker-tests.py \
  --java25 "$JAVA_HOME" --java17 "$JAVA17_HOME"

# Full qualification: seven configurations, five security profiles.
python3 scripts/broker-tests.py \
  --java25 "$JAVA_HOME" --java17 "$JAVA17_HOME" \
  --profiles sasl-plain plaintext mtls sasl-tls scram-tls

# Three separate controllers, two brokers and injected failures.
python3 scripts/resilience-tests.py --java25 "$JAVA_HOME"

# Quickstart, ZIP/tar.gz launchers, amd64 and arm64 images.
python3 scripts/verify-distribution.py
```

All commands fail on infrastructure errors and failed assertions. `./mvnw verify` intentionally runs the tests without external brokers; it does not qualify Kafka by itself. Failsafe's `broker-it` profile is an internal entry point used by the test runner with an existing fixture directory, not a cluster launcher.

The test runner downloads official Apache archives and checks their SHA-512 against [broker-matrix.json](../scripts/broker-matrix.json). `--cache /path` or `KSM_KAFKA_CACHE` selects a reusable cache. Each invocation has an isolated output directory and starts/stops only its own processes. Run one broker test invocation at a time per checkout: Maven's Failsafe report directory is shared.

The manifest lists the Apache CDN and Huawei Cloud mirror before the Apache archive fallback. Every downloaded or cached archive must match the pinned Apache SHA-512 before extraction. Interrupted transfers resume within the same mirror; unavailable mirrors fall back, and checksum mismatches fail immediately. CI caches the verified archives. The Python contract tests exercise fallback, resumption and rejection of corrupt downloads/cache entries through a local HTTP server.

To focus a broker test:

```sh
python3 scripts/broker-tests.py --cases 4.3.1-kraft --profiles scram-tls
python3 scripts/verify-distribution.py --platforms linux/arm64
```

## Broker matrix

The metadata mode below configures the test broker, never KSM.

| Kafka | Fixture | Broker JVM | KSM JVM/client | Security profiles |
| --- | --- | --- | --- | --- |
| 2.8.2 | ZooKeeper | 17 | 25 / 4.3.1 | All five |
| 3.9.2 | ZooKeeper | 17 | 25 / 4.3.1 | All five |
| 3.9.2 | KRaft | 17 | 25 / 4.3.1 | All five |
| 4.0.2 | KRaft | 25 | 25 / 4.3.1 | All five |
| 4.1.2 | KRaft | 25 | 25 / 4.3.1 | All five |
| 4.2.2 | KRaft | 25 | 25 / 4.3.1 | All five |
| 4.3.1 | KRaft | 25 | 25 / 4.3.1 | All five |

The five profiles are PLAINTEXT, SASL_PLAINTEXT/PLAIN, SSL with client certificates, SASL_SSL/PLAIN and SASL_SSL/SCRAM-SHA-512. TLS tests verify trust and hostname failures. Invalid-password tests verify authentication failure without credential disclosure.

The authorizer is active and denies access without ACLs. KSM's manager identity is **not** a super-user. A separate fixture administrator bootstraps management permissions; application and outsider identities exercise allowed and denied operations. On the PLAINTEXT listener KSM is ANONYMOUS, while the other identities use the fixture's authenticated internal listener.

For each case, `BrokerIT` launches the packaged KSM in child JVMs. It covers dry-run/disabled/once/continuous modes, create/delete, duplicate/idempotent policy application, externally introduced drift, literal/prefixed/wildcard ACLs, DENY precedence, CSV/YAML export/reimport, invalid-source preservation and unauthorized administration. Actual producers and consumers cover writes, reads, groups, transactions and revocation. The YAML Produce regression must permit creation of the declared topic and reject creation of an unrelated topic.

Older exact versions remain qualified migration targets, not maintained Kafka distributions. No claim covers unlisted releases, every Kafka-compatible hosted provider, custom authorizers or a live ZooKeeper-to-KRaft migration. Public AdminClient property pass-through does not certify an untested authentication plugin.

CSV creation, extraction and removal cover `DelegationToken` on every fixture and `User` resources with CreateTokens/DescribeTokens on 3.9.2 and 4.x fixtures. Kafka 2.8.2 does not support the User resource type. These checks manage ACLs; they do not issue delegation tokens. YAML export must reject both resource types without partial output.

## Failure and source tests

The separate-controller fixture uses SASL/PLAIN and root-only super-user access. It verifies ACL propagation through both brokers, loss of a CreateAcls response after request forwarding, recovery after connection failure, controller leader election, total broker outage, restart with existing metadata, missing/malformed sources and process restart without an in-memory cache. Its Slack endpoint is deliberately unavailable.

The proxy records the actual ACL count during an uncertain write. The count may show that all, some or none of a batch applied; the required outcome is a reported failed attempt followed by convergence from a new broker read. Unit tests additionally force per-phase partial failure and verify that failed creates prevent deletes.

Local HTTP contract tests cover every built-in remote source, conditional requests, malformed responses, redirects, authentication headers, path encoding, response limits and timeouts. S3 tests use the real AWS SDK against a local HTTP server. Google IAM tests exercise the credential-provider interface and token cache with a fake provider. These are not live GitHub/GitLab/Bitbucket/AWS/GCP account certification.

Parser tests retain the valid legacy examples and reject the corruption cases found in the audit. Randomized set-difference checks verify reconciliation independently of order. Process tests cover external configuration, precedence, exit codes, cycle deadlines and completion of resource cleanup after SIGTERM.

## Distribution checks and evidence

Distribution tests start the README Compose example from fresh storage, produce and consume with application credentials, then stop its continuous writer. CI sets `KSM_IMAGE` to an isolated local image and builds the current checkout; the README uses the published image with `--no-build`. Each ZIP/tar.gz launcher and Docker architecture applies a changed policy, extracts the result and restores the initial policy through the real Admin API. The launcher test includes a configuration path containing spaces.

Docker images run as UID/GID 10001. The test executes the Java runtime and records architecture, image ID and whether the engine uses emulation. Local cross-architecture execution requires Docker emulation; CI uses native amd64 and arm64 runners.

Reports live under `target/broker-it/`, `target/resilience-it/` and `target/distribution-it/`. They include verdicts, artifact checksums, JUnit reports where applicable, process logs and disposable fixture configurations. Credentials in these files are generated test values, never cluster secrets. Archive evidence before `mvn clean`.

CI runs the matrix from the same JSON manifest and retains reports even on failure. A release requires the complete matrix, resilience tests, both distributions/architectures, and a reviewed dependency/image scan. CI does not publish automatically.
