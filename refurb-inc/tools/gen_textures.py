#!/usr/bin/env python3
"""Procedural placeholder PBR texture sets for Refurb Inc.

Writes  assets/textures/surfaces/<name>/{albedo,normal,roughness}.png  (tileable, 1024px).
Replace any set 1:1 with Poly Haven / ambientCG / AI textures using the same file names.

    python3 tools/gen_textures.py            # all sets
    python3 tools/gen_textures.py concrete   # one set
"""
import os
import sys

import numpy as np
from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "assets", "textures", "surfaces")
N = 1024
rng = np.random.default_rng(1337)


# ----------------------------------------------------------------- noise helpers (all tileable)

def value_noise(size, cells, seed):
    r = np.random.default_rng(seed)
    grid = r.random((cells, cells))
    x = np.linspace(0, cells, size, endpoint=False)
    xi = np.floor(x).astype(int)
    xf = x - xi
    xf = xf * xf * (3 - 2 * xf)
    x0, x1 = xi % cells, (xi + 1) % cells
    a = grid[np.ix_(x0, x0)]
    b = grid[np.ix_(x0, x1)]
    c = grid[np.ix_(x1, x0)]
    d = grid[np.ix_(x1, x1)]
    fy = xf[:, None]
    fx = xf[None, :]
    return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy


def fbm(size, base_cells, octaves, seed, gain=0.5):
    out = np.zeros((size, size))
    amp, total = 1.0, 0.0
    cells = base_cells
    for o in range(octaves):
        out += value_noise(size, cells, seed + o * 17) * amp
        total += amp
        amp *= gain
        cells *= 2
    return out / total


def normal_from_height(h, strength):
    dx = (np.roll(h, -1, axis=1) - np.roll(h, 1, axis=1)) * strength
    dy = (np.roll(h, -1, axis=0) - np.roll(h, 1, axis=0)) * strength
    nz = np.ones_like(h)
    n = np.stack([-dx, dy, nz], axis=-1)   # OpenGL-style (Godot default)
    n /= np.linalg.norm(n, axis=-1, keepdims=True)
    return ((n * 0.5 + 0.5) * 255).astype(np.uint8)


def save(name, albedo, height, rough, nstrength=6.0):
    d = os.path.join(ROOT, name)
    os.makedirs(d, exist_ok=True)
    Image.fromarray(np.clip(albedo * 255, 0, 255).astype(np.uint8)).save(os.path.join(d, "albedo.png"))
    Image.fromarray(normal_from_height(height, nstrength)).save(os.path.join(d, "normal.png"))
    Image.fromarray(np.clip(rough * 255, 0, 255).astype(np.uint8)).save(os.path.join(d, "roughness.png"))
    print("wrote", d)


def tint(base, v, spread):
    """base rgb (0-1) modulated by scalar field v (0-1)."""
    return np.clip(np.array(base)[None, None, :] * (1 - spread + 2 * spread * v[..., None]), 0, 1)


# ----------------------------------------------------------------- sets

def concrete():
    big = fbm(N, 4, 6, 1)
    mid = fbm(N, 16, 4, 2)
    fine = fbm(N, 128, 2, 3)
    stains = np.clip((fbm(N, 6, 5, 4) - 0.55) * 4, 0, 1)
    oil = np.clip((fbm(N, 3, 5, 5) - 0.62) * 6, 0, 1)
    v = 0.55 * big + 0.3 * mid + 0.15 * fine
    alb = tint([0.52, 0.51, 0.49], v, 0.18)
    alb *= (1 - 0.25 * stains[..., None])
    alb *= (1 - 0.45 * oil[..., None])
    # Aggregate speckle
    speck = (rng.random((N, N)) > 0.985).astype(float) * 0.12
    alb = np.clip(alb + speck[..., None] - 0.03, 0, 1)
    # Expansion joint lines every half tile (tile = 2 m on the floor)
    h = 0.7 * mid + 0.3 * fine
    joint = np.zeros((N, N))
    for c in (0, N // 2):
        joint[:, max(c - 2, 0):c + 2] = 1
        joint[max(c - 2, 0):c + 2, :] = 1
    alb *= (1 - 0.35 * joint[..., None])
    h -= joint * 0.6
    rough = np.clip(0.82 - 0.25 * oil - 0.1 * stains + 0.08 * fine, 0.3, 1)
    save("concrete_floor", alb, h, rough, 3.0)


def blockwork():
    # Painted breeze blocks 440 x 215 mm, 10 mm mortar; tile = 4 blocks wide x 8 courses (1.8 m x 1.8 m)
    bw, bh = N // 4, N // 8
    y, x = np.mgrid[0:N, 0:N]
    course = y // bh
    xo = (x + (course % 2) * (bw // 2)) % N
    mortar = ((xo % bw) < 5) | ((y % bh) < 5)
    paint = fbm(N, 8, 4, 10)
    pits = (fbm(N, 96, 2, 11) > 0.72).astype(float)
    alb = tint([0.80, 0.79, 0.75], paint, 0.05)
    alb *= (1 - 0.12 * mortar[..., None])
    grime = np.clip((1 - y / N) * 0 + np.clip((fbm(N, 4, 4, 12) - 0.5) * 2, 0, 1) * 0.12, 0, 1)
    alb *= (1 - grime[..., None])
    h = 0.6 + 0.2 * paint - mortar * 0.5 - pits * 0.15
    rough = np.clip(0.78 + 0.1 * paint, 0, 1)
    save("blockwork_painted", alb, h, rough, 4.0)


def plywood():
    y, x = np.mgrid[0:N, 0:N] / N
    warp = fbm(N, 4, 4, 20) * 2.5
    rings = np.sin((y * 38 + warp * 3) * np.pi) * 0.5 + 0.5
    fine = fbm(N, 64, 3, 21)
    v = 0.6 * rings + 0.4 * fine
    alb = tint([0.72, 0.56, 0.38], v, 0.16)
    h = 0.5 * rings + 0.5 * fine
    rough = np.clip(0.62 + 0.15 * fine, 0, 1)
    save("plywood", alb, h, rough, 1.5)


def tarmac():
    v = 0.5 * fbm(N, 8, 5, 30) + 0.5 * fbm(N, 200, 2, 31)
    stones = (rng.random((N, N)) > 0.93).astype(float)
    alb = tint([0.23, 0.23, 0.24], v, 0.25) + stones[..., None] * 0.06
    patches = np.clip((fbm(N, 5, 4, 32) - 0.58) * 5, 0, 1)
    alb *= (1 - 0.18 * patches[..., None])
    h = v + stones * 0.3
    rough = np.clip(0.88 - 0.1 * patches, 0, 1)
    save("tarmac", alb, h, rough, 5.0)


def brick():
    # UK stretcher bond, 215 x 65 mm bricks, 10 mm mortar
    bw, bh = N // 4, N // 12
    y, x = np.mgrid[0:N, 0:N]
    course = y // bh
    xo = (x + (course % 2) * (bw // 2)) % N
    mortar = ((xo % bw) < 7) | ((y % bh) < 7)
    brick_id = (course * 7 + xo // bw) % 23
    r = np.random.default_rng(40).random(32)
    per = r[brick_id]
    fine = fbm(N, 64, 3, 41)
    base = np.stack([0.55 + 0.12 * per, 0.25 + 0.06 * per, 0.18 + 0.04 * per], -1)
    alb = base * (0.85 + 0.3 * fine[..., None])
    alb[mortar] = np.array([0.62, 0.60, 0.56]) * (0.9 + 0.2 * fine[mortar][..., None])
    h = 0.7 + 0.2 * fine - mortar * 0.5
    rough = np.clip(0.85 + 0.1 * fine, 0, 1)
    save("brick_red", np.clip(alb, 0, 1), h, rough, 5.0)


def cardboard():
    y, x = np.mgrid[0:N, 0:N] / N
    flutes = np.sin(x * 2 * np.pi * 96) * 0.5 + 0.5
    v = 0.7 * fbm(N, 16, 4, 50) + 0.3 * flutes
    alb = tint([0.66, 0.50, 0.33], v, 0.08)
    h = 0.3 * flutes + 0.7 * fbm(N, 64, 2, 51)
    rough = np.full((N, N), 0.86)
    save("cardboard", alb, h, rough, 1.0)


def antistatic():
    v = fbm(N, 64, 3, 60)
    grid = np.zeros((N, N))
    y, x = np.mgrid[0:N, 0:N]
    grid[((x % 64) < 2) | ((y % 64) < 2)] = 1
    alb = tint([0.20, 0.36, 0.44], v, 0.05) * (1 - 0.15 * grid[..., None])
    h = v * 0.4
    rough = np.clip(0.7 + 0.1 * v, 0, 1)
    save("antistatic_mat", alb, h, rough, 1.0)


def painted_metal():
    v = fbm(N, 8, 5, 70)
    scratches = np.zeros((N, N))
    r = np.random.default_rng(71)
    for _ in range(140):
        y0, x0 = r.integers(0, N, 2)
        length = r.integers(20, 160)
        ang = r.random() * np.pi
        for t in range(length):
            yy = int(y0 + np.sin(ang) * t) % N
            xx = int(x0 + np.cos(ang) * t) % N
            scratches[yy, xx] = 1
    alb = np.clip(np.full((N, N, 3), 0.85) * (0.95 + 0.1 * v[..., None]) + scratches[..., None] * 0.1, 0, 1)
    h = v * 0.2 - scratches * 0.3
    rough = np.clip(0.45 + 0.2 * v + 0.2 * scratches, 0, 1)
    save("painted_metal", alb, h, rough, 1.5)


def pegboard():
    y, x = np.mgrid[0:N, 0:N]
    holes = (((x % 64) - 32) ** 2 + ((y % 64) - 32) ** 2) < 49
    v = fbm(N, 32, 3, 80)
    alb = tint([0.60, 0.46, 0.32], v, 0.08)
    alb[holes] = 0.05
    h = 1 - holes * 1.0
    rough = np.full((N, N), 0.8)
    save("pegboard", alb, h, rough, 2.0)


SETS = {f.__name__: f for f in [concrete, blockwork, plywood, tarmac, brick, cardboard, antistatic, painted_metal, pegboard]}

if __name__ == "__main__":
    names = sys.argv[1:] or SETS.keys()
    for n in names:
        SETS[n]()
