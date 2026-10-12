# Policy formats

KSM treats the policy as the complete desired set of every ACL it can see. Export existing ACLs before editing a policy, and keep the management permissions KSM needs. The examples below illustrate formats; they are not a policy to apply unchanged to an existing cluster.

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

`Consume` expands to topic Describe/Read. `Produce` expands to topic Describe/Create/Write; it does not grant cluster-wide Create, IdempotentWrite or transactional permissions. Declare these explicitly when needed. The [migration guide](migration.md) explains the correction to the old Produce helper.

Built-in sources: local file, HTTP (including Google IAM), GitHub, GitLab, Bitbucket Server, Bitbucket Cloud and S3. Notifications: console and Slack. Configuration names and Java extension interfaces are described in [configuration](configuration.md) and [migration](migration.md).
