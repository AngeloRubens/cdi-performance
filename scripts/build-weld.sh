#!/usr/bin/env bash
# Builds weld-se-core (and the modules it needs) from a git repository/ref and installs it into ~/.m2.
# usage: scripts/build-weld.sh <repo-url> <ref>      prints the built version on the last line
set -euo pipefail
repo=$1; ref=$2
dir=${WELD_SRC:-$RUNNER_TEMP/weld-src}
rm -rf "$dir"
git clone -q --depth 1 --branch "$ref" "$repo" "$dir"
(cd "$dir" && git log -1 --format='weld source: %H %s' >&2 \
   && mvn -B -ntp -q install -DskipTests -Dno-format -pl environments/se/core -am >&2)
(cd "$dir" && mvn -B -ntp -q help:evaluate -Dexpression=project.version -DforceStdout)
