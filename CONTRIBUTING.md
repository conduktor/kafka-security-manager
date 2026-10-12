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

## Required checks

Every PR runs the following checks. A finding fails the job; the Kafka matrix starts only after lint and Java contracts pass.

| Area | Blocking checks |
| --- | --- |
| Java | Spotless formatting, all javac warnings treated as errors, SpotBugs at maximum effort including low-priority findings |
| Python fixtures | Ruff lint (E, F, I, UP, B, RUF) and formatting |
| Shell scripts | ShellCheck, including style findings |
| GitHub Actions | actionlint, including embedded shell checks |
| Markdown | markdownlint on all tracked and new, non-ignored Markdown files |

SpotBugs exceptions are limited to documented SPI and framework lifecycle cases in [the filter](config/spotbugs-exclude.xml). Do not add a project-wide exclusion to silence a new finding. Line-length rules are disabled for prose/URLs and Python fixture strings; Markdown HTML is limited to `details` and `summary`. The generated Maven wrapper is kept upstream and is not included in ShellCheck.

For local script and documentation checks, install Node 22+, Python 3.12+, ShellCheck 0.11.0 and actionlint 1.7.12, then:

```sh
python3 -m venv .venv
. .venv/bin/activate
python3 -m pip install --requirement tools/lint/requirements.txt
npm ci --prefix tools/lint --ignore-scripts
./scripts/lint.sh
./mvnw verify
```

Ruff and Markdown dependencies are pinned in `tools/lint`; CI verifies SHA-256 checksums for the ShellCheck and actionlint binaries. The Markdown runner uses literal Git paths, and KaTeX is pinned to its security patch in the tooling lockfile. These packages are development tools and are not included in the KSM image or JAR.

## Release preparation

Update the project version and distribution paths together, rerun the entire qualification, review dependency and image scans, and attach the reports to the release candidate. CI builds and tests; it does not automatically publish from a branch or tag.

Publishing artifacts or merging existing pull requests is a separate maintainer action. The scripts only target disposable local fixtures for testing.

Docker releases target [`conduktor/kafka-security-manager`](https://hub.docker.com/r/conduktor/kafka-security-manager) on Docker Hub, for both amd64 and arm64. After updating the project version and distribution paths and completing qualification, use `./docker_push.sh vMAJOR.MINOR.PATCH` with an account authorized to push there. The script rejects tags that do not match the Maven project version. Verify the published version's platforms and startup, then promote its manifest to `latest` with `docker buildx imagetools create --tag conduktor/kafka-security-manager:latest conduktor/kafka-security-manager:vMAJOR.MINOR.PATCH`. Never overwrite an existing version tag. Historical `v1.x-release` tags remain Scala builds.

Create a Git tag at the qualified commit and publish a GitHub release with the executable JAR, ZIP, tar.gz and SHA-256 checksums. Include migration changes and the qualification run in the release notes. KSM 2 releases are distributed through GitHub and Docker Hub; Maven Central publishing is not configured.

The Docker Hub [overview](docs/dockerhub.md) and [short description](docs/dockerhub-description.txt) are kept in this repository. Update their image-status text when publishing a release, then synchronize both fields through the Docker Hub API.
