#!/usr/bin/env python3
"""[fields] The tools of the fields and the pens: their pictures, and the blocks' shapes.

Every picture is sixteen by sixteen, drawn a pixel at a time in the game's own manner: a dark outline, two or
three shades lit from the top left, a highlight or two. The tools are drawn from little maps (one letter a
pixel, a palette for each); the blocks' faces from the grain up (planks, staves, straw, wicker, water).

    python3 tools/fields_art.py            # write the pictures, the models and the blockstates

What it writes, under src/main/resources/assets/mc_assistant/:
  textures/item/   copper_watering_can(_filled), seed_satchel(_full), copper_sickle, bee_smoker
  textures/block/  nesting_box_*, feed_trough_*, fish_trap_*, rain_barrel_* (the water four frames, rippling)
  models/block/    the boxes of each block (the eggs, the grain, the catch and the water as parts of their own)
  models/item/     the tools (the can and the satchel by how full they are) and the blocks in the hand
  blockstates/     the four blocks
"""
import json
import os
import random

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ASSETS = os.path.join(ROOT, "src/main/resources/assets/mc_assistant")
TEX = os.path.join(ASSETS, "textures")
MODELS = os.path.join(ASSETS, "models")
STATES = os.path.join(ASSETS, "blockstates")
NS = "mc_assistant"


def hexc(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def from_map(rows, palette):
    """A picture from a map of letters, one a pixel ('.' is clear)."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        assert len(row) == 16, (y, row, len(row))
        for x, ch in enumerate(row):
            if ch == ".":
                continue
            img.putpixel((x, y), palette[ch])
    return img


def save(img, *path):
    full = os.path.join(TEX, *path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    img.save(full)


def shade(c, f):
    return tuple(max(0, min(255, int(round(v * f)))) for v in c[:3]) + (c[3],)


# ===================================================================== the tools

COPPER = {
    "o": hexc("#4b2414"),   # outline
    "d": hexc("#8c4527"),   # shadow
    "c": hexc("#bd6638"),   # copper
    "l": hexc("#de8d58"),   # lit
    "h": hexc("#f7c095"),   # highlight
    "g": hexc("#4f9a82"),   # a fleck of verdigris
    "k": hexc("#2a160c"),   # the dark of the can's mouth
    "w": hexc("#2f62c9"),   # water
    "W": hexc("#7fb2f5"),   # water's glint
}

CAN = [
    "................",
    "....oooo........",
    "...odllco.......",
    "...oc..do.......",
    "..oooooooo....oo",
    ".ohkkkkkkdo..ohd",
    ".ohlcccccdo.ohlo",
    ".ohlcccgcdooocdo",
    ".ollllllldoclco.",
    ".ohlcccccdclco..",
    ".ohlcccccdcdo...",
    ".ohlccccccdo....",
    ".ohlcccccdo.....",
    ".odddddddddo....",
    "..ooooooooo.....",
    "................",
]

CAN_FILLED = [
    "................",
    "....oooo........",
    "...odllco.......",
    "...oc..do.......",
    "..oooooooo....oo",
    ".ohWwwwwwdo..ohd",
    ".ohlcccccdo.ohlo",
    ".ohlcccgcdooocdo",
    ".ollllllldoclcoW",
    ".ohlcccccdclco.w",
    ".ohlcccccdcdo.W.",
    ".ohlccccccdo..w.",
    ".ohlcccccdo...W.",
    ".odddddddddo....",
    "..ooooooooo.....",
    "................",
]

LEATHER = {
    "o": hexc("#3a2213"),
    "d": hexc("#6b4126"),
    "c": hexc("#966339"),
    "l": hexc("#bb8652"),
    "h": hexc("#dbae7b"),
    "s": hexc("#e9e3d2"),   # the string strap
    "S": hexc("#a8a08c"),   # its shade
    "b": hexc("#c9a227"),   # the brass toggle
    "B": hexc("#7d5f12"),
    "e": hexc("#7fb23a"),   # seed: green
    "E": hexc("#c9b74c"),   # seed: straw
    "r": hexc("#e0873a"),   # a carrot's tip
}

class Canvas:
    """A sixteen-by-sixteen of letters, drawn on shape by shape, then outlined and coloured."""

    def __init__(self):
        self.px = {}

    def put(self, x, y, c):
        if 0 <= x < 16 and 0 <= y < 16:
            self.px[(x, y)] = c

    def empty(self, x, y):
        return 0 <= x < 16 and 0 <= y < 16 and (x, y) not in self.px

    def outline(self, c, of=None):
        """Every clear pixel beside one drawn (of these letters, or any) takes the outline."""
        add = set()
        for (x, y), v in self.px.items():
            if of is not None and v not in of:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                if self.empty(x + dx, y + dy):
                    add.add((x + dx, y + dy))
        for q in add:
            self.px[q] = c

    def image(self, pal):
        img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        for (x, y), v in self.px.items():
            img.putpixel((x, y), pal[v])
        return img


LEATHER.update({
    "D": hexc("#4d2d18"),   # the shadow under the flap
    "t": hexc("#d2a671"),   # the stitching
})


def satchel(full):
    """A leather bag: its flap over the top, stitched round, a brass toggle; a string strap looped over; full, it bulges
    and the seed shows at its mouth."""
    cv = Canvas()
    x0, x1 = (1, 14) if full else (2, 13)
    top, bottom = 6, 13
    for y in range(top, bottom + 1):
        for x in range(x0, x1 + 1):
            if y == bottom and (x <= x0 + 1 or x >= x1 - 1):
                continue                                      # the bag's round foot
            if y == bottom - 1 and (x == x0 or x == x1):
                continue
            c = "c"
            if x == x0 or (y == bottom - 1 and x == x0 + 1):
                c = "l"
            if x == x1 or (y == bottom - 1 and x == x1 - 1):
                c = "d"
            if y == bottom:
                c = "d"
            cv.put(x, y, c)
    # A seam down its middle, lower half.
    for y in range(top + 6, bottom):
        cv.put((x0 + x1) // 2, y, "d")
    # The flap: over the top, its edge rounded, the light on it.
    fl = top + 3
    for y in range(top - 1, fl + 1):
        for x in range(x0, x1 + 1):
            if y == fl and (x <= x0 + 1 or x >= x1 - 1):
                continue
            c = "l"
            if y == top - 1 or x == x0:
                c = "h"
            if x == x1:
                c = "c"
            cv.put(x, y, c)
    for x in range(x0 + 2, x1 - 1):
        cv.put(x, fl + 1, "D")
    cv.put(x0 + 1, fl, "D")
    cv.put(x1 - 1, fl, "D")
    for x in range(x0 + 2, x1 - 1, 2):
        cv.put(x, fl - 1, "t")                                  # stitched round its edge
    mid = (x0 + x1) // 2
    cv.put(mid, fl, "b")
    cv.put(mid + 1, fl, "b")
    cv.put(mid, fl + 1, "B")
    cv.put(mid + 1, fl + 1, "B")
    if full:
        # Seed heaped at its mouth, over the flap's top.
        heap = {(4, 4): "e", (5, 4): "E", (6, 4): "e", (7, 4): "r", (8, 4): "E", (9, 4): "e", (10, 4): "E", (11, 4): "e",
                (6, 3): "E", (7, 3): "e", (8, 3): "e", (9, 3): "E"}
        for (x, y), c in heap.items():
            cv.put(x, y, c)
    cv.outline("o")
    # The strap: a string loop from corner to corner over the top.
    strap = [(x0 + 1, 4), (x0 + 1, 3), (x0 + 2, 2), (x0 + 3, 1), (x0 + 4, 1), (x0 + 5, 1), (x0 + 6, 1), (x0 + 7, 1),
             (x0 + 8, 1), (x1 - 2, 2), (x1 - 1, 3), (x1 - 1, 4)]
    for i, (x, y) in enumerate(strap):
        if cv.empty(x, y):
            cv.put(x, y, "s" if i < len(strap) - 3 else "S")
    for (x, y) in [(x0 + 2, 3), (x1 - 2, 3)]:
        if cv.empty(x, y):
            cv.put(x, y, "S")
    return cv.image(LEATHER)


SICKLE_PAL = dict(COPPER)
SICKLE_PAL.update({
    "o": hexc("#47220f"),
    "t": hexc("#2e1d0e"),   # the handle's outline
    "w": hexc("#6a4622"),   # wood, dark
    "W": hexc("#956834"),   # wood
    "L": hexc("#c49155"),   # wood, lit
    "f": hexc("#7a7a7a"),   # the ferrule's iron collar
    "F": hexc("#b8b8b8"),
})


def sickle():
    """A curved copper blade on a turned handle: the blade loops up and over from the collar, its sharp inner edge
    bright, its back dark, thinning to a point."""
    import math
    cv = Canvas()
    cx, cy = 9.4, 7.0
    start = math.radians(152)
    span = math.radians(232)
    for y in range(16):
        for x in range(16):
            px, py = x + 0.5 - cx, y + 0.5 - cy
            d = math.hypot(px, py)
            a = math.atan2(py, px)
            # from the collar (lower left) up the left side, over the top and down the right to its point
            t = (a - start) % (2 * math.pi)
            if t > span:
                continue
            frac = t / span
            outer = 5.6 - 0.5 * frac
            inner = 3.4 + 0.9 * frac
            if inner <= d <= outer:
                c = "c"
                if d >= outer - 0.75:
                    c = "d"
                elif d <= inner + 0.6:
                    c = "h"
                elif frac < 0.5 and d <= inner + 1.2:
                    c = "l"
                cv.put(x, y, c)
    cv.outline("o")
    # The collar where the blade is set in the handle, and the handle down to the bottom left.
    for (x, y, c) in [(5, 9, "F"), (6, 10, "f"), (5, 10, "f"), (6, 9, "f")]:
        cv.put(x, y, c)
    handle = [(4, 11), (3, 12), (2, 13), (1, 14)]
    for (x, y) in handle:
        cv.put(x, y, "W")
        cv.put(x + 1, y, "w")
        cv.put(x, y - 1, "L") if cv.px.get((x, y - 1)) in (None, "o") else None
    cv.put(1, 15, "t")
    for (x, y) in [(0, 14), (0, 13), (1, 12), (2, 11), (3, 10), (2, 15), (3, 14), (4, 13), (5, 12), (6, 11)]:
        if cv.empty(x, y):
            cv.put(x, y, "t")
    return cv.image(SICKLE_PAL)


SMOKER_PAL = dict(COPPER)
SMOKER_PAL.update({
    "s": hexc("#f6f6f2", 240),   # smoke: the puff's lit top,
    "S": hexc("#cfcfcb", 230),   # its body,
    "K": hexc("#9d9d99", 215),   # its shadowed underside,
    "f": hexc("#e8e8e4", 140),   # and its thinning edge
    "L": hexc("#8a5a33"),        # the bellows' leather
    "M": hexc("#5e3a1f"),        # its folds
    "B": hexc("#6e4f2c"),        # the bellows' boards
    "N": hexc("#a27a4a"),
    "e": hexc("#ffb347"),        # the coal glowing behind its grate
    "E": hexc("#d4501e"),
    "q": hexc("#2a160c"),        # the grate
})


def smoker():
    """A copper can, its lid drawn up to a nozzle puffing smoke, a grate with the coal glowing in it, and a leather
    bellows at its side."""
    cv = Canvas()
    for y in range(7, 15):
        for x in range(7, 13):
            c = "c"
            if x == 7:
                c = "h"
            elif x == 8:
                c = "l"
            elif x == 12:
                c = "d"
            if y == 14:
                c = "d"
            cv.put(x, y, c)
    for x in range(7, 13):
        cv.put(x, 7, "d" if x == 12 else "l")                   # the rolled rim of the lid
    for x in range(8, 12):
        cv.put(x, 6, "h" if x == 8 else "l" if x == 9 else "c")
    for x in range(9, 11):
        cv.put(x, 5, "l" if x == 9 else "d")
    cv.put(10, 4, "c")
    cv.put(11, 3, "d")                                          # the nozzle, bent over
    for (x, y, c) in [(9, 10, "q"), (10, 10, "q"), (9, 11, "e"), (10, 11, "E"), (9, 12, "q"), (10, 12, "q")]:
        cv.put(x, y, c)
    cv.put(11, 9, "g")
    # The bellows: two boards hinged at the can, wide open at the far end, the leather folded between them.
    # The boards close toward the hinge at the can like a pair of jaws; the folds between them light and dark in turn.
    for (x, y) in [(0, 7), (1, 7), (2, 8), (3, 8), (4, 9)]:
        cv.put(x, y, "N")
    for (x, y) in [(0, 14), (1, 14), (2, 13), (3, 13), (4, 12)]:
        cv.put(x, y, "B")
    folds = {1: range(8, 14), 2: range(9, 13), 3: range(9, 13), 4: range(10, 12)}
    for x, ys in folds.items():
        for y in ys:
            cv.put(x, y, "L" if (y + (x > 2)) % 2 == 1 else "M")
    for (x, y) in [(5, 10), (5, 11), (6, 10), (6, 11)]:
        cv.put(x, y, "d")                                         # the bellows' pipe into the can
    cv.outline("o")
    # The smoke: a wisp from the nozzle rising up and away to the right, lit above and shadowed beneath, thinning out.
    for (x, y, c) in [(12, 2, "S"), (12, 1, "f"), (13, 1, "s"), (14, 1, "S"), (14, 0, "s"), (15, 0, "f"), (15, 1, "K"),
                      (13, 2, "K")]:
        if cv.empty(x, y):
            cv.put(x, y, c)
    return cv.image(SMOKER_PAL)


def tools():
    save(from_map(CAN, COPPER), "item", "copper_watering_can.png")
    save(from_map(CAN_FILLED, COPPER), "item", "copper_watering_can_filled.png")
    save(satchel(False), "item", "seed_satchel.png")
    save(satchel(True), "item", "seed_satchel_full.png")
    save(sickle(), "item", "copper_sickle.png")
    save(smoker(), "item", "bee_smoker.png")


# ===================================================================== the blocks' faces

def planks(base, seed, rows=4, nails=True):
    """Planks laid across, a grain in each, the seams dark, a nail at each end."""
    rnd = random.Random(seed)
    img = Image.new("RGBA", (16, 16))
    h = 16 // rows
    for y in range(16):
        band = y // h
        top = y % h == 0
        bottom = y % h == h - 1
        tone = 1.0 + (band % 2) * -0.05 + rnd.uniform(-0.02, 0.02)
        for x in range(16):
            f = tone + rnd.uniform(-0.05, 0.05)
            # grain: a long streak or two in each plank
            if (x * 3 + band * 7 + (y % h) * 11) % 13 == 0:
                f -= 0.12
            if bottom:
                f = 0.62
            elif top:
                f += 0.10
            img.putpixel((x, y), shade(base, f))
        if nails and not top and not bottom and y % h == h // 2:
            for nx in (1, 14):
                img.putpixel((nx, y), shade(base, 0.45))
    return img


def staves(base, seed):
    """A barrel's staves, upright: a seam every three or four, each stave lit at its left."""
    rnd = random.Random(seed)
    img = Image.new("RGBA", (16, 16))
    edges = [0, 3, 7, 10, 13]
    for x in range(16):
        seam = x in edges
        first = (x - 1) in edges
        for y in range(16):
            f = 1.0 + rnd.uniform(-0.05, 0.05)
            if (y * 5 + x * 2) % 11 == 0:
                f -= 0.1
            if seam:
                f = 0.6
            elif first:
                f += 0.12
            img.putpixel((x, y), shade(base, f))
    return img


def hoop():
    """The copper hoop round a barrel, two pixels deep (the top row lit, the lower in shade), a rivet every eight, and a
    fleck of green where the rain has got at it. The rest of the picture repeats it."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    lit, base, low = hexc("#e9a06a"), hexc("#c06a3b"), hexc("#8a4426")
    for y in range(16):
        for x in range(16):
            r = y % 2
            c = lit if r == 0 else low
            if x % 8 == 3:
                c = hexc("#f7c8a0") if r == 0 else hexc("#4b2414")      # a rivet: its head catches the light
            elif x % 8 == 4:
                c = base
            if r == 1 and x in (10, 11) and y < 2:
                c = hexc("#4f9a82")
            img.putpixel((x, y), c)
    return img


def straw(seed):
    """Hay: golden strands lying every which way, light over dark."""
    rnd = random.Random(seed)
    img = Image.new("RGBA", (16, 16), hexc("#a8822a"))
    cols = [hexc("#7e5f1c"), hexc("#b8902f"), hexc("#d6b14a"), hexc("#ecd27a")]
    for i in range(46):
        x, y = rnd.randrange(16), rnd.randrange(16)
        dx, dy = rnd.choice([(1, 0), (1, 1), (0, 1), (1, -1)])
        c = cols[min(3, i * 4 // 46)]
        for k in range(rnd.randint(3, 6)):
            img.putpixel(((x + dx * k) % 16, (y + dy * k) % 16), c)
    return img


def egg():
    """An egg: cream, a soft shade round its foot, a few freckles. Its sides are drawn at 0-2 (2 wide, 3 high)."""
    img = Image.new("RGBA", (16, 16), hexc("#e9d9b4"))
    for x in range(16):
        for y in range(16):
            f = 1.06 - 0.05 * (y % 4) - (0.04 if x % 2 else 0)
            img.putpixel((x, y), shade(hexc("#efe2c2"), f))
    for (x, y) in [(0, 0), (1, 1), (0, 2), (5, 3), (9, 1), (12, 6), (3, 9), (14, 12)]:
        img.putpixel((x, y), hexc("#b98f5c"))
    img.putpixel((0, 0), hexc("#fff7e6"))
    return img


def grain(seed):
    """Feed heaped in a trough: grains of wheat and seed, golden and pale, a few dark husks, a green seed here and there."""
    rnd = random.Random(seed)
    img = Image.new("RGBA", (16, 16))
    for x in range(16):
        for y in range(16):
            r = rnd.random()
            c = hexc("#c49a36") if r < 0.42 else hexc("#ddb956") if r < 0.70 else hexc("#9a7424") if r < 0.90 \
                else hexc("#f0d781") if r < 0.97 else hexc("#8fa648")
            img.putpixel((x, y), c)
    # Grains lying in twos, lit along their tops.
    for i in range(14):
        x, y = rnd.randrange(15), rnd.randrange(1, 16)
        img.putpixel((x, y), hexc("#e8c868"))
        img.putpixel((x + 1, y), hexc("#e8c868"))
        img.putpixel((x, y - 1), hexc("#f7e4a0"))
    return img


def wicker(seed, open_weave=True):
    """Woven willow: upright stakes four apart, the weavers passing over one and under the next, each weaver lit along
    its top and darkening where it dips under a stake. The open weave leaves a gap between each course (clear), so the
    catch shows through; the bound rim is woven close."""
    rnd = random.Random(seed)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    stake, stake_lit = hexc("#5a3b1c"), hexc("#7a5530")
    top, lit, base, deep = hexc("#d4ad70"), hexc("#b38552"), hexc("#8f683a"), hexc("#5e4224")
    period = 4 if open_weave else 2
    for y in range(16):
        course, r = y // period, y % period
        for x in range(16):
            col, gx = x // 4, x % 4
            over = (col + course) % 2 == 0
            if open_weave and r >= 2:
                if gx == 0:
                    img.putpixel((x, y), stake)                 # the stake, seen between the courses
                continue
            if gx == 0 and not over:
                c = stake_lit if r == 0 else stake              # the stake over the weaver here
            else:
                c = top if r == 0 else lit
                if not over and gx in (1, 3):
                    c = base if r == 0 else deep                # the weaver bending under the next stake
                elif over and gx == 0:
                    c = top if r == 0 else lit
            if rnd.random() < 0.05:
                c = shade(c, 0.9)
            img.putpixel((x, y), c)
    return img


def fish_tex():
    """The catch, side on: a cod at the top (0-7 across, 0-2 down), a salmon beneath it (0-7, 4-6), the head to the left
    and the tail to the right."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    cod = [".cbcbc.t", "elllllct", ".ddddd.t"]
    sal = [".RrRrR.T", "eppppprT", ".ddddd.T"]
    pal = {"c": hexc("#a89068"), "b": hexc("#8a7452"), "l": hexc("#d8c8a0"), "d": hexc("#6e5c40"), "e": hexc("#141414"),
           "t": hexc("#7a6648"), "R": hexc("#c64a3a"), "r": hexc("#9a3428"), "p": hexc("#e8806a"), "T": hexc("#a83a2e")}
    for oy, rows in ((0, cod), (4, sal)):
        for y, row in enumerate(rows):
            for x, ch in enumerate(row):
                if ch != ".":
                    img.putpixel((x, y + oy), pal[ch])
    return img


def rope():
    """A twisted cord, its strands lying on the slant, light and shade in turn."""
    img = Image.new("RGBA", (16, 16))
    cols = [hexc("#d8c08a"), hexc("#b89a62"), hexc("#8c7244"), hexc("#c9ad74")]
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), cols[(x + y) % 4])
    return img


def water_frames(n=8):
    """Rain water standing in a barrel: deep blue, long soft ripples drifting slowly across it with the odd glint, n
    frames tall (the game plays them in turn, blending one into the next)."""
    import math
    img = Image.new("RGBA", (16, 16 * n))
    deep, mid, light, glint = (36, 78, 170), (54, 108, 210), (98, 152, 236), (186, 220, 255)
    for k in range(n):
        ph = 2 * math.pi * k / n
        for y in range(16):
            for x in range(16):
                v = math.sin((x * 0.55 + y * 0.35) + ph) + 0.6 * math.sin((x * -0.3 + y * 0.7) * 1.3 - ph)
                c = light if v > 1.15 else mid if v > -0.2 else deep
                if v > 1.48 and (x * 7 + y * 3 + k) % 5 == 0:
                    c = glint
                img.putpixel((x, y + 16 * k), c + (210,))
    return img


def blocks():
    oak = hexc("#a98454")
    dark = hexc("#7a5932")
    save(planks(oak, 1), "block", "nesting_box_side.png")
    save(planks(shade(oak, 0.86), 2, nails=False), "block", "nesting_box_inside.png")
    save(straw(3), "block", "nesting_box_straw.png")
    save(egg(), "block", "nesting_box_egg.png")
    save(planks(dark, 4), "block", "feed_trough_wood.png")
    save(planks(shade(dark, 0.8), 5, rows=2, nails=False), "block", "feed_trough_inside.png")
    save(grain(6), "block", "feed_trough_grain.png")
    save(wicker(7), "block", "fish_trap_wicker.png")
    save(wicker(8, open_weave=False), "block", "fish_trap_rim.png")
    save(fish_tex(), "block", "fish_trap_fish.png")
    save(rope(), "block", "fish_trap_rope.png")
    save(staves(hexc("#8a6238"), 9), "block", "rain_barrel_side.png")
    save(planks(hexc("#6a4a2a"), 10, rows=4, nails=False), "block", "rain_barrel_inside.png")
    save(hoop(), "block", "rain_barrel_hoop.png")
    rim = Image.new("RGBA", (16, 16))
    for x in range(16):
        for y in range(16):
            ring = max(abs(x - 7.5), abs(y - 7.5))
            f = 1.08 if ring > 6.5 else 0.85 if ring > 5.5 else 0.7
            if (x + y) % 5 == 0:
                f -= 0.08
            rim.putpixel((x, y), shade(hexc("#9a7044"), f))
    save(rim, "block", "rain_barrel_top.png")
    save(water_frames(), "block", "rain_barrel_water.png")
    with open(os.path.join(TEX, "block", "rain_barrel_water.png.mcmeta"), "w") as f:
        json.dump({"animation": {"frametime": 5, "interpolate": True}}, f, indent=2)
        f.write("\n")


# ===================================================================== the shapes

def box(frm, to, tex, faces="all", uv=None, rotation=None, cull=None):
    """A cuboid: textures by face ('all' or a dict face->texture key), the game working out each face's UV from where
    it stands unless one is given."""
    names = ["north", "south", "east", "west", "up", "down"]
    if isinstance(tex, str):
        tex = {n: tex for n in names}
    out = {"from": frm, "to": to, "faces": {}}
    for n, t in tex.items():
        if t is None:
            continue
        face = {"texture": "#" + t}
        if uv and n in uv:
            face["uv"] = uv[n]
        if cull and n in cull:
            face["cullface"] = n
        out["faces"][n] = face
    if rotation:
        out["rotation"] = rotation
    return out


def model(name, textures, elements, parent="minecraft:block/block", render=None, ao=True):
    m = {"parent": parent, "textures": textures, "elements": elements}
    if render:
        m["render_type"] = render
    if not ao:
        m["ambientocclusion"] = False
    path = os.path.join(MODELS, "block", name + ".json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(m, f, indent=1)
        f.write("\n")


def item_model(name, m):
    path = os.path.join(MODELS, "item", name + ".json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(m, f, indent=1)
        f.write("\n")


def state(name, s):
    os.makedirs(STATES, exist_ok=True)
    with open(os.path.join(STATES, name + ".json"), "w") as f:
        json.dump(s, f, indent=1)
        f.write("\n")


def b(name):
    return NS + ":block/" + name


# --- the nesting box: a low box, its front lip lower, hay in it, eggs on the hay (facing north: the lip to the north)

def nest_elements(eggs, hay):
    t = {"side": "side", "in": "inside"}
    els = [
        box([1, 0, 1], [15, 1, 15], {"down": "side", "up": "inside", "north": "side", "south": "side", "east": "side", "west": "side"},
            cull=["down"]),
        # the back, high; the front lip, low; the sides stepping down from back to front
        box([1, 1, 13], [15, 7, 15], {"north": "inside", "south": "side", "east": "side", "west": "side", "up": "side"}),
        box([1, 1, 1], [15, 3.5, 3], {"north": "side", "south": "inside", "east": "side", "west": "side", "up": "side"}),
        box([1, 1, 3], [3, 5, 8], {"north": "side", "east": "inside", "west": "side", "up": "side"}),
        box([1, 1, 8], [3, 6, 13], {"north": "side", "east": "inside", "west": "side", "up": "side"}),
        box([13, 1, 3], [15, 5, 8], {"north": "side", "west": "inside", "east": "side", "up": "side"}),
        box([13, 1, 8], [15, 6, 13], {"north": "side", "west": "inside", "east": "side", "up": "side"}),
    ]
    floor = 1.0
    if hay:
        els.append(box([3, 1, 3], [13, 2.5, 13], {"up": "straw", "north": "straw", "south": "straw", "east": "straw", "west": "straw"}))
        # tufts round the edge, poking up past the lip
        els.append(box([3, 2.5, 3], [6, 3.5, 4.5], {"up": "straw", "north": "straw", "east": "straw", "west": "straw"}))
        els.append(box([10, 2.5, 11], [13, 4, 13], {"up": "straw", "north": "straw", "east": "straw", "west": "straw"}))
        els.append(box([11.5, 2.5, 3], [13, 3.5, 6], {"up": "straw", "north": "straw", "west": "straw", "south": "straw"}))
        floor = 2.5
    spots = [(6.5, 7.0, 15), (9.0, 8.5, -20), (5.5, 9.5, 30), (9.5, 5.5, 0), (7.5, 10.5, -10)]
    n = {0: 0, 1: 1, 2: 3, 3: 5}[eggs]
    for (x, z, ang) in spots[:n]:
        side = [0, 0, 2, 3]
        turn = {"origin": [x + 1, floor, z + 1], "axis": "y", "angle": [22.5, -22.5, 0, 22.5, -22.5][spots.index((x, z, ang))]}
        els.append(box([x, floor, z], [x + 2, floor + 2.5, z + 2], "egg",
                       uv={"north": side, "south": side, "east": side, "west": side, "up": [0, 0, 2, 2]}, rotation=turn))
        cap = [0, 0, 1, 1]
        els.append(box([x + 0.4, floor + 2.5, z + 0.4], [x + 1.6, floor + 3.2, z + 1.6], "egg",
                       uv={"north": cap, "south": cap, "east": cap, "west": cap, "up": cap}, rotation=dict(turn)))
    return els


def nesting_box():
    tex = {"particle": b("nesting_box_side"), "side": b("nesting_box_side"), "inside": b("nesting_box_inside"),
           "straw": b("nesting_box_straw"), "egg": b("nesting_box_egg")}
    for hay in (False, True):
        for eggs in range(4):
            model("nesting_box_%s_%d" % ("hay" if hay else "bare", eggs), tex, nest_elements(eggs, hay))
    variants = {}
    rot = {"north": 0, "east": 90, "south": 180, "west": 270}
    for facing, y in rot.items():
        for hay in (False, True):
            for eggs in range(4):
                v = {"model": b("nesting_box_%s_%d" % ("hay" if hay else "bare", eggs))}
                if y:
                    v["y"] = y
                variants["eggs=%d,facing=%s,hay=%s" % (eggs, facing, "true" if hay else "false")] = v
    state("nesting_box", {"variants": variants})
    item_model("nesting_box", {"parent": b("nesting_box_hay_2")})


# --- the feed trough: a plank trough on four short legs, along x; grain heaped in it by how full

def trough_elements(feed):
    els = [
        box([1, 0, 3], [3, 3, 5], "wood", cull=["down"]), box([13, 0, 3], [15, 3, 5], "wood", cull=["down"]),
        box([1, 0, 11], [3, 3, 13], "wood", cull=["down"]), box([13, 0, 11], [15, 3, 13], "wood", cull=["down"]),
        box([0, 3, 3], [16, 4, 13], {"down": "wood", "up": "inside", "east": "wood", "west": "wood", "north": "wood", "south": "wood"}),
        box([0, 3, 2], [16, 8, 3.5], {"north": "wood", "south": "inside", "up": "wood", "east": "wood", "west": "wood", "down": "wood"}),
        box([0, 3, 12.5], [16, 8, 14], {"north": "inside", "south": "wood", "up": "wood", "east": "wood", "west": "wood", "down": "wood"}),
        box([0, 4, 3.5], [1.5, 7.5, 12.5], {"east": "inside", "west": "wood", "up": "wood"}),
        box([14.5, 4, 3.5], [16, 7.5, 12.5], {"west": "inside", "east": "wood", "up": "wood"}),
    ]
    if feed >= 1:
        h = {1: 5.0, 2: 6.2, 3: 7.2}[feed]
        els.append(box([1.5, 4, 3.5], [14.5, h, 12.5], {"up": "grain", "north": "grain", "south": "grain", "east": "grain", "west": "grain"}))
        if feed == 3:
            els.append(box([4, h, 5], [12, h + 0.8, 11], {"up": "grain", "north": "grain", "south": "grain", "east": "grain", "west": "grain"}))
    return els


def feed_trough():
    tex = {"particle": b("feed_trough_wood"), "wood": b("feed_trough_wood"), "inside": b("feed_trough_inside"), "grain": b("feed_trough_grain")}
    for feed in range(4):
        model("feed_trough_%d" % feed, tex, trough_elements(feed))
    variants = {}
    for axis, y in (("x", 0), ("z", 90)):
        for feed in range(4):
            v = {"model": b("feed_trough_%d" % feed)}
            if y:
                v["y"] = y
            variants["axis=%s,feed=%d" % (axis, feed)] = v
    state("feed_trough", {"variants": variants})
    item_model("feed_trough", {"parent": b("feed_trough_3")})


# --- the fish trap: a woven cage (each wall a sheet of wicker seen from both sides), a funnel mouth to the north,
#     a bound rim top and bottom; the catch inside it

def plane(frm, to, faces, tex, uv=None):
    out = {"from": frm, "to": to, "faces": {}}
    for f in faces:
        face = {"texture": "#" + tex}
        if uv:
            face["uv"] = uv
        out["faces"][f] = face
    return out


def oct_wall(cx, cz, length, angle, y0, y1, tex):
    """One side of the eight-sided cage: a sheet of wicker seen from both sides, along x before it is turned."""
    half = length / 2
    el = plane([cx - half, y0, cz], [cx + half, y1, cz], ["north", "south"], tex)
    if angle:
        el["rotation"] = {"origin": [cx, y0, cz], "axis": "y", "angle": angle}
    return el


def oct_ring(y0, y1, tex):
    """The eight sides of the cage between these heights: four square to the block, four on the slant between them."""
    d = 4.24
    return [
        oct_wall(8, 2, 6, 0, y0, y1, tex), oct_wall(8, 14, 6, 0, y0, y1, tex),
        dict(plane([2, y0, 5], [2, y1, 11], ["east", "west"], tex)), dict(plane([14, y0, 5], [14, y1, 11], ["east", "west"], tex)),
        oct_wall(12.5, 3.5, d, -45, y0, y1, tex), oct_wall(3.5, 3.5, d, 45, y0, y1, tex),
        oct_wall(12.5, 12.5, d, 45, y0, y1, tex), oct_wall(3.5, 12.5, d, -45, y0, y1, tex),
    ]


def trap_elements(catch):
    els = [box([3, 0, 3], [13, 0.5, 13], {"up": "rim", "down": "rim"})]
    els += oct_ring(0, 9, "wicker")
    # The bound rims, top and foot: a band of close weave round the eight sides.
    els += oct_ring(0, 1.5, "rim")
    els += oct_ring(8, 9.5, "rim")
    # The lid, woven open so the catch shows, and a rope loop to lift it out of the water by.
    els.append(box([3.5, 9, 3.5], [12.5, 9.5, 12.5], {"up": "wicker", "down": "wicker"}))
    els.append(box([6.5, 9.5, 7.75], [7, 12, 8.25], "rope"))
    els.append(box([9, 9.5, 7.75], [9.5, 12, 8.25], "rope"))
    els.append(box([6.5, 12, 7.75], [9.5, 12.5, 8.25], "rope"))
    # The funnel mouth, standing out of the north side: the fish swim in, and cannot find the way out.
    els.append(plane([5.5, 2.5, 0.5], [10.5, 2.5, 2], ["up", "down"], "rim"))
    els.append(plane([5.5, 7, 0.5], [10.5, 7, 2], ["up", "down"], "rim"))
    els.append(plane([5.5, 2.5, 0.5], [5.5, 7, 2], ["east", "west"], "rim"))
    els.append(plane([10.5, 2.5, 0.5], [10.5, 7, 2], ["east", "west"], "rim"))
    cod_side, sal_side = [0, 0, 8, 3], [0, 4, 8, 7]
    fishes = []
    if catch >= 1:
        fishes.append(([4, 0.5, 6], cod_side, 22.5))
    if catch >= 2:
        fishes.append(([5, 0.5, 9.5], sal_side, -22.5))
        fishes.append(([4.5, 2.5, 8], cod_side, 0))
    for (p, uv, ang) in fishes:
        x, y, z = p
        els.append({"from": [x, y, z], "to": [x + 8, y + 3, z], "rotation": {"origin": [x + 4, y, z], "axis": "y", "angle": ang},
                    "faces": {"north": {"texture": "#fish", "uv": [uv[2], uv[1], uv[0], uv[3]]}, "south": {"texture": "#fish", "uv": uv}}})
    return els


def fish_trap():
    tex = {"particle": b("fish_trap_rim"), "wicker": b("fish_trap_wicker"), "rim": b("fish_trap_rim"), "fish": b("fish_trap_fish"),
           "rope": b("fish_trap_rope")}
    for catch in range(3):
        model("fish_trap_%d" % catch, tex, trap_elements(catch), render="minecraft:cutout", ao=False)
    variants = {}
    rot = {"north": 0, "east": 90, "south": 180, "west": 270}
    for facing, y in rot.items():
        for catch in range(3):
            for wet in ("false", "true"):
                v = {"model": b("fish_trap_%d" % catch)}
                if y:
                    v["y"] = y
                variants["catch=%d,facing=%s,waterlogged=%s" % (catch, facing, wet)] = v
    state("fish_trap", {"variants": variants})
    item_model("fish_trap", {"parent": b("fish_trap_2")})


# --- the rain barrel: staves round an eight-sided ring, two copper hoops, its head knocked out; the water in it

def barrel_elements(water):
    walls = [
        ([3, 0, 1], [13, 15, 2.5], {"north": "side", "south": "inside", "up": "top"}),
        ([3, 0, 13.5], [13, 15, 15], {"south": "side", "north": "inside", "up": "top"}),
        ([1, 0, 3], [2.5, 15, 13], {"west": "side", "east": "inside", "up": "top"}),
        ([13.5, 0, 3], [15, 15, 13], {"east": "side", "west": "inside", "up": "top"}),
        # the corners, filling the ring out to eight sides
        ([2, 0, 2], [4, 15, 4], {"north": "side", "west": "side", "up": "top", "south": "inside", "east": "inside"}),
        ([12, 0, 2], [14, 15, 4], {"north": "side", "east": "side", "up": "top", "south": "inside", "west": "inside"}),
        ([2, 0, 12], [4, 15, 14], {"south": "side", "west": "side", "up": "top", "north": "inside", "east": "inside"}),
        ([12, 0, 12], [14, 15, 14], {"south": "side", "east": "side", "up": "top", "north": "inside", "west": "inside"}),
    ]
    els = []
    for frm, to, faces in walls:
        f = dict(faces)
        f["down"] = "top"
        els.append(box(frm, to, f, cull=["down"]))
    els.append(box([2.5, 0.5, 2.5], [13.5, 2, 13.5], {"up": "inside"}))
    # the hoops, a shade proud of the staves, low and high
    for (y0, y1) in ((2.5, 4.5), (10.5, 12.5)):
        hv = [0, 0, 16, 2]
        els.append(box([2.75, y0, 0.75], [13.25, y1, 15.25], {"north": "hoop", "south": "hoop"}, uv={"north": hv, "south": hv}))
        els.append(box([0.75, y0, 2.75], [15.25, y1, 13.25], {"east": "hoop", "west": "hoop"}, uv={"east": hv, "west": hv}))
        for (x, z) in ((1.75, 1.75), (12.25, 1.75), (1.75, 12.25), (12.25, 12.25)):
            els.append(box([x, y0, z], [x + 2, y1, z + 2], {"north": "hoop", "south": "hoop", "east": "hoop", "west": "hoop"},
                           uv={k: [5, 0, 7, 2] for k in ("north", "south", "east", "west")}))
    if water > 0:
        h = {1: 4.5, 2: 7.5, 3: 10.5, 4: 13.5}[water]
        els.append({"from": [2.5, 2, 2.5], "to": [13.5, h, 13.5],
                    "faces": {"up": {"texture": "#water", "uv": [2.5, 2.5, 13.5, 13.5]}}})
    return els


def rain_barrel():
    tex = {"particle": b("rain_barrel_side"), "side": b("rain_barrel_side"), "inside": b("rain_barrel_inside"),
           "top": b("rain_barrel_top"), "hoop": b("rain_barrel_hoop"), "water": b("rain_barrel_water")}
    for water in range(5):
        model("rain_barrel_%d" % water, tex, barrel_elements(water), render="minecraft:translucent")
    state("rain_barrel", {"variants": {"water=%d" % w: {"model": b("rain_barrel_%d" % w)} for w in range(5)}})
    item_model("rain_barrel", {"parent": b("rain_barrel_3")})


def tool_models():
    gen = "minecraft:item/generated"
    item_model("copper_watering_can_filled", {"parent": gen, "textures": {"layer0": NS + ":item/copper_watering_can_filled"}})
    item_model("copper_watering_can", {"parent": gen, "textures": {"layer0": NS + ":item/copper_watering_can"},
                                       "overrides": [{"predicate": {NS + ":water": 0.01}, "model": NS + ":item/copper_watering_can_filled"}]})
    item_model("seed_satchel_full", {"parent": gen, "textures": {"layer0": NS + ":item/seed_satchel_full"}})
    item_model("seed_satchel", {"parent": gen, "textures": {"layer0": NS + ":item/seed_satchel"},
                                "overrides": [{"predicate": {NS + ":seeds": 0.001}, "model": NS + ":item/seed_satchel_full"}]})
    item_model("copper_sickle", {"parent": "minecraft:item/handheld", "textures": {"layer0": NS + ":item/copper_sickle"}})
    item_model("bee_smoker", {"parent": gen, "textures": {"layer0": NS + ":item/bee_smoker"}})


def main():
    tools()
    blocks()
    nesting_box()
    feed_trough()
    fish_trap()
    rain_barrel()
    tool_models()
    print("the tools of the fields drawn")


if __name__ == "__main__":
    main()
