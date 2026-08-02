#!/usr/bin/env bash
#
# dump-262-api-3.sh — round 3, and the last one.
#
# Round 2's javap section failed for every class: Fabric API 0.156.0 is compiled
# for Java 25 (class file v69) and the `javap` on PATH is from an older JDK, which
# cannot read it. The error was hidden by a 2>/dev/null. This round reads the
# Fabric API *sources* jars instead, and only falls back to javap using a JDK new
# enough to parse the classes.
#
# Usage:
#     ./tools/dump-262-api-3.sh     # writes dump-262-api-3.txt

set -u

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT="${REPO_ROOT}/dump-262-api-3.txt"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/vibeweather-dump3.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
MC="$WORK/mc"
API="$WORK/api"
mkdir -p "$MC" "$API"

# ------------------------------------------------------------ Minecraft sources
MC_JARS=$(find "$GRADLE_HOME/caches/fabric-loom" "$REPO_ROOT/.gradle" \
	-name '*sources*.jar' 2>/dev/null | grep -Ev 'fabric-api|fabric-loader' | sort -u)
if [ -z "$MC_JARS" ]; then
	echo "!! No Minecraft sources jar. Run './gradlew genSources' first." >&2
	exit 1
fi
for j in $MC_JARS; do
	unzip -o -q "$j" -d "$MC" 'net/minecraft/*' 'com/mojang/blaze3d/*' 2>/dev/null
done

# ----------------------------------------------------------- Fabric API sources
# Loom downloads -sources.jar for IDE support; prefer those over bytecode.
API_SRC_JARS=$(find "$GRADLE_HOME/caches" -path '*fabric-api*' -name '*-sources.jar' 2>/dev/null | sort -u)
for j in $API_SRC_JARS; do
	unzip -o -q "$j" -d "$API" 'net/fabricmc/*' 2>/dev/null
done
API_SRC_COUNT=$(find "$API" -name '*.java' 2>/dev/null | wc -l)

# Fallback: a javap new enough to read Java 25 class files.
API_CP=$(find "$GRADLE_HOME/caches/modules-2/files-2.1/net.fabricmc.fabric-api" \
	-name '*.jar' 2>/dev/null | grep -Ev 'sources|javadoc' | tr '\n' ':')
JAVAP=""
for cand in "${JAVA_HOME:-}/bin/javap" $(ls -d /usr/lib/jvm/*/bin/javap /opt/*/bin/javap 2>/dev/null) "$(command -v javap)"; do
	[ -x "$cand" ] || continue
	if "$cand" -classpath "$API_CP" net.fabricmc.fabric.api.particle.v1.FabricParticleTypes >/dev/null 2>&1; then
		JAVAP="$cand"
		break
	fi
done

dump() {
	local root="$1" rel="$2" cap="${3:-400}" f="$root/$2"
	echo "########################################################################"
	echo "### $rel"
	echo "########################################################################"
	if [ -f "$f" ]; then
		awk -v cap="$cap" 'NR<=cap {print NR": "$0} END {if (NR>cap) print "... [truncated, "NR" lines]"}' "$f"
	else
		echo "!! NOT FOUND: $f"
	fi
	echo
}

grepf() {
	local root="$1" rel="$2" pat="$3" cap="${4:-100}" f="$root/$2"
	echo "--- GREP $rel   /$pat/"
	if [ -f "$f" ]; then
		grep -nE "$pat" "$f" | awk -v cap="$cap" 'NR<=cap {print} END {if (NR>cap) print "... [truncated, "NR" matches]"}'
	else
		echo "!! NOT FOUND: $f"
	fi
	echo
}

# Dump a Fabric API class from sources if available, else javap.
fapi() {
	local rel="$1" cap="${2:-200}"
	if [ -f "$API/$rel" ]; then
		dump "$API" "$rel" "$cap"
	elif [ -n "$JAVAP" ]; then
		local cls="${rel%.java}"; cls="${cls//\//.}"
		echo "### javap $cls"
		"$JAVAP" -classpath "$API_CP" "$cls" 2>&1
		echo
	else
		echo "### $rel"
		echo "!! no sources jar and no Java-25-capable javap found"
		echo
	fi
}

{
echo "=========================================================================="
echo " Vibe Weather — Minecraft 26.2 API dump (round 3)"
echo " generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo " fabric-api source files extracted: $API_SRC_COUNT"
echo " javap able to read Java 25 classes: ${JAVAP:-<none found>}"
echo "=========================================================================="
echo

echo "=== 1. FABRIC RENDER-STATE + LEVEL RENDER EVENTS ========================="
fapi "net/fabricmc/fabric/api/client/rendering/v1/FabricRenderState.java" 120
fapi "net/fabricmc/fabric/api/client/rendering/v1/RenderStateDataKey.java" 120
fapi "net/fabricmc/fabric/api/client/rendering/v1/level/LevelExtractionEvents.java" 220
fapi "net/fabricmc/fabric/api/client/rendering/v1/level/LevelRenderEvents.java" 320
fapi "net/fabricmc/fabric/api/client/rendering/v1/level/LevelExtractionContext.java" 120
fapi "net/fabricmc/fabric/api/client/rendering/v1/level/LevelRenderContext.java" 120
fapi "net/fabricmc/fabric/api/client/rendering/v1/level/AbstractLevelRenderContext.java" 120

echo "=== 2. FABRIC PARTICLES =================================================="
fapi "net/fabricmc/fabric/api/client/particle/v1/ParticleProviderRegistry.java" 160
fapi "net/fabricmc/fabric/api/client/particle/v1/FabricSpriteSet.java" 80
fapi "net/fabricmc/fabric/api/particle/v1/FabricParticleTypes.java" 160

echo "=== 3. LOCALITY HOOKS (exact bodies we must replicate) ==================="
grepf "$MC" "net/minecraft/world/level/Level.java" "precipitationAt" 20
echo "--- Level.precipitationAt full body (lines 950-975) ---"
awk 'NR>=948 && NR<=975 {print NR": "$0}' "$MC/net/minecraft/world/level/Level.java" 2>/dev/null
echo
echo "--- ClientLevel.getPrecipitationAt full body (lines 408-425) ---"
awk 'NR>=406 && NR<=425 {print NR": "$0}' "$MC/net/minecraft/client/multiplayer/ClientLevel.java" 2>/dev/null
echo
echo "--- ServerLevel.tickPrecipitation full body (lines 575-615) ---"
awk 'NR>=575 && NR<=615 {print NR": "$0}' "$MC/net/minecraft/server/level/ServerLevel.java" 2>/dev/null
echo
echo "--- ServerLevel.tickThunder full body (lines 538-575) ---"
awk 'NR>=538 && NR<=576 {print NR": "$0}' "$MC/net/minecraft/server/level/ServerLevel.java" 2>/dev/null
echo
echo "--- ServerLevel lines 360-380 (where advanceWeatherCycle is called) ---"
awk 'NR>=358 && NR<=382 {print NR": "$0}' "$MC/net/minecraft/server/level/ServerLevel.java" 2>/dev/null
echo

echo "=== 4. WEATHER DATA + setWeatherParameters ==============================="
dump "$MC" "net/minecraft/world/level/saveddata/WeatherData.java" 200
grepf "$MC" "net/minecraft/server/MinecraftServer.java" "(setWeatherParameters|getWeatherData|WeatherData)" 40

echo "=== 5. RENDER PIPELINE + TARGET FOR WEATHER =============================="
grepf "$MC" "net/minecraft/client/renderer/RenderPipelines.java" "(WEATHER|PARTICLE_SNIPPET)" 40
dump "$MC" "net/minecraft/client/renderer/rendertype/OutputTarget.java" 150
grepf "$MC" "com/mojang/blaze3d/vertex/DefaultVertexFormat.java" "PARTICLE" 20
echo "--- LevelRenderer.addWeatherPass full body (lines 455-480) ---"
awk 'NR>=455 && NR<=482 {print NR": "$0}' "$MC/net/minecraft/client/renderer/LevelRenderer.java" 2>/dev/null
echo

echo "=== 6. CLIENT OPTIONS: weatherRadius ====================================="
grepf "$MC" "net/minecraft/client/Options.java" "(weatherRadius|cloudRange|renderDistance\(\))" 30

echo "=== 7. PARTICLE BASE CLASSES FOR THE WIND STREAK ========================="
dump "$MC" "net/minecraft/client/particle/SingleQuadParticle.java" 220
dump "$MC" "net/minecraft/client/particle/ParticleRenderType.java" 120
grepf "$MC" "net/minecraft/client/particle/ParticleGroup.java" "(public |class |abstract )" 40

echo "=== 8. SOUNDS + BOAT/ELYTRA HOOKS ========================================"
grepf "$MC" "net/minecraft/sounds/SoundEvents.java" "WEATHER_(RAIN|RAIN_ABOVE)|LIGHTNING_BOLT" 20
grepf "$MC" "net/minecraft/world/entity/Entity.java" "(public void addDeltaMovement|public void setDeltaMovement|public Vec3 getDeltaMovement|isFallFlying|hasControllingPassenger)" 30
grepf "$MC" "net/minecraft/world/entity/vehicle/AbstractBoat.java" "(hasControllingPassenger|getControllingPassenger|public )" 40

echo "=========================================================================="
echo " done"
echo "=========================================================================="
} > "$REPORT" 2>&1

echo "Report written to: $REPORT"
echo "Lines: $(wc -l < "$REPORT")   Size: $(du -h "$REPORT" | cut -f1)"
echo "fabric-api source files found: $API_SRC_COUNT"
echo "javap used: ${JAVAP:-<none — relied on sources jars>}"
