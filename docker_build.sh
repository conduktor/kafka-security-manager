#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
./mvnw -B -ntp verify
docker build -t "${KSM_IMAGE:-conduktor/kafka-security-manager:local}" .
