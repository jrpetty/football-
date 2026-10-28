#!/usr/bin/env python3
"""[individual] Every folk its own face: the layers a folk's picture is built from, and how they go together.

folk_art.py painted ten whole faces, and a town of forty was four of each. This paints the PARTS of a face
instead -- a skin and a face shape, a nose, eyes, brows, a dozen ways of wearing hair in three textures, the
beards, the lines of age, freckles, a scar, soot and sunburn, spectacles and an eyepatch, plain clothes -- and
the game puts them together for each folk (client/FolkFaces), once per look, out of the folk's own colours:

  * coded layers  (look/<name>.png): every pixel says WHICH colour it is (green: skin, hair, brow, beard, iris,
                  eye white, lip, blush, freckle, scar, soot, sun, shirt, trousers, shoes, mole), HOW light or dark
                  a shade of it (red: 128 the colour itself, less a shadow, more a highlight), and for hair, WHEN it
                  greys (blue: the temples first, the crown last). The game fills them in from the folk's skin tone,
                  hair and eye colours, and its years.
  * plain layers  (look/<name>.png, listed in PLAIN): drawn as they are -- spectacles, an eyepatch, a walking
                  stick, a pipe.

    python3 tools/folk_looks.py                     # write the layers and the Java tables
    python3 tools/folk_looks.py --check             # only say what would change
    python3 tools/folk_looks.py --sheet out.png     # a contact sheet of forty folk, drawn as the game draws them
                                                    # (needs numpy and Pillow; the rest needs nothing)

The colours, the shading ramp and the layer lists live here and nowhere else: `java` writes them into
client/FolkFaces.java, so the picture the game makes and the one this sheet draws are the same picture.
"""
import math
import os
import random
import re
import struct
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import folk_art as fa  # noqa: E402  (the boxes, their faces and the noise are folk_art's)

ROOT = fa.ROOT
LOOK_DIR = os.path.join(fa.TEX_DIR, "look")
FACES_JAVA = os.path.join(ROOT, "src/main/java/com/jrpetty/mcassistant/client/FolkFaces.java")
SIZE = 128

# ---------------------------------------------------------------------------------------------- the colours
#
# Ten skin tones, very fair to very dark; the natural hair colours; the eyes. Each a colour the game shades with
# the ramp below, so a dark skin has its own dark shadows and its own warm highlights, not a fair skin's greyed.

TONES = [
    ("very fair", (244, 214, 196)),
    ("fair", (236, 196, 166)),
    ("light", (224, 178, 140)),
    ("light olive", (208, 162, 118)),
    ("olive", (190, 142, 98)),
    ("tan", (170, 120, 80)),
    ("light brown", (146, 100, 66)),
    ("brown", (122, 80, 52)),
    ("dark brown", (98, 64, 42)),
    ("very dark", (76, 50, 36)),
]
HAIRS = [
    ("black", (40, 34, 36)),
    ("dark brown", (66, 46, 34)),
    ("brown", (102, 70, 44)),
    ("chestnut", (128, 76, 42)),
    ("auburn", (146, 64, 38)),
    ("red", (176, 66, 36)),
    ("ginger", (206, 112, 52)),
    ("blonde", (222, 186, 112)),
    ("ash", (182, 168, 140)),
    ("flaxen", (236, 214, 160)),
]
EYES = [
    ("brown", (102, 62, 34)),
    ("dark", (62, 40, 28)),
    ("hazel", (128, 102, 52)),
    ("amber", (178, 122, 42)),
    ("green", (70, 128, 72)),
    ("blue", (72, 116, 186)),
    ("grey", (122, 134, 146)),
]
SHIRTS = [(196, 182, 150), (214, 200, 170), (130, 152, 172), (164, 96, 68), (112, 126, 86), (226, 216, 190),
          (150, 146, 140), (176, 150, 112)]
TROUSERS = [(110, 92, 70), (84, 78, 72), (70, 68, 88), (98, 84, 60), (62, 56, 50), (122, 106, 80)]
SHOES = [(62, 44, 32), (40, 34, 30), (92, 64, 40), (70, 60, 52)]

GREY_HAIR = (172, 170, 166)
WHITE_HAIR = (232, 230, 224)
EYE_WHITE = (240, 236, 228)
DARK = (34, 24, 22)
SOOT = (48, 44, 42)

# The palettes a coded pixel can name (its green channel).
SKIN, HAIR, BROW, BEARD, IRIS, WHITE, LIP, BLUSH, DARKP, FRECKLE, SCAR, SOOTP, SUN, SHIRT, TROUSER, SHOE, MOLE = range(17)
GREYING = (HAIR, BROW, BEARD)          # the palettes that grey with age


def mixc(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


# How each palette's colour is had, for a folk with these looks: (from, its colour if fixed, scaled by, mixed toward,
# that colour if fixed, how far). "from" and "toward": skin, hair, eye, shirt, trousers, shoes or fixed.
SOURCES = ["skin", "hair", "eye", "shirt", "trousers", "shoes", "fixed"]
DERIVED = {
    SKIN: ("skin", None, 1.0, "fixed", (0, 0, 0), 0.0),
    HAIR: ("hair", None, 1.0, "fixed", (0, 0, 0), 0.0),
    BROW: ("hair", None, 0.86, "fixed", (0, 0, 0), 0.0),
    BEARD: ("hair", None, 0.92, "fixed", (0, 0, 0), 0.0),
    IRIS: ("eye", None, 1.0, "fixed", (0, 0, 0), 0.0),
    WHITE: ("fixed", EYE_WHITE, 1.0, "skin", None, 0.20),
    LIP: ("skin", None, 0.86, "fixed", (176, 74, 74), 0.24),
    BLUSH: ("skin", None, 1.0, "fixed", (232, 104, 104), 0.30),
    DARKP: ("fixed", DARK, 1.0, "skin", None, 0.12),
    FRECKLE: ("skin", None, 0.86, "fixed", (168, 96, 54), 0.22),
    SCAR: ("skin", None, 1.0, "fixed", (236, 176, 168), 0.42),
    SOOTP: ("fixed", SOOT, 1.0, "fixed", (0, 0, 0), 0.0),
    SUN: ("skin", None, 1.0, "fixed", (226, 92, 70), 0.30),
    SHIRT: ("shirt", None, 1.0, "fixed", (0, 0, 0), 0.0),
    TROUSER: ("trousers", None, 1.0, "fixed", (0, 0, 0), 0.0),
    SHOE: ("shoes", None, 1.0, "fixed", (0, 0, 0), 0.0),
    MOLE: ("skin", None, 0.50, "fixed", (84, 50, 36), 0.40),
}


def palette_colours(g):
    """The colour of each palette for a folk with these looks: g a dict of tone, hair, eyes, shirt, trousers, shoes."""
    have = {"skin": TONES[g["tone"]][1], "hair": HAIRS[g["hair"]][1], "eye": EYES[g["eyes"]][1],
            "shirt": SHIRTS[g["shirt"]], "trousers": TROUSERS[g["trousers"]], "shoes": SHOES[g["shoes"]]}
    out = {}
    for pal, (src, fixed, scale, tgt, tfixed, t) in DERIVED.items():
        a = tuple(k * scale for k in (fixed if src == "fixed" else have[src]))
        b = tfixed if tgt == "fixed" else have[tgt]
        out[pal] = mixc(a, b, t)
    return out


# The ramp: 128 is the colour itself; down to nought its shadow, a warm darker tone (a little red and blue kept,
# the green let go: shadows on skin and hair go ruddy-brown, not grey); up to 255 its highlight.
SHADOW = (0.56, 0.45, 0.50)
LIGHT_GAIN = (0.46, 0.42, 0.34)


def ramp(base, v):
    """The shade v (0-255) of a colour: the same sums as FolkFaces.ramp, to the unit."""
    if v == 128:
        return tuple(int(math.floor(k + 0.5)) for k in base)
    if v < 128:
        t = (128 - v) / 128.0
        return tuple(int(math.floor(base[i] + (base[i] * SHADOW[i] - base[i]) * t + 0.5)) for i in range(3))
    t = (v - 128) / 127.0
    return tuple(int(math.floor(base[i] + (255 - base[i]) * LIGHT_GAIN[i] * t + 0.5)) for i in range(3))


# ---------------------------------------------------------------------------------------------- the canvas

class Layer:
    """A 128 x 128 picture of coded (or, for a plain layer, ordinary) pixels: (r, g, b, a), or None."""

    def __init__(self):
        self.px = [[None] * SIZE for _ in range(SIZE)]

    def set(self, x, y, c):
        if not (0 <= x < SIZE and 0 <= y < SIZE):
            return
        if c is None or c is False:
            self.px[y][x] = None
            return
        if len(c) == 3:
            c = (c[0], c[1], c[2], 255)
        self.px[y][x] = tuple(int(max(0, min(255, k))) for k in c)

    def get(self, x, y):
        return self.px[y][x]

    def empty(self):
        return all(p is None for row in self.px for p in row)

    def png(self):
        raw = b""
        for row in self.px:
            raw += b"\x00" + b"".join(bytes(c) if c is not None else b"\x00\x00\x00\x00" for c in row)

        def chunk(t, data):
            body = t + data
            return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)
        return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def C(pal, v=128, a=255, order=0):
    """A coded pixel: which palette, how light, how opaque, and (hair) when it greys."""
    return (int(max(0, min(255, v))), pal, int(max(0, min(255, order))), int(max(0, min(255, a))))


def n01(x, y, seed):
    """A fixed hash of a pixel, nought to one."""
    return (fa.noise(x, y, seed) + 1.0) / 2.0


def box(layer, name):
    return fa.Box(layer, name)


def side_back(face, x, w):
    """How far a pixel on a side of the head is from its back edge (0) toward the face (w - 1)."""
    return x if face == "right" else (w - 1 - x)


# The head's front face is 8 wide and 10 high: the brows on row 3, the eyes on row 4, the nose's box over the
# middle two columns from row 7, the corners of the mouth on row 8, the chin on row 9. Pixels by (x, y) there.

# ---------------------------------------------------------------------------------------------- skin and face

FACE_SHAPES = ["oval", "round", "square", "long", "heart"]


def paint_skin(shape):
    L = Layer()
    head = box(L, "head")
    sk = lambda x, y, v, seed=40: C(SKIN, v + fa.noise(x, y, seed) * 1.5)
    head.fill("top", lambda x, y, w, h: sk(x, y, 140))
    head.fill("bottom", lambda x, y, w, h: sk(x, y, 104))
    head.fill("back", lambda x, y, w, h: sk(x, y, 118))
    for face in ("right", "left"):
        head.fill(face, lambda x, y, w, h, face=face: sk(x, y, 120 - (2 if side_back(face, x, w) < 3 else 0)))
        ex = 4 if face == "right" else 3                     # the ear, a shade darker, its rim lighter
        head.put(face, ex, 5, C(SKIN, 98))
        head.put(face, ex, 6, C(SKIN, 108))
        head.put(face, ex + (-1 if face == "right" else 1), 5, C(SKIN, 126))
        head.put(face, ex, 4, C(SKIN, 112))

    def front(x, y, w, h):
        v = 128
        if x in (0, 7) and y >= 2:
            v = 121                                          # the sides of the face turn from the light
        if y == 9:
            v = 116 if x in (0, 1, 6, 7) else 124            # under the jaw
        if y in (0, 1) and 2 <= x <= 5:
            v = 134                                          # the forehead catches the light
        return sk(x, y, v)
    head.fill("front", front)
    # The mouth: its corners either side of the nose, a lip's shade behind it.
    for x in (2, 5):
        head.put("front", x, 8, C(LIP, 122))
    for x in (3, 4):
        head.put("front", x, 8, C(LIP, 104))
        head.put("front", x, 9, C(SKIN, 120))
    shade = {
        "oval": [((1, 6), 133), ((6, 6), 133), ((0, 9), 112), ((7, 9), 112)],
        "round": [((1, 6), 136), ((6, 6), 136), ((1, 7), 133), ((6, 7), 133), ((0, 8), 126), ((7, 8), 126),
                  ((0, 9), 124), ((7, 9), 124), ((2, 9), 130), ((5, 9), 130)],
        "square": [((1, 5), 134), ((6, 5), 134), ((0, 8), 112), ((7, 8), 112), ((0, 9), 106), ((7, 9), 106),
                   ((1, 9), 114), ((6, 9), 114)],
        "long": [((1, 5), 136), ((6, 5), 136), ((1, 7), 115), ((6, 7), 115), ((0, 6), 115), ((7, 6), 115),
                 ((0, 7), 115), ((7, 7), 115), ((2, 9), 117), ((5, 9), 117)],
        "heart": [((1, 5), 134), ((6, 5), 134), ((1, 6), 133), ((6, 6), 133), ((0, 8), 112), ((7, 8), 112),
                  ((0, 9), 104), ((7, 9), 104), ((1, 9), 110), ((6, 9), 110)],
    }[shape]
    for (x, y), v in shade:
        head.put("front", x, y, C(SKIN, v))
    # Bare arms and hands: the shirt's short sleeves cover the tops (clothes), a trade's sleeves the rest.
    for name in ("right_arm", "left_arm"):
        arm = box(L, name)
        arm.all(lambda face, x, y, w, h: sk(x, y, 124 if face in ("front", "back") else 118, 41),
                which=("right", "front", "left", "back"))
        arm.fill("top", lambda x, y, w, h: sk(x, y, 130, 41))
        arm.fill("bottom", lambda x, y, w, h: C(SKIN, 108))
        arm.row(9, lambda x: C(SKIN, 132))                  # the back of the hand
        arm.fill("front", lambda x, y, w, h: C(SKIN, 112) if y == h - 1 and x in (1, 2) else None)   # fingers
    return L


NOSES = ["straight", "broad", "button", "hooked"]


def paint_nose(kind):
    L = Layer()
    nose = box(L, "nose")
    base = {"straight": 124, "broad": 128, "button": 126, "hooked": 122}[kind]
    nose.all(lambda face, x, y, w, h: C(SKIN, base - (8 if face in ("right", "left") else 0) + fa.noise(x, y, 43) * 3))
    nose.fill("top", lambda x, y, w, h: C(SKIN, base + 10))
    if kind == "straight":
        nose.fill("front", lambda x, y, w, h: C(SKIN, 140 if y == 0 else (130 if x == 0 else 122)))
        nose.fill("bottom", lambda x, y, w, h: C(SKIN, 72))
    elif kind == "broad":
        nose.fill("front", lambda x, y, w, h: C(SKIN, 138 if y >= 2 else 126))
        nose.fill("bottom", lambda x, y, w, h: C(SKIN, 64))
        for face in ("right", "left"):
            nose.fill(face, lambda x, y, w, h: C(SKIN, 108 if y >= 2 else 118))
    elif kind == "button":
        nose.fill("front", lambda x, y, w, h: C(SKIN, 144 if y == 3 else (120 if y == 0 else 128)))
        nose.fill("bottom", lambda x, y, w, h: C(SKIN, 80))
    else:
        nose.fill("front", lambda x, y, w, h: C(SKIN, 146 if y == 1 else (110 if y == 3 else 126)))
        nose.fill("bottom", lambda x, y, w, h: C(SKIN, 66))
    return L


EYE_SHAPES = ["round", "wide", "narrow", "hooded", "deep", "almond"]


def paint_eyes(shape):
    L = Layer()
    head = box(L, "head")
    p = lambda x, y, c: head.put("front", x, y, c)
    if shape == "round":
        for x, c in ((1, C(WHITE, 124)), (2, C(IRIS, 128)), (5, C(IRIS, 128)), (6, C(WHITE, 124))):
            p(x, 4, c)
        for x in (2, 5):
            p(x, 5, C(SKIN, 120))
    elif shape == "wide":                                   # big eyes: the iris two high, a catch of light
        for x, c in ((1, C(WHITE, 136)), (2, C(IRIS, 104)), (5, C(IRIS, 104)), (6, C(WHITE, 136))):
            p(x, 4, c)
        for x, c in ((1, C(WHITE, 112)), (2, C(IRIS, 150)), (5, C(IRIS, 150)), (6, C(WHITE, 112))):
            p(x, 5, c)
        p(1, 6, C(SKIN, 120))
        p(6, 6, C(SKIN, 120))
    elif shape == "narrow":                                 # a squint: a lash line, a sliver of white
        for x, c in ((1, C(WHITE, 84)), (2, C(IRIS, 100)), (5, C(IRIS, 100)), (6, C(WHITE, 84))):
            p(x, 4, c)
        for x in (1, 2, 5, 6):
            p(x, 3, C(DARKP, 128, 120))
        for x in (1, 2, 5, 6):
            p(x, 5, C(SKIN, 112))
    elif shape == "hooded":                                 # a heavy lid over the eye, set a row lower
        for x in (1, 2, 5, 6):
            p(x, 4, C(SKIN, 104 if x in (2, 5) else 110))
        for x, c in ((1, C(WHITE, 124)), (2, C(IRIS, 118)), (5, C(IRIS, 118)), (6, C(WHITE, 124))):
            p(x, 5, c)
    elif shape == "deep":                                   # deep-set: the sockets in shadow
        for x, c in ((1, C(WHITE, 112)), (2, C(IRIS, 112)), (5, C(IRIS, 112)), (6, C(WHITE, 112))):
            p(x, 4, c)
        for x in (1, 2, 5, 6):
            p(x, 5, C(SKIN, 100))
        p(3, 4, C(SKIN, 112))
        p(4, 4, C(SKIN, 112))
        p(0, 4, C(SKIN, 110))
        p(7, 4, C(SKIN, 110))
    else:                                                   # almond: a lash winged out at the corner
        for x, c in ((1, C(WHITE, 132)), (2, C(IRIS, 120)), (5, C(IRIS, 120)), (6, C(WHITE, 132))):
            p(x, 4, c)
        p(0, 4, C(DARKP, 128, 150))
        p(7, 4, C(DARKP, 128, 150))
        p(2, 5, C(SKIN, 114))
        p(5, 5, C(SKIN, 114))
    return L


BROWS = ["straight", "thick", "arched", "stern", "soft", "bushy", "joined"]


def paint_brows(kind):
    L = Layer()
    head = box(L, "head")
    order = lambda x, y: 60 + int(n01(x, y, 44) * 120)
    b = lambda x, y, v=128, a=255: head.put("front", x, y, C(BROW, v, a, order(x, y)))
    if kind == "straight":
        for x in (1, 2, 5, 6):
            b(x, 3)
    elif kind == "thick":
        for x in (0, 1, 2, 5, 6, 7):
            b(x, 3, 112)
    elif kind == "arched":
        for (x, y) in ((0, 3), (1, 2), (2, 3), (5, 3), (6, 2), (7, 3)):
            b(x, y, 126)
    elif kind == "stern":
        for (x, y) in ((1, 2), (2, 3), (5, 3), (6, 2)):
            b(x, y, 116)
        b(0, 2, 128, 160)
        b(7, 2, 128, 160)
    elif kind == "soft":
        for (x, y) in ((1, 3), (2, 2), (5, 2), (6, 3)):
            b(x, y, 146, 200)
    elif kind == "bushy":
        for x in (0, 1, 2, 5, 6, 7):
            b(x, 3, 120)
        b(1, 2, 140, 220)
        b(6, 2, 140, 220)
    else:
        for x in range(1, 7):
            b(x, 3, 112 if x in (3, 4) else 120)
    return L


# ---------------------------------------------------------------------------------------------- hair

STYLES = ["crop", "short", "long", "braid", "bun", "curls", "shaved", "ponytail", "tied", "balding", "wild", "bob"]
TEXTURES = ["straight", "wavy", "curly"]


def strand(texture, x, y, seed):
    """How light a hair pixel is, for its texture: straight hair falls in strands, wavy in diagonals, curls in
    little highlights and shadows."""
    n = fa.noise(x, y, seed)
    if texture == "straight":
        v = 128 + (12 if x % 3 == 0 else -6 if x % 3 == 2 else 0) + n * 6
    elif texture == "wavy":
        k = (x + y) % 4
        v = 128 + (16 if k == 0 else -10 if k == 2 else 0) + n * 6
    else:
        v = 128 + (20 if (x + y) % 2 == 0 and (x * 3 + y) % 5 < 3 else -14 if (x + y) % 2 else 0) + n * 8
    return v


def hair_order(face, x, y, w, h):
    """When a hair pixel greys: at the temples first, over the ears, the back and last of all the crown."""
    n = n01(x, y, 47 + len(face))
    if face == "front":
        temple = 1.0 - min(abs(x - 0), abs(x - 7)) / 3.5
        return int(30 + (1.0 - temple) * 120 + n * 90)
    if face in ("right", "left"):
        bd = side_back(face, x, w)
        return int(20 + (7 - bd) * 6 + (1 - bd / 7.0) * 80 + n * 100)
    if face == "top":
        return int(150 + n * 105)
    return int(70 + n * 140)


def hair_px(texture, face, x, y, w, h, shade=0, a=255, seed=50):
    return C(HAIR, strand(texture, x, y, seed + len(face)) + shade, a, hair_order(face, x, y, w, h))


def paint_hair(style, texture):
    L = Layer()
    head = box(L, "head")
    hl = box(L, "hair")
    H = lambda face, x, y, w, h, shade=0, a=255: hair_px(texture, face, x, y, w, h, shade, a)

    def put_mask(b, face, test, shade=0, a=255, ragged=0.0, seed=0):
        def fn(x, y, w, h):
            r = test(x, y, w, h)
            if not r:
                return None
            if ragged and fa.noise(x, y, 60 + seed) > 1 - ragged * 2:
                return None
            return H(face, x, y, w, h, shade + (r if isinstance(r, int) and not isinstance(r, bool) else 0), a)
        b.fill(face, fn)

    def sides(test, b=head, **kw):
        for face in ("right", "left"):
            put_mask(b, face, lambda x, y, w, h, face=face: test(side_back(face, x, w), y), **kw)

    top_all = lambda x, y, w, h: True
    if style == "crop":
        put_mask(head, "top", top_all, shade=-4)
        put_mask(head, "front", lambda x, y, w, h: y <= 1 or (y == 2 and x in (0, 7)))
        sides(lambda bd, y: y < 3 or (bd < 4 and y < 6) or (bd < 2 and y < 7))
        put_mask(head, "back", lambda x, y, w, h: y < 7)
        put_mask(hl, "top", top_all, ragged=0.25, seed=1)
        put_mask(hl, "front", lambda x, y, w, h: y == 0 and x not in (2,))
        put_mask(hl, "back", lambda x, y, w, h: y < 3)
    elif style == "short":
        put_mask(head, "top", top_all)
        put_mask(head, "front", lambda x, y, w, h: y <= 1 or (y == 2 and x in (0, 4, 5, 6, 7)))
        sides(lambda bd, y: y < 4 or (bd < 5 and y < 7))
        put_mask(head, "back", lambda x, y, w, h: y < 8)
        put_mask(hl, "top", top_all, ragged=0.15, seed=2)
        put_mask(hl, "front", lambda x, y, w, h: (y == 0 and x != 2) or (y == 1 and x >= 4), shade=8)
        put_mask(hl, "back", lambda x, y, w, h: y < 6)
        sides(lambda bd, y: y < 2, b=hl)
    elif style in ("long", "bob"):
        long_ = style == "long"
        put_mask(head, "top", top_all)
        if long_:
            put_mask(head, "front", lambda x, y, w, h: y <= 1 or (x in (0, 7) and y <= 8) or (x in (1, 6) and y == 2))
        else:
            put_mask(head, "front", lambda x, y, w, h: y <= 2 or (x in (0, 7) and y <= 8))
        sides(lambda bd, y: (bd < 6 or y < 3) and y < (10 if long_ else 9))
        put_mask(head, "back", lambda x, y, w, h: y < (10 if long_ else 9))
        put_mask(hl, "top", top_all)
        put_mask(hl, "back", lambda x, y, w, h: y < (10 if long_ else 9), shade=-6)
        sides(lambda bd, y: bd < 6 and y < (10 if long_ else 9), b=hl, shade=-4)
        if long_:
            put_mask(hl, "front", lambda x, y, w, h: y == 0 or (x in (0, 7) and y <= 7), shade=6)
            fall = box(L, "hair_fall")
            fall.all(lambda face, x, y, w, h: (None if (face in ("front", "back") and y == h - 1 and x in (0, w - 1))
                                               else H(face, x, y, w, h, -8)))
        else:
            put_mask(hl, "front", lambda x, y, w, h: y <= 1 or (x in (0, 7) and y <= 8), shade=6)
            # The bob's straight-cut fringe: a darker line where it ends.
            put_mask(head, "front", lambda x, y, w, h: y == 2, shade=-14)
    elif style in ("braid", "bun", "ponytail", "tied"):
        # Pulled back from the face: a parting down the middle, smooth over the crown, gathered at the back.
        put_mask(head, "top", lambda x, y, w, h: 9 if x in (3, 4) and y >= 4 else True, shade=0)
        loose = style == "tied"
        put_mask(head, "front", lambda x, y, w, h: y <= 1 or (loose and y == 2 and x in (0, 7)))
        put_mask(head, "front", lambda x, y, w, h: y == 0 and x in (3, 4), shade=18)   # the parting's sheen
        sides(lambda bd, y: y < 3 or (bd < 5 and y < 5) or (bd < 3 and y < 8))
        put_mask(head, "back", lambda x, y, w, h: y < 9)
        put_mask(hl, "top", lambda x, y, w, h: True, shade=6)
        put_mask(hl, "back", lambda x, y, w, h: y < 7, shade=-4)
        sides(lambda bd, y: y < 2 or (bd < 3 and y < 5), b=hl)
        # The parting, a line of scalp, a shade of skin.
        head.put("top", 3, 6, C(SKIN, 130))
        head.put("top", 4, 7, C(SKIN, 130))
        if style == "bun":
            bun = box(L, "hair_bun")
            bun.all(lambda face, x, y, w, h: H(face, x, y, w, h, 10 if face == "top" else (-12 if y == h - 1 else 0)))
            bun.put("back", 1, 1, H("back", 1, 1, 4, 3, 28))
        elif style == "ponytail":
            tail = box(L, "hair_tail")
            tail.all(lambda face, x, y, w, h: (C(SHIRT, 96) if face != "top" and face != "bottom" and y == 0
                                               else (None if face in ("front", "back", "right", "left") and y == h - 1
                                                     and x == (y % 2) else H(face, x, y, w, h, -4 + (y % 3) * 4))))
        elif style == "braid":
            br = box(L, "hair_braid")

            def plait(face, x, y, w, h):
                if face in ("top",):
                    return H(face, x, y, w, h)
                if y == h - 2:
                    return C(SHIRT, 92)                     # a ribbon round the end
                if y == h - 1:
                    return H(face, x, y, w, h, 10) if x == 0 else None
                return H(face, x, y, w, h, 16 if (x + y) % 2 == 0 else -18)
            br.all(plait)
        else:
            knot = box(L, "hair_knot")
            knot.all(lambda face, x, y, w, h: C(SHIRT, 90) if y == 0 and face not in ("top", "bottom")
                     else H(face, x, y, w, h, -6 + (y % 2) * 10))
    elif style == "curls":
        put_mask(head, "top", top_all)
        put_mask(head, "front", lambda x, y, w, h: y <= 1 or (y == 2 and x in (0, 1, 3, 5, 6, 7)) or (x in (0, 7) and y <= 4))
        sides(lambda bd, y: y < 6 or (bd < 3 and y < 8))
        put_mask(head, "back", lambda x, y, w, h: y < 9)
        put_mask(hl, "top", top_all)
        put_mask(hl, "front", lambda x, y, w, h: y <= 1 and (x + y) % 3 != 2)
        put_mask(hl, "back", lambda x, y, w, h: y < 8)
        sides(lambda bd, y: y < 5, b=hl)
        # The volume of a head of curls: a rounded cap a little out from the head, its edge in curls.
        puff = box(L, "hair_puff")
        corner = lambda x, y, w, h: (x in (0, w - 1)) and (y in (0, h - 1))
        puff.fill("top", lambda x, y, w, h: None if corner(x, y, w, h) else H("top", x, y, w, h, 10))
        for face in ("right", "left", "back"):
            def curl(x, y, w, h, face=face):
                if face != "back" and side_back(face, x, w) >= 6:
                    return None
                if y == h - 1:
                    return H(face, x, y, w, h, -8) if x % 2 == 0 else None
                if y == h - 2 and face != "back" and side_back(face, x, w) == 5:
                    return None
                return H(face, x, y, w, h, -2)
            puff.fill(face, curl)
        puff.fill("front", lambda x, y, w, h: H("front", x, y, w, h, 6) if y == 0 and x % 2 == 1 else None)
    elif style == "shaved":
        stub = lambda face: (lambda x, y, w, h: H(face, x, y, w, h, -14, 170) if fa.noise(x, y, 62) > -0.6 else H(face, x, y, w, h, -20, 110))
        head.fill("top", stub("top"))
        head.fill("front", lambda x, y, w, h: stub("front")(x, y, w, h) if y == 0 else None)
        for face in ("right", "left"):
            head.fill(face, lambda x, y, w, h, face=face: stub(face)(x, y, w, h) if y < 4 or (side_back(face, x, w) < 4 and y < 7) else None)
        head.fill("back", lambda x, y, w, h: stub("back")(x, y, w, h) if y < 8 else None)
    elif style == "balding":
        put_mask(head, "top", lambda x, y, w, h: x in (0, 7) or y == 7, shade=-4)
        sides(lambda bd, y: 3 <= y <= 6 and bd < 5 or (y == 2 and bd < 2))
        put_mask(head, "back", lambda x, y, w, h: 2 <= y <= 7)
        put_mask(hl, "back", lambda x, y, w, h: 3 <= y <= 6, ragged=0.15, seed=3)
        sides(lambda bd, y: 3 <= y <= 6 and bd < 4, b=hl, ragged=0.15, seed=4)
        # The scalp catches the light.
        head.fill("top", lambda x, y, w, h: C(SKIN, 150) if (x, y) in ((3, 3), (4, 3), (3, 4)) else None)
    elif style == "wild":
        put_mask(head, "top", top_all)
        put_mask(head, "front", lambda x, y, w, h: y <= 1 or (y == 2 and fa.noise(x, y, 63) > -0.3) or (x in (0, 7) and y <= 5))
        sides(lambda bd, y: y < 6 + (1 if fa.noise(bd, y, 64) > 0 else 0))
        put_mask(head, "back", lambda x, y, w, h: y < 10)
        put_mask(hl, "top", top_all, ragged=0.2, seed=5)
        put_mask(hl, "front", lambda x, y, w, h: y <= 1 or (x in (0, 7) and y <= 4), ragged=0.25, seed=6)
        put_mask(hl, "back", lambda x, y, w, h: y < 10, ragged=0.2, seed=7)
        sides(lambda bd, y: y < 7, b=hl, ragged=0.25, seed=8)
        # A mop that will not lie down: a fuller cap than the curls', its edges in tufts.
        puff = box(L, "hair_puff")
        puff.fill("top", lambda x, y, w, h: H("top", x, y, w, h, 8) if fa.noise(x, y, 71) > -0.75 else None)
        for face in ("right", "left", "back"):
            def tuft(x, y, w, h, face=face):
                if face != "back" and side_back(face, x, w) >= 6:
                    return None
                if y >= 2 and fa.noise(x, y, 72 + len(face)) < (y - 1) * 0.35 - 0.2:
                    return None
                return H(face, x, y, w, h, -2)
            puff.fill(face, tuft)
        puff.fill("front", lambda x, y, w, h: H("front", x, y, w, h, 4) if y == 0 and fa.noise(x, y, 75) > -0.4 else None)
    return L


# ---------------------------------------------------------------------------------------------- beards

FACIAL = ["none", "stubble", "moustache", "beard", "long beard", "sideburns", "goatee"]


def beard_px(face, x, y, shade=0, a=255, seed=80):
    order = int(10 + (1 - y / 10.0) * 90 + n01(x, y, seed + len(face)) * 140) if face != "bottom" else int(20 + n01(x, y, seed) * 120)
    return C(BEARD, 128 + shade + fa.noise(x, y, seed + len(face)) * 8 + (8 if (x + y) % 3 == 0 else 0), a, order)


def paint_facial(kind):
    L = Layer()
    head = box(L, "head")
    if kind == "none":
        return L
    if kind == "stubble":
        def st(face):
            def fn(x, y, w, h):
                if fa.noise(x, y, 81 + len(face)) < -0.35:
                    return None
                if face == "front":
                    on = y >= 7 or (y == 6 and x in (0, 7))
                    if (x, y) in ((2, 8), (5, 8)):
                        return None
                elif face == "bottom":
                    on = y >= 2
                else:
                    on = y >= 6 and side_back(face, x, w) >= 4
                return beard_px(face, x, y, -30, 120) if on else None
            return fn
        for face in ("front", "bottom", "right", "left"):
            head.fill(face, st(face))
        return L
    if kind in ("beard", "long beard"):
        def full(face):
            def fn(x, y, w, h):
                if face == "front":
                    on = y >= 7 or (y == 6 and x in (0, 1, 6, 7)) or (y == 5 and x in (0, 7))
                elif face == "bottom":
                    on = y >= 2
                else:
                    bd = side_back(face, x, w)
                    on = (y >= 6 and bd >= 3) or (y >= 3 and bd >= 5)
                return beard_px(face, x, y, -6 if face != "front" else 0) if on else None
            return fn
        for face in ("front", "bottom", "right", "left"):
            head.fill(face, full(face))
        head.put("front", 2, 8, C(LIP, 100))
        head.put("front", 5, 8, C(LIP, 100))
        bd = box(L, "beard")
        bd.all(lambda face, x, y, w, h: beard_px(face, x + 3, y + 1, 4 if face == "top" else 0))
        bd.fill("front", lambda x, y, w, h: False if (y == h - 1 and x in (0, w - 1) and kind == "beard") else
                (beard_px("front", x, y, -10) if y == 0 and x in (2, 3) else beard_px("front", x, y + 4, 6 if (x + y) % 2 == 0 else -4)))
        if kind == "long beard":
            lb = box(L, "beard_long")

            def longfn(face, x, y, w, h):
                taper = {3: (0, 5), 4: (1, 4), 5: (2, 3)}
                if face in ("front", "back") and y in taper and not (taper[y][0] <= x <= taper[y][1]):
                    return None
                if face in ("right", "left") and y >= 3:
                    return None
                return beard_px(face, x, y + 5, -2 - y * 3)
            lb.all(longfn)
        return L
    if kind == "moustache":
        bd = box(L, "beard")
        bd.fill("front", lambda x, y, w, h: beard_px("front", x, y + 4, 14) if y == 0 or (y == 1 and x in (0, 5)) else None)
        bd.fill("top", lambda x, y, w, h: beard_px("top", x, 4, 10))
        bd.fill("bottom", lambda x, y, w, h: None)
        bd.fill("right", lambda x, y, w, h: beard_px("right", x, y + 4) if y <= 1 else None)
        bd.fill("left", lambda x, y, w, h: beard_px("left", x, y + 4) if y <= 1 else None)
        for x in (1, 6):
            head.put("front", x, 7, beard_px("front", x, 7, -8))
        return L
    if kind == "goatee":
        bd = box(L, "beard")
        bd.fill("front", lambda x, y, w, h: beard_px("front", x, y + 4, 2) if (y == 0 and x in (0, 1, 4, 5)) or (y >= 2 and 1 <= x <= 4 and y < 4) or (y == 4 and x in (2, 3)) else None)
        bd.fill("right", lambda x, y, w, h: beard_px("right", x, y + 4) if 2 <= y < 4 else None)
        bd.fill("left", lambda x, y, w, h: beard_px("left", x, y + 4) if 2 <= y < 4 else None)
        bd.fill("bottom", lambda x, y, w, h: beard_px("bottom", x, 4) if 1 <= x <= 4 else None)
        head.put("front", 2, 9, beard_px("front", 2, 9))
        head.put("front", 5, 9, beard_px("front", 5, 9))
        return L
    # sideburns: down in front of the ear to the jaw.
    for face in ("right", "left"):
        head.fill(face, lambda x, y, w, h, face=face: beard_px(face, x, y, -4) if 3 <= y <= 8 and 5 <= side_back(face, x, w) <= 6 else None)
    for x in (0, 7):
        for y in range(3, 7):
            head.put("front", x, y, beard_px("front", x, y, -8))
    return L


# ---------------------------------------------------------------------------------------------- marks of a life

def paint_wrinkles(level):
    L = Layer()
    head = box(L, "head")
    p = lambda x, y, v, a=200: head.put("front", x, y, C(SKIN, v, a))
    if level >= 1:                                          # crow's feet, and the lines from nose to mouth
        p(0, 4, 112, 190)
        p(7, 4, 112, 190)
        p(2, 7, 116, 150)
        p(5, 7, 116, 150)
    if level >= 2:                                          # bags under the eyes, a line across the brow
        p(2, 5, 110, 190)
        p(5, 5, 110, 190)
        for x in (2, 3, 4, 5):
            p(x, 1, 116, 130)
    if level >= 3:                                          # the cheeks fallen, jowls
        p(1, 5, 112, 160)
        p(6, 5, 112, 160)
        p(1, 8, 112, 170)
        p(6, 8, 112, 170)
        p(0, 9, 104, 200)
        p(7, 9, 104, 200)
    return L


def paint_freckles():
    L = Layer()
    head = box(L, "head")
    for (x, y, a) in ((0, 5, 140), (1, 6, 120), (6, 5, 140), (7, 6, 120)):
        head.put("front", x, y, C(FRECKLE, 128, a))
    nose = box(L, "nose")
    nose.put("front", 0, 1, C(FRECKLE, 136, 150))
    nose.put("top", 1, 1, C(FRECKLE, 136, 130))
    for name in ("right_arm", "left_arm"):
        arm = box(L, name)
        arm.fill("front", lambda x, y, w, h: C(FRECKLE, 136, 150) if 4 <= y <= 8 and fa.noise(x, y, 90) > 0.45 else None)
        arm.fill("right", lambda x, y, w, h: C(FRECKLE, 136, 150) if 4 <= y <= 8 and fa.noise(x, y, 91) > 0.5 else None)
    return L


def paint_rosy():
    L = Layer()
    head = box(L, "head")
    for (x, y, a) in ((1, 6, 170), (6, 6, 170), (0, 6, 100), (7, 6, 100)):
        head.put("front", x, y, C(BLUSH, 128, a))
    nose = box(L, "nose")
    nose.fill("front", lambda x, y, w, h: C(BLUSH, 136, 110) if y == 3 else None)
    return L


# Where a mole may sit, on the face (x, y): the cheek, the chin, the temple, above the lip.
MOLE_SPOTS = [(1, 7), (6, 7), (2, 9), (5, 9), (0, 5), (7, 6), (6, 2), (1, 2)]

SCARS = ["none", "right cheek", "left cheek", "brow"]


def paint_scar(kind):
    L = Layer()
    head = box(L, "head")
    if kind == "right cheek":
        pts = [((0, 5), C(SCAR, 150)), ((1, 6), C(SCAR, 144)), ((2, 7), C(SCAR, 134)), ((1, 5), C(SKIN, 98, 180)), ((0, 4), C(SCAR, 126, 160))]
        head.fill("right", lambda x, y, w, h: C(SCAR, 140) if (x, y) in ((7, 4), (6, 4)) else None)
    elif kind == "left cheek":
        pts = [((7, 5), C(SCAR, 150)), ((6, 6), C(SCAR, 144)), ((5, 7), C(SCAR, 134)), ((6, 5), C(SKIN, 98, 180)), ((7, 4), C(SCAR, 126, 160))]
        head.fill("left", lambda x, y, w, h: C(SCAR, 140) if (x, y) in ((0, 4), (1, 4)) else None)
    else:                                                   # down through the left brow and onto the cheek
        pts = [((6, 1), C(SCAR, 140)), ((6, 2), C(SCAR, 150)), ((5, 3), C(SCAR, 150)), ((6, 5), C(SCAR, 140)), ((6, 6), C(SCAR, 130))]
    for (x, y), c in pts:
        head.put("front", x, y, c)
    return L


def paint_soot():
    L = Layer()
    head = box(L, "head")
    for (x, y, a) in ((1, 6, 170), (2, 6, 130), (1, 7, 120), (5, 1, 130), (6, 1, 110), (6, 7, 90)):
        head.put("front", x, y, C(SOOTP, 128, a))
    nose = box(L, "nose")
    nose.put("front", 1, 2, C(SOOTP, 128, 120))
    for name in ("right_arm", "left_arm"):
        arm = box(L, name)
        arm.all(lambda face, x, y, w, h: C(SOOTP, 128, 150) if y >= 9 and fa.noise(x, y, 92 + len(face)) > -0.1 else None,
                which=("right", "front", "left", "back"))
        arm.fill("bottom", lambda x, y, w, h: C(SOOTP, 128, 170))
    return L


def paint_sun():
    L = Layer()
    head = box(L, "head")
    for (x, y, a) in ((1, 5, 150), (6, 5, 150), (1, 6, 170), (6, 6, 170), (2, 6, 110), (5, 6, 110),
                      (2, 1, 90), (3, 1, 110), (4, 1, 110), (5, 1, 90), (3, 2, 90), (4, 2, 90)):
        head.put("front", x, y, C(SUN, 128, a))
    nose = box(L, "nose")
    nose.fill("front", lambda x, y, w, h: C(SUN, 140, 210) if y <= 2 else None)
    nose.fill("top", lambda x, y, w, h: C(SUN, 140, 200))
    head.fill("back", lambda x, y, w, h: C(SUN, 120, 140) if y == 9 else None)       # the back of its neck
    for name in ("right_arm", "left_arm"):
        arm = box(L, name)
        arm.all(lambda face, x, y, w, h: C(SUN, 128, 100) if 5 <= y <= 9 else None, which=("right", "front", "left", "back"))
    return L


# Plain layers: drawn as they are.
LEATHER, LEATHER_HI, STRAP = (44, 34, 30), (74, 58, 48), (32, 26, 24)
GOLD, GOLD_DARK, GOLD_HI = (168, 134, 62), (104, 80, 38), (214, 186, 112)
WOOD, WOOD_DARK, WOOD_HI = (116, 82, 48), (78, 54, 32), (150, 112, 70)


def paint_patch(side):
    L = Layer()
    head = box(L, "head")
    hl = box(L, "hair")
    xs = (0, 1, 2) if side == "right" else (5, 6, 7)
    for x in xs:
        for y in (3, 4, 5):
            c = LEATHER_HI if (x, y) == (xs[1], 4) else LEATHER
            head.put("front", x, y, c)
    # The strap: from the patch's top corner up across the forehead to the far temple, round the far side and the
    # back of the head, and back round its own side at the patch's level. On the head, and over the hair.
    mirror = (lambda x: x) if side == "right" else (lambda x: 7 - x)
    front = [(mirror(x), y) for (x, y) in ((3, 2), (4, 2), (5, 1), (6, 1), (7, 0))]
    away = "left" if side == "right" else "right"
    for b in (head, hl):
        for (x, y) in front:
            b.put("front", x, y, STRAP)
        w, h = b.size(away)
        for x in range(w):                                  # the far side, high: from the temple to the back
            bd = side_back(away, x, w)
            b.put(away, x, 0 if bd >= 4 else 1, STRAP)
        w, h = b.size("back")
        for x in range(w):                                  # across the back, sloping down to the patch's side
            k = x if side == "left" else w - 1 - x
            b.put("back", x, 1 + (k * 2) // w, STRAP)
        w, h = b.size(side)
        for x in range(w):                                  # its own side, at the patch's level
            bd = side_back(side, x, w)
            b.put(side, x, 3 if bd >= 4 else 2, STRAP)
    return L


def paint_spectacles():
    L = Layer()
    sp = box(L, "spectacles")
    frame = {(1, 0), (2, 0), (5, 0), (6, 0), (0, 1), (3, 1), (4, 1), (7, 1), (1, 2), (2, 2), (5, 2), (6, 2)}
    sp.fill("front", lambda x, y, w, h: (GOLD_HI if (x, y) in ((1, 0), (5, 0)) else GOLD) if (x, y) in frame else False)
    sp.fill("back", lambda x, y, w, h: GOLD_DARK if (7 - x, y) in frame else False)
    sp.fill("top", lambda x, y, w, h: GOLD_DARK if x in (1, 2, 5, 6) else False)
    sp.fill("bottom", lambda x, y, w, h: GOLD_DARK if x in (1, 2, 5, 6) else False)
    sp.fill("right", lambda x, y, w, h: GOLD if y == 1 else False)
    sp.fill("left", lambda x, y, w, h: GOLD if y == 1 else False)
    # The arms back over the ears, on the head and on the hair over it.
    for b in (box(L, "head"), box(L, "hair")):
        for face in ("right", "left"):
            w, h = b.size(face)
            for x in range(w):
                if side_back(face, x, w) >= 3:
                    b.put(face, x, 4, GOLD_DARK if side_back(face, x, w) == 3 else GOLD)
    return L


def paint_cane():
    L = Layer()
    for name in ("cane_right", "cane_left"):
        cb = fa.Box(L, name)                 # the first box of the part: the shaft
        cb.all(lambda face, x, y, w, h: (WOOD_DARK if y >= h - 1 else (WOOD_HI if face == "front" and y % 5 == 1 else
                                         (WOOD if (y // 3) % 2 == 0 else fa.lit(WOOD, 0.9)))))
    # The handle: the part's second box, laid out after the shaft.
    for u in (44, 56):
        for (fx, fy, fw, fh) in fa.faces(u, 76, 1, 1, 3).values():
            for yy in range(fy, fy + fh):
                for xx in range(fx, fx + fw):
                    L.set(xx, yy, WOOD_DARK if (xx + yy) % 3 == 0 else WOOD)
    return L


def paint_pipe():
    L = Layer()
    stem = fa.faces(20, 76, 1, 1, 4)
    for (fx, fy, fw, fh) in stem.values():
        for yy in range(fy, fy + fh):
            for xx in range(fx, fx + fw):
                L.set(xx, yy, (58, 42, 30))
    bowl = fa.faces(30, 76, 2, 2, 2)
    for face, (fx, fy, fw, fh) in bowl.items():
        for yy in range(fy, fy + fh):
            for xx in range(fx, fx + fw):
                c = (150, 96, 54) if face != "top" else ((210, 96, 40) if (xx + yy) % 2 == 0 else (60, 40, 30))
                if face in ("right", "left", "front", "back") and yy == fy:
                    c = (176, 120, 70)
                L.set(xx, yy, c)
    return L


def paint_clothes():
    """The plain clothes under everything: a shirt with short sleeves and a collar, trousers, shoes. Coded, so a
    child in a russet shirt and one in faded blue are not in the same shirt."""
    L = Layer()
    sh = lambda x, y, v=128, seed=95: C(SHIRT, v + fa.noise(x, y, seed) * 6)
    body = box(L, "body")
    body.all(lambda face, x, y, w, h: sh(x, y, 128 if face != "back" else 120))
    body.fill("front", lambda x, y, w, h: C(DARKP, 128, 200) if x == 3 and y in (2, 5, 8) else (sh(x, y, 112) if x == 4 else None))
    body.row(0, lambda x: sh(x, 0, 146))
    body.row(11, lambda x: sh(x, 11, 108))
    coat = box(L, "coat")
    coat.around(lambda s, y, sw, h, face, x: (sh(s, y, 112 if y == 11 else 126) if y < 12 else None))
    coat.fill("front", lambda x, y, w, h: (C(SKIN, 112) if y == 0 and x in (3, 4) else (sh(x, y, 150) if y == 0 else None)))
    coat.fill("top", lambda x, y, w, h: sh(x, y, 132))
    for name in ("right_arm", "left_arm"):
        arm = box(L, name)
        arm.all(lambda face, x, y, w, h: (sh(x, y, 112 if y == 3 else 126) if y <= 3 else None), which=("right", "front", "left", "back"))
        arm.fill("top", lambda x, y, w, h: sh(x, y, 132))
    for name in ("right_leg", "left_leg"):
        leg = box(L, name)
        leg.all(lambda face, x, y, w, h: (C(TROUSER, 124 + fa.noise(x, y, 96) * 5) if y < 10 else C(SHOE, 128 + fa.noise(x, y, 97) * 4)),
                which=("right", "front", "left", "back"))
        leg.fill("top", lambda x, y, w, h: C(TROUSER, 128))
        leg.fill("bottom", lambda x, y, w, h: C(SHOE, 80))
        leg.row(9, C(TROUSER, 100))
        leg.fill("front", lambda x, y, w, h: C(SHOE, 156) if y == 10 and x in (1, 2) else None)
    return L


# ---------------------------------------------------------------------------------------------- the layers

def layers():
    """Every layer the game composes from, by name (look/<name>.png)."""
    out = {"clothes": paint_clothes()}
    for i, s in enumerate(FACE_SHAPES):
        out["face_%d" % i] = paint_skin(s)
    for i, s in enumerate(NOSES):
        out["nose_%d" % i] = paint_nose(s)
    for i, s in enumerate(EYE_SHAPES):
        out["eyes_%d" % i] = paint_eyes(s)
    for i, s in enumerate(BROWS):
        out["brows_%d" % i] = paint_brows(s)
    for i, s in enumerate(STYLES):
        for j, t in enumerate(TEXTURES):
            out["hair_%d_%d" % (i, j)] = paint_hair(s, t)
    for i, s in enumerate(FACIAL):
        if i:
            out["facial_%d" % i] = paint_facial(s)
    for i in (1, 2, 3):
        out["lines_%d" % i] = paint_wrinkles(i)
    for i, s in enumerate(SCARS):
        if i:
            out["scar_%d" % i] = paint_scar(s)
    out["freckles"] = paint_freckles()
    out["rosy"] = paint_rosy()
    out["soot"] = paint_soot()
    out["sun"] = paint_sun()
    out["patch_1"] = paint_patch("right")
    out["patch_2"] = paint_patch("left")
    out["spectacles"] = paint_spectacles()
    out["cane"] = paint_cane()
    out["pipe"] = paint_pipe()
    return out


PLAIN = ("patch_1", "patch_2", "spectacles", "cane", "pipe")


def stack(g):
    """The layers of one folk's picture, bottom to top: g its looks (see random_looks)."""
    names = ["clothes", "face_%d" % g["face"], "nose_%d" % g["nose"]]
    if g["lines"]:
        names.append("lines_%d" % g["lines"])
    if g["freckles"]:
        names.append("freckles")
    if g["rosy"]:
        names.append("rosy")
    if g["sun"]:
        names.append("sun")
    if g["scar"]:
        names.append("scar_%d" % g["scar"])
    names.append("eyes_%d" % g["eye_shape"])
    names.append("brows_%d" % g["brows"])
    if g["facial"] in (1,):
        names.append("facial_1")                    # stubble under the hair
    names.append("hair_%d_%d" % (g["style"], g["texture"]))
    if g["facial"] > 1:
        names.append("facial_%d" % g["facial"])
    if g["soot"]:
        names.append("soot")
    if g["patch"]:
        names.append("patch_%d" % g["patch"])
    if g["specs"]:
        names.append("spectacles")
    names.append("cane")
    names.append("pipe")
    return names


GREY_AT = [0, 55, 105, 160, 215, 256]           # a pixel whose greying order is below this has gone grey
GREY_KEEP, GREY_FADE = 0.22, 0.13               # how much colour a grey hair keeps; how fast the rest fade, a level


def mixr(a, b, t):
    """mixc to the unit, as FolkFaces.mix has it."""
    return tuple(int(math.floor(a[i] + (b[i] - a[i]) * t + 0.5)) for i in range(3))


def compose(g, all_layers):
    """One folk's picture: the same sums as FolkFaces.compose. Returns rows of (r, g, b, a)."""
    pal = palette_colours(g)
    out = [[None] * SIZE for _ in range(SIZE)]
    for name in stack(g):
        L = all_layers[name]
        plain = name in PLAIN
        for y in range(SIZE):
            row = L.px[y]
            for x in range(SIZE):
                p = row[x]
                if p is None or p[3] == 0:
                    continue
                if plain:
                    col = p[:3]
                else:
                    v, which, order, _ = p
                    base = pal.get(which, (255, 0, 255))
                    level = g["grey"] - (1 if which == BROW else 0)
                    if which in GREYING and level > 0:
                        # Salt and pepper, not a chequerboard: a hair gone grey keeps a touch of its colour, and the
                        # rest fade towards grey as the years go on.
                        if level >= 5:
                            base = WHITE_HAIR
                        elif order < GREY_AT[level]:
                            base = mixr(GREY_HAIR, base, GREY_KEEP)
                        else:
                            base = mixr(base, GREY_HAIR, GREY_FADE * level)
                    col = ramp(base, v)
                a = p[3]
                under = out[y][x]
                if a >= 255 or under is None:
                    out[y][x] = (col[0], col[1], col[2], 255)
                else:
                    t = a / 255.0
                    out[y][x] = tuple(int(math.floor(under[i] + (col[i] - under[i]) * t + 0.5)) for i in range(3)) + (255,)
    return out


# ---------------------------------------------------------------------------------------------- the Java

def java_tables():
    hexes = lambda cs: ", ".join("0x%02X%02X%02X" % tuple(int(k) for k in c) for c in cs)
    names = lambda ns: ", ".join('"%s"' % n for n in ns)
    lines = [
        "    static final int[] TONES = {%s};" % hexes([c for _, c in TONES]),
        "    static final int[] HAIRS = {%s};" % hexes([c for _, c in HAIRS]),
        "    static final int[] EYES = {%s};" % hexes([c for _, c in EYES]),
        "    static final int[] SHIRTS = {%s};" % hexes(SHIRTS),
        "    static final int[] TROUSERS = {%s};" % hexes(TROUSERS),
        "    static final int[] SHOES = {%s};" % hexes(SHOES),
        "    static final int GREY_HAIR = 0x%02X%02X%02X, WHITE_HAIR = 0x%02X%02X%02X, EYE_WHITE = 0x%02X%02X%02X;"
        % (GREY_HAIR + WHITE_HAIR + EYE_WHITE),
        "    static final int DARK = 0x%02X%02X%02X, SOOT = 0x%02X%02X%02X;" % (DARK + SOOT),
        "    static final float[] SHADOW = {%sF, %sF, %sF}, LIGHT_GAIN = {%sF, %sF, %sF};" % (SHADOW + LIGHT_GAIN),
        "    static final int[] GREY_AT = {%s};" % ", ".join(str(k) for k in GREY_AT),
        "    static final double GREY_KEEP = %s, GREY_FADE = %s;" % (GREY_KEEP, GREY_FADE),
        "    static final int[][] MOLE_SPOTS = {%s};" % ", ".join("{%d, %d}" % (x + 8, y + 8) for x, y in MOLE_SPOTS),
        "    // {from, its colour if fixed, scaled by (thousandths), toward, its colour if fixed, how far (thousandths)}",
        "    static final int[][] DERIVED = {%s};" % ", ".join(
            "{%d, 0x%02X%02X%02X, %d, %d, 0x%02X%02X%02X, %d}" % ((SOURCES.index(src),) + tuple(int(k) for k in (fx or (0, 0, 0)))
                                                              + (int(round(sc * 1000)), SOURCES.index(tg))
                                                              + tuple(int(k) for k in (tf or (0, 0, 0))) + (int(round(t * 1000)),))
            for pal, (src, fx, sc, tg, tf, t) in sorted(DERIVED.items())),
        "    static final int HAIR = %d, BROW = %d, BEARD = %d, MOLE = %d;" % (HAIR, BROW, BEARD, MOLE),
        "    static final String[] PLAIN = {%s};" % names(PLAIN),
        "    static final int FACES = %d, NOSES = %d, EYE_SHAPES = %d, BROWS = %d, STYLES = %d, TEXTURES = %d, FACIAL = %d, SCARS = %d;"
        % (len(FACE_SHAPES), len(NOSES), len(EYE_SHAPES), len(BROWS), len(STYLES), len(TEXTURES), len(FACIAL), len(SCARS)),
    ]
    return "\n".join(lines)


def java_words():
    """The words for each look, for the server's "Looks" line (entity/Looks)."""
    q = lambda ns: ", ".join('"%s"' % n for n in ns)
    return "\n".join([
        "    public static final String[] TONE_WORDS = {%s};" % q([n for n, _ in TONES]),
        "    public static final String[] HAIR_WORDS = {%s};" % q([n for n, _ in HAIRS]),
        "    public static final String[] EYE_WORDS = {%s};" % q([n for n, _ in EYES]),
        "    public static final String[] FACE_WORDS = {%s};" % q(FACE_SHAPES),
        "    public static final String[] NOSE_WORDS = {%s};" % q(NOSES),
        "    public static final String[] EYE_SHAPE_WORDS = {%s};" % q(EYE_SHAPES),
        "    public static final String[] BROW_WORDS = {%s};" % q(BROWS),
        "    public static final String[] STYLE_WORDS = {%s};" % q(STYLES),
        "    public static final String[] TEXTURE_WORDS = {%s};" % q(TEXTURES),
        "    public static final String[] FACIAL_WORDS = {%s};" % q(FACIAL),
        "    public static final String[] SCAR_WORDS = {%s};" % q(SCARS),
    ])


LOOKS_JAVA = os.path.join(ROOT, "src/main/java/com/jrpetty/mcassistant/entity/Looks.java")


# ---------------------------------------------------------------------------------------------- the sheet

def random_looks(r, age=None):
    """A folk's looks as the game rolls a founder's (entity/Looks.founder), near enough for a sheet."""
    male = r.random() < 0.5
    tone = r.randrange(len(TONES))
    if age is None:
        age = r.choice([8, 12, 20, 24, 28, 33, 38, 45, 52, 58, 64, 70, 78, 86])
    child = age < 18
    hair = r.choices(range(len(HAIRS)), weights=[5, 6, 5, 3, 2, 1, 1.4, 2.4, 1.2, 1.2])[0]
    if tone >= 6 and r.random() < 0.8:
        hair = r.choice([0, 0, 1, 1, 2])
    eyes = r.choices(range(len(EYES)), weights=[6, 3, 3, 1, 2.2, 2.6, 1.6])[0]
    if tone >= 6 and r.random() < 0.85:
        eyes = r.choice([0, 1, 1, 2])
    texture = r.choices([0, 1, 2], weights=[5, 3, 2 + (4 if tone >= 6 else 0)])[0]
    if male:
        style = r.choices(range(len(STYLES)), weights=[3, 3, .7, .1, .2, 1.4 + texture, 1.2, .3, 1, 0, 1, .3])[0]
    else:
        style = r.choices(range(len(STYLES)), weights=[.4, .8, 3, 2, 2, 1.4 + texture, .1, 2, 1.2, 0, .6, 1.5])[0]
    if male and age >= 45 and r.random() < 0.35:
        style = STYLES.index("balding")
    facial = 0
    if male and not child:
        facial = r.choices(range(len(FACIAL)), weights=[4, 2, 1.2, 2, .5 + (2 if age > 60 else 0), .8, .8])[0]
    grey = 0 if age < 38 else min(5, (age - 38) // 9 + r.choice([0, 0, 1]))
    lines = 0 if age < 40 else 1 if age < 60 else 2 if age < 75 else 3
    return {
        "male": male, "age": age, "tone": tone, "hair": hair, "eyes": eyes, "texture": texture, "style": style,
        "facial": facial, "face": r.randrange(len(FACE_SHAPES)), "nose": r.randrange(len(NOSES)),
        "eye_shape": r.randrange(len(EYE_SHAPES)), "brows": r.choices(range(len(BROWS)), weights=[4, 2, 2, 2, 2, 1 + (2 if age > 60 else 0), .3])[0],
        "freckles": tone <= 2 and r.random() < 0.35 or hair in (5, 6) and r.random() < 0.6,
        "rosy": tone <= 3 and r.random() < 0.3, "mole": r.random() < 0.15,
        "grey": grey, "lines": lines, "scar": 0 if r.random() > 0.08 else r.randrange(1, 4),
        "patch": 0 if r.random() > 0.03 else r.choice([1, 2]), "specs": age >= 55 and r.random() < 0.35,
        "soot": r.random() < 0.06, "sun": r.random() < 0.08,
        "shirt": r.randrange(len(SHIRTS)), "trousers": r.randrange(len(TROUSERS)), "shoes": r.randrange(len(SHOES)),
        "stick": age >= 78,
    }


def describe(g):
    bits = ["%s, %d" % ("m" if g["male"] else "f", g["age"]), TONES[g["tone"]][0], HAIRS[g["hair"]][0] + " " + STYLES[g["style"]]]
    if g["facial"]:
        bits.append(FACIAL[g["facial"]])
    return "; ".join(bits)


def sheet(path, n=40, seed=11, size=(160, 176), yaw=22, cols=8):
    import numpy as np
    from PIL import Image, ImageDraw
    all_layers = layers()
    r = random.Random(seed)
    tiles = []
    for i in range(n):
        g = random_looks(r)
        if i == 0:
            g.update(scar=1, specs=False)
        if i == 5:
            g.update(patch=2)
        pic = compose(g, all_layers)
        if g["mole"]:
            x, y = MOLE_SPOTS[i % len(MOLE_SPOTS)]
            pal = palette_colours(g)
            pic[y + 8][x + 8] = ramp(pal[MOLE], 128) + (255,)
        tex = np.zeros((SIZE, SIZE, 4), np.float32)
        for y in range(SIZE):
            for x in range(SIZE):
                if pic[y][x] is not None:
                    tex[y, x] = pic[y][x]
        tiles.append((g, render_head(tex, g, size, yaw)))
    w, h = size
    out = Image.new("RGB", (w * cols, (h + 14) * ((n + cols - 1) // cols)), (236, 232, 222))
    d = ImageDraw.Draw(out)
    for i, (g, im) in enumerate(tiles):
        x, y = (i % cols) * w, (i // cols) * (h + 14)
        out.paste(im, (x, y))
        d.text((x + 3, y + h), describe(g)[:24], fill=(40, 36, 30))
    out.save(path)
    return path


def render_head(tex, g, size, yaw):
    """The head and shoulders, the way the game draws the model: the boxes of folk_art.PARTS, this picture on them."""
    import numpy as np
    from PIL import Image
    W, H = size
    img = np.zeros((H, W, 3), np.float32)
    img[:] = (170, 196, 222)
    zbuf = np.full((H, W), np.inf)
    young = g["age"] < 18
    by = {p[0]: p for p in fa.PARTS}
    show = set()
    for name, parent, pivot, rot, cubes, worn in fa.PARTS:
        if worn in ("all", "beard", "look"):
            show.add(name)
        if worn == "cane" and g.get("stick") and name == "cane_left":
            show.add(name)
    T = {}

    def rotm(xr, yr, zr):
        cx, sx, cy, sy, cz, sz = math.cos(xr), math.sin(xr), math.cos(yr), math.sin(yr), math.cos(zr), math.sin(zr)
        rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
        ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
        rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
        return rz @ ry @ rx

    def tf(name):
        if name in T:
            return T[name]
        _, parent, pivot, rot, cubes, worn = by[name]
        m = np.eye(4)
        m[:3, :3] = rotm(*rot)
        m[:3, 3] = pivot
        if name == "head" and young:
            m[:3, :3] = m[:3, :3] * 1.3
        if parent:
            m = tf(parent) @ m
        T[name] = m
        return m
    cam = rotm(math.radians(8), math.radians(yaw), 0)
    L0 = np.array([0.3, 1.0, -0.8])
    L0 /= np.linalg.norm(L0)
    scale = W / 14.0 if not young else W / 17.0
    centre_y = 29.3 if not young else 31.0
    for name in fa.PARTS:
        nm = name[0]
        if nm not in show:
            continue
        M = tf(nm)
        for c in name[4]:
            u, v, x, y, z, w, h, dd, gi = c
            x0, y0, z0 = x - gi, y - gi, z - gi
            x1, y1, z1 = x + w + gi, y + h + gi, z + dd + gi
            F = fa.faces(u, v, w, h, dd)
            Cn = {
                "front": [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0)],
                "back": [(x1, y0, z1), (x0, y0, z1), (x0, y1, z1), (x1, y1, z1)],
                "right": [(x0, y0, z1), (x0, y0, z0), (x0, y1, z0), (x0, y1, z1)],
                "left": [(x1, y0, z0), (x1, y0, z1), (x1, y1, z1), (x1, y1, z0)],
                "top": [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)],
                "bottom": [(x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0)],
            }
            N = {"front": (0, 0, -1), "back": (0, 0, 1), "right": (-1, 0, 0), "left": (1, 0, 0), "top": (0, -1, 0), "bottom": (0, 1, 0)}
            for face, corners in Cn.items():
                fx, fy, fw, fh = F[face]
                P = (M[:3, :3] @ np.array(corners, float).T).T + M[:3, 3]
                nn = M[:3, :3] @ np.array(N[face], float)
                Pw = P * np.array([-1, -1, 1]) + np.array([0, 24, 0])
                nw = nn * np.array([-1, -1, 1])
                Pc = (cam @ Pw.T).T
                nc = cam @ nw
                if nc[2] >= 0:
                    continue
                light = min(1.0, 0.55 + 0.5 * max(0, nw @ L0) + 0.12 * max(0, nw[1]))
                sx = W / 2 - Pc[:, 0] * scale
                sy = H * 0.5 - (Pc[:, 1] - centre_y) * scale
                uv = np.array([(fx, fy), (fx + fw, fy), (fx + fw, fy + fh), (fx, fy + fh)], float)
                for tri in ((0, 1, 2), (0, 2, 3)):
                    _raster(img, zbuf, sx[list(tri)], sy[list(tri)], Pc[list(tri), 2], uv[list(tri)], tex, light)
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))


def _raster(img, zbuf, xs, ys, zs, uv, tex, light):
    import numpy as np
    H, W = zbuf.shape
    minx, maxx = int(max(0, math.floor(xs.min()))), int(min(W - 1, math.ceil(xs.max())))
    miny, maxy = int(max(0, math.floor(ys.min()))), int(min(H - 1, math.ceil(ys.max())))
    if minx > maxx or miny > maxy:
        return
    (x0, x1, x2), (y0, y1, y2) = xs, ys
    den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
    if abs(den) < 1e-9:
        return
    gx, gy = np.meshgrid(np.arange(minx, maxx + 1) + 0.5, np.arange(miny, maxy + 1) + 0.5)
    a = ((y1 - y2) * (gx - x2) + (x2 - x1) * (gy - y2)) / den
    b = ((y2 - y0) * (gx - x2) + (x0 - x2) * (gy - y2)) / den
    c = 1 - a - b
    inside = (a >= -1e-6) & (b >= -1e-6) & (c >= -1e-6)
    if not inside.any():
        return
    z = a * zs[0] + b * zs[1] + c * zs[2]
    u = a * uv[0, 0] + b * uv[1, 0] + c * uv[2, 0]
    v = a * uv[0, 1] + b * uv[1, 1] + c * uv[2, 1]
    ui = np.clip(np.floor(u).astype(int), 0, tex.shape[1] - 1)
    vi = np.clip(np.floor(v).astype(int), 0, tex.shape[0] - 1)
    texel = tex[vi, ui]
    sub = zbuf[miny:maxy + 1, minx:maxx + 1]
    ok = inside & (texel[..., 3] > 25) & (z < sub)
    region = img[miny:maxy + 1, minx:maxx + 1]
    region[ok] = texel[ok][:, :3] * light
    sub[ok] = z[ok]


# ---------------------------------------------------------------------------------------------- main

def main():
    if "--sheet" in sys.argv:
        path = sys.argv[sys.argv.index("--sheet") + 1]
        arg = lambda k, d: type(d)(sys.argv[sys.argv.index(k) + 1]) if k in sys.argv else d
        tw, th = arg("--tile", "160x176").split("x")
        print(sheet(path, n=arg("--n", 40), seed=arg("--seed", 11), size=(int(tw), int(th)), yaw=arg("--yaw", 22),
                    cols=arg("--cols", 8)))
        return
    check = "--check" in sys.argv
    os.makedirs(LOOK_DIR, exist_ok=True)
    changed = []
    for name, L in layers().items():
        data = L.png()
        path = os.path.join(LOOK_DIR, name + ".png")
        old = open(path, "rb").read() if os.path.exists(path) else None
        if old != data:
            changed.append(name)
            if not check:
                with open(path, "wb") as fh:
                    fh.write(data)
    for path, tag, body in ((FACES_JAVA, "FACES", java_tables()), (LOOKS_JAVA, "WORDS", java_words())):
        if not os.path.exists(path):
            continue
        text = open(path).read()
        new = fa.splice(text, tag, body)
        if new != text:
            changed.append(os.path.basename(path))
            if not check:
                open(path, "w").write(new)
    print(("would change: " if check else "wrote: ") + (", ".join(changed) if changed else "nothing"))


if __name__ == "__main__":
    main()
