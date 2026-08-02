#!/usr/bin/env bash
#
# dump-262-api.sh — round 2 of the Minecraft 26.2 / Fabric API 0.156.0 name check.
#
# Round 1 (verify-262-names.sh) found the file names. This dumps the actual
# signatures: full source of the small critical classes, weather-related lines
# from the big ones, and javap output for the Fabric API classes we intend to use.
#
# Usage:
#     ./gradlew genSources          # if not already done
#     ./tools/dump-262-api.sh       # writes dump-262-api.txt
#
# Deliberately dumb: no process substitution, no `tee`, no `head` inside pipes,
# plain `grep -E` only. Round 1 lost output to exactly those constructs.

set -u

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT="${REPO_ROOT}/dump-262-api.txt"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/vibeweather-dump.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
SRC="$WORK/mc"
mkdir -p "$SRC"

# ---------------------------------------------------------------- collect inputs
MC_JARS=$(find "$GRADLE_HOME/caches/fabric-loom" "$REPO_ROOT/.gradle" \
	-name '*sources*.jar' 2>/dev/null | grep -Ev 'fabric-api|fabric-loader' | sort -u)

if [ -z "$MC_JARS" ]; then
	echo "!! No Minecraft sources jar found. Run './gradlew genSources' first." >&2
	exit 1
fi

for j in $MC_JARS; do
	unzip -o -q "$j" -d "$SRC" 'net/minecraft/*' 'com/mojang/blaze3d/*' 2>/dev/null
done

# Individual Fabric API module jars (not the bundle) make the cleanest javap classpath.
API_CP=$(find "$GRADLE_HOME/caches/modules-2/files-2.1/net.fabricmc.fabric-api" \
	-name '*.jar' 2>/dev/null | grep -Ev 'sources|javadoc' | tr '\n' ':')

# Helper: print a whole file, capped, without SIGPIPE games.
dump_file() {
	local rel="$1" cap="${2:-400}" f="$SRC/$1"
	echo "########################################################################"
	echo "### FILE: $rel"
	echo "########################################################################"
	if [ -f "$f" ]; then
		awk -v cap="$cap" 'NR<=cap {print NR": "$0} END {if (NR>cap) print "... [truncated, "NR" lines total]"}' "$f"
	else
		echo "!! NOT FOUND: $f"
	fi
	echo
}

# Helper: matching lines with context from a big file.
grep_file() {
	local rel="$1" pat="$2" cap="${3:-120}" f="$SRC/$1"
	echo "------------------------------------------------------------------------"
	echo "--- GREP in $rel   /$pat/"
	echo "------------------------------------------------------------------------"
	if [ -f "$f" ]; then
		grep -nE "$pat" "$f" | awk -v cap="$cap" 'NR<=cap {print} END {if (NR>cap) print "... [truncated, "NR" matches]"}'
	else
		echo "!! NOT FOUND: $f"
	fi
	echo
}

# Helper: javap one class, tolerating absence.
japp() {
	local cls="$1"
	echo "--- javap $cls"
	javap -classpath "$API_CP" "$cls" 2>/dev/null || echo "!! not on classpath: $cls"
	echo
}

{
echo "=========================================================================="
echo " Vibe Weather — Minecraft 26.2 API dump (round 2)"
echo " generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "=========================================================================="
echo

echo "=== A. WEATHER RENDERING ================================================="
dump_file "net/minecraft/client/renderer/WeatherEffectRenderer.java" 400
dump_file "net/minecraft/client/renderer/state/level/WeatherRenderState.java" 150
grep_file "net/minecraft/client/renderer/LevelRenderer.java" \
	"([Ww]eather|WeatherEffectRenderer|WeatherRenderState|extract|submit)" 150

echo "=== B. FOG ==============================================================="
dump_file "net/minecraft/client/renderer/fog/FogRenderer.java" 350
dump_file "net/minecraft/client/renderer/fog/FogData.java" 120
dump_file "net/minecraft/client/renderer/fog/environment/FogEnvironment.java" 120
dump_file "net/minecraft/client/renderer/fog/environment/AtmosphericFogEnvironment.java" 150

echo "=== C. SERVER-SIDE WEATHER STATE ========================================="
grep_file "net/minecraft/server/level/ServerLevel.java" \
	"([Rr]ain|[Tt]hunder|[Ww]eather|[Pp]recipitation|advanceWeather|tickPrecipitation)" 150
grep_file "net/minecraft/world/level/Level.java" \
	"([Rr]ain|[Tt]hunder|[Ww]eather|[Pp]recipitation|isRainingAt|oRain|oThunder)" 150
grep_file "net/minecraft/client/multiplayer/ClientLevel.java" \
	"([Rr]ain|[Tt]hunder|[Ww]eather|[Pp]recipitation)" 100
grep_file "net/minecraft/world/level/storage/ServerLevelData.java" \
	"([Rr]ain|[Tt]hunder|[Ww]eather)" 60

echo "=== D. VANILLA /weather COMMAND =========================================="
dump_file "net/minecraft/server/commands/WeatherCommand.java" 150

echo "=== E. GAMEPLAY HOOKS WE PIGGYBACK ON ===================================="
grep_file "net/minecraft/world/level/block/LayeredCauldronBlock.java" \
	"(precipitation|handlePrecipitation|Precipitation|LAYERS|fill)" 60
grep_file "net/minecraft/world/level/biome/Biome.java" \
	"(Precipitation|coldEnoughToSnow|warmEnoughToRain|getPrecipitationAt|hasPrecipitation)" 60
echo "--- files named *Lightning* ---"
find "$SRC" -iname '*lightning*' 2>/dev/null | sed "s|$SRC/||" | sort
echo

echo "=== F. BLAZE3D / PIPELINE ================================================"
echo "--- com/mojang/blaze3d/pipeline + textures + vertex ---"
find "$SRC/com/mojang/blaze3d/pipeline" "$SRC/com/mojang/blaze3d/vertex" \
	"$SRC/com/mojang/blaze3d/shaders" "$SRC/com/mojang/blaze3d/systems" \
	-name '*.java' 2>/dev/null | sed "s|$SRC/||" | sort
echo
grep_file "net/minecraft/client/renderer/RenderPipelines.java" \
	"(RenderPipeline |WEATHER|RAIN|SNOW|PARTICLE|TRANSLUCENT|builder|withLocation)" 120
echo "--- RenderType entries: weather / particle / translucent ---"
grep_file "net/minecraft/client/renderer/rendertype/RenderType.java" \
	"(weather|rain|snow|particle|WEATHER|PARTICLE|public static)" 120

echo "=== G. PARTICLES ========================================================="
grep_file "net/minecraft/client/particle/ParticleEngine.java" \
	"(register|SpriteSet|ParticleProvider|createParticle|public |private )" 100
dump_file "net/minecraft/client/particle/ParticleProvider.java" 80
dump_file "net/minecraft/client/particle/SpriteSet.java" 60
grep_file "net/minecraft/client/particle/Particle.java" \
	"(public |protected |abstract )" 120

echo "=== H. FABRIC API 0.156.0 — javap ========================================"
echo "classpath entries: $(echo "$API_CP" | tr ':' '\n' | grep -c '\.jar')"
echo
for c in \
	net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents \
	net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents \
	net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext \
	net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext \
	net.fabricmc.fabric.api.client.rendering.v1.level.AbstractLevelRenderContext \
	net.fabricmc.fabric.api.client.rendering.v1.level.LevelTerrainRenderContext \
	net.fabricmc.fabric.api.client.rendering.v1.FabricRenderPipeline \
	net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState \
	net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey \
	net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhase \
	net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases \
	net.fabricmc.fabric.api.client.rendering.v1.FabricOrderedSubmitNodeCollector \
	net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback \
	net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry \
	net.fabricmc.fabric.api.client.particle.v1.FabricSpriteSet \
	net.fabricmc.fabric.api.client.particle.v1.ParticleRenderEvents \
	net.fabricmc.fabric.api.particle.v1.FabricParticleTypes \
	net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents \
	net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents \
	net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry \
	net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback \
	; do
	japp "$c"
done

echo "--- nested types of the level rendering + particle packages ---"
for j in $(echo "$API_CP" | tr ':' '\n' | grep -E 'fabric-(rendering|particles)-v1'); do
	unzip -l "$j" 2>/dev/null | awk '{print $4}' |
		grep -E '(rendering/v1/level/|particle/v1/).*\$.*\.class$' |
		sed 's|/|.|g; s|\.class$||' | sort -u
done
echo
echo "--- javap of those nested types ---"
for j in $(echo "$API_CP" | tr ':' '\n' | grep -E 'fabric-(rendering|particles)-v1'); do
	for c in $(unzip -l "$j" 2>/dev/null | awk '{print $4}' |
		grep -E '(rendering/v1/level/|particle/v1/).*\$.*\.class$' |
		sed 's|/|.|g; s|\.class$||' | sort -u); do
		japp "$c"
	done
done

echo "=========================================================================="
echo " done"
echo "=========================================================================="
} > "$REPORT" 2>&1

echo "Report written to: $REPORT"
echo "Lines: $(wc -l < "$REPORT")   Size: $(du -h "$REPORT" | cut -f1)"
echo "Paste it back into the conversation."
