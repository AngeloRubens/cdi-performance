#!/usr/bin/env bash
# Builds and benchmarks one or more patched Weld branches, one after another (they share the same
# SNAPSHOT version, so each is installed into ~/.m2 right before it is benchmarked).
# usage: scripts/bench-patched.sh <repo-url> "<label>=<ref> [<label>=<ref> ...]"
set -uo pipefail
repo=$1; specs=$2
failed=0
for spec in $specs; do
  label=${spec%%=*}; ref=${spec#*=}
  if version=$(scripts/build-weld.sh "$repo" "$ref" | tail -1) && [ -n "$version" ]; then
    echo "== $label: $ref -> $version"
    if ! scripts/bench.sh "$label" -PWeld -Dweld.version="$version"; then
      echo "benchmark of $label failed" >&2
      failed=1
    fi
  else
    echo "build of $ref failed" >&2
    failed=1
  fi
done
exit "$failed"
