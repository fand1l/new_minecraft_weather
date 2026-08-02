#!/usr/bin/env bash
#
# verify-262-names.sh — round 1: locate the decompiled sources and list the
# Minecraft 26.2 / Fabric API file names relevant to Vibe Weather.
#
# Usage:
#     ./gradlew genSources
#     ./tools/verify-262-names.sh   # writes verify-262-names.txt
#
# For the actual signatures use tools/dump-262-api.sh (round 2).
#
# NOTE: the first version of this script lost most of its output to
# `exec > >(tee …)` combined with `while … done < <(find …)` loops — output
# written inside those subshells never reached the report. Everything here now
# uses plain `for` loops and a single redirection block. Keep it that way.

set -u

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT="${REPO_ROOT}/verify-262-names.txt"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/vibeweather-verify.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
SRC="$WORK/mc"
mkdir -p "$SRC"

MC_JARS=$(find "$GRADLE_HOME/caches/fabric-loom" "$REPO_ROOT/.gradle" \
	-name '*sources*.jar' 2>/dev/null | grep -Ev 'fabric-api|fabric-loader' | sort -u)

if [ -z "$MC_JARS" ]; then
	echo "!! No Minecraft sources jar found." >&2
	echo "!! Run './gradlew genSources' first, then re-run this script." >&2
	exit 1
fi

for j in $MC_JARS; do
	unzip -o -q "$j" -d "$SRC" 'net/minecraft/*' 'com/mojang/blaze3d/*' 2>/dev/null
done

{
echo "=================================================================="
echo " Vibe Weather — Minecraft 26.2 file discovery (round 1)"
echo " generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "=================================================================="
echo

echo "### Source jars"
for j in $MC_JARS; do echo "  MC : $j"; done
echo

echo "### Weather / rain / snow"
find "$SRC" -iname '*weather*' -o -iname '*rain*' -o -iname '*snow*' 2>/dev/null |
	sed "s|$SRC/||" | sort
echo

echo "### Fog"
find "$SRC" -iname '*fog*' 2>/dev/null | sed "s|$SRC/||" | sort
echo

echo "### Lightning"
find "$SRC" -iname '*lightning*' 2>/dev/null | sed "s|$SRC/||" | sort
echo

echo "### Particle"
find "$SRC/net/minecraft/client/particle" "$SRC/net/minecraft/core/particles" \
	-name '*.java' 2>/dev/null | sed "s|$SRC/||" | sort
echo

echo "### Blaze3D (pipeline / vertex / systems / textures)"
find "$SRC/com/mojang/blaze3d" -name '*.java' 2>/dev/null |
	grep -Ev '/(audio|font|opengl|vulkan)/' | sed "s|$SRC/||" | sort
echo

echo "### Fabric API jars on the classpath"
find "$GRADLE_HOME/caches/modules-2/files-2.1/net.fabricmc.fabric-api" \
	-name '*.jar' 2>/dev/null | grep -Ev 'sources|javadoc' | sort
echo

echo "=================================================================="
echo " Next: ./tools/dump-262-api.sh  (signatures, not just names)"
echo "=================================================================="
} > "$REPORT" 2>&1

echo "Report written to: $REPORT"
echo "Lines: $(wc -l < "$REPORT")"
