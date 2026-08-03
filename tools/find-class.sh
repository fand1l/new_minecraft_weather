#!/usr/bin/env bash
#
# find-class.sh — resolves Minecraft class names to their packages in 26.2.
#
#     ./tools/find-class.sh GameRules SoundSource SimpleParticleType
#
# Packages move between versions far more often than class names do, and a wrong package is a
# compile error found only on a machine that can run Loom. This turns "which package is X in" from
# a round trip into a one-line lookup.
#
# Requires ./gradlew genSources to have run once.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"

if [ "$#" -eq 0 ]; then
	echo "usage: $(basename "$0") ClassName [ClassName...]" >&2
	exit 2
fi

MC_JARS=$(find "$GRADLE_HOME/caches/fabric-loom" "$REPO_ROOT/.gradle" \
	-name '*sources*.jar' 2>/dev/null | grep -Ev 'fabric-api|fabric-loader' | sort -u)

if [ -z "$MC_JARS" ]; then
	echo "!! No Minecraft sources jar. Run './gradlew genSources' first." >&2
	exit 1
fi

for name in "$@"; do
	echo "=== $name"
	found=0

	for jar in $MC_JARS; do
		# Match the file name exactly, so "Options" does not also report "ChatOptions".
		while IFS= read -r entry; do
			[ -n "$entry" ] || continue
			found=1
			# net/minecraft/foo/Bar.java -> net.minecraft.foo.Bar
			printf '    %s\n' "$(echo "${entry%.java}" | tr '/' '.')"
		done < <(unzip -l "$jar" 2>/dev/null | awk '{print $4}' \
			| grep -E "(^|/)${name}\.java$" | sort -u)
	done

	if [ "$found" -eq 0 ]; then
		echo "    !! not found -- check the spelling, or it may be a nested class"
		echo "       (nested classes live inside their outer class's file; try the outer name)"
	fi
done
