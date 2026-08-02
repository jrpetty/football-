#!/usr/bin/env bash
# Functional tests for the water-pressure engine against a deterministic fake world.
# Builds real dams and asserts the numbers: the wooden dam bursts, the arch holds, buttresses
# lend capacity by distance, water on both sides cancels, terrain anchors, breaches cascade.
#
#   ./run.sh <path-to-structint-src/main/java> <fastutil.jar>
set -euo pipefail
SRC=${1:?path to src/main/java}
FASTUTIL=${2:?fastutil jar}
out=$(mktemp -d)
javac -nowarn -d "$out" -cp "$FASTUTIL" \
  $(find "$(dirname "$0")" -name '*.java') \
  "$SRC/dev/structint/core/HydroLoad.java" \
  "$SRC/dev/structint/world/HydroTags.java" \
  "$SRC/dev/structint/world/WaterPressure.java" \
  "$SRC/dev/structint/world/WaterPressureScanner.java"
java -cp "$out:$FASTUTIL" harness.Test
