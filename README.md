[![Build Status](https://travis-ci.com/simplesteph/kafka-security-manager.svg?branch=master)](https://travis-ci.com/simplesteph/kafka-security-manager)

# Kafka Security Manager

Kafka Security Manager (KSM) allows you to manage your Kafka ACLs at scale by leveraging an external source as the source of truth. Zookeeper just contains a copy of the ACLs instead of being the source.

![Kafka Security Manager Diagram](https://i.imgur.com/BuikeuB.png)

There are several advantages to this:
- **Kafka administration is done outside of Kafka:** anyone with access to the external ACL source can manage Kafka Security
- **Prevents intruders:** if someone were to add ACLs to Kafka using the CLI, they would be reverted by KSM within 10 seconds.
- **Full auditability:** KSM provides the guarantee that ACLs in Kafka are those in the external source. Additionally, if for example your external source is GitHub, then PRs, PR approvals and commit history will provide Audit the full log of who did what to the ACLs and when
- **Notifications**: KSM can notify external channels (such as Slack) in order to give feedback to admins when ACLs are changed. This is particularly useful to ensure that 1) ACL changes are correctly applied 2) ACL are not changed in Kafka directly.

Your role is to ensure that Kafka Security Manager is never down, as it is now a custodian of your ACL.

A sample CSV to manage ACL is:
```
KafkaPrincipal,ResourceType,PatternType,ResourceName,Operation,PermissionType,Host
User:alice,Topic,LITERAL,foo,Read,Allow,*
User:bob,Group,bar,PREFIXED,Write,Deny,12.34.56.78
User:peter,Cluster,LITERAL,kafka-cluster,Create,Allow,*
```

**Important Note**: As of KSM 0.4, a new column `PatternType` has been added to match the changes that happened in Kafka 2.0. This enables KSM to manage `LITERAL` and `PREFIXED` ACLs. See #28

Current sources shipping with KSM include:
- File
- GitHub
- GitLab (using Personal Auth Tokens)
- BitBucket
- Amazon S3
- Google Secret Manager
- Build your own (and contribute back!)

# Building

```
sbt clean test
sbt universal:stage
```
Fat JAR:
```
sbt clean assembly
```
This is a Scala app and therefore should run on the JVM like any other application

# Artifacts

By using the JAR dependency, you can create your own `SourceAcl`.

SNAPSHOTS artifacts are deployed to [Sonatype](https://oss.sonatype.org/content/repositories/snapshots/com/github/simplesteph/ksm/)

RELEASES artifacts are deployed to [Maven Central](http://search.maven.org/#search%7Cga%7C1%7Ccom.github.simplesteph):

`build.sbt` (see [Maven Central](http://search.maven.org/#search%7Cga%7C1%7Ccom.github.simplesteph) for the latest `version`)
```
libraryDependencies += "com.github.simplesteph" %% "kafka-security-manager" % "version"
```

# Configuration

## Security configuration - Zookeeper client

Make sure the app is using a property file and launch options similar to your broker so that it can
1. Authenticate to Zookeeper using secure credentials (usually done with JAAS)
2. Apply Zookeeper ACL if enabled

*Kafka Security Manager does not connect to Kafka.*

Sample run for a typical SASL Setup:
```
target/universal/stage/bin/kafka-security-manager -Djava.security.auth.login.config=conf/jaas.conf
```

Where `conf/jaas.conf` contains something like:
```
Client {
    com.sun.security.auth.module.Krb5LoginModule required
    useKeyTab=true
    storeKey=true
    keyTab="/etc/kafka/secrets/zkclient1.keytab"
    principal="zkclient/example.com@EXAMPLE.COM";
};
```

## Security configuration - Admin client
When configured authorizer class is `com.github.simplesteph.ksm.compat.AdminClientAuthorizer`,
`kafka-security-manager` will use kafka admin client instead of direct zookeeper connection.
Configuration example would be
```
KafkaClient {
  org.apache.kafka.common.security.plain.PlainLoginModule required
  username="admin"
  password="admin-secret";
};
```

## Configuration file

For a list of configuration see [application.conf](src/main/resources/application.conf). You can customise them using environment variables or create your own `application.conf` file and pass it at runtime doing:
```
target/universal/stage/bin/kafka-security-manager -Dconfig.file=path/to/config-file.conf
```

Overall we use the [lightbend config](https://github.com/lightbend/config) library to configure this project.

## Environment variables
The [default configurations](src/main/resources/application.conf) can be overwritten using the following environment variables:

- `KSM_READONLY=false`: enables KSM to synchronize from an External ACL source. The default value is `true`, which prevents KSM from altering ACLs in Zookeeper
- `KSM_EXTRACT=true`: enable extract mode (get all the ACLs from Kafka formatted as a CSV)
- `KSM_REFRESH_FREQUENCY_MS=10000`: how often to check for changes in ACLs in Kafka and in the Source. 10000 ms by default. If it's set to `0` or negative value, for example `-1`, then KMS executes ACL synchronization just once and exits
- `AUTHORIZER_CLASS`: authorizer class for ACL operations. Default is `SimpleAclAuthorizer`, configured with
  - `AUTHORIZER_ZOOKEEPER_CONNECT`: zookeeper connection string
  - `AUTHORIZER_ZOOKEEPER_SET_ACL=true` (default `false`): set to true if you want your ACLs in Zookeeper to be secure (you probably do want them to be secure) - when in doubt set as the same as your Kafka brokers.

  No-zookeeper authorizer class on top of Kafka Admin Client is bundled with KSM as `com.github.simplesteph.ksm.compat.AdminClientAuthorizer`,
  configured with options for `org.apache.kafka.clients.admin.AdminClientConfig`:
  - `ADMIN_CLIENT_ID` - `client.id`, an id to pass to the server when making requests, for tracing/audit purposes, default `kafka-security-manager`
  Properties below are not provided to client unless environment variable is set:
  - `ADMIN_CLIENT_BOOTSTRAP_SERVERS` - `bootstrap.servers`
  - `ADMIN_CLIENT_SECURITY_PROTOCOL` - `security.protocol`
  - `ADMIN_CLIENT_SASL_JAAS_CONFIG` -`sasl.jaas.config` - alternative to system jaas configuration
  - `ADMIN_CLIENT_SASL_MECHANISM` - `sasl.mechanism`
  - `ADMIN_CLIENT_SSL_KEY_PASSWORD` - `ssl.key.password`
  - `ADMIN_CLIENT_SSL_KEYSTORE_LOCATION` - `ssl.keystore.location`
  - `ADMIN_CLIENT_SSL_KEYSTORE_PASSWORD` - `ssl.keystore.password`
  - `ADMIN_CLIENT_SSL_TRUSTSTORE_LOCATION` - `ssl.truststore.location`
  - `ADMIN_CLIENT_SSL_TRUSTSTORE_PASSWORD` - `ssl.truststore.password`

- `SOURCE_CLASS`: Source class. Valid values include
    - `com.github.simplesteph.ksm.source.NoSourceAcl` (default): No source for the ACLs. Only use with `KSM_READONLY=true`
    - `com.github.simplesteph.ksm.source.FileSourceAcl`: get the ACL source from a file on disk. Good for POC
    - `com.github.simplesteph.ksm.source.GitHubSourceAcl`: get the ACL from GitHub. Great to get started quickly and store the ACL securely under version control.
    - `com.github.simplesteph.ksm.source.GitLabSourceAcl`: get the ACL from GitLab using pesonal access tokens. Great to get started quickly and store the ACL securely under version control.
      - `SOURCE_GITLAB_REPOID` GitLab project id
      - `SOURCE_GITLAB_FILEPATH` Path to the ACL file in GitLab project
      - `SOURCE_GITLAB_BRANCH` Git Branch name
      - `SOURCE_GITLAB_HOSTNAME` GitLab Hostname
      - `SOURCE_GITLAB_ACCESSTOKEN` GitLab Personal Access Token. See [Personal access tokens
](https://docs.gitlab.com/ee/user/profile/personal_access_tokens.html) to authenticate with the GitLab API.
    - `com.github.simplesteph.ksm.source.S3SourceAcl`: get the ACL from S3. Good for when you have a S3 bucket managed by Terraform or Cloudformation. This requires `region`, `bucketname` and `objectkey`. See [Access credentials](https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html) for credentials management.
      - `SOURCE_S3_REGION` AWS S3 Region
      - `SOURCE_S3_BUCKETNAME` AWS S3 Bucket name
      - `SOURCE_S3_OBJECTKEY` The Object containing the ACL CSV in S3
    - `com.github.simplesteph.ksm.source.BitbucketServerSourceAcl`: get the ACL from Bitbucket Server using the v1 REST API. Great if you have private repos in Bitbucket.
    - `com.github.simplesteph.ksm.source.BitbucketCloudSourceAcl`: get the ACL from Bitbucket Cloud using the Bitbucket Cloud REST API v2.
    - `com.github.simplesteph.ksm.source.GoogleSecretManagerAcl`: get the ACL from Google Secret Manager using Google Cloud SDK. You can store ACL contents in many secrets as you want (secrets contents are limited in 64kb) and filter it using labels. Use the same ACL CSV layout and **do not include** the Header. This requires `projectid` and `label_filter`:
      - `SOURCE_GCP_PROJECTID`: GCP Project ID
      - `SOURCE_GCP_LABEL_FILTER`: You can filter Secrets using the format: `label=value,label2=value`
      - `GOOGLE_APPLICATION_CREDENTIALS`: One option to configure your GCP credentials. More information: [GCP Authentication](https://cloud.google.com/docs/authentication/getting-started)
- `NOTIFICATION_CLASS`: Class for notification in case of ACL changes in Kafka.
    - `com.github.simplesteph.ksm.notification.ConsoleNotification` (default): Print changes to the console. Useful for logging
    - `com.github.simplesteph.ksm.notification.SlackNotification`: Send notifications to a Slack channel (useful for devops / admin team)
- `ACL_PARSER_CSV_DELIMITER`: Change the delimiter character for the CSV Parser (useful when you have SSL)

# Running on Docker

## Building the image

```
./build-docker.sh
```

## Docker Hub

Alternatively, you can get the automatically built Docker images on [Docker Hub](https://hub.docker.com/r/simplesteph/kafka-security-manager)  

## Running

(read above for configuration details)

Then apply to the docker run using for example (in EXTRACT mode):

```
docker run -it -e AUTHORIZER_ZOOKEEPER_CONNECT="zookeeper-url:2181" -e KSM_EXTRACT=true \
            simplesteph/kafka-security-manager:latest
```

Any of the environment variables described above can be used by the docker run command with the `-e ` options.

## Example

```
docker-compose up -d
docker-compose logs kafka-security-manager
# view the logs, have fun changing example/acls.csv
docker-compose down
```

For full usage of the docker-compose file see [kafka-stack-docker-compose](https://github.com/simplesteph/kafka-stack-docker-compose)

## Extracting ACLs

You can initially extract all your existing ACL in Kafka by running the program with the config `extract=true` or `export KSM_EXTRACT=true`

Output should look like:
```
[2018-03-06 21:49:44,704] INFO Running ACL Extraction mode (ExtractAcl)
[2018-03-06 21:49:44,704] INFO Getting ACLs from Kafka (ExtractAcl)
[2018-03-06 21:49:44,704] INFO Closing Authorizer (ExtractAcl)

KafkaPrincipal,ResourceType,PatternType,ResourceName,Operation,PermissionType,Host
User:bob,Group,PREFIXED,bar,Write,Deny,12.34.56.78
User:alice,Topic,LITERAL,foo,Read,Allow,*
User:peter,Cluster,LITERAL,kafka-cluster,Create,Allow,*
```

You can then use place this CSV anywhere and use it as your source of truth.

# External API
To activate this feature, set `FEATURE_GRPC=true`.

## gRPC Endpoint
Kafka Security Manager exposes a GRPC endpoint to be consumed by external systems. The use case is to build a UI on top of KSM or some level of automation.

By default KSM binds to port 50051, but you can configure this using the `GRPC_PORT` environment variable.

## gRPC Gateway (REST Endpoint)
By default KSM binds to port 50052, but you can configure this using the `GRPC_GATEWAY_PORT` environment variable.

This provides a REST API to consume data from KSM.

## Service Definition

The API is defined according to the proto file in [src/main/protobuf/](src/main/protobuf/)

# Upgrade Notes
TODO: Mention to look for inter broker protocol version before doing this

# Compatibility

KSM Version | Kafka Version | Notes
--- | --- | ---
0.9-SNAPSHOT | 2.3.1 | TODO: Upgrade to Kafka 2.4.x (PR welcome)
0.8 | 2.3.1 | Add a "run once" mode
0.7 | 2.1.1 | Kafka Based ACL refresher available (no zookeeper dependency)
0.6 | 2.0.0 | important stability fixes - please update
0.5 | 2.0.0 |
0.4 | 2.0.0 | important change: added column 'PatternType' in CSV
0.3 | 1.1.x |
0.2 | 1.1.x | upgrade to 0.3 recommended
0.1 | 1.0.x | might work for earlier versions

# Contributing

You can break the API / configs as long as we haven't reached 1.0. Each API break would introduce a new version number.

PRs are welcome, especially with the following:
- Code refactoring  / cleanup / renaming
- External Sources for ACLs (JDBC, Microsoft AD, etc...)
- Notification Channels (Email, etc...)

Please open an issue before opening a PR.
