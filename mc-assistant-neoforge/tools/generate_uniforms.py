#!/usr/bin/env python3
"""Generate one 64x64 player skin per job, so a crew reads at a glance.

No art assets to ship and no dependency on the vanilla Steve texture: each
uniform is drawn from flat colours with per-face shading, which is exactly
how a low-res skin reads in game anyway.
"""
import os, struct, zlib

W = H = 64
OUT = os.environ.get("OUT", ".")


def blank():
    return [[(0, 0, 0, 0) for _ in range(W)] for _ in range(H)]


def shade(c, f):
    return (max(0, min(255, int(c[0] * f))),
            max(0, min(255, int(c[1] * f))),
            max(0, min(255, int(c[2] * f))), 255)


def grain(x, y, c, amount=7):
    """Deterministic dither so a flat colour doesn't look like plastic."""
    n = ((x * 73856093) ^ (y * 19349663)) & 0xFF
    d = (n % (amount * 2 + 1)) - amount
    return (max(0, min(255, c[0] + d)),
            max(0, min(255, c[1] + d)),
            max(0, min(255, c[2] + d)), 255)


def rect(img, x, y, w, h, c, textured=True):
    for j in range(y, y + h):
        for i in range(x, x + w):
            if 0 <= i < W and 0 <= j < H:
                img[j][i] = grain(i, j, c) if textured else (c[0], c[1], c[2], 255)


# Face brightnesses, so a flat colour still reads as a solid cuboid in world.
TOP, BOTTOM, FRONT, BACK, RIGHT, LEFT = 1.14, 0.62, 1.0, 0.90, 0.86, 0.78


def box(img, u, v, w, h, d, c, top=None, bottom=None):
    """Fill all six faces of a skin cuboid laid out at (u,v)."""
    top = top or c
    bottom = bottom or c
    rect(img, u + d, v, w, d, shade(top, TOP))                     # top
    rect(img, u + d + w, v, w, d, shade(bottom, BOTTOM))           # bottom
    rect(img, u, v + d, d, h, shade(c, RIGHT))                     # right
    rect(img, u + d, v + d, w, h, shade(c, FRONT))                 # front
    rect(img, u + d + w, v + d, d, h, shade(c, LEFT))              # left
    rect(img, u + d + w + d, v + d, w, h, shade(c, BACK))          # back


SKIN = (198, 134, 66)
SKIN_D = (168, 110, 54)
HAIR = (58, 42, 28)
EYE_W = (222, 222, 226)
EYE_D = (44, 60, 106)
MOUTH = (100, 62, 44)


def head(img, hair=HAIR):
    # Skull in skin tone, then the hair over the back and crown.
    box(img, 0, 0, 8, 8, 8, SKIN, top=SKIN, bottom=SKIN_D)
    rect(img, 8, 0, 8, 8, shade(hair, TOP))            # crown
    rect(img, 24, 8, 8, 8, shade(hair, BACK))          # back of the head
    rect(img, 0, 8, 3, 8, shade(hair, RIGHT))          # right side, behind the ear
    rect(img, 21, 8, 3, 8, shade(hair, LEFT))          # left side
    rect(img, 8, 8, 8, 2, shade(hair, FRONT))          # fringe over the brow

    # Face, on the front 8x8 at (8,8).
    fx, fy = 8, 8
    for x, c in ((1, EYE_W), (2, EYE_D), (5, EYE_D), (6, EYE_W)):
        img[fy + 3][fx + x] = (c[0], c[1], c[2], 255)
    for x in (3, 4):
        img[fy + 6][fx + x] = (MOUTH[0], MOUTH[1], MOUTH[2], 255)


def hat(img, c):
    """The overlay layer over the skull — a helmet, a straw brim, a cap.

    Only the crown and a band round the brow: filling the whole overlay cube
    (the obvious thing) puts a solid block where the face should be, and every
    hatted trade ends up with no face at all.
    """
    u, v = 32, 0
    rect(img, u + 8, v, 8, 8, shade(c, TOP))              # crown, full
    rect(img, u + 8, v + 8, 8, 3, shade(c, FRONT))        # band over the brow
    rect(img, u, v + 8, 8, 5, shade(c, RIGHT))            # right side, ears down
    rect(img, u + 16, v + 8, 8, 5, shade(c, LEFT))        # left side
    rect(img, u + 24, v + 8, 8, 6, shade(c, BACK))        # back, down to the nape


def body(img, shirt, trousers, sleeves=None, boots=None):
    sleeves = sleeves or shirt
    boots = boots or trousers
    box(img, 16, 16, 8, 12, 4, shirt)                        # torso
    box(img, 40, 16, 4, 12, 4, SKIN, top=sleeves)            # right arm
    box(img, 32, 48, 4, 12, 4, SKIN, top=sleeves)            # left arm
    # Sleeve down to the elbow, so the arm isn't bare to the shoulder.
    for u, v in ((40, 16), (32, 48)):
        rect(img, u, v + 4, 4, 6, shade(sleeves, RIGHT))
        rect(img, u + 4, v + 4, 4, 6, shade(sleeves, FRONT))
        rect(img, u + 8, v + 4, 4, 6, shade(sleeves, LEFT))
        rect(img, u + 12, v + 4, 4, 6, shade(sleeves, BACK))
    box(img, 0, 16, 4, 12, 4, trousers, bottom=boots)        # right leg
    box(img, 16, 48, 4, 12, 4, trousers, bottom=boots)       # left leg
    # Boots on the bottom third of each leg.
    for u, v in ((0, 16), (16, 48)):
        rect(img, u, v + 9, 4, 3, shade(boots, RIGHT))
        rect(img, u + 4, v + 9, 4, 3, shade(boots, FRONT))
        rect(img, u + 8, v + 9, 4, 3, shade(boots, LEFT))
        rect(img, u + 12, v + 9, 4, 3, shade(boots, BACK))


def belt(img, c):
    """A band across the waist — cheap, but it breaks up a plain torso."""
    for dx, f in ((0, RIGHT), (4, FRONT), (12, LEFT), (20, BACK)):
        w = 4 if dx in (0, 12) else 8
        rect(img, 16 + dx, 16 + 4 + 9, w, 2, shade(c, f))


def make(shirt, trousers, hair=HAIR, hat_c=None, sleeves=None,
         boots=None, belt_c=None):
    img = blank()
    head(img, hair)
    body(img, shirt, trousers, sleeves, boots)
    if belt_c:
        belt(img, belt_c)
    if hat_c:
        hat(img, hat_c)
    return img


def png(path, img):
    raw = b"".join(b"\x00" + bytes(v for px in row for v in px) for row in img)

    def chunk(kind, data):
        c = kind + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c))

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 6, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(raw, 9)))
        f.write(chunk(b"IEND", b""))


# One uniform per job. The colours are the job's own materials wherever there
# is an obvious one — wheat gold for the farmer, denim for the miner, hi-vis
# orange for the hauler — so you can name the trade from across the field.
UNIFORMS = {
    "unassigned": dict(shirt=(110, 122, 138), trousers=(62, 70, 82), belt_c=(48, 40, 34)),
    "farmer":     dict(shirt=(201, 162, 39), trousers=(107, 75, 42), hat_c=(217, 194, 122),
                       belt_c=(90, 62, 34)),
    "lumberjack": dict(shirt=(166, 57, 44), trousers=(61, 49, 40), hair=(74, 48, 30),
                       sleeves=(120, 40, 32), belt_c=(52, 38, 28), boots=(46, 34, 26)),
    "miner":      dict(shirt=(60, 74, 107), trousers=(46, 56, 80), hat_c=(224, 178, 58),
                       belt_c=(58, 44, 34)),
    "rancher":    dict(shirt=(78, 122, 58), trousers=(107, 75, 42), hat_c=(138, 106, 58),
                       belt_c=(74, 52, 32)),
    "guard":      dict(shirt=(89, 96, 107), trousers=(51, 56, 64), hat_c=(154, 161, 172),
                       sleeves=(70, 76, 86), belt_c=(40, 44, 50), boots=(38, 42, 48)),
    "smelter":    dict(shirt=(58, 58, 58), trousers=(74, 50, 38), sleeves=(96, 62, 44),
                       belt_c=(186, 96, 40), boots=(44, 40, 38)),
    "fisher":     dict(shirt=(47, 109, 158), trousers=(41, 80, 110), hat_c=(229, 196, 74),
                       belt_c=(46, 60, 74)),
    "storekeeper": dict(shirt=(106, 74, 122), trousers=(64, 48, 74), belt_c=(48, 36, 58)),
    "hauler":     dict(shirt=(196, 105, 42), trousers=(74, 59, 42), sleeves=(150, 78, 32),
                       belt_c=(52, 42, 32)),
}

os.makedirs(OUT, exist_ok=True)
for name, kw in UNIFORMS.items():
    png(os.path.join(OUT, name + ".png"), make(**kw))
    print("wrote", name + ".png")
