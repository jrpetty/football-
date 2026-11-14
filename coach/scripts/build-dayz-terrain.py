#!/usr/bin/env python3
"""Builds the DayZ terrain grids (src/profiles/dayz/terrain.<map>.json) used to
plot routes that follow roads and open ground and keep out of water: a
1024 x 1024 grid over each map (15 m cells on Chernarus and Sakhal, 12.5 m on
Livonia), each cell road, open ground (fields, meadows, snow), forest or
water.

There is no road network in Bohemia's published files, so the grid is read
from the top-down map images in its Central Economy tool (CETool/<Map>/map.png,
ADPL-SA): roads are thin lines brighter and greyer than what is around them,
forest is dark and textured, water is dark and smooth (the sea is the smooth
dark area joined to the map's edge, lakes are large smooth dark patches).
It is an estimate from the picture: small paths and bridges can be missed,
and town streets count as road.

    python3 scripts/build-dayz-terrain.py <central-economy-repo>

Needs Pillow and numpy.
"""
import base64
import json
import sys
from collections import deque
from pathlib import Path

import numpy as np
from PIL import Image

Image.MAX_IMAGE_PIXELS = None
# Analysis size in pixels per map (Livonia's image is 4096: its thin roads need the full size).
MAPS = [('ChernarusPlus', 'chernarus', 15360, 2048), ('Enoch', 'livonia', 12800, 4096), ('Sakhal', 'sakhal', 15360, 2048)]
G = 1024  # grid size, cells
ROAD, OPEN, FOREST, WATER = 1, 2, 3, 4


def box_mean(a: np.ndarray, r: int) -> np.ndarray:
    """Mean over a (2r+1)^2 window, edges clamped."""
    p = np.pad(a, r + 1, mode='edge').astype(np.float64)
    c = p.cumsum(0).cumsum(1)
    k = 2 * r + 1
    s = c[k:, k:] - c[:-k, k:] - c[k:, :-k] + c[:-k, :-k]
    return (s / (k * k))[: a.shape[0], : a.shape[1]].astype(np.float32)


def hsv(rgb: np.ndarray):
    r, g, b = [rgb[..., i].astype(np.float32) / 255 for i in range(3)]
    mx = np.maximum(np.maximum(r, g), b)
    mn = np.minimum(np.minimum(r, g), b)
    s = np.where(mx > 0, (mx - mn) / np.maximum(mx, 1e-6), 0)
    return s, mx, r, g, b


def components(mask: np.ndarray):
    """Connected components (4-neighbour) of a boolean mask: label array and sizes; touches-edge flags."""
    h, w = mask.shape
    lab = np.zeros((h, w), dtype=np.int32)
    sizes = [0]
    edge = [False]
    n = 0
    ys, xs = np.nonzero(mask)
    for y0, x0 in zip(ys, xs):
        if lab[y0, x0]:
            continue
        n += 1
        q = deque([(y0, x0)])
        lab[y0, x0] = n
        size = 0
        touches = False
        while q:
            y, x = q.popleft()
            size += 1
            if y == 0 or x == 0 or y == h - 1 or x == w - 1:
                touches = True
            for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                yy, xx = y + dy, x + dx
                if 0 <= yy < h and 0 <= xx < w and mask[yy, xx] and not lab[yy, xx]:
                    lab[yy, xx] = n
                    q.append((yy, xx))
        sizes.append(size)
        edge.append(touches)
    return lab, np.array(sizes), np.array(edge)


def otsu(values: np.ndarray) -> float:
    """The threshold that best splits values (0..1) into two groups (Otsu)."""
    hist, edges = np.histogram(values, bins=256, range=(0, 1))
    w = hist.cumsum()
    m = (hist * edges[:-1]).cumsum()
    total, mt = w[-1], m[-1]
    between = np.where((w > 0) & (w < total), (mt * w / total - m) ** 2 / np.maximum(w * (total - w), 1), 0)
    return float(edges[int(between.argmax())])


def classify(img: Image.Image, name: str, A: int) -> np.ndarray:
    rgb = np.asarray(img.convert('RGB').resize((A, A), Image.LANCZOS) if img.size[0] != A else img.convert('RGB'))
    if name == 'livonia':
        # The dark image, brightened channel by channel as for the map picture (else every dark pixel looks saturated).
        lut = np.array([round(255 * (i / 255) ** 0.62) for i in range(256)], dtype=np.uint8)
        rgb = lut[rgb]
    s, v, r, g, b = hsv(rgb)
    mean5 = box_mean(v, 2)
    mean9 = box_mean(v, 4)
    sd9 = np.sqrt(np.maximum(box_mean(v * v, 4) - mean9 * mean9, 0))
    # Water: dark and smooth; the sea joins the edge, lakes are big patches.
    smooth_dark = (v < 0.36) & (sd9 < 0.014) & (s < 0.5)
    step = A // 1024
    lab, sizes, edge = components(smooth_dark[::step, ::step])
    keep = (sizes >= 120) | (edge & (sizes >= 30))
    keep[0] = False
    water = np.kron(keep[lab], np.ones((step, step), dtype=bool))[:A, :A]
    # Roads: thin, brighter than around them and greyer than fields (dirt roads a little warmer).
    # Not in snow fields (bright all round), where the snow's own texture would look like roads.
    # Asphalt on Livonia is no brighter than the fields, but clearly greyer than them.
    sat5 = box_mean(s, 2)
    brighter = (((s < 0.3) & (v > mean5 + 0.035)) | ((s < 0.42) & (v > mean5 + 0.08))) & (v > 0.26)
    greyer = (s < 0.3) & (sat5 - s > 0.12) & (v > 0.2)
    road = (brighter | greyer) & (mean9 < 0.5) & ~water
    if name == 'sakhal':
        # Rock and old snow on Sakhal's mountains are grey and streaky: only clear, darker-ground roads count.
        road = (s < 0.25) & (v > mean5 + 0.06) & (mean9 < 0.4) & ~water
    # Forest: the darker of the land's two brightness groups (split per map), greenish.
    land = ~water & ~road & (v > 0.04)
    cut = min(0.3, otsu(mean5[land]))
    forest = (mean5 < cut) & land & (g >= r * 0.9)
    cls = np.full((A, A), OPEN, dtype=np.uint8)
    cls[forest] = FOREST
    cls[road] = ROAD
    cls[water] = WATER
    return cls


def to_grid(cls: np.ndarray) -> np.ndarray:
    A = cls.shape[0]
    f = A // G
    blocks = cls.reshape(G, f, G, f).transpose(0, 2, 1, 3).reshape(G, G, f * f)
    count = lambda c: (blocks == c).sum(axis=2)
    grid = np.full((G, G), OPEN, dtype=np.uint8)
    grid[count(FOREST) * 2 >= f * f] = FOREST
    grid[count(ROAD) >= 1] = ROAD
    grid[count(WATER) * 4 >= 3 * f * f] = WATER
    # A road cell with no road next to it is a speck of texture, not a road.
    rd = grid == ROAD
    p = np.pad(rd, 1)
    neighbours = sum(np.roll(np.roll(p, dy, 0), dx, 1)[1:-1, 1:-1] for dy in (-1, 0, 1) for dx in (-1, 0, 1) if dy or dx)
    grid[rd & (neighbours == 0)] = OPEN
    return grid


def rle(grid: np.ndarray) -> str:
    """Runs of (class, length ≤ 255), row by row from the north edge, base64."""
    flat = grid.reshape(-1)
    out = bytearray()
    i = 0
    n = flat.size
    while i < n:
        c = flat[i]
        j = i + 1
        while j < n and flat[j] == c and j - i < 255:
            j += 1
        out += bytes((int(c), j - i))
        i = j
    return base64.b64encode(bytes(out)).decode('ascii')


def main() -> None:
    if len(sys.argv) not in (2, 3):
        sys.exit('usage: python3 scripts/build-dayz-terrain.py <central-economy-repo> [preview-dir]')
    ce = Path(sys.argv[1]) / 'CETool'
    out = Path(__file__).resolve().parent.parent / 'src' / 'profiles' / 'dayz'
    preview = Path(sys.argv[2]) if len(sys.argv) == 3 else None
    for folder, name, world, size in MAPS:
        grid = to_grid(classify(Image.open(ce / folder / 'map.png'), name, size))
        data = {
            'source': "Read from Bohemia Interactive's top-down map image (DayZ Central Economy tool, ADPL-SA) by scripts/build-dayz-terrain.py: an estimate.",
            'map': name,
            'worldSize': world,
            'size': G,
            'classes': {'1': 'road', '2': 'open', '3': 'forest', '4': 'water'},
            'rle': rle(grid),
        }
        path = out / f'terrain.{name}.json'
        path.write_text(json.dumps(data) + '\n')
        shares = {k: round(float((grid == c).mean()) * 100, 1) for k, c in (('road', ROAD), ('open', OPEN), ('forest', FOREST), ('water', WATER))}
        print(name, path.stat().st_size // 1024, 'KB', shares)
        if preview:
            colours = np.array([[0, 0, 0], [250, 220, 120], [120, 170, 90], [30, 70, 35], [40, 90, 200]], dtype=np.uint8)
            Image.fromarray(colours[grid]).save(preview / f'terrain_{name}.png')


if __name__ == '__main__':
    main()
