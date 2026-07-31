#!/usr/bin/env bash
# Differential test: runs the ORIGINAL FloodWorld jar and the OPTIMISED build against the same
# deterministic fake world and diffs every observable effect (water writes, saved-data contents,
# per-tick dirty flags, log warnings, /floodworld clear output).
#
#   ./run.sh <original-floodworld.jar> <optimised-classes-dir-or-jar> [fastutil.jar]
#
# Exits 0 only if the two builds are byte-for-byte identical.
set -euo pipefail
ORIG=${1:?original jar}
OPT=${2:?optimised classes dir or jar}
FASTUTIL=${3:-$(ls ~/.gradle/caches/**/fastutil-*.jar 2>/dev/null | head -1)}
[ -n "$FASTUTIL" ] || { echo "need fastutil on the classpath (arg 3)"; exit 2; }

out=$(mktemp -d)
find "$(dirname "$0")/src" -name '*.java' > "$out/srcs"
javac -nowarn -d "$out/env" -cp "$FASTUTIL:$ORIG" @"$out/srcs"

java -cp "$out/env:$ORIG:$FASTUTIL"      harness.Main > "$out/original.txt"
java -cp "$out/env:$OPT:$ORIG:$FASTUTIL" harness.Main > "$out/optimised.txt"

strip() { grep -vE '^(  |--- ops for)' "$1"; }
if diff -q <(strip "$out/original.txt") <(strip "$out/optimised.txt") >/dev/null; then
    echo "PASS - behaviour identical ($(grep -c '^SET ' "$out/original.txt") water writes compared)"
    echo
    echo "operation counts (original vs optimised):"
    diff <(grep -E '^  ' "$out/original.txt") <(grep -E '^  ' "$out/optimised.txt") || true
    exit 0
else
    echo "FAIL - behaviour differs"
    diff <(strip "$out/original.txt") <(strip "$out/optimised.txt") | head -60
    exit 1
fi
