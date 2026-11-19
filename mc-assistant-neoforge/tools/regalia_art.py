#!/usr/bin/env python3
"""[regalia19] What a town's ruler wears (entity/Regalia): the monarch's crown, cape and sceptre, the royal house's
coronet, the elected leader's chain of office and hat, the elder's circlet and staff, the chief's plumed band.

Two kinds of picture:

  * On a model of their own (client/RegaliaModel), posed limb for limb with the folk like its own clothes:
      regalia_cloth.png    the cloth in greys, tinted with the town's first colour (a cape, a cap of velvet, a band)
      regalia_accent.png   the same, tinted with its second (a cape's lining, an enamel, a plume)
      regalia_fixed.png    what no dye touches: the gold and the gems, the ermine, the silver, the staff's wood
  * Laid over the folk's own picture where its clothes are (client/FolkFaces.dressed bakes them in, one picture a folk):
      regalia_dress_<kind>.png      the robe's gold, ermine and buttons, the dark coat, the hose and the boots
      regalia_dress_<kind>_dye.png  its cloth in greys, tinted with the town's first colour
  * regalia_marks.png: the little sign over a ruler's head (client/RegaliaLayer.mark), five of sixteen by sixteen.

    python3 tools/regalia_art.py            # write the pictures and the Java
    python3 tools/regalia_art.py --check    # only say what would change

Change a box here, never in RegaliaModel's GENERATED blocks.
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import folk_art as fa  # noqa: E402  (the canvas, the faces and the noise are folk_art's)

ROOT = os.path.dirname(HERE)
TEX_DIR = fa.TEX_DIR
JAVA = os.path.join(ROOT, "src/main/java/com/jrpetty/mcassistant/client/RegaliaModel.java")
SIZE = fa.SIZE

# --------------------------------------------------------------------- the model
#
# (name, parent, pivot, rotation, cubes, kinds)
# cube: (x, y, z, w, h, d, inflate). kinds: who wears it, a letter each: m the monarch, r the royal house, e an elected
# leader, d an elder, c a councillor, h a chief. A part on an arm ending _right or _left is carried in that hand, and only
# while the hand is free (client/RegaliaLayer.holds). Model space is the folk's: y grows down, the face looks toward -z,
# the feet stand on y = 24, the top of the head is y = -10 (its hair -10.5).

PARTS = [
    ("head", None, (0, 0, 0), (0, 0, 0), [], ""),
    ("body", None, (0, 0, 0), (0, 0, 0), [], ""),
    ("right_arm", None, (-5, 2, 0), (0, 0, 0), [], ""),
    ("left_arm", None, (5, 2, 0), (0, 0, 0), [], ""),

    # The crown: a gold band set with gems, its points rising above the head, over a cap of velvet in the town's colour,
    # a gold orb and cross on top.
    ("crown_band", "head", (0, 0, 0), (0, 0, 0), [(-4, -13, -4, 8, 4, 8, 0.6)], "m"),
    ("crown_cap", "head", (0, 0, 0), (0, 0, 0), [(-3.5, -12, -3.5, 7, 2, 7, 0)], "m"),
    ("crown_orb", "head", (0, 0, 0), (0, 0, 0), [(-1, -14, -1, 2, 2, 2, 0), (-0.5, -15, -0.5, 1, 1, 1, 0)], "m"),
    # The long cape, from the shoulders nearly to the heels, its edge and hem of ermine; and the short one of the house.
    ("cape_long", "body", (0, 0.2, 3.4), (0.14, 0, 0), [(-4.5, 0, 0, 9, 21, 1, 0)], "m"),
    ("cape_short", "body", (0, 0.2, 3.4), (0.14, 0, 0), [(-4.5, 0, 0, 9, 12, 1, 0)], "r"),
    # The ermine collar round the shoulders, a gold clasp at the throat.
    ("ermine_collar", "body", (0, 0, 0), (0, 0, 0), [(-5, -0.8, -3.5, 10, 3, 7, 0.3)], "mr"),
    # The sceptre, upright in the free hand (the arm brought forward, the sceptre turned back upright on it).
    ("sceptre_right", "right_arm", (-1, 9.5, -0.5), (0.5, 0, 0), [(-0.5, -11, -0.5, 1, 12, 1, 0), (-1, -13, -1, 2, 2, 2, 0)], "m"),
    ("sceptre_left", "left_arm", (1, 9.5, -0.5), (0.5, 0, 0), [(-0.5, -11, -0.5, 1, 12, 1, 0), (-1, -13, -1, 2, 2, 2, 0)], "m"),

    # The coronet of the heir and the consort: a narrower band, small points.
    ("coronet", "head", (0, 0, 0), (0, 0, 0), [(-4, -11.5, -4, 8, 3, 8, 0.6)], "r"),

    # The chain of office over the shoulders and its medallion on the breast; the magistrate's hat.
    ("chain", "body", (0, 0, 0), (0, 0, 0), [(-4, 0, -3, 8, 12, 6, 0.66)], "e"),
    ("medallion", "body", (0, 0, 0), (0, 0, 0), [(-1.5, 4.5, -4.45, 3, 3, 1, 0)], "e"),
    ("hat_crown", "head", (0, 0, 0), (0, 0, 0), [(-4, -14, -4, 8, 5, 8, 0.6)], "e"),
    ("hat_brim", "head", (0, 0, 0), (0, 0, 0), [(-6.5, -9.3, -6.5, 13, 1, 13, 0)], "e"),

    # The elder's silver circlet (a councillor's too), and its staff, a pale stone in its silver head.
    ("circlet", "head", (0, 0, 0), (0, 0, 0), [(-4, -10, -4, 8, 2, 8, 0.62)], "dc"),
    ("staff_right", "right_arm", (-1, 10, -0.5), (0.45, 0, 0), [(-0.5, -21, -0.5, 1, 34, 1, 0), (-1, -23, -1, 2, 2, 2, 0)], "d"),
    ("staff_left", "left_arm", (1, 10, -0.5), (0.45, 0, 0), [(-0.5, -21, -0.5, 1, 34, 1, 0), (-1, -23, -1, 2, 2, 2, 0)], "d"),

    # The chief's band in the town's colours with its gold beads, a crest of plumes standing at the back; the beaded
    # mantle; the staff with its plumes and coloured bindings.
    ("plume_band", "head", (0, 0, 0), (0, 0, 0), [(-4, -11, -4, 8, 2, 8, 0.62)], "h"),
    ("plumes", "head", (0, 0, 0), (0, 0, 0), [(-4, -16, -4, 8, 6, 8, 0.9)], "h"),
    ("mantle", "body", (0, 0, 0), (0, 0, 0), [(-5, -0.6, -3.5, 10, 4, 7, 0.35)], "h"),
    ("tall_staff_right", "right_arm", (-1, 10, -0.5), (0.45, 0, 0), [(-0.5, -21, -0.5, 1, 34, 1, 0), (-1, -24, -1, 2, 3, 2, 0)], "h"),
    ("tall_staff_left", "left_arm", (1, 10, -0.5), (0.45, 0, 0), [(-0.5, -21, -0.5, 1, 34, 1, 0), (-1, -24, -1, 2, 3, 2, 0)], "h"),
]


def pack():
    """Every cube a place on the pictures: shelves, the tallest first. Returns {(part, i): (u, v, w, h, d)}."""
    cubes = []
    seen = {}
    for name, parent, pivot, rot, cs, kinds in PARTS:
        for i, c in enumerate(cs):
            w, h, d = (int(round(k)) for k in c[3:6])
            cubes.append(((name, i), w, h, d, 2 * (d + w), d + h))
    cubes.sort(key=lambda c: (-c[5], -c[4]))
    out = {}
    x = y = shelf = 0
    for key, w, h, d, tw, th in cubes:
        # a part's twin on the other hand shares its picture
        twin = (key[0].replace("_right", "_left"), key[1])
        if key[0].endswith("_right") and twin in out:
            out[key] = out[twin]
            continue
        if x + tw > SIZE:
            x, y, shelf = 0, y + shelf, 0
        if y + th > SIZE:
            raise SystemExit("the regalia do not fit on a %d picture" % SIZE)
        out[key] = (x, y, w, h, d)
        seen[key] = True
        x += tw
        shelf = max(shelf, th)
    for key in list(out):
        twin = (key[0].replace("_left", "_right"), key[1])
        if key[0].endswith("_left") and twin not in out:
            out[twin] = out[key]
    return out


PLACES = pack()


class Box(fa.Box):
    def __init__(self, canvas, name, i=0):
        self.cv = canvas
        self.u, self.v, self.w, self.h, self.d = PLACES[(name, i)]
        self.f = fa.faces(self.u, self.v, self.w, self.h, self.d)


SIDES = ("right", "front", "left", "back")
ALL = ("top", "bottom") + SIDES

# --------------------------------------------------------------------- the colours

GOLD = [(92, 59, 11), (168, 112, 26), (221, 161, 44), (246, 210, 102), (255, 243, 194)]
SILVER = [(66, 72, 82), (128, 136, 146), (186, 194, 202), (226, 232, 236), (250, 252, 253)]
RUBY, RUBY_L = (168, 18, 38), (255, 104, 120)
SAPPHIRE, SAPPHIRE_L = (28, 56, 168), (118, 158, 255)
EMERALD, EMERALD_L = (16, 122, 58), (110, 226, 146)
PEARL = (242, 238, 226)
MOON, MOON_L = (150, 196, 228), (226, 244, 255)
ERMINE, ERMINE_SH, FLECK = (244, 242, 236), (214, 210, 200), (26, 24, 28)
FELT, FELT_L, FELT_D = (40, 38, 50), (62, 60, 76), (24, 22, 30)
WOOD, WOOD_L, WOOD_D = (110, 76, 42), (146, 104, 60), (70, 46, 24)


def g(v):
    return (v, v, v)


def gold(x, y, k=2):
    return fa.grain(GOLD[k], x, y, 6, 70)


def velvet(x, y, base=214):
    """Cloth in greys for the tint: a weave, and soft folds down it."""
    fold = (12, -6, 16, -10, 8, -14, 14, -4, 10)[x % 9]
    return fa.grain(g(base + fold), x, y, 5, 71)


def ermine(x, y, off=0):
    """White fur with its black tails, set in a staggered grid."""
    row, col = (y + off) // 3, x
    if (y + off) % 3 == 0 and (col + (row % 2) * 2) % 4 == 1:
        return FLECK
    if (y + off) % 3 == 1 and (col + (row % 2) * 2) % 4 == 1:
        return (70, 66, 70)
    return fa.grain(ERMINE if (x + y) % 5 else ERMINE_SH, x, y, 4, 72)


def ermine_box(b):
    for face in ALL:
        k = {"top": 1.0, "bottom": 0.86, "right": 0.95, "left": 0.95}.get(face, 1.0)
        b.fill(face, lambda x, y, w, h, k=k, face=face: fa.lit(ermine(x, y, 1 if face in ("right", "left") else 0), k))


# --------------------------------------------------------------------- the monarch

CROWN_ROWS = [
    "P..PP..P",
    "G.GGGG.G",
    "LLLLLLLL",
    "GRGSSGRG",
]
CROWN_SIDE_ROWS = [
    "P..PP..P",
    "G.GGGG.G",
    "LLLLLLLL",
    "GEGRRGEG",
]


def crown_px(ch, x, y):
    return {
        "P": PEARL, "G": gold(x, y, 2), "L": gold(x, y, 3), "D": GOLD[1],
        "R": RUBY if (x + y) % 2 else RUBY_L, "S": SAPPHIRE if x % 2 else SAPPHIRE_L,
        "E": EMERALD if y % 2 else EMERALD_L,
    }.get(ch)


def monarch(cv, ac, fx):
    band = Box(fx, "crown_band")
    for face in SIDES:
        rows = CROWN_ROWS if face in ("front", "back") else CROWN_SIDE_ROWS
        def px(x, y, w, h, rows=rows, face=face):
            c = crown_px(rows[y][x], x, y)
            if c is None:
                return None
            # the band's inner side is in shadow
            return fa.lit(c, 0.92 if face in ("right", "left") else 1.0)
        band.fill(face, px)
    # the velvet cap, a gold cross-band over its top
    cap = Box(cv, "crown_cap")
    for face in ALL:
        k = {"top": 1.04, "bottom": 0.6}.get(face, 0.9)
        cap.fill(face, lambda x, y, w, h, k=k: fa.lit(velvet(x, y, 200), k))
    capf = Box(fx, "crown_cap")
    capf.fill("top", lambda x, y, w, h: gold(x, y, 3) if x == w // 2 or y == h // 2 else None)
    for i in range(2):
        orb = Box(fx, "crown_orb", i)
        orb.all(lambda face, x, y, w, h: GOLD[4] if (face == "top" or (x == 0 and y == 0)) else gold(x, y, 3 if face != "bottom" else 1))
    orb = Box(fx, "crown_orb", 0)
    orb.put("front", 1, 1, RUBY)
    orb.put("back", 1, 1, RUBY)

    for name in ("cape_long", "cape_short"):
        cape(cv, ac, fx, name)

    collar = Box(fx, "ermine_collar")
    ermine_box(collar)
    collar.put("front", 4, 1, GOLD[3])
    collar.put("front", 5, 1, GOLD[2])
    collar.put("front", 4, 2, GOLD[1])
    collar.put("front", 5, 2, GOLD[1])

    for side in ("right", "left"):
        rod = Box(fx, "sceptre_" + side, 0)
        rod.all(lambda face, x, y, w, h: GOLD[3] if y % 4 == 0 else gold(x, y, 2 if face in ("front", "left") else 1))
        head = Box(fx, "sceptre_" + side, 1)
        head.all(lambda face, x, y, w, h: PEARL if face == "top" and (x + y) % 2 == 0 else gold(x, y, 3 if y == 0 else 2))
        for face in SIDES:
            head.put(face, 0, 1, RUBY)
            head.put(face, 1, 1, RUBY_L)


def cape(cv, ac, fx, name):
    """A cape: the town's colour without, its second colour within, ermine down its edges and along its hem."""
    out = Box(cv, name)
    lining = Box(ac, name)
    trim = Box(fx, name)
    hem = 3 if name == "cape_long" else 2
    w, h = out.size("back")

    def edge(x, y):
        return x == 0 or x == w - 1 or y >= h - hem
    # Without (the back face, as one sees it from behind): the colour, folds down it; ermine at the edges and hem.
    out.fill("back", lambda x, y, fw, fh: None if edge(x, y) else fa.lit(velvet(x, y), 1.0 - 0.012 * y))
    trim.fill("back", lambda x, y, fw, fh: ermine(x, y) if edge(x, y) else None)
    # a thin gold line inside the ermine
    trim.fill("back", lambda x, y, fw, fh: gold(x, y, 3) if not edge(x, y) and (x in (1, fw - 2) or y == fh - hem - 1) else None)
    out.fill("back", lambda x, y, fw, fh: False if trim.at("back", x, y) is not None else None)
    # Within (toward the body): the lining in the second colour, darker.
    lining.fill("front", lambda x, y, fw, fh: fa.lit(velvet(x, y, 190), 0.85))
    out.fill("top", lambda x, y, fw, fh: velvet(x, y))
    for face in ("right", "left", "bottom"):
        trim.fill(face, lambda x, y, fw, fh: ermine(x, y))


# --------------------------------------------------------------------- the royal house

CORONET_ROWS = [
    "P.P..P.P",
    "LLLLLLLL",
    "GGRGGRGG",
]


def royal(cv, ac, fx):
    b = Box(fx, "coronet")
    for face in SIDES:
        b.fill(face, lambda x, y, w, h: crown_px(CORONET_ROWS[y][x], x, y))
    b.put("front", 3, 2, SAPPHIRE_L)
    b.put("front", 4, 2, SAPPHIRE)


# --------------------------------------------------------------------- the elected leader

def elected(cv, ac, fx):
    chain = Box(fx, "chain")
    w, h = chain.size("front")
    # A collar of gold links: from each shoulder down to the medallion on the breast, a link light and a link dark.
    path = [(1, 0), (1, 1), (2, 2), (2, 3), (3, 4)]
    for i, (x, y) in enumerate(path):
        chain.put("front", x, y, GOLD[3] if i % 2 == 0 else GOLD[1])
        chain.put("front", w - 1 - x, y, GOLD[3] if i % 2 == 0 else GOLD[1])
    tw, th = chain.size("top")
    for y in range(th):
        chain.put("top", 1, y, GOLD[3] if y % 2 == 0 else GOLD[1])
        chain.put("top", tw - 2, y, GOLD[3] if y % 2 == 0 else GOLD[1])
    for x in range(1, w - 1):
        chain.put("back", x, 0 if x in (1, w - 2) else 1, GOLD[3] if x % 2 == 0 else GOLD[1])
    med = Box(fx, "medallion")
    med.all(lambda face, x, y, fw, fh: gold(x, y, 3 if (x == 0 or y == 0) else 2) if not (face == "front" and x == 1 and y == 1) else None)
    med.put("front", 1, 0, GOLD[4])
    enamel = Box(ac, "medallion")
    enamel.put("front", 1, 1, g(230))

    # The magistrate's hat: dark felt, a band of the town's colour round its foot with a gold buckle; a gold-laced brim.
    hat = Box(fx, "hat_crown")
    hband = Box(cv, "hat_crown")
    for face in ALL:
        k = {"top": 1.12, "bottom": 0.7, "right": 0.9, "left": 0.9}.get(face, 1.0)
        def px(x, y, w, h, k=k, face=face):
            if face in SIDES and y == h - 2:
                return None
            c = FELT_L if (face in SIDES and y == 0) else FELT
            return fa.lit(fa.grain(c, x, y, 4, 73), k)
        hat.fill(face, px)
    for face in SIDES:
        hband.fill(face, lambda x, y, w, h: velvet(x, y, 210) if y == h - 2 else None)
    hat.put("front", 3, 3, GOLD[3])
    hat.put("front", 4, 3, GOLD[2])
    hband.fill("front", lambda x, y, w, h: False if (x in (3, 4) and y == h - 2) else None)
    brim = Box(fx, "hat_brim")
    for face in ("top", "bottom"):
        brim.fill(face, lambda x, y, w, h, face=face: GOLD[2] if (x in (0, w - 1) or y in (0, h - 1)) and face == "top"
                  else fa.lit(fa.grain(FELT, x, y, 4, 74), 1.0 if face == "top" else 0.72))
    for face in SIDES:
        brim.fill(face, lambda x, y, w, h: gold(x, y, 2))


# --------------------------------------------------------------------- the elder

def elder(cv, ac, fx):
    b = Box(fx, "circlet")
    for face in SIDES:
        def px(x, y, w, h, face=face):
            if y == 0:
                if face == "front" and x in (3, 4):
                    return SILVER[3]
                return None
            return fa.grain(SILVER[3] if (x + face.__len__()) % 3 else SILVER[2], x, y, 4, 75)
        b.fill(face, px)
    b.put("front", 3, 1, MOON_L)
    b.put("front", 4, 1, MOON)
    for side in ("right", "left"):
        rod = Box(fx, "staff_" + side, 0)
        rod.all(lambda face, x, y, w, h: fa.grain(WOOD_L if face in ("front", "left") else WOOD, x, y, 8, 76) if y % 9 else WOOD_D)
        head = Box(fx, "staff_" + side, 1)
        head.all(lambda face, x, y, w, h: SILVER[3] if y == 0 else SILVER[2])
        for face in SIDES:
            head.put(face, 0, 1, MOON)
            head.put(face, 1, 0, MOON_L)
        head.fill("top", lambda x, y, w, h: MOON_L if (x + y) % 2 == 0 else MOON)


# --------------------------------------------------------------------- the chief

def chief(cv, ac, fx):
    band = Box(cv, "plume_band")
    beads = Box(fx, "plume_band")
    for face in SIDES:
        band.fill(face, lambda x, y, w, h: None if (y == 1 and x % 2 == 0) else velvet(x, y, 206))
        beads.fill(face, lambda x, y, w, h: (GOLD[3] if x % 4 == 0 else GOLD[2]) if (y == 1 and x % 2 == 0) else None)
    beads.put("front", 3, 0, GOLD[3])
    beads.put("front", 4, 0, GOLD[2])
    band.fill("front", lambda x, y, w, h: False if (y == 0 and x in (3, 4)) else None)
    # The crest: plumes stand round the back only, the tallest behind, the face left clear. Each plume a colour (the
    # town's two by turns), a gold quill at its foot and a pale tip.
    plume_c = Box(cv, "plumes")
    plume_a = Box(ac, "plumes")
    plume_f = Box(fx, "plumes")
    heights = {"back": [0, 4, 0, 6, 6, 0, 4, 0],
               "right": [5, 0, 3, 0, 0, 0, 0, 0],     # the right side: its back edge is the left column
               "left": [0, 0, 0, 0, 0, 3, 0, 5]}      # the left side: its back edge is the right column
    for face, hs in heights.items():
        _, fh = plume_c.size(face)
        for x, top in enumerate(hs):
            if not top:
                continue
            which = plume_a if (x + len(face)) % 2 else plume_c
            for y in range(fh - 1 - top, fh):
                if y == fh - 1:
                    plume_f.put(face, x, y, GOLD[2])
                elif y == fh - 1 - top:
                    plume_f.put(face, x, y, PEARL)
                else:
                    which.put(face, x, y, g(236 - 6 * (fh - y)))
    mantle = Box(cv, "mantle")
    fringe = Box(fx, "mantle")
    for face in ALL:
        k = {"top": 1.05, "bottom": 0.7}.get(face, 0.95)
        mantle.fill(face, lambda x, y, w, h, k=k, face=face: (None if face in SIDES and y == h - 1 else fa.lit(velvet(x, y, 206), k)))
    for face in SIDES:
        fringe.fill(face, lambda x, y, w, h: (GOLD[3] if x % 4 == 0 else GOLD[1]) if (y == h - 1 and x % 2 == 0)
                    else (GOLD[2] if (y == h - 2 and x % 3 == 1) else None))
        mantle.fill(face, lambda x, y, w, h, face=face: False if fringe.at(face, x, y) is not None else None)
    for side in ("right", "left"):
        rod = Box(fx, "tall_staff_" + side, 0)
        wrap = Box(ac, "tall_staff_" + side, 0)
        rod.all(lambda face, x, y, w, h: None if 2 <= y % 10 <= 3 and y < 20 else fa.grain(WOOD_D if face in ("right", "back") else WOOD, x, y, 8, 77))
        wrap.all(lambda face, x, y, w, h: g(228) if 2 <= y % 10 <= 3 and y < 20 else None)
        tuft = Box(fx, "tall_staff_" + side, 1)
        tuftc = Box(ac, "tall_staff_" + side, 1)
        tuft.all(lambda face, x, y, w, h: PEARL if y == 0 else (GOLD[2] if y == h - 1 else None))
        tuftc.all(lambda face, x, y, w, h: g(232 - 20 * x) if 0 < y < h - 1 else None)


# --------------------------------------------------------------------- the dresses, baked over the folk's own picture
#
# Painted on the folk's own layout (folk_art's body, coat, arms and legs) as a trade's outfit is: every pixel painted
# takes the place of the one under it. Two pictures a kind: the fixed and the dyed (greys, for the town's colour).

def skin_box(cv, name):
    return fa.Box(cv, name)


# The coat's pixels below a robe's hem, to be cleared out of the folk's picture (the trade's coat must not show under
# it): written nearly clear (alpha 8), which a cut-out pass never draws and FolkFaces.dressed takes for "clear here".
CLEAR = set()


def hose(fx, cloth=(40, 32, 46), boot=(38, 28, 22), buckle=None):
    for name in ("right_leg", "left_leg"):
        leg = skin_box(fx, name)
        leg.around(lambda s, y, sw, h, face, x: fa.grain(cloth, s, y, 5, 78) if y < 9 else fa.grain(boot, s, y, 5, 79))
        leg.fill("top", lambda x, y, w, h: fa.grain(cloth, x, y, 4, 78))
        leg.fill("bottom", lambda x, y, w, h: fa.lit(boot, 0.6))
        leg.row(9, lambda x: fa.lit(boot, 1.25))
        if buckle:
            leg.put("front", 1, 10, buckle)
            leg.put("front", 2, 10, fa.lit(buckle, 0.8))


def sleeves_of(dye, fx, to, cuff_rows, cuff_fixed=None, cuff_dye=False):
    """Sleeves in the dyed cloth to row `to`, their cuffs (rows) in gold, ermine or the dye."""
    for name in ("right_arm", "left_arm"):
        d = skin_box(dye, name)
        f = skin_box(fx, name)
        for face in SIDES:
            d.fill(face, lambda x, y, w, h: velvet(x, y, 206) if y <= to and y not in cuff_rows else None)
            if cuff_fixed:
                f.fill(face, lambda x, y, w, h, face=face: cuff_fixed(x, y) if y in cuff_rows else None)
            elif cuff_dye:
                d.fill(face, lambda x, y, w, h: fa.lit(velvet(x, y, 230), 1.08) if y in cuff_rows else None)
        d.fill("top", lambda x, y, w, h: velvet(x, y, 214))


def robe(dye, fx, rows, front_fixed=None, all_fixed=None, base=206):
    """The coat and the body under it in the dyed cloth to `rows` rows (clear below), with what is fixed laid on top."""
    for name, n in (("coat", rows), ("body", min(rows, 12))):
        d = skin_box(dye, name)
        f = skin_box(fx, name)
        d.around(lambda s, y, sw, h, face, x, n=n: fa.lit(velvet(x, y, base), 1.0 if face == "front" else 0.94) if y < n else False)
        if name == "coat":
            for face in SIDES:
                fx0, fy0, fw, fh = f.f[face]
                for yy in range(n, fh):
                    for xx in range(fw):
                        CLEAR.add((fx0 + xx, fy0 + yy))
        d.fill("top", lambda x, y, w, h: velvet(x, y, base + 8))
        if name == "coat":
            d.fill("bottom", lambda x, y, w, h: fa.lit(velvet(x, y, base), 0.7) if rows >= 18 else False)
        for face in SIDES:
            def px(x, y, w, h, face=face, n=n):
                if y >= n:
                    return None
                c = all_fixed(face, x, y, w, n) if all_fixed else None
                if c is None and face == "front" and front_fixed:
                    c = front_fixed(x, y, w, n)
                return c
            f.fill(face, px)
            d.fill(face, lambda x, y, w, h, face=face: False if f.at(face, x, y) is not None else None)


def dress_monarch():
    dye, fx = fa.Canvas(), fa.Canvas()
    # A robe of the town's colour to the shins: ermine down the front between two lines of gold, a gold girdle, the hem
    # of ermine; sleeves to the wrist, gold and ermine at the cuff; dark hose, black boots with gold buckles.
    def front(x, y, w, n):
        if x in (3, 4):
            return ermine(x, y)
        if x in (2, 5):
            return gold(x, y, 3 if y % 3 else 4)
        return None

    def every(face, x, y, w, n):
        if y >= n - 2:
            return ermine(x, y)
        if y == n - 3 or y == 7:
            return gold(x, y, 3 if (x + y) % 2 else 2)
        return None
    robe(dye, fx, 18, front, every)
    sleeves_of(dye, fx, 9, (8, 9), lambda x, y: gold(x, y, 3) if y == 8 else ermine(x, y))
    hose(fx, buckle=GOLD[3])
    return fx, dye


def dress_royal():
    dye, fx = fa.Canvas(), fa.Canvas()
    # A tunic of the colour to the knee, gold down its front and at its hem, a gold belt; gold at the cuff; hose and boots.
    def front(x, y, w, n):
        if x in (3, 4) and y % 3 == 1:
            return GOLD[3]
        return None

    def every(face, x, y, w, n):
        if y == n - 1 or y == 7:
            return gold(x, y, 3 if (x + y) % 2 else 2)
        return None
    robe(dye, fx, 14, front, every)
    sleeves_of(dye, fx, 9, (9,), lambda x, y: gold(x, y, 3))
    hose(fx, cloth=(52, 44, 58))
    return fx, dye


def dress_elected():
    dye, fx = fa.Canvas(), fa.Canvas()
    # The formal coat: dark, to the knee, its lapels faced in the town's colour, a white cravat, three gold buttons; dark
    # sleeves with cuffs of the colour; dark breeches and black shoes.
    def front(x, y, w, n):
        if y < 3 and x in (3, 4):
            return (236, 234, 228) if y < 2 else (206, 204, 198)
        if y < 5 and (x == 2 and y >= 1 or x == 5 and y >= 1 or x in (1, 6) and y >= 3):
            return None                                   # the lapels: the dye shows here
        if x in (3, 4) and y in (6, 9, 12) and x == 3:
            return GOLD[3]
        if x == 4 and y in (6, 9, 12):
            return GOLD[1]
        return fa.lit(fa.grain(FELT, x, y, 4, 80), 1.1)

    def every(face, x, y, w, n):
        if face == "front":
            return None
        return fa.lit(fa.grain(FELT, x, y, 4, 80), 1.0 if face == "back" else 0.95)
    robe(dye, fx, 16, front, every)
    for name in ("right_arm", "left_arm"):
        f = skin_box(fx, name)
        d = skin_box(dye, name)
        for face in SIDES:
            f.fill(face, lambda x, y, w, h: fa.grain(FELT, x, y, 4, 81) if y < 8 else None)
            d.fill(face, lambda x, y, w, h: velvet(x, y, 220) if y in (8, 9) else None)
        f.fill("top", lambda x, y, w, h: fa.grain(FELT, x, y, 4, 81))
    hose(fx, cloth=(34, 32, 42), boot=(20, 18, 22))
    return fx, dye


def dress_elder():
    dye, fx = fa.Canvas(), fa.Canvas()
    # A long robe of undyed wool to the ankle, a cord at the waist, and a stole of the town's colour hanging down the
    # front, a silver fringe at its ends; wide sleeves; sandals.
    wool = (206, 198, 178)

    def every(face, x, y, w, n):
        if face == "front" and x in (2, 5) and y < 15:
            return None                                   # the stole: the dye shows here
        if face == "front" and x in (2, 5) and y == 15:
            return SILVER[3]
        if face == "back" and x in (2, 5) and y < 3:
            return None
        if y == 7:
            return (150, 120, 76) if (x + y) % 2 else (120, 94, 58)
        c = fa.grain(wool, x, y, 6, 82)
        return fa.lit(c, (0.9 if (x % 3 == 0) else 1.0) * (0.94 if face in ("right", "left") else 1.0))
    robe(dye, fx, 18, None, every, base=220)
    for name in ("right_arm", "left_arm"):
        f = skin_box(fx, name)
        for face in SIDES:
            f.fill(face, lambda x, y, w, h: fa.grain(wool if y < 9 else fa.lit(wool, 0.85), x, y, 6, 83) if y <= 9 else None)
        f.fill("top", lambda x, y, w, h: fa.grain(wool, x, y, 6, 83))
    hose(fx, cloth=wool, boot=(110, 78, 46))
    return fx, dye


def dress_chief():
    dye, fx = fa.Canvas(), fa.Canvas()
    # A tunic of the town's colour to the knee, a band of gold zigzag across the breast and at the hem; bare arms with gold
    # bands at the shoulder and the wrist; leather leggings and soft boots.
    def every(face, x, y, w, n):
        if y in (3, 4):
            return GOLD[3] if (x + y) % 2 == 0 else GOLD[1] if (x % 4) == (y - 3) * 2 else None
        if y == n - 1:
            return GOLD[2] if x % 2 == 0 else None
        return None
    robe(dye, fx, 14, None, every, base=210)
    for name in ("right_arm", "left_arm"):
        f = skin_box(fx, name)
        for face in SIDES:
            f.fill(face, lambda x, y, w, h: (GOLD[3] if x % 2 == 0 else GOLD[2]) if y in (1, 8) else None)
    hose(fx, cloth=(124, 88, 52), boot=(96, 66, 40))
    return fx, dye


DRESSES = {"monarch": dress_monarch, "royal": dress_royal, "elected": dress_elected, "elder": dress_elder, "chief": dress_chief}


# --------------------------------------------------------------------- the marks over the head

MARKS = [
    # the crown
    ["................",
     "................",
     "..P....PP....P..",
     "..G...GGGG...G..",
     "..GG..GGGG..GG..",
     "..GGG.GGGG.GGG..",
     "..GGGGGGGGGGGG..",
     "..GGGGGGGGGGGG..",
     "..LLLLLLLLLLLL..",
     "..GRGGGSSGGGRG..",
     "..GRGGGSSGGGRG..",
     "..DDDDDDDDDDDD..",
     "................",
     "................",
     "................",
     "................"],
    # the coronet
    ["................",
     "................",
     "................",
     "................",
     "...P..P..P..P...",
     "...G..G..G..G...",
     "...GG.GG.GG.G...",
     "...LLLLLLLLLL...",
     "...GGRGGGGRGG...",
     "...DDDDDDDDDD...",
     "................",
     "................",
     "................",
     "................",
     "................",
     "................"],
    # the chain of office and its medallion
    ["................",
     "...L........L...",
     "...D........D...",
     "....L......L....",
     "....D......D....",
     ".....L....L.....",
     ".....D....D.....",
     "......LDDL......",
     ".....GGLLGG.....",
     "....GGLAALGG....",
     "....GLAAAAGG....",
     "....GGAAAAGG....",
     ".....GGAAGG.....",
     "......DDDD......",
     "................",
     "................"],
    # the silver circlet
    ["................",
     "................",
     "................",
     "................",
     "................",
     ".......SS.......",
     "......SMMS......",
     "..SSSSSMMSSSSS..",
     "..WWWWWWWWWWWW..",
     "..SSSSSSSSSSSS..",
     "..KKKKKKKKKKKK..",
     "................",
     "................",
     "................",
     "................",
     "................"],
    # the plumed band
    ["................",
     "......P..P......",
     "...P..A..C..P...",
     "...C..A..C..A...",
     "...C..A..C..A...",
     "...C..A..C..A...",
     "...C.QA.QC..A...",
     "...Q..Q..Q..Q...",
     "..CCCCCCCCCCCC..",
     "..CGCGCGGCGCGC..",
     "..CCCCCCCCCCCC..",
     "................",
     "................",
     "................",
     "................",
     "................"],
]


def png_clearing(cv, clear):
    """A canvas as a picture, with the pixels to clear written nearly clear (alpha 8)."""
    import struct
    import zlib
    raw = b""
    for y, row in enumerate(cv.px):
        raw += b"\x00" + b"".join(
            bytes((c[0], c[1], c[2], 255)) if c is not None else (b"\x00\x00\x00\x08" if (x, y) in clear else b"\x00\x00\x00\x00")
            for x, c in enumerate(row))

    def chunk(t, data):
        body = t + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def marks_png():
    """Five signs side by side, each outlined in dark so it reads against the sky; the cloth ones in the town's red and
    gold (the sign is small: it says what kind of ruler, the clothes say whose)."""
    w, h = 16 * len(MARKS), 16
    px = [[None] * w for _ in range(h)]
    pal = {"P": PEARL, "G": GOLD[2], "L": GOLD[3], "D": GOLD[1], "R": RUBY, "S": SILVER[2], "W": SILVER[4], "K": SILVER[1],
           "M": MOON, "A": (196, 40, 44), "C": (176, 36, 40), "Q": GOLD[3]}
    for i, rows in enumerate(MARKS):
        for y, row in enumerate(rows):
            for x, ch in enumerate(row):
                if ch in pal:
                    c = pal[ch]
                    if ch == "S" and i == 0:
                        c = SAPPHIRE_L
                    px[y][16 * i + x] = c
        # a dark outline round the sign
        for y in range(h):
            for x in range(16):
                if px[y][16 * i + x] is not None:
                    continue
                near = any(0 <= y + dy < h and 0 <= x + dx < 16 and px[y + dy][16 * i + x + dx] is not None
                           and px[y + dy][16 * i + x + dx] != (24, 18, 22)
                           for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
                if near:
                    px[y][16 * i + x] = (24, 18, 22)
    import struct
    import zlib
    raw = b""
    for row in px:
        raw += b"\x00" + b"".join(bytes((c[0], c[1], c[2], 255)) if c else b"\x00\x00\x00\x00" for c in row)

    def chunk(t, data):
        body = t + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


# ------------------------------------------------------------------------ Java

def java_geometry():
    out = ["        MeshDefinition mesh = new MeshDefinition();",
           "        PartDefinition root = mesh.getRoot();"]
    made = {None: "root"}
    for name, parent, pivot, rot, cubes, kinds in PARTS:
        var = re.sub(r"_(\w)", lambda m: m.group(1).upper(), name)
        builder = "CubeListBuilder.create()"
        for i, (x, y, z, w, h, d, gr) in enumerate(cubes):
            u, v = PLACES[(name, i)][:2]
            builder += ".texOffs(%d, %d).addBox(%s, %s, %s, %s, %s, %s%s)" % (
                u, v, fa.fnum(x), fa.fnum(y), fa.fnum(z), fa.fnum(w), fa.fnum(h), fa.fnum(d),
                (", new CubeDeformation(%s)" % fa.fnum(gr)) if gr else "")
        if any(rot):
            pose = "PartPose.offsetAndRotation(%s, %s, %s, %s, %s, %s)" % tuple(fa.fnum(k) for k in pivot + rot)
        elif any(pivot):
            pose = "PartPose.offset(%s, %s, %s)" % tuple(fa.fnum(k) for k in pivot)
        else:
            pose = "PartPose.ZERO"
        has_children = any(p[1] == name for p in PARTS)
        prefix = ("PartDefinition %s = " % var) if has_children else ""
        out.append("        %s%s.addOrReplaceChild(\"%s\", %s, %s);" % (prefix, made[parent], name, builder, pose))
        made[name] = var
    out.append("        return LayerDefinition.create(mesh, %d, %d);" % (SIZE, SIZE))
    return "\n".join(out)


def java_pieces(has):
    lines = []
    for name, parent, pivot, rot, cubes, kinds in PARTS:
        if not kinds:
            continue
        pics = "".join(k for k in "caf" if name in has[k])
        lines.append("        {\"%s\", \"%s\", \"%s\", \"%s\"}," % (name, parent, kinds, pics))
    return "\n".join(lines)


def main():
    check = "--check" in sys.argv
    cloth, accent, fixed = fa.Canvas(), fa.Canvas(), fa.Canvas()
    for paint in (monarch, royal, elected, elder, chief):
        paint(cloth, accent, fixed)
    has = {"c": set(), "a": set(), "f": set()}
    for name, parent, pivot, rot, cubes, kinds in PARTS:
        for i, c in enumerate(cubes):
            u, v, w, h, d = PLACES[(name, i)]
            for yy in range(v, v + d + h):
                for xx in range(u, u + 2 * (d + w)):
                    for k, cv in (("c", cloth), ("a", accent), ("f", fixed)):
                        if cv.get(xx, yy) is not None:
                            has[k].add(name)
    pictures = {"regalia_cloth.png": cloth.png(), "regalia_accent.png": accent.png(), "regalia_fixed.png": fixed.png(),
                "regalia_marks.png": marks_png()}
    for kind, make in DRESSES.items():
        CLEAR.clear()
        fx, dye = make()
        pictures["regalia_dress_%s.png" % kind] = png_clearing(fx, CLEAR)
        pictures["regalia_dress_%s_dye.png" % kind] = dye.png()
    changed = []
    for name, data in pictures.items():
        path = os.path.join(TEX_DIR, name)
        old = open(path, "rb").read() if os.path.exists(path) else None
        if old != data:
            changed.append(name)
            if not check:
                with open(path, "wb") as fh:
                    fh.write(data)
    text = open(JAVA).read()
    new = fa.splice(text, "GEOMETRY", java_geometry())
    new = fa.splice(new, "PIECES", java_pieces(has))
    if new != text:
        changed.append(os.path.basename(JAVA))
        if not check:
            open(JAVA, "w").write(new)
    print(("would change: " if check else "wrote: ") + (", ".join(changed) if changed else "nothing"))


if __name__ == "__main__":
    main()
