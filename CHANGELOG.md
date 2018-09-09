# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/en/1.0.0/)
and this project adheres to [Semantic Versioning](http://semver.org/spec/v2.0.0.html).

## [0.5-SNAPSHOT]
- 

## [0.4] - 09/09/2018
- Added S3 Acl Source (#27)
- Upgraded to Kafka 2.0
- New format to ACLs that allows Patterns (like prefixes)
- Upgrades to Docker Compose file
- Improvements to Docker Compose file
- Using OpenJDK-8 slim as the base image for Docker releases

## [0.3] - 13/06/2018
- Added gRPC endpoint to perform API calls on KSM (the goal is to build a UI on top of KSM)
- Feature flag for gRPC server (off by default)
- Added gRPC reflection service
- updated to `1.1.0-kafka1.1.0-nosr`
- Added gRPC gateway service (REST)
- Fixed a nasty stability issue (#20).
- using ScalaFMT instead of Scalariform
- Added Read-Only mode (enabled by default) to make KSM more safe for new users (setting is `KSM_READONLY` and should be explicitly set to `false` for production)
- Renamed a few environment variables in KSM (breaking)
- `NoAclSource` is now the default AclSource (to be used with `KSM_READONLY=true`)
- Artifacts are now deployed to Sonatype / Maven Central

## [0.2] - 05/05/2018
- Kafka 1.1.0
- Updated Embedded Kafka to v1.1.0-kafka1.1.0 (test dependency)


## [0.1] - 08/03/2018
- Kafka 1.0.1
- Initial Release
- Travis CI automation
- Docker Hub release
- Parsers: CsvAclParser
- Source: GitHub & File
- Notification: Console & Slack
- Extract ACLs from Kafka. 
- Tests including with Kafka running 
- GitHub Enterprise Support
- GitHub authentication Support
- Slack Notification Support