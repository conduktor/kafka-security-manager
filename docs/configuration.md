# Configuration

Defaults and environment mappings are in [reference.conf](../src/main/resources/reference.conf). Typesafe Config loads these defaults, application overrides, an optional `-Dconfig.file=/path/ksm.conf` and JVM system properties. System properties take precedence. Environment mappings are optional substitutions in the defaults; an explicit setting in your configuration file overrides that default mapping.

KSM connects through the Kafka Admin API. Set `ADMIN_CLIENT_BOOTSTRAP_SERVERS` or use:

```hocon
authorizer.admin-client-config {
  "bootstrap.servers" = "broker:9092"
  "security.protocol" = "SASL_SSL"
  "sasl.mechanism" = "SCRAM-SHA-512"
  "sasl.jaas.config" = ${?ADMIN_CLIENT_SASL_JAAS_CONFIG}
  "ssl.truststore.location" = "/config/truststore.p12"
  "ssl.truststore.password" = ${?ADMIN_CLIENT_SSL_TRUSTSTORE_PASSWORD}
  "default.api.timeout.ms" = 15000
  "request.timeout.ms" = 10000
}
```

Quote dotted Kafka property names. Additional public AdminClient properties pass through unchanged. SSL keystore/key/truststore passwords, SASL mechanism, JAAS and security-protocol environment variables retain their `ADMIN_CLIENT_*` names. Kerberos/OAuth or provider-specific handlers can be configured through these properties but are not certified by the local test matrix.

| Setting | Default | Behavior |
| --- | --- | --- |
| `KSM_READONLY` | `true` | Disable synchronization; no source fetch or Kafka connection |
| `KSM_REFRESH_FREQUENCY_MS` | `10000` | Delay after a cycle; zero/negative runs once |
| `KSM_EXTRACT_ENABLE` | `false` | Export observed ACLs |
| `KSM_EXTRACT_FORMAT` | `csv` | `csv`, `yaml` or `yml` |
| `KSM_NUM_FAILED_REFRESHES_BEFORE_NOTIFICATION` | `1` | Consecutive failed cycles before notification; reset after success |
| `KSM_CYCLE_TIMEOUT_MS` | `60000` | Total cycle deadline; exceeding it exits nonzero |
| `ACL_PARSER_CSV_DELIMITER` | `,` | One-character CSV delimiter |
| `SOURCE_CLASS` | `io.conduktor.ksm.source.NoSourceAcl` | NoSource is only useful with synchronization disabled |
| `NOTIFICATION_CLASS` | `io.conduktor.ksm.notification.ConsoleNotification` | Console or Slack |

Use `JAVA_TOOL_OPTIONS=-Dconfig.file=/config/ksm.conf` for JVM options with the ZIP launcher or Docker. The launcher also accepts `-Dproperty=value` arguments, preserving the legacy invocation style. `KSM_DEBUG=true` enables KSM debug counts without enabling Kafka credential/configuration logging.

## Sources

Built-ins retain their `io.conduktor.ksm.source.*` names and environment mappings.

| Class | Prefix | Values |
| --- | --- | --- |
| `FileSourceAcl` | `source.file` | `filename` / `SOURCE_FILE_FILENAME` |
| `HttpSourceAcl` | `source.http` | `url`, `parser`, `method`, `auth.type` |
| `GitHubSourceAcl` | `source.github` | `user`, `repo`, `filepath`, `branch`, `hostname`; optional `auth.basic` or `auth.token` |
| `GitLabSourceAcl` | `source.gitlab` | `repoid`, `filepath`, `branch`, `hostname`, `accesstoken` |
| `BitbucketServerSourceAcl` | `source.bitbucket-server` | `hostname`, `port`, `protocol`, `project`, `repo`, `filepath`, optional `branch`, `auth.username/password` |
| `BitbucketCloudSourceAcl` | `source.bitbucket-cloud` | `api.url`, `organization`, `repo`, `filepath`, `auth.username/password`; optional `branch`, default master |
| `S3SourceAcl` | `source.s3` | `region`, `bucketname`, `objectkey`; AWS SDK v2 default credential chain |

Repository paths and branches are URL-encoded. Redirects are rejected, preventing authentication headers from being forwarded to another destination. ETag/Last-Modified are used when provided; otherwise KSM fetches again and compares ACL sets.

`source.defaults.request.timeout.ms=10000` and `source.defaults.max.bytes=8388608` apply to all sources; override them per source. Maximum configurable source size is 64 MiB. UTF-8 is required. Files are also size-limited.

Publish local policy changes by writing a complete temporary file in the same directory and atomically renaming it over the configured filename. Do not truncate and rewrite the live file: even a syntactically valid partial policy is authoritative. A file mounted individually into a container may remain attached to its old inode after replacement; mount the containing directory when updating files this way.

HTTP authentication supports `NONE` and `googleiam` (also `GOOGLE_IAM`). Google IAM uses `auth.googleiam.target-audience` and optional JSON `service-account-key`; otherwise it uses application default credentials implementing `IdTokenProvider`. The historical `service-account` field does not enable impersonation. Unknown authentication types fail configuration.

S3 uses AWS SDK v2; review credential-provider precedence if multiple credential sources were configured. `source.s3.endpoint` supports a custom endpoint and enables path-style addressing. Provider compatibility beyond the local S3 HTTP contract tests requires separate qualification.

## Notifications and errors

Slack uses `notification.slack.webhook`, `username`, `icon` and `channel` with their existing environment mappings. `notification.defaults.request.timeout.ms=10000` bounds requests.

Diagnostics go to stderr. Source, parsing and Kafka failures prevent success notifications. Delivery failures do not change the ACL result. Failed requests never become empty desired ACL sets.

On SIGTERM, synchronization stops and the process allows up to seven seconds for resource cleanup. Give the container or service supervisor a longer termination grace period. Custom extensions must keep their own `close()` methods bounded.
