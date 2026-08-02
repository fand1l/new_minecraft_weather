#!/usr/bin/env bash
#
# verify-262-names.sh — dumps the exact Minecraft 26.2 / Fabric API 0.156.0 names that
# the Vibe Weather implementation needs but that could not be verified from documentation.
#
# Usage:
#     ./gradlew genSources          # decompile Minecraft with the project's mappings
#     ./tools/verify-262-names.sh   # writes verify-262-names.txt
#
# Then paste verify-262-names.txt back into the conversation.
#
# The script is read-only: it never writes into any Gradle cache, only into a scratch
# directory under /tmp and the report file in the repository root.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT="${REPO_ROOT}/verify-262-names.txt"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/vibeweather-verify.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"

if command -v rg >/dev/null 2>&1; then
	SEARCH() { rg --no-heading --line-number "$@" 2>/dev/null; }
else
	SEARCH() { grep -rn "$@" 2>/dev/null; }
fi

exec > >(tee "$REPORT") 2>&1

echo "=================================================================="
echo " Vibe Weather — Minecraft 26.2 name verification"
echo " generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "=================================================================="
echo

# ------------------------------------------------------------------ locate sources
echo "### 0. Located source jars"
mapfile -t MC_SOURCE_JARS < <(
	find "$GRADLE_HOME/caches/fabric-loom" "$REPO_ROOT/.gradle" -name '*sources*.jar' 2>/dev/null |
		grep -Ev 'fabric-api|fabric-loader' | sort -u
)
mapfile -t API_JARS < <(
	find "$GRADLE_HOME/caches/modules-2/files-2.1/net.fabricmc.fabric-api" \
		"$GRADLE_HOME/caches/fabric-loom" -name '*.jar' 2>/dev/null |
		grep -E 'fabric-api' | sort -u
)

if [ "${#MC_SOURCE_JARS[@]}" -eq 0 ]; then
	echo "!! No Minecraft sources jar found."
	echo "!! Run './gradlew genSources' first, then re-run this script."
	echo "!! Searched: $GRADLE_HOME/caches/fabric-loom and $REPO_ROOT/.gradle"
	exit 1
fi

for j in "${MC_SOURCE_JARS[@]}"; do echo "  MC  : $j"; done
for j in "${API_JARS[@]}"; do echo "  API : $j"; done
echo

SRC="$WORK/mc"
mkdir -p "$SRC"
for j in "${MC_SOURCE_JARS[@]}"; do
	unzip -o -q "$j" -d "$SRC" 'net/minecraft/*' 'com/mojang/blaze3d/*' 2>/dev/null
done

# ------------------------------------------------------- 1. weather render entry point
echo "### 1. Weather rendering entry point"
echo "--- files whose name mentions Weather / Rain / Snow under client ---"
find "$SRC/net/minecraft/client" -iname '*weather*' -o -iname '*rain*' -o -iname '*snow*' 2>/dev/null |
	sed "s|$SRC/||" | sort
echo
echo "--- declarations inside those files ---"
while IFS= read -r f; do
	[ -f "$f" ] || continue
	echo "== ${f#"$SRC"/}"
	SEARCH -e '^\s*(public|protected|private)?\s*(static\s+)?[A-Za-z0-9_<>,\[\]\. ]+\s+[a-zA-Z0-9_]+\s*\(' "$f" | head -30
	echo
done < <(find "$SRC/net/minecraft/client" -iname '*weather*' 2>/dev/null)
echo

# ------------------------------------------------------------------------- 2. fog
echo "### 2. Fog"
echo "--- files whose name mentions Fog ---"
find "$SRC" -iname '*fog*' 2>/dev/null | sed "s|$SRC/||" | sort
echo
echo "--- declarations inside those files ---"
while IFS= read -r f; do
	[ -f "$f" ] || continue
	echo "== ${f#"$SRC"/}"
	SEARCH -e '^\s*(public|protected|private|record|class|static)' "$f" | head -30
	echo
done < <(find "$SRC" -iname '*fog*' 2>/dev/null | head -6)
echo

# ------------------------------------------- 3. ServerLevel / Level weather members
echo "### 3. ServerLevel + Level weather members"
for f in "$SRC/net/minecraft/server/level/ServerLevel.java" "$SRC/net/minecraft/world/level/Level.java"; do
	echo "== ${f#"$SRC"/}"
	if [ -f "$f" ]; then
		SEARCH -e '(rain|thunder|Weather|weather|precipitation)' "$f" |
			SEARCH -e '(void|float|boolean|int|private|public|protected)\s' | head -40
	else
		echo "!! not found: $f"
	fi
	echo
done

echo "--- isRainingAt / getRainLevel / getThunderLevel across all sources ---"
SEARCH -e '(isRainingAt|getRainLevel|getThunderLevel|setRainLevel|isThundering|isRaining)\s*\(' "$SRC/net/minecraft/world/level/Level.java" | head -20
echo

# ------------------------------------------------------------------ 4. /weather command
echo "### 4. Vanilla /weather command"
find "$SRC" -iname '*WeatherCommand*' 2>/dev/null | sed "s|$SRC/||"
while IFS= read -r f; do
	[ -f "$f" ] || continue
	echo "== ${f#"$SRC"/}"
	SEARCH -e '^\s*(public|private|protected|static)' "$f" | head -20
done < <(find "$SRC" -iname '*WeatherCommand*' 2>/dev/null)
echo

# --------------------------------------------------------- 5. Blaze3D pipeline surface
echo "### 5. Blaze3D pipeline surface (for the custom precipitation mesh)"
echo "--- com/mojang/blaze3d top-level classes ---"
find "$SRC/com/mojang/blaze3d" -name '*.java' 2>/dev/null | sed "s|$SRC/||" | sort | head -60
echo
echo "--- RenderPipeline builder methods ---"
for f in $(find "$SRC" -name 'RenderPipeline.java' 2>/dev/null); do
	echo "== ${f#"$SRC"/}"
	SEARCH -e '^\s*public\s' "$f" | head -40
done
echo
echo "--- vanilla RenderType entries mentioning weather/rain/snow ---"
for f in $(find "$SRC" -name 'RenderType.java' -o -name 'RenderPipelines.java' 2>/dev/null); do
	echo "== ${f#"$SRC"/}"
	SEARCH -ie '(weather|rain|snow)' "$f" | head -20
done
echo

# ------------------------------------------------------------ 6. particle client hooks
echo "### 6. Particle registration"
echo "--- ParticleEngine registration-ish methods ---"
for f in $(find "$SRC" -name 'ParticleEngine.java' 2>/dev/null); do
	echo "== ${f#"$SRC"/}"
	SEARCH -e '^\s*(public|private)\s.*\(' "$f" | head -30
done
echo
echo "--- SpriteSet / ParticleProvider ---"
find "$SRC" -name 'ParticleProvider.java' -o -name 'SpriteSet.java' -o -name 'ParticleType.java' 2>/dev/null | sed "s|$SRC/||"
echo

# ------------------------------------------------- 7. Fabric API: rendering / fog / particles
echo "### 7. Fabric API 0.156.0 — entries of interest"
if [ "${#API_JARS[@]}" -eq 0 ]; then
	echo "!! No fabric-api jar located; skipping."
else
	for j in "${API_JARS[@]}"; do
		unzip -l "$j" 2>/dev/null | awk '{print $4}' |
			grep -Ei '(rendering|particle|fog|world)/.*\.class$' |
			grep -v '\$' | sed 's|^|  |'
	done | sort -u | head -80
fi
echo

echo "=================================================================="
echo " Report written to: $REPORT"
echo " Paste this file back into the conversation."
echo "=================================================================="
