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
#
# A JDK on PATH is not assumed. Gradle downloads and uses its own toolchain, so a machine that
# builds this project perfectly well can still have no `javac` on PATH -- which is exactly what
# happened the first time this script was run. The JDK search below mirrors the one in
# tools/dump-262-api-3.sh and picks the newest suitable JDK it can find.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"

# The model uses Math.clamp, which is Java 21 and later.
MIN_JDK=21

# --------------------------------------------------------------------- find a usable javac
javac_major() {
	local out
	# Select the version line rather than the first line: JAVA_TOOL_OPTIONS and similar env
	# banners are printed to stderr ahead of it, and `head -1` picks those up instead.
	out="$("$1" -version 2>&1 | tr -d '\r' | grep -E '^[[:space:]]*javac[[:space:]]+[0-9]' | head -1)"
	[ -n "$out" ] || return 1

	# "javac 25.0.1" -> 25 ; "javac 1.8.0_402" -> 8
	local version="${out##* }"
	local major="${version%%.*}"

	if [ "$major" = "1" ]; then
		version="${version#1.}"
		major="${version%%.*}"
	fi

	case "$major" in
		'' | *[!0-9]*) return 1 ;;
	esac

	echo "$major"
}

JAVAC=""
BEST=0

for cand in \
	"${JAVA_HOME:-}/bin/javac" \
	$(ls -d "$GRADLE_HOME"/jvms/*/bin/javac 2>/dev/null) \
	$(ls -d "$GRADLE_HOME"/jdks/*/bin/javac 2>/dev/null) \
	$(ls -d "$HOME"/.sdkman/candidates/java/*/bin/javac 2>/dev/null) \
	$(ls -d /usr/lib/jvm/*/bin/javac 2>/dev/null) \
	$(ls -d /opt/*/bin/javac 2>/dev/null) \
	$(ls -d /Library/Java/JavaVirtualMachines/*/Contents/Home/bin/javac 2>/dev/null) \
	"$(command -v javac 2>/dev/null)"; do
	[ -n "$cand" ] && [ -x "$cand" ] || continue
	major="$(javac_major "$cand")" || continue

	if [ "$major" -ge "$MIN_JDK" ] && [ "$major" -gt "$BEST" ]; then
		BEST="$major"
		JAVAC="$cand"
	fi
done

if [ -z "$JAVAC" ]; then
	{
		echo "!! No JDK $MIN_JDK+ found -- this needs javac, not just java."
		echo "!! Searched JAVA_HOME, $GRADLE_HOME/{jvms,jdks}, SDKMAN, /usr/lib/jvm, /opt and PATH."
		echo "!!"
		echo "!! Gradle uses its own toolchain, so './gradlew genSources' can succeed even with no"
		echo "!! javac on PATH. To find the JDK Gradle downloaded:"
		echo "!!     ls -d $GRADLE_HOME/jvms/*/bin/javac"
		echo "!! then re-run as:"
		echo "!!     JAVA_HOME=<that jdk root> ./tools/run-model-harness.sh"
	} >&2
	exit 1
fi

JAVA="$(dirname "$JAVAC")/java"

if [ ! -x "$JAVA" ]; then
	echo "!! Found $JAVAC but no matching java beside it." >&2
	exit 1
fi

echo "using JDK $BEST -- $JAVAC"

# ------------------------------------------------------------------------------- build and run
OUT="$(mktemp -d "${TMPDIR:-/tmp}/vibeweather-harness.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT

# Only the pure packages. Server and client code pull in Minecraft and cannot build standalone.
SOURCES=$(find \
	"$REPO_ROOT/src/main/java/com/fand1l/vibeweather/api" \
	"$REPO_ROOT/src/main/java/com/fand1l/vibeweather/util" \
	"$REPO_ROOT/src/main/java/com/fand1l/vibeweather/weather" \
	-name '*.java' 2>/dev/null)

if [ -z "$SOURCES" ]; then
	echo "!! no model sources found under src/main/java/com/fand1l/vibeweather" >&2
	exit 1
fi

# shellcheck disable=SC2086
"$JAVAC" -d "$OUT" $SOURCES || exit 1
"$JAVAC" -cp "$OUT" -d "$OUT" "$REPO_ROOT/tools/model-harness/ModelHarness.java" || exit 1
"$JAVA" -cp "$OUT" ModelHarness
