#!/usr/bin/env bash
# tools/shoot.sh <level> [extra args]  → renders tools/out/<level>_*.png and a contact sheet /tmp/claude-0/<level>_sheet.png
cd "$(dirname "$0")/.."
L=$1; shift
timeout 500 xvfb-run -a -s "-screen 0 1920x1080x24" godot --path . --rendering-driver vulkan res://tools/screenshot.tscn -- --level $L "$@" > /tmp/claude-0/shot_$L.log 2>&1
grep -E "SCRIPT ERROR|saved" -A2 /tmp/claude-0/shot_$L.log | grep -v "^--" | head -20
python3 - "$L" <<'PY'
import sys, glob
from PIL import Image
L = sys.argv[1]
files = sorted(glob.glob(f"tools/out/{L}_*.png"))
ims = [Image.open(f).resize((800, 450)) for f in files]
if ims:
    rows = (len(ims) + 1) // 2
    sheet = Image.new("RGB", (1600, 450 * rows))
    for i, im in enumerate(ims):
        sheet.paste(im, ((i % 2) * 800, (i // 2) * 450))
    sheet.save(f"/tmp/claude-0/{L}_sheet.png")
    print("sheet", [f.split('/')[-1] for f in files])
PY
