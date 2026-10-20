#!/usr/bin/env bash
# tools/model_pipeline/run.sh "<folder with the source .glb files>" [name filter]
# Rebuilds assets/models/* from the raw AI models listed in models.json. Set GODOT to your Godot 4.7 binary.
HERE="$(cd "$(dirname "$0")" && pwd)"
GODOT="${GODOT:-godot}"
"$GODOT" --headless --path "$HERE/runner" -s "$HERE/process_models.gd" -- "$@"
