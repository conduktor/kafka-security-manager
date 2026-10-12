# Migrating to KSM 2

KSM 2 is a Java 25 application using Kafka's public Admin API. It preserves valid CSV/YAML policies, built-in sources, console/Slack notifications, environment variables unrelated to the removed backend, and the once/continuous/extract modes.

## Connection and runtime

Use Java 25 or the Java 25 container image. The runtime of your Kafka brokers is independent of KSM.

Configure `ADMIN_CLIENT_BOOTSTRAP_SERVERS` and Kafka authentication properties. Remove `AUTHORIZER_CLASS` if it refers to `SimpleAclAuthorizer` or another broker-side authorizer. The existing `io.conduktor.ksm.compat.AdminClientAuthorizer` name remains an accepted alias.

Remove `authorizer.config` and the `AUTHORIZER_ZOOKEEPER_CONNECT`, `AUTHORIZER_ZOOKEEPER_SET_ACL`, `AUTHORIZER_CONNECTION_TIMEOUT_MS` and `AUTHORIZER_SESSION_TIMEOUT_MS` variables. KSM rejects these settings with a migration message. There is no direct ZooKeeper backend or automatic translation of its credentials.

KSM needs an accessible broker listener, an enabled authorizer, and a Kafka principal allowed to describe and alter ACLs on the literal cluster resource `kafka-cluster`. The tests use Cluster Describe and Alter without making KSM a super-user. Include KSM's own visible ACLs in the source: the entire visible ACL set is authoritative.

The Scala artifact (`kafka-security-manager_2.12`) remains historical. Get the Java JAR or ZIP/tar.gz from the [2.0.0 release](https://github.com/conduktor/kafka-security-manager/releases/tag/v2.0.0), or use `conduktor/kafka-security-manager:v2.0.0`. The Docker `latest` tag now follows KSM 2; pin a version when upgrading. The Maven coordinates are `io.conduktor:kafka-security-manager:2.0.0` for local builds; this release is distributed through GitHub and Docker Hub, not Maven Central.

## Intentional corrections

| Previous behavior | KSM 2 |
| --- | --- |
| Empty/malformed CSV or an unknown YAML field could yield zero ACLs | Reject the whole input before reading or mutating Kafka |
| Empty source confused with an intentionally empty policy | Use a CSV header with no rows or `users: {}` for an intentional empty policy |
| YAML Produce emitted an invalid Cluster `*` ACL | Grant Describe/Create/Write on declared topics only; declare cluster-wide Create separately if intended |
| YAML export omitted `users`, lost principal types or renamed transactional IDs | Round-trip checked export; unsupported representations fail before stdout; use CSV for other resource/principal types |
| CSV exports did not quote commas in certificate principals | Quote and escape CSV fields |
| Errors could stop the scheduler with exit code 0 | Run-once failures exit nonzero; continuous recoverable errors retry |
| Fetch validators were cached before parsing succeeded | Accept validators only with a complete, validated snapshot |
| Old file timestamps and deletion could be missed | Read and validate the file on each cycle |
| GitLab's unchecked HEAD could hide errors; paths were not encoded | Fetch file content through the API and encode project, file and branch values |
| Bitbucket Server used web UI browse endpoints and commit polling | Fetch the selected branch through its raw-content REST endpoint |
| HTTP requests could block for days | Bound the whole request and response size; reject redirects |
| Google IAM key setting typo and restricted credential class | Correct `service-account-key` and accept credentials implementing `IdTokenProvider` |
| Extraction mixed logs with policy data | Data only on stdout; diagnostics on stderr |

`Consume` expands to Describe/Read on the topic. Declare Group Read separately. `Produce` does not grant idempotent or transactional permissions implicitly; declare the required Cluster IdempotentWrite and/or TransactionalId permissions for your clients.

CSV without `PatternType` still means LITERAL. Unknown or absent filename extensions still use CSV. YAML accepts `topics`, `groups`, `clusters` and `transactional_ids` only. Duplicate keys, unknown fields, malformed UTF-8, query patterns such as ANY/MATCH and invalid cluster names fail validation.

Use CSV for DelegationToken and User resource ACLs. User ACLs with CreateTokens/DescribeTokens are qualified on Kafka 3.9.2 and the listed 4.x releases; Kafka 2.8.2 does not support that resource type. Publish local file policies with atomic replacement as described in [configuration](configuration.md).

`KSM_READONLY=true` still disables synchronization completely. The new `--dry-run` validates and previews a delta once, requiring a source and Kafka ACL-read permissions. It never writes. Extraction and dry-run cannot be combined.

The continuous loop waits `ksm.refresh.frequency.ms` after each completed attempt. Cycles never overlap. Source failures do not trigger reconciliation from stale cached data. Unchanged responses reuse the last valid snapshot and re-read Kafka to correct drift. A failed Kafka batch may have partially applied; the next cycle recomputes the delta from the broker. Creates still precede deletes; a failed create batch prevents the delete phase.

Slack delivery is best effort and bounded by its request timeout. Delivery failures are logged separately from successful reconciliation. A cycle exceeding `ksm.cycle.timeout.ms` exits nonzero instead of starting overlapping work. A process supervisor can restart it after the timeout has been diagnosed.

Exit codes: `0` successful execution, extraction, preview or disabled run-once; `1` execution failure; `2` invalid arguments, obsolete environment variables or startup configuration rejected before execution.

## Migration procedure

1. Build and verify the Java version. Keep the previous artifact and configuration for rollback.
2. Export current ACLs through a working Admin API connection. Verify CSV parses back to the same set and review it against the intended policy.
3. Port custom extensions, then run `--dry-run` with the complete source. Review every removal, including KSM's own ACLs.
4. Stop the old writer before enabling writes in the new process. Do not run two controllers on the same ACL scope.
5. Run one reconciliation, compare observed ACLs and exercise application credentials, then enable continuous mode.
6. For rollback, stop the new writer first. Re-read Kafka state and restore the last reviewed policy with the previous artifact. A partially applied batch has no automatic rollback transaction.

## Java extensions

The Scala SPI is not binary compatible. Recompile extensions against the Java interfaces; built-in class names remain valid.

- Implement `SourceAcl` with a public constructor taking `AclParserRegistry`. `configPrefix()` selects `source.<prefix>`, merged with `source.defaults`.
- `fetch()` returns a complete `SourceSnapshot`, `Optional.empty()` only for confirmed unchanged content, or throws on failure. Never return unchanged on the first read. The non-throwing `accept` callback records a validator after content validation.
- Implement `Notification` with a public no-argument constructor and a configuration prefix.
- `AclParser` consumes a complete string and returns a set of public Kafka `AclBinding` objects. Reject the entire document if any entry is invalid.
- Launch extensions with `java -cp 'lib/*:extensions/*' io.conduktor.ksm.KafkaSecurityManager`. `java -jar` does not load arbitrary neighboring JARs.

Custom broker authorizers are server components, not KSM plugins. The server authorizer must implement Kafka's ACL-management API.
