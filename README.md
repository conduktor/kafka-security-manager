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
sbt clean test
sbt universal:stage
```

This is a Scala app and therefore should run on the JVM like any other application


# Configuration 

## Security configuration

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

## Configuration file

For a list of configuration see [application.conf](src/main/resources/application.conf). You can customise them using environment variables or create your own `application.conf` file and pass it at runtime doing:
```
target/universal/stage/bin/kafka-security-manager -Dconfig.file=path/to/config-file.conf
```

Overall we use the [lightbend config](https://github.com/lightbend/config) library to configure this project.

## Environment variables 
The [default configurations](src/main/resources/application.conf) can be overwritten using the following environment variables:

- `EXTRACT=true`: enable extract mode (get all the ACLs from Kafka formatted as a CSV)
- `REFRESH_FREQUENCY_MS=10000`: how often to check for changes in ACLs in Kafka and in the Source. 10000 ms by default
- `AUTHORIZER_CLASS`: override the authorizer class if you're not using the `SimpleAclAuthorizer`
- `AUTHORIZER_ZOOKEEPER_CONNECT`: zookeeper connection string
- `AUTHORIZER_ZOOKEEPER_SET_ACL=true` (default `false`): set to true if you want your ACLs in Zookeeper to be secure (you probably do want them to be secure) - when in doubt set as the same as your Kafka brokers.  
- `SOURCE_CLASS`: Source class. Valid values include
    - `com.github.simplesteph.ksm.source.FileSourceAcl` (default): get the ACL source from a file on disk. Good for POC
    - `com.github.simplesteph.ksm.source.GitHubSourceAcl`: get the ACL from GitHub. Great to get started quickly and store the ACL securely under version control. 
- `NOTIFICATION_CLASS`: Class for notification in case of ACL changes in Kafka. 
    - `com.github.simplesteph.ksm.notification.ConsoleNotification` (default): Print changes to the console. Useful for logging
    - `com.github.simplesteph.ksm.notification.SlackNotification`: Send notifications to a Slack channel (useful for devops / admin team)

# Running on Docker

## Building the image

```
./build-docker.sh
```

Alternatively, you can get the automatically built Docker images on [Docker Hub](https://hub.docker.com/r/simplesteph/kafka-security-manager)  

## Running

(read above for configuration details)

Then apply to the docker run using for example:

```
docker run -it -e AUTHORIZER_ZOOKEEPER_CONNECT="localhost:2181" -e FOO=BAR \
            simplesteph/kafka-security-manager:latest \
            -Djava.security.auth.login.config=conf/jaas.conf
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

Add the entry to your `/etc/hosts` file
```
127.0.0.1 kafka1
```

## Extracting ACLs

You can initially extract all your existing ACL in Kafka by running the program with the config `extract=true` or `export EXTRACT=true`

Output should look like:
```
[2018-03-06 21:49:44,704] INFO Running ACL Extraction mode (ExtractAcl)
[2018-03-06 21:49:44,704] INFO Getting ACLs from Kafka (ExtractAcl)
[2018-03-06 21:49:44,704] INFO Closing Authorizer (ExtractAcl)

KafkaPrincipal,ResourceType,ResourceName,Operation,PermissionType,Host
User:bob,Group,bar,Write,Deny,12.34.56.78
User:alice,Topic,foo,Read,Allow,*
User:peter,Cluster,kafka-cluster,Create,Allow,*
```

You can then use place this CSV anywhere and use it as your source of truth. 

# Compatibility

KSM Version | Kafka Version
--- | ---
0.1 | 1.0.x (might work for earlier versions)

# Contributing

You can break the API / configs as long as we haven't reached 1.0. Each API break would introduce a new version number. 

PRs are welcome, especially with the following:
- Code refactoring  / cleanup / renaming
- External Sources for ACLs (JDBC, Microsoft AD, etc...)
- Notification Channels (Email, etc...) 

Please open an issue before opening a PR. 

# Used By...

PR to README.md to add your company