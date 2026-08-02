#!/usr/bin/env bash
#
# Compiles and runs the weather-model invariant checks.
#
# The model packages (api, util, weather) deliberately have no Minecraft or Fabric imports, so
# this needs nothing but a JDK -- no Gradle, no decompiled sources, no game. It runs in seconds
# and is the fastest way to know the invariants still hold after a change.
#
#     ./tools/run-model-harness.sh
#
# Exits non-zero if any check fails.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/vibeweather-harness.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT

# Only the pure packages. Server and client code pull in Minecraft and cannot build standalone.
mapfile -t SOURCES < <(find \
	"$REPO_ROOT/src/main/java/com/fand1l/vibeweather/api" \
	"$REPO_ROOT/src/main/java/com/fand1l/vibeweather/util" \
	"$REPO_ROOT/src/main/java/com/fand1l/vibeweather/weather" \
	-name '*.java' 2>/dev/null)

if [ "${#SOURCES[@]}" -eq 0 ]; then
	echo "!! no model sources found under src/main/java/com/fand1l/vibeweather" >&2
	exit 1
fi

javac -d "$OUT" "${SOURCES[@]}"
javac -cp "$OUT" -d "$OUT" "$REPO_ROOT/tools/model-harness/ModelHarness.java"
java -cp "$OUT" ModelHarness
