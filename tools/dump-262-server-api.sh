#!/usr/bin/env bash
#
# dump-262-server-api.sh — the last API dump.
#
# The weather model, config, zone lifecycle and grid encoding are written and checked. What is left
# is the layer that touches Minecraft directly: saved data, registries, networking payloads,
# commands, entity accessors and the client hooks. This collects the signatures for exactly that,
# plus the three vanilla names still open from the plan.
#
# Usage:
#     ./tools/dump-262-server-api.sh     # writes dump-262-server-api.txt
#
# Structure is copied from tools/dump-262-api-3.sh, which worked: plain `for` loops, `grep -E`, one
# redirected block, and an exit trap that speaks up on the terminal when something fails inside it.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT="${REPO_ROOT}/dump-262-server-api.txt"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/vibeweather-server.XXXXXX")"

exec 3>&1
trap 'code=$?; if [ "$code" -ne 0 ]; then
        { echo "!! dump-262-server-api.sh failed (exit $code)."
          echo "!! Last lines of $REPORT:"
          tail -6 "$REPORT" 2>/dev/null || echo "   (no report written)"; } >&3
      fi
      rm -rf "$WORK"' EXIT

GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
MC="$WORK/mc"
API="$WORK/api"
mkdir -p "$MC" "$API"

MC_JARS=$(find "$GRADLE_HOME/caches/fabric-loom" "$REPO_ROOT/.gradle" \
	-name '*sources*.jar' 2>/dev/null | grep -Ev 'fabric-api|fabric-loader' | sort -u)

if [ -z "$MC_JARS" ]; then
	echo "!! No Minecraft sources jar. Run './gradlew genSources' first." >&2
	exit 1
fi

for j in $MC_JARS; do
	unzip -o -q "$j" -d "$MC" 'net/minecraft/*' 2>/dev/null
done

API_SRC_JARS=$(find "$GRADLE_HOME/caches" "$REPO_ROOT/.gradle" \
	-name '*sources*.jar' 2>/dev/null | grep -E 'fabric' | sort -u)
for j in $API_SRC_JARS; do
	unzip -o -q "$j" -d "$API" 'net/fabricmc/*' 2>/dev/null
done
API_SRC_COUNT=$(find "$API" -name '*.java' 2>/dev/null | wc -l)

dump() {
	local root="$1"
	local rel="$2"
	local cap="${3:-200}"
	local f="$root/$rel"
	echo "### $rel"
	if [ -f "$f" ]; then
		awk -v cap="$cap" 'NR<=cap {print NR": "$0} END {if (NR>cap) print "... [truncated, "NR" lines]"}' "$f"
	else
		echo "!! NOT FOUND"
	fi
	echo
}

grepf() {
	local root="$1"
	local rel="$2"
	local pat="$3"
	local cap="${4:-60}"
	local f="$root/$rel"
	echo "--- $rel   /$pat/"
	if [ -f "$f" ]; then
		grep -nE "$pat" "$f" | awk -v cap="$cap" 'NR<=cap {print} END {if (NR>cap) print "... [truncated, "NR" matches]"}'
	else
		echo "!! NOT FOUND"
	fi
	echo
}

{
echo "=========================================================================="
echo " Vibe Weather — Minecraft 26.2 server/client API dump"
echo " generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo " fabric-api source files: $API_SRC_COUNT"
echo "=========================================================================="
echo

echo "=== 1. THE THREE NAMES STILL OPEN ========================================"
grepf "$MC" "net/minecraft/world/entity/LivingEntity.java" \
	"(FallFlying|[Gg]liding|isSpectator|GLIDER)" 40
grepf "$MC" "net/minecraft/world/entity/player/Player.java" \
	"(getAbilities|Abilities|isSpectator|isCreative)" 40
grepf "$MC" "net/minecraft/world/entity/player/Abilities.java" "(public|flying)" 40
grepf "$MC" "net/minecraft/world/entity/Entity.java" \
	"(public boolean isSpectator|public Vec3 position|public double getX\(|public double getZ\(|getControllingPassenger)" 30
echo "--- any class whose name mentions Boat ---"
find "$MC" -iname '*boat*' -o -iname '*raft*' 2>/dev/null | sed "s|$MC/||" | sort
echo

echo "=== 2. SAVED DATA (zone persistence) ====================================="
dump "$MC" "net/minecraft/world/level/saveddata/SavedData.java" 120
dump "$MC" "net/minecraft/world/level/saveddata/SavedDataType.java" 120
grepf "$MC" "net/minecraft/world/level/storage/DimensionDataStorage.java" \
	"(public |computeIfAbsent|get\()" 40
grepf "$MC" "net/minecraft/server/level/ServerLevel.java" \
	"(getDataStorage|public List<ServerPlayer>|players\(\)|getGameTime|getSeed|dimension\(\)|getServer\(\))" 40
grepf "$MC" "net/minecraft/util/datafix/DataFixTypes.java" "(SAVED_DATA|public static final)" 30
echo

echo "=== 3. NETWORKING (custom payloads) ======================================"
dump "$MC" "net/minecraft/network/protocol/common/custom/CustomPacketPayload.java" 120
grepf "$MC" "net/minecraft/network/codec/StreamCodec.java" \
	"(static .*composite|static .*unit|static .*of\(|public static)" 50
grepf "$MC" "net/minecraft/network/codec/ByteBufCodecs.java" \
	"(BYTE_ARRAY|VAR_INT|INT|FLOAT|BOOL|public static final)" 40
grepf "$MC" "net/minecraft/network/FriendlyByteBuf.java" \
	"(writeByteArray|readByteArray|writeVarInt|readVarInt|writeFloat|readFloat)" 30
echo

echo "=== 4. COMMANDS =========================================================="
grepf "$MC" "net/minecraft/commands/Commands.java" \
	"(public static.*literal|public static.*argument|hasPermission|LEVEL_)" 40
grepf "$MC" "net/minecraft/commands/CommandSourceStack.java" \
	"(public ServerLevel|public ServerPlayer|sendSuccess|sendFailure|getPosition|getEntity)" 40
echo "--- argument types we need ---"
find "$MC/net/minecraft/commands/arguments" -name '*.java' 2>/dev/null | sed "s|$MC/||" | sort | head -40
echo

echo "=== 5. REGISTRIES AND ENTITY TYPES ======================================="
grepf "$MC" "net/minecraft/core/registries/BuiltInRegistries.java" \
	"(ENTITY_TYPE|PARTICLE_TYPE|public static final)" 40
grepf "$MC" "net/minecraft/core/Registry.java" "(public static.*register|getOptional|get\()" 30
grepf "$MC" "net/minecraft/world/entity/EntityTypes.java" "(LIGHTNING_BOLT|SKELETON_HORSE)" 10
grepf "$MC" "net/minecraft/world/entity/EntityType.java" "(public .*create\(|builder)" 30
echo

echo "=== 6. LEVEL AND BIOME ACCESSORS WE CALL ================================="
grepf "$MC" "net/minecraft/world/level/Level.java" \
	"(public .*getBiome|getSeaLevel|canSeeSky|getHeightmapPos|getGameTime|getDayTime|dimensionType|isClientSide)" 40
grepf "$MC" "net/minecraft/world/level/dimension/DimensionType.java" "(hasSkyLight|natural|public boolean)" 30
grepf "$MC" "net/minecraft/world/level/biome/Biome.java" "(shouldFreeze|shouldSnow|public boolean)" 30
echo

echo "=== 7. CLIENT SIDE ======================================================="
grepf "$MC" "net/minecraft/client/Minecraft.java" \
	"(public ClientLevel level|public LocalPlayer player|public ParticleEngine|gameRenderer|levelRenderer|public Options|getTextureManager)" 40
grepf "$MC" "net/minecraft/client/multiplayer/ClientLevel.java" \
	"(public .*getPrecipitationAt|playLocalSound|addParticle|public ClientLevel)" 30
grepf "$MC" "net/minecraft/client/player/LocalPlayer.java" "(public |input)" 30
echo

echo "=== 8. FABRIC API PIECES WE WILL CALL ===================================="
for f in \
	net/fabricmc/fabric/api/networking/v1/PayloadTypeRegistry.java \
	net/fabricmc/fabric/api/networking/v1/ServerPlayConnectionEvents.java \
	net/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking.java \
	net/fabricmc/fabric/api/command/v2/CommandRegistrationCallback.java \
	net/fabricmc/fabric/api/event/lifecycle/v1/ServerLifecycleEvents.java \
	net/fabricmc/fabric/api/client/rendering/v1/level/LevelRenderEvents.java \
	net/fabricmc/fabric/api/client/rendering/v1/RenderStateDataKey.java; do
	if [ -f "$API/$f" ]; then
		dump "$API" "$f" 140
	else
		echo "### $f"
		echo "!! not extracted (no fabric-api sources jar in the cache)"
		echo
	fi
done

echo "=========================================================================="
echo " done"
echo "=========================================================================="
} > "$REPORT" 2>&1

echo "Report written to: $REPORT"
echo "Lines: $(wc -l < "$REPORT")   Size: $(du -h "$REPORT" | cut -f1)"
echo "fabric-api source files found: $API_SRC_COUNT"
