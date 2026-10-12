#!/usr/bin/env bash
# Explicit manual publishing only; CI never calls this script.
set -euo pipefail
cd -- "$(dirname -- "$0")"
if [[ $# != 1 || ! $1 =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.-]+)?$ ]]; then
  echo "Usage: $0 vMAJOR.MINOR.PATCH (after all qualification checks)" >&2
  exit 2
fi
project_version=$(./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version)
if [[ "$project_version" != "${1#v}" ]]; then
  echo "Refusing to publish: tag $1 does not match project version $project_version" >&2
  exit 2
fi
image="conduktor/kafka-security-manager:$1"
./mvnw -B -ntp verify
# Publishing requires both architectures to have passed verify-distribution.py first.
docker buildx build --platform linux/amd64,linux/arm64 -t "$image" --push .
