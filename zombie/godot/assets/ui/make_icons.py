#!/usr/bin/env python3
"""Draws the interface's own item icons (firearms, ammunition, pipe, crowbar) to assets/ui/icons/item_<id>.png.
The shared icon set has one toy pistol standing in for every gun and plain discs for ammunition; hud.icon()
prefers a file from this folder when there is one.   Run:  python3 make_icons.py   (needs Pillow)"""
import math, os
from PIL import Image, ImageDraw

S = 72          # final size
K = 6           # supersampling
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "icons")

STEEL = (96, 101, 110); STEEL_D = (62, 66, 74); STEEL_L = (146, 151, 158)
WOOD = (132, 92, 58); WOOD_D = (98, 66, 42); WOOD_L = (160, 118, 78)
BRASS = (198, 160, 76); BRASS_D = (150, 116, 50); BRASS_L = (232, 204, 128)
COPPER = (178, 112, 72); RED = (170, 58, 48); RED_D = (124, 40, 34)

def canvas(w=S, h=S):
    im = Image.new("RGBA", (w * K, h * K), (0, 0, 0, 0))
    return im, ImageDraw.Draw(im)

def P(pts):
    return [(x * K, y * K) for x, y in pts]

def poly(d, pts, col):
    d.polygon(P(pts), fill=col + (255,))

def rect(d, x0, y0, x1, y1, col, r=0.0):
    d.rounded_rectangle([x0 * K, y0 * K, x1 * K, y1 * K], radius=r * K, fill=col + (255,))

def line(d, pts, col, w):
    d.line(P(pts), fill=col + (255,), width=int(w * K), joint="curve")
    for x, y in pts[:1] + pts[-1:]:
        d.ellipse([(x - w / 2) * K, (y - w / 2) * K, (x + w / 2) * K, (y + w / 2) * K], fill=col + (255,))

def finish(im, name, rot=0.0):
    if rot:
        im = im.rotate(rot, resample=Image.BICUBIC, expand=False)
    bbox = im.getbbox()
    im = im.crop(bbox)
    # fit into the square with a small margin, keeping the shape's proportions
    m = 5 * K
    k = min((S * K - 2 * m) / im.width, (S * K - 2 * m) / im.height)
    im = im.resize((max(1, int(im.width * k)), max(1, int(im.height * k))), Image.LANCZOS)
    out = Image.new("RGBA", (S * K, S * K), (0, 0, 0, 0))
    out.alpha_composite(im, ((S * K - im.width) // 2, (S * K - im.height) // 2))
    out = out.resize((S, S), Image.LANCZOS)
    os.makedirs(OUT, exist_ok=True)
    out.save(os.path.join(OUT, name + ".png"))

def pistol():
    im, d = canvas(80, 64)
    poly(d, [(50, 26), (68, 26), (74, 58), (58, 60)], WOOD)                 # grip
    poly(d, [(60, 30), (68, 30), (72, 56), (64, 57)], WOOD_D)
    rect(d, 28, 26, 70, 34, STEEL_D, 2)                                     # frame
    d.arc([36 * K, 30 * K, 54 * K, 46 * K], 0, 180, fill=STEEL_D + (255,), width=int(2.4 * K))   # trigger guard
    line(d, [(47, 34), (45, 40)], STEEL_D, 2.0)                             # trigger
    rect(d, 6, 14, 70, 27, STEEL, 2.5)                                      # slide
    rect(d, 6, 14, 70, 17.5, STEEL_L, 2)
    rect(d, 6, 18.5, 12, 24, STEEL_D, 1)                                    # muzzle
    for x in (54, 58, 62, 66):
        rect(d, x, 18, x + 1.6, 26, STEEL_D)                                # slide grip cuts
    rect(d, 64, 10.5, 69, 14.5, STEEL_D, 1)                                 # rear sight
    rect(d, 8, 11.5, 11, 14.5, STEEL_D, 1)                                  # front sight
    finish(im, "item_pistol")

def long_gun(name, scope):
    im, d = canvas(120, 120)
    oy = 50
    poly(d, [(2, oy + 6), (30, oy + 1), (36, oy + 6), (36, oy + 14), (2, oy + 22)], WOOD)       # stock
    poly(d, [(2, oy + 15), (36, oy + 10), (36, oy + 14), (2, oy + 22)], WOOD_D)
    rect(d, 34, oy, 58, oy + 13, STEEL_D, 1.5)                                                   # receiver
    d.arc([40 * K, (oy + 9) * K, 54 * K, (oy + 21) * K], 0, 180, fill=STEEL_D + (255,), width=int(2.0 * K))
    if scope:
        rect(d, 56, oy + 3, 116, oy + 7, STEEL, 1.5)                                             # barrel
        rect(d, 56, oy + 3, 116, oy + 4.4, STEEL_L, 1)
        poly(d, [(56, oy + 7), (84, oy + 7), (80, oy + 13), (56, oy + 13)], WOOD)                # fore-stock
        rect(d, 44, oy + 13, 51, oy + 23, STEEL_D, 1)                                            # magazine
        rect(d, 36, oy - 9, 66, oy - 3, STEEL_D, 2.5)                                            # scope
        rect(d, 33, oy - 10.5, 39, oy - 1.5, STEEL, 2); rect(d, 63, oy - 10.5, 69, oy - 1.5, STEEL, 2)
        rect(d, 42, oy - 3, 45, oy + 1, STEEL_D); rect(d, 57, oy - 3, 60, oy + 1, STEEL_D)
    else:
        rect(d, 56, oy + 1, 116, oy + 5.5, STEEL, 1.5)                                           # barrel
        rect(d, 56, oy + 1, 116, oy + 2.4, STEEL_L, 1)
        rect(d, 56, oy + 6.5, 108, oy + 10.5, STEEL_D, 1.5)                                      # magazine tube
        rect(d, 68, oy + 4.5, 92, oy + 13, WOOD, 2.5)                                            # pump
        for x in (73, 78, 83, 88):
            rect(d, x, oy + 6, x + 1.4, oy + 12, WOOD_D)
        rect(d, 112, oy - 2, 115, oy + 1.5, STEEL_D, 0.5)                                        # bead
    finish(im, name, 38.0)

def rounds(name, body, tip, tall, pointed, base=None):
    im, d = canvas(72, 72)
    xs = (14, 30, 46)
    h = 40 if tall else 30
    for i, x in enumerate(xs):
        y1 = 62 - (4 if i == 1 else 0)
        y0 = y1 - h
        w = 12
        if pointed:
            poly(d, [(x, y0 + 12), (x + w / 2, y0 - 8), (x + w, y0 + 12)], tip)
            rect(d, x, y0 + 10, x + w, y0 + 16, tip, 1)
        else:
            d.pieslice([x * K, (y0 - 7) * K, (x + w) * K, (y0 + 9) * K], 180, 360, fill=tip + (255,))
            rect(d, x, y0, x + w, y0 + 4, tip)
        rect(d, x, y0 + (14 if pointed else 3), x + w, y1, body, 1)
        rect(d, x + 1.5, y0 + (15 if pointed else 4), x + 4, y1 - 3, tuple(min(255, c + 34) for c in body), 1)
        if base:
            rect(d, x - 0.8, y1 - 9, x + w + 0.8, y1, base, 1)
        rect(d, x - 1, y1 - 2.5, x + w + 1, y1, BRASS_D if base is None else tuple(max(0, c - 44) for c in base), 1)
    finish(im, name)

def pipe():
    im, d = canvas(96, 40)
    rect(d, 4, 14, 92, 26, STEEL, 2)
    rect(d, 4, 14, 92, 17.5, STEEL_L, 2)
    rect(d, 4, 22.5, 92, 26, STEEL_D, 2)
    rect(d, 2, 11.5, 12, 28.5, STEEL_D, 2); rect(d, 2, 11.5, 12, 15, STEEL, 2)       # threaded collar
    rect(d, 60, 12.5, 66, 27.5, STEEL_D, 1.5)
    finish(im, "item_pipe", 42.0)

def crowbar():
    im, d = canvas(110, 60)
    col = (124, 46, 40); dark = (88, 32, 28); light = (164, 72, 62)
    line(d, [(10, 40), (84, 40)], col, 7)                                   # shaft
    line(d, [(10, 38.4), (84, 38.4)], light, 1.6)
    line(d, [(84, 40), (96, 38), (102, 28), (100, 16)], col, 7)             # hook
    poly(d, [(96.5, 16), (103.5, 16), (101, 6), (99, 6)], dark)             # claw tip
    poly(d, [(10, 36.5), (10, 43.5), (0, 46), (0, 41)], dark)               # chisel end
    finish(im, "item_crowbar", 40.0)

if __name__ == "__main__":
    pistol()
    long_gun("item_shotgun", False)
    long_gun("item_rifle", True)
    rounds("item_ammo_9mm", BRASS, COPPER, False, False)
    rounds("item_ammo_shells", RED, RED_D, False, False, BRASS)
    rounds("item_ammo_rifle", BRASS, COPPER, True, True)
    pipe()
    crowbar()
    print("icons written to", OUT)
