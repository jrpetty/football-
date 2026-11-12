#!/usr/bin/env python3
"""Turn photographic source tiles into game textures for the Godot project.

usage: python3 scripts/godot-export/textures.py <source-dir> [name ...]
For each <name>.png in the source dir it writes godot/assets/tex/hf_<name>_{a,n,r}.webp:
  a  albedo, edges cross-faded so it tiles
  n  normal map (OpenGL/Godot convention) derived from the picture's luminance at two scales
  r  roughness in the green channel, ambient occlusion in red (the layout the material library expects)
"""
import sys, os
import numpy as np
from PIL import Image, ImageFilter

OUT = os.path.join(os.path.dirname(__file__), '..', '..', 'godot', 'assets', 'tex')
# per texture: (bump strength, roughness lo, roughness hi, seam margin as a fraction, albedo gain)
TUNE = {
    'grass': (2.2, 0.72, 0.95, 0.08, 0.86), 'forest': (2.6, 0.78, 0.98, 0.08, 0.8), 'dirt': (2.0, 0.8, 0.98, 0.08, 0.8),
    'rock': (3.2, 0.62, 0.92, 0.08, 0.64), 'sand': (1.6, 0.7, 0.95, 0.08, 0.66), 'asphalt': (1.8, 0.7, 0.93, 0.06, 1.0),
    'plaster': (0.7, 0.75, 0.92, 0.05, 1.0), 'siding': (2.4, 0.55, 0.85, 0.02, 1.0), 'shingle': (2.8, 0.75, 0.96, 0.02, 1.0),
    'brick': (3.0, 0.72, 0.95, 0.02, 1.0), 'concrete': (1.2, 0.72, 0.94, 0.06, 1.0), 'planks': (2.4, 0.6, 0.9, 0.02, 1.0),
}

def tileable(a, frac):
    """cross-fade a margin across each pair of opposite edges, then scale back to the original size"""
    h, w, _ = a.shape
    m = max(2, int(w * frac))
    t = np.linspace(0.0, 1.0, m, dtype=np.float32)
    t = t * t * (3 - 2 * t)
    b = a[:, : w - m].copy()
    b[:, :m] = a[:, :m] * t[None, :, None] + a[:, w - m:] * (1 - t)[None, :, None]
    c = b[: h - m].copy()
    c[:m] = b[:m] * t[:, None, None] + b[h - m:] * (1 - t)[:, None, None]
    im = Image.fromarray(np.clip(c * 255, 0, 255).astype(np.uint8)).resize((w, h), Image.LANCZOS)
    return np.asarray(im, dtype=np.float32) / 255

def blur_wrap(g, radius):
    """gaussian blur with wrap-around edges"""
    h, w = g.shape
    big = np.tile(g, (3, 3))
    im = Image.fromarray(np.clip(big * 255, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(radius))
    return np.asarray(im, dtype=np.float32)[h:2 * h, w:2 * w] / 255

def process(src, name):
    bump, r_lo, r_hi, margin, gain = TUNE.get(name, (2.0, 0.7, 0.95, 0.05, 1.0))
    a = np.asarray(Image.open(src).convert('RGB'), dtype=np.float32) / 255
    a = tileable(a, margin)
    lum = a @ np.array([0.299, 0.587, 0.114], dtype=np.float32)
    # flatten large-scale brightness drift so repeats don't show as blotches
    low = blur_wrap(lum, 96)
    a = np.clip(a * (low.mean() / np.maximum(low, 1e-3))[..., None] ** 0.6, 0, 1)
    lum = a @ np.array([0.299, 0.587, 0.114], dtype=np.float32)
    # height: fine detail plus a broader shape
    hgt = 0.6 * blur_wrap(lum, 1.2) + 0.4 * blur_wrap(lum, 5)
    dx = (np.roll(hgt, -1, 1) - np.roll(hgt, 1, 1)) * bump * 4
    dy = (np.roll(hgt, -1, 0) - np.roll(hgt, 1, 0)) * bump * 4      # +v is down the image
    nz = np.ones_like(dx)
    ln = np.sqrt(dx * dx + dy * dy + nz * nz)
    # Godot: red = +u, green = image-up
    nrm = np.stack([-dx / ln * 0.5 + 0.5, dy / ln * 0.5 + 0.5, nz / ln * 0.5 + 0.5], -1)
    # darker crevices are rougher and more occluded
    cav = np.clip((blur_wrap(lum, 6) - lum) * 4 + 0.5, 0, 1)
    rough = r_lo + (r_hi - r_lo) * np.clip(1.0 - (lum - lum.min()) / max(1e-3, lum.max() - lum.min()) * 0.8, 0, 1)
    ao = np.clip(1.0 - (cav - 0.5) * 0.9, 0.55, 1.0)
    orm = np.stack([ao, rough, np.zeros_like(ao)], -1)
    os.makedirs(OUT, exist_ok=True)
    save = lambda arr, suf, q: Image.fromarray(np.clip(arr * 255, 0, 255).astype(np.uint8)).save(os.path.join(OUT, f'hf_{name}_{suf}.webp'), quality=q, method=6)
    save(a * gain, 'a', 90); save(nrm, 'n', 92); save(orm, 'r', 88)
    print(name, 'ok')

if __name__ == '__main__':
    d = sys.argv[1]
    names = sys.argv[2:] or [f[:-4] for f in sorted(os.listdir(d)) if f.endswith('.png')]
    for n in names:
        process(os.path.join(d, n + '.png'), n)
