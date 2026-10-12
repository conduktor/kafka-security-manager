# Contributing

KSM reconciles Kafka ACLs from one authoritative policy through the Kafka Admin API.

Useful contributions improve correctness, compatibility, diagnostics, source reliability, documentation and tests. Proposals for team RBAC, approval workflows or a self-service portal belong in a broader access-management product such as [Conduktor](https://www.conduktor.io/). Discuss substantial additions before implementing them.

## Before opening a pull request

Use Java 25 and the checked-in Maven wrapper:

```sh
./mvnw spotless:apply
./mvnw verify
python3 scripts/broker-tests.py
```

Include a failing example for a bug, the resulting behavior and the commands you ran. ACL changes must preserve the rule that an unavailable or invalid source cannot become an empty desired policy. Cover any changed Kafka behavior with real broker tests, not only mocks.

Run the security, resilience and distribution suites documented in [testing](docs/testing.md) when changing those areas. The full qualification is required before a release. A skipped test does not qualify a broker, source provider or authentication mechanism.

Keep the [migration guide](docs/migration.md), [configuration reference](src/main/resources/reference.conf) and compatibility matrix consistent with behavior. Explain intentional compatibility changes. Do not claim support for an untested Kafka version or provider.

## Release preparation

Update the project version and distribution paths together, rerun the entire qualification, review dependency and image scans, and attach the reports to the release candidate. CI builds and tests; it does not automatically publish from a branch or tag.

Publishing artifacts or merging existing pull requests is a separate maintainer action. The scripts only target disposable local fixtures for testing.

Docker releases target [`conduktor/kafka-security-manager`](https://hub.docker.com/r/conduktor/kafka-security-manager) on Docker Hub, for both amd64 and arm64. After updating the project version and distribution paths and completing qualification, use `./docker_push.sh vMAJOR.MINOR.PATCH` with an account authorized to push there. The script rejects tags that do not match the Maven project version. Verify the published version's platforms and startup, then promote its manifest to `latest` with `docker buildx imagetools create --tag conduktor/kafka-security-manager:latest conduktor/kafka-security-manager:vMAJOR.MINOR.PATCH`. Never overwrite an existing version tag. Historical `v1.x-release` tags remain Scala builds.

Create a Git tag at the qualified commit and publish a GitHub release with the executable JAR, ZIP, tar.gz and SHA-256 checksums. Include migration changes and the qualification run in the release notes. KSM 2 releases are distributed through GitHub and Docker Hub; Maven Central publishing is not configured.

The Docker Hub [overview](docs/dockerhub.md) and [short description](docs/dockerhub-description.txt) are kept in this repository. Update their image-status text when publishing a release, then synchronize both fields through the Docker Hub API.
