#!/usr/bin/env bash
#
# show-source.sh — prints real 26.2 source out of the decompiled Minecraft jar.
#
#     ./tools/show-source.sh ServerLevel tickThunder        # a method, with its body
#     ./tools/show-source.sh -g GameRules 'GameRule<Boolean>'   # matching lines only
#     ./tools/show-source.sh GameRules                      # every declaration in the class
#
# find-class.sh answers "which package". This answers "what does it actually say", which is the
# question behind every wrong guess so far: a method body reproduced from memory of an older
# version compiles fine and behaves differently, and a static field name that got renamed costs a
# whole build round to discover.
#
# Requires ./gradlew genSources to have run once.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"

GREP_ONLY=0

if [ "${1:-}" = "-g" ]; then
	GREP_ONLY=1
	shift
fi

if [ "$#" -eq 0 ]; then
	echo "usage: $(basename "$0") [-g] ClassName [pattern...]" >&2
	exit 2
fi

CLASS="$1"
shift

MC_JARS=$(find "$GRADLE_HOME/caches/fabric-loom" "$REPO_ROOT/.gradle" \
	-name '*sources*.jar' 2>/dev/null | grep -Ev 'fabric-api|fabric-loader' | sort -u)

if [ -z "$MC_JARS" ]; then
	echo "!! No Minecraft sources jar. Run './gradlew genSources' first." >&2
	exit 1
fi

FOUND=0

for jar in $MC_JARS; do
	# Exact file-name match, so "Options" does not also pull in "ChatOptions".
	ENTRIES=$(unzip -l "$jar" 2>/dev/null | awk '{print $4}' | grep -E "(^|/)${CLASS}\.java$" | sort -u)

	for entry in $ENTRIES; do
		FOUND=1
		echo "=== $entry"

		if [ "$#" -eq 0 ]; then
			# No pattern: every declaration, which is enough to see what a class offers.
			unzip -p "$jar" "$entry" 2>/dev/null \
				| grep -nE '^\s{0,4}(public|protected|private|static|default)[^;]*[;({]' \
				| grep -vE '^\s*[0-9]+:\s*//'
			continue
		fi

		for pattern in "$@"; do
			echo "--- $pattern"

			if [ "$GREP_ONLY" -eq 1 ]; then
				unzip -p "$jar" "$entry" 2>/dev/null | grep -nF -- "$pattern"
				continue
			fi

			# Print from the first line containing the pattern until braces balance again. Braces
			# inside strings or comments can end a block early; that is worth it for a script that
			# needs no Java parser and works on any class.
			unzip -p "$jar" "$entry" 2>/dev/null | awk -v pat="$pattern" '
				BEGIN { printing = 0; depth = 0; opened = 0 }
				printing == 0 && index($0, pat) > 0 { printing = 1; depth = 0; opened = 0 }
				printing == 1 {
					print NR ": " $0
					n = gsub(/\{/, "{")
					m = gsub(/\}/, "}")
					depth += n - m
					if (n > 0) opened = 1
					if (opened == 1 && depth <= 0) { printing = 0; print "" }
					else if (opened == 0 && $0 ~ /;\s*$/) { printing = 0; print "" }
				}
			'
		done
	done
done

if [ "$FOUND" -eq 0 ]; then
	echo "!! $CLASS not found. Check the spelling, or try the outer class if it is nested."
	exit 1
fi
