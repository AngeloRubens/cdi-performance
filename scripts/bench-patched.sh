#!/usr/bin/env bash
# Builds and benchmarks one or more patched Weld branches, one after another (they share the same
# SNAPSHOT version, so each is installed into ~/.m2 right before it is benchmarked).
# usage: scripts/bench-patched.sh <repo-url> "<label>=<ref> [<label>=<ref> ...]"
set -uo pipefail
repo=$1; specs=$2
for spec in $specs; do
  label=${spec%%=*}; ref=${spec#*=}
  if version=$(scripts/build-weld.sh "$repo" "$ref" | tail -1) && [ -n "$version" ]; then
    echo "== $label: $ref -> $version"
    scripts/bench.sh "$label" -PWeld -Dweld.version="$version" || echo "benchmark of $label failed"
  else
    echo "build of $ref failed"
  fi
done
