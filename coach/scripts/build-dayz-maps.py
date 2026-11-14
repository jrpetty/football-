#!/usr/bin/env python3
"""Builds the DayZ map backgrounds (src/ui/assets/dayz/<map>.jpg) from the
top-down map images Bohemia Interactive publishes with its Central Economy
tool (CETool/<Map>/map.png in https://github.com/BohemiaInteractive/DayZ-Central-Economy,
ADPL-SA: attribution, non-commercial, DayZ/Arma only, share alike).

Each image covers the whole terrain, north up, so a pixel maps straight to
world metres (x east, y north from the south-west corner).

    python3 scripts/build-dayz-maps.py <central-economy-repo>

Needs Pillow. Livonia's image is 16-bit and dark: its shadows are brightened
(gamma 0.62) before saving.
"""
import sys
from pathlib import Path
from PIL import Image

Image.MAX_IMAGE_PIXELS = None
MAPS = [('ChernarusPlus', 'chernarus', 2048, 82), ('Enoch', 'livonia', 3072, 78), ('Sakhal', 'sakhal', 2048, 82)]

def main() -> None:
    if len(sys.argv) != 2:
        sys.exit('usage: python3 scripts/build-dayz-maps.py <central-economy-repo>')
    ce = Path(sys.argv[1]) / 'CETool'
    out = Path(__file__).resolve().parent.parent / 'src' / 'ui' / 'assets' / 'dayz'
    out.mkdir(parents=True, exist_ok=True)
    for folder, name, size, quality in MAPS:
        im = Image.open(ce / folder / 'map.png').convert('RGB')
        if im.size != (size, size):
            im = im.resize((size, size), Image.LANCZOS)
        if name == 'livonia':
            # Brighten the shadows only (gamma), keeping the colours.
            lut = [round(255 * (v / 255) ** 0.62) for v in range(256)]
            im = im.point(lut * 3)
        im.save(out / f'{name}.jpg', quality=quality, optimize=True, progressive=True)
        print(name, im.size, (out / f'{name}.jpg').stat().st_size // 1024, 'KB')

if __name__ == '__main__':
    main()
