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
    alb = tint([0.33, 0.32, 0.31], v, 0.22) + stones[..., None] * 0.07
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



def cladding():
    # Trapezoidal profiled steel sheet: 5 ribs per 1 m tile, near-white so it can be tinted any RAL colour.
    y, x = np.mgrid[0:N, 0:N]
    u = (x % (N // 5)) / (N / 5)
    rib = np.clip(1 - np.abs(u - 0.5) * 6, 0, 1)
    rib = np.where(rib > 0.55, 1.0, rib / 0.55)
    v = fbm(N, 16, 3, 90)
    streak = np.clip((fbm(N, 3, 4, 91) - 0.55) * 1.5, 0, 1) * (y / N)
    alb = np.full((N, N, 3), 0.92) * (0.96 + 0.06 * v[..., None]) * (1 - 0.08 * streak[..., None])
    h = rib * 0.8 + v * 0.05
    rough = np.clip(0.5 + 0.1 * v + 0.1 * streak, 0, 1)
    save("cladding_profiled", alb, h, rough, 9.0)


def composite():
    # Flat composite panels with horizontal joints every half tile (1 m panels at 2 m tile).
    y, x = np.mgrid[0:N, 0:N]
    joint = ((y % (N // 2)) < 4).astype(float)
    micro = ((y % 24) < 2).astype(float) * 0.25
    v = fbm(N, 8, 3, 95)
    alb = np.full((N, N, 3), 0.93) * (0.97 + 0.04 * v[..., None]) * (1 - 0.35 * joint[..., None])
    h = 1 - joint - micro * 0.2
    rough = np.clip(0.35 + 0.08 * v + joint * 0.3, 0, 1)
    save("composite_panel", alb, h, rough, 5.0)


def corrugated():
    y, x = np.mgrid[0:N, 0:N]
    wave = np.sin(x / N * 2 * np.pi * 14) * 0.5 + 0.5
    rust = np.clip((fbm(N, 6, 5, 100) - 0.6) * 4, 0, 1) * (0.4 + 0.6 * (y / N))
    v = fbm(N, 32, 3, 101)
    alb = np.full((N, N, 3), 0.75) * (0.9 + 0.15 * v[..., None])
    alb = alb * (1 - rust[..., None]) + np.array([0.45, 0.25, 0.12])[None, None, :] * rust[..., None]
    h = wave
    rough = np.clip(0.55 + 0.3 * rust + 0.05 * v, 0, 1)
    save("corrugated", alb, h, rough, 6.0)


def epoxy():
    v = fbm(N, 6, 4, 110)
    speck = (rng.random((N, N)) > 0.992).astype(float)
    scuff = np.clip((fbm(N, 24, 3, 111) - 0.62) * 3, 0, 1)
    alb = np.full((N, N, 3), 0.9) * (0.97 + 0.04 * v[..., None]) - speck[..., None] * 0.08 - scuff[..., None] * 0.05
    h = v * 0.05
    rough = np.clip(0.18 + 0.08 * v + 0.35 * scuff, 0, 1)
    save("epoxy_floor", alb, h, rough, 0.6)


def polished_concrete():
    v = 0.6 * fbm(N, 6, 5, 120) + 0.4 * fbm(N, 64, 2, 121)
    agg = (fbm(N, 180, 1, 122) > 0.7).astype(float)
    alb = tint([0.72, 0.71, 0.69], v, 0.08) * (1 - 0.06 * agg[..., None])
    h = v * 0.08
    rough = np.clip(0.22 + 0.12 * v, 0, 1)
    save("polished_concrete", alb, h, rough, 0.8)


def block_paving():
    # Herringbone-ish stretcher pavers 200 x 100 mm (tile 1 m = 5 x 10 pavers)
    y, x = np.mgrid[0:N, 0:N]
    pw, ph = N // 5, N // 10
    row = y // ph
    xo = (x + (row % 2) * (pw // 2)) % N
    gap = ((xo % pw) < 4) | ((y % ph) < 4)
    pid = (row * 13 + xo // pw) % 17
    r = np.random.default_rng(130).random(17)
    per = r[pid]
    v = fbm(N, 48, 3, 131)
    base = np.stack([0.55 + 0.1 * per, 0.5 + 0.08 * per, 0.46 + 0.06 * per], -1)
    alb = base * (0.9 + 0.2 * v[..., None])
    alb[gap] = alb[gap] * 0.45
    h = 0.8 - gap * 0.6 + v * 0.1
    rough = np.clip(0.8 + 0.1 * v, 0, 1)
    save("block_paving", np.clip(alb, 0, 1), h, rough, 4.0)


def grass():
    v = 0.5 * fbm(N, 8, 5, 140) + 0.5 * fbm(N, 128, 2, 141)
    blades = rng.random((N, N))
    alb = np.stack([0.18 + 0.1 * v, 0.34 + 0.14 * v, 0.11 + 0.05 * v], -1) * (0.85 + 0.3 * blades[..., None])
    dry = np.clip((fbm(N, 5, 4, 142) - 0.6) * 3, 0, 1)
    alb = alb * (1 - dry[..., None] * 0.5) + np.array([0.4, 0.38, 0.2])[None, None, :] * dry[..., None] * 0.5
    h = blades * 0.6 + v * 0.4
    rough = np.full((N, N), 0.95)
    save("grass", np.clip(alb, 0, 1), h, rough, 3.0)


def gravel():
    v = fbm(N, 96, 2, 150)
    stones = rng.random((N, N))
    alb = tint([0.52, 0.5, 0.47], stones * 0.5 + v * 0.5, 0.3)
    h = v
    rough = np.full((N, N), 0.9)
    save("gravel", alb, h, rough, 6.0)


def timber():
    # Vertical cedar boards, 6 per tile
    y, x = np.mgrid[0:N, 0:N]
    bw = N // 6
    board = np.minimum(x // bw, 5)
    gap = (x % bw) < 5
    r = np.random.default_rng(160).random(6)
    grain = np.sin((x / N * 3 + fbm(N, 4, 3, 161) * 2 + board * 0.3) * 40) * 0.5 + 0.5
    v = fbm(N, 32, 3, 162)
    base = np.stack([0.55 + 0.1 * r[board], 0.36 + 0.06 * r[board], 0.22 + 0.04 * r[board]], -1)
    alb = base * (0.85 + 0.15 * grain[..., None] + 0.1 * v[..., None])
    alb[gap] *= 0.35
    h = 0.8 - gap * 0.7 + grain * 0.1
    rough = np.clip(0.6 + 0.1 * v, 0, 1)
    save("timber_cladding", np.clip(alb, 0, 1), h, rough, 3.0)


def rubber():
    v = fbm(N, 64, 3, 170)
    y, x = np.mgrid[0:N, 0:N]
    slits = ((x % (N // 8)) < 3).astype(float)
    alb = np.full((N, N, 3), 0.07) * (0.9 + 0.3 * v[..., None])
    h = 1 - slits
    rough = np.full((N, N), 0.85)
    save("rubber", alb, h, rough, 3.0)


def grating():
    y, x = np.mgrid[0:N, 0:N]
    bars = ((x % 32) < 6) | ((y % 96) < 6)
    v = fbm(N, 32, 2, 180)
    alb = np.full((N, N, 3), 0.15)
    alb[bars] = 0.55 + 0.1 * v[bars][..., None]
    h = bars.astype(float)
    rough = np.where(bars, 0.45, 0.9)
    save("metal_grating", alb, h, rough, 4.0)


# ----------------------------------------------------------------- decals (RGBA albedo + normal)

def save_decal(name, rgba, height=None, nstrength=3.0):
    d = os.path.join(ROOT, "..", "decals", name)
    os.makedirs(d, exist_ok=True)
    Image.fromarray(np.clip(rgba * 255, 0, 255).astype(np.uint8), "RGBA").save(os.path.join(d, "albedo.png"))
    if height is not None:
        Image.fromarray(normal_from_height(height, nstrength)).save(os.path.join(d, "normal.png"))
    print("wrote decal", name)


def radial(size, cx=0.5, cy=0.5):
    y, x = np.mgrid[0:size, 0:size] / size
    return np.sqrt((x - cx) ** 2 + (y - cy) ** 2)


def decals():
    M = 512
    def noise(c, o, seed):
        return fbm(M, c, o, seed)
    # Oil stain: dark, irregular, slightly glossy centre
    r = radial(M) + (noise(4, 4, 200) - 0.5) * 0.35
    a = np.clip((0.42 - r) * 4.5, 0, 1) * (0.6 + 0.4 * noise(32, 3, 201))
    rgba = np.zeros((M, M, 4))
    rgba[..., :3] = np.array([0.05, 0.045, 0.04])
    rgba[..., 3] = a * 0.85
    save_decal("oil_stain", rgba)
    # Damp patch on walls: brownish tide-mark at the edge
    r = radial(M, 0.5, 0.7) + (noise(5, 4, 210) - 0.5) * 0.3
    a = np.clip((0.45 - r) * 3, 0, 1)
    edge = np.clip(1 - np.abs(r - 0.42) * 25, 0, 1)
    rgba = np.zeros((M, M, 4))
    rgba[..., :3] = np.array([0.32, 0.28, 0.2])
    rgba[..., 3] = np.clip(a * 0.35 + edge * 0.5, 0, 1) * (0.6 + 0.4 * noise(40, 3, 211))
    save_decal("damp_patch", rgba)
    # Tyre marks: two arcs of rubber
    y, x = np.mgrid[0:M, 0:M] / M
    rgba = np.zeros((M, M, 4))
    a = np.zeros((M, M))
    for off in (0.32, 0.68):
        curve = off + 0.12 * np.sin(y * 3.0)
        a += np.clip(1 - np.abs(x - curve) * 22, 0, 1)
    a *= (0.4 + 0.6 * noise(64, 3, 220)) * np.clip(np.sin(y * np.pi) * 1.5, 0, 1)
    rgba[..., :3] = 0.04
    rgba[..., 3] = np.clip(a, 0, 1) * 0.7
    save_decal("tyre_marks", rgba)
    # Scuffs: many short dark strokes
    rgba = np.zeros((M, M, 4))
    a = np.zeros((M, M))
    rr = np.random.default_rng(230)
    for _ in range(90):
        y0, x0 = rr.integers(20, M - 20, 2)
        ang = rr.random() * np.pi
        ln = rr.integers(10, 60)
        for t in range(ln):
            yy = int(y0 + np.sin(ang) * t)
            xx = int(x0 + np.cos(ang) * t)
            if 0 <= yy < M and 0 <= xx < M:
                a[yy, xx] = max(a[yy, xx], 0.6)
    a *= np.clip((0.5 - radial(M)) * 3, 0, 1)
    rgba[..., :3] = 0.08
    rgba[..., 3] = a
    save_decal("scuffs", rgba)
    # Rust streak running down from the top edge
    y, x = np.mgrid[0:M, 0:M] / M
    w = np.clip(1 - np.abs(x - 0.5 - (noise(3, 3, 240) - 0.5) * 0.2) * 7, 0, 1)
    a = w * np.clip(1 - y * 1.1, 0, 1) * (0.5 + 0.5 * noise(48, 3, 241))
    rgba = np.zeros((M, M, 4))
    rgba[..., :3] = np.array([0.42, 0.2, 0.08])
    rgba[..., 3] = np.clip(a, 0, 1) * 0.8
    save_decal("rust_streak", rgba)
    # Puddle: dark wet patch (rendered glossy by the decal's material)
    r = radial(M) + (noise(4, 5, 250) - 0.5) * 0.4
    a = np.clip((0.4 - r) * 6, 0, 1)
    rgba = np.zeros((M, M, 4))
    rgba[..., :3] = np.array([0.1, 0.11, 0.12])
    rgba[..., 3] = a * 0.75
    save_decal("puddle", rgba, a * 0.2, 1.0)
    # Painted safety hatching (yellow/black) for kerbs, columns, dock edges
    y, x = np.mgrid[0:M, 0:M]
    stripes = (((x + y) // 48) % 2).astype(float)
    rgba = np.zeros((M, M, 4))
    rgba[..., 0] = np.where(stripes > 0, 0.95, 0.05)
    rgba[..., 1] = np.where(stripes > 0, 0.75, 0.05)
    rgba[..., 2] = np.where(stripes > 0, 0.05, 0.05)
    rgba[..., 3] = 0.95 * (0.85 + 0.15 * noise(64, 2, 260))
    save_decal("hazard_stripes", rgba)
    # Leaves / litter for the lock-up yard
    rgba = np.zeros((M, M, 4))
    rr = np.random.default_rng(270)
    for _ in range(60):
        cy, cx = rr.integers(10, M - 10, 2)
        rad = rr.integers(4, 10)
        col = rr.choice([[0.45, 0.3, 0.1], [0.55, 0.4, 0.12], [0.35, 0.25, 0.1]])
        yy, xx = np.ogrid[-cy:M - cy, -cx:M - cx]
        mask = (xx * xx) / (rad * rad) + (yy * yy) / ((rad * 0.5) ** 2) <= 1
        rgba[mask, :3] = col
        rgba[mask, 3] = 0.9
    save_decal("leaves", rgba)

SETS = {f.__name__: f for f in [concrete, blockwork, plywood, tarmac, brick, cardboard, antistatic, painted_metal, pegboard, cladding, composite, corrugated, epoxy, polished_concrete, block_paving, grass, gravel, timber, rubber, grating, decals]}

if __name__ == "__main__":
    names = sys.argv[1:] or SETS.keys()
    for n in names:
        SETS[n]()
