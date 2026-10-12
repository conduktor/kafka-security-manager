#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."

ruff check scripts
ruff format --check scripts
shellcheck --severity=style bin/kafka-security-manager docker_build.sh docker_push.sh scripts/*.sh
actionlint

node --check tools/lint/markdown.mjs
node tools/lint/markdown.mjs
