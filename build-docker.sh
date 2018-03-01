#!/usr/bin/env bash
set -ex

./test.sh
sbt docker:publishLocal