#!/usr/bin/env python3
"""[leisure] Home, play and the town's evenings: the pictures of the seven things (item/LeisureItems).

Every picture is sixteen by sixteen (the football's model sheet sixty-four), drawn pixel by pixel in the game's own
way: a dark outline, two or three shades lit from the top left, a highlight. Most are drawn from a grid of letters
(one letter a pixel, '.' clear), so a pixel can be moved here and the picture made again; the round and the many-
coloured ones (the football, the lanterns in their sixteen colours, the quilt's patches) are worked out.

    python3 tools/leisure_art.py            # write the pictures (and the lanterns' block models and states)

What it writes:
  item/   patchwork_quilt, lute, kite (the paper, tinted) + kite_frame (sticks, bows), leather_football,
          slate_and_chalk, <colour>_paper_lantern x16
  block/  patchwork_quilt (top), patchwork_quilt_edge, draughts_board_top, draughts_board_side, draughts_pieces,
          <colour>_paper_lantern x16, paper_lantern_cap, paper_lantern_string, paper_lantern_tassel
  entity/ football (64x64, the model's three boxes), kite_cloth, kite_frame, kite_bow, kite_string, draughts_pieces
"""
import json
import math
import os

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ASSETS = os.path.join(ROOT, "src/main/resources/assets/mc_assistant")
TEX = os.path.join(ASSETS, "textures")


def hexc(s, a=255):
    s = s.lstrip("#")
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), a)


def mix(c1, c2, t):
    return tuple(int(round(c1[i] + (c2[i] - c1[i]) * t)) for i in range(3)) + (255,)


def scale(c, k):
    return tuple(max(0, min(255, int(round(c[i] * k)))) for i in range(3)) + (255,)


def grid(rows, pal, size=16):
    """A picture from rows of letters; '.' is clear."""
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        assert len(row) == size, (y, row, len(row))
        for x, ch in enumerate(row):
            if ch == ".":
                continue
            im.putpixel((x, y), pal[ch])
    return im


def save(im, *path):
    p = os.path.join(TEX, *path)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    im.save(p)
    return p


def outline(im, colour):
    """A one-pixel outline round everything drawn, on the clear pixels touching it (not diagonally)."""
    w, h = im.size
    out = im.copy()
    for y in range(h):
        for x in range(w):
            if im.getpixel((x, y))[3] != 0:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < w and 0 <= ny < h and im.getpixel((nx, ny))[3] != 0:
                    out.putpixel((x, y), colour)
                    break
    return out


# ============================================================================ the leather football

LEATHER = {
    "O": hexc("3a1f0b"),   # outline
    "d": hexc("6b3a16"),   # shade
    "m": hexc("8f5524"),   # leather
    "l": hexc("b06e35"),   # lit
    "h": hexc("d3965a"),   # highlight
    "s": hexc("4a2810"),   # seam
    "t": hexc("e9d3a6"),   # stitch
    "w": hexc("f4ecdc"),   # lace
    "g": hexc("b9a687"),   # lace in shadow
}


def football_item():
    """The ball in the hand, as the old eighteen-panel ball is made: a cube's six faces blown round, each face three
    strips of leather, each face's strips lying across its neighbours'. Seen nearly face on and from a little above and
    to the left (so its top and left faces show too), lit from the top left; the faces stitched apart with dark seams,
    the strips with fainter ones; down the middle strip of the face toward us, the laced slit."""
    pal = LEATHER
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    r, c0, ss = 7.15, 8.0, 5
    yaw, pitch = -0.3, 0.3

    def turn(v):
        x, y, z = v
        cp, sp = math.cos(pitch), math.sin(pitch)
        y, z = y * cp - z * sp, y * sp + z * cp
        cy, sy = math.cos(yaw), math.sin(yaw)
        return (x * cy + z * sy, y, -x * sy + z * cy)

    def label(n):
        ax = max(range(3), key=lambda i: abs(n[i]))
        along = {0: 2, 1: 0, 2: 1}[ax]               # the face toward us has its strips up and down
        across = [i for i in range(3) if i not in (ax, along)][0]
        return (ax, n[ax] > 0, min(2, int((n[across] / abs(n[ax]) + 1) * 1.5)))

    info = {}
    for y in range(16):
        for x in range(16):
            dx, dy = (x + 0.5 - c0) / r, (y + 0.5 - c0) / r
            d2 = dx * dx + dy * dy
            if d2 > 1.0:
                continue
            light = -0.52 * dx - 0.6 * dy + 0.6 * math.sqrt(1 - d2)
            c = pal["h"] if light > 0.74 else pal["l"] if light > 0.45 else pal["m"] if light > 0.06 else pal["d"]
            votes = {}                                   # which face and strip, from a five-by-five sample
            for sy in range(ss):
                for sx in range(ss):
                    ddx, ddy = (x + (sx + 0.5) / ss - c0) / r, (y + (sy + 0.5) / ss - c0) / r
                    q = ddx * ddx + ddy * ddy
                    if q <= 1:
                        lab = label(turn((ddx, -ddy, math.sqrt(1 - q))))
                        votes[lab] = votes.get(lab, 0) + 1
            info[(x, y)] = (max(votes, key=votes.get), c)
            im.putpixel((x, y), c)
    seams = set()
    for (x, y), (lab, c) in info.items():
        for nxy in ((x + 1, y), (x, y + 1)):
            if nxy not in info or info[nxy][0] == lab:
                continue
            im.putpixel((x, y), pal["s"] if info[nxy][0][:2] != lab[:2] else mix(c, pal["s"], 0.6))
            seams.add((x, y))
    # The lace: down the middle of the front face's middle strip from its top, the slit laced across and across.
    mid = [xy for xy, (lab, c) in info.items() if lab == (2, True, 1) and xy not in seams]
    xs = sorted(set(x for x, y in mid))
    xc = xs[len(xs) // 2]
    top = min(y for x, y in mid if x == xc)
    for i, y in enumerate(range(top, top + 5)):
        im.putpixel((xc, y), pal["s"])
        if i % 2 == 0:
            im.putpixel((xc - 1, y), pal["w"])
            im.putpixel((xc + 1, y), pal["g"])
        else:
            im.putpixel((xc, y), pal["w"])
    return outline(im, pal["O"])


def norm(v):
    l = math.sqrt(sum(c * c for c in v))
    return tuple(c / l for c in v)


def dot(u, v):
    return sum(a * b for a, b in zip(u, v))


def football_sheet():
    """The ball's model (FootballRenderer): three boxes laid through one another, ten by eight by eight each way, drawn
    at four fifths. The two ends of each box are two of the ball's six faces, eight by eight: three strips of leather
    (two, a seam, two, a seam, two), each face's strips lying across its neighbours' (the x faces' run up and down, the
    y faces' run along z, the z faces' along x), so the ball turns as the old one did; one top face laced. The rest of
    each box shows only as the ball's rounded edges, a pixel wide, where the faces are sewn together."""
    im = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    lit, tan, dark, deep = hexc("b97a40"), hexc("a56a34"), hexc("7a4520"), hexc("5e3416")
    hi = hexc("cf9257")
    seam, stitch = hexc("6a3c19"), hexc("8d5a2e")
    lace, laceg, slit = hexc("f6efe0"), hexc("c8b796"), hexc("3a1f0b")
    grain = [(1, 1), (6, 2), (3, 6), (4, 3), (0, 5), (7, 4), (2, 0), (5, 7)]

    def face(x0, y0, across, laced=False):
        """One face: its strips across (a counts down the rows) or up and down (a counts along the columns)."""
        for y in range(8):
            for x in range(8):
                a, b = (y, x) if across else (x, y)
                c = tan
                if a in (0, 3, 6):
                    c = lit                              # each strip rounds up toward the light on one side
                if (x, y) in grain:
                    c = mix(c, hi, 0.5) if (x + y) % 2 else mix(c, dark, 0.35)
                if a in (2, 5):
                    c = stitch if b % 2 else seam        # the seams between the strips, stitched
                if x in (0, 7) or y in (0, 7):
                    c = mix(c, dark, 0.35)               # the face's edge, turning away
                im.putpixel((x0 + x, y0 + y), c)
        if laced:
            for i, b in enumerate(range(1, 7)):
                pos = [(2, lace if i % 2 == 0 else None), (3, slit), (4, slit), (5, laceg if i % 2 == 0 else None)]
                if i % 2 == 1:
                    pos = [(3, lace), (4, laceg)]
                for a, c in pos:
                    if c is None:
                        continue
                    im.putpixel((x0 + a, y0 + b) if not across else (x0 + b, y0 + a), c)

    def edges(x0, y0, w, h, ends_in_columns):
        """A face of which only its two ends show: the sewn edge between two faces."""
        for y in range(h):
            for x in range(w):
                end = (x in (0, w - 1)) if ends_in_columns else (y in (0, h - 1))
                c = deep if end and ((y if ends_in_columns else x) % 3 == 1) else dark
                im.putpixel((x0 + x, y0 + y), c)

    # Box A (10 x 8 x 8) at 0,0: x faces (west 0,8 and east 18,8) are faces, strips up and down; its y faces (8,0 and
    # 18,0) and z faces (8,8 and 26,8) show only their x ends.
    for (x, y) in ((8, 0), (18, 0), (8, 8), (26, 8)):
        edges(x, y, 10, 8, True)
    face(0, 8, across=False)
    face(18, 8, across=False)
    # Box B (8 x 10 x 8) at 0,16: y faces (8,16 and 16,16) are faces, strips along z (up and down the picture), one laced;
    # its four sides (0,24 8,24 16,24 24,24) show only their y ends.
    for x in (0, 8, 16, 24):
        edges(x, 24, 8, 10, False)
    face(8, 16, across=False, laced=True)
    face(16, 16, across=False)
    # Box C (8 x 8 x 10) at 0,34: z faces (north 10,44 and south 28,44) are faces, strips along x (the rows); its y
    # faces (10,34 and 18,34) show their z ends (rows), its x faces (0,44 and 18,44) their z ends (columns).
    edges(10, 34, 8, 10, False)
    edges(18, 34, 8, 10, False)
    edges(0, 44, 10, 8, True)
    edges(18, 44, 10, 8, True)
    face(10, 44, across=True)
    face(28, 44, across=True)
    return im


# ============================================================================ the lute

def lute_item():
    """The lute in the hand: its round-backed body (bottom left) with the soundboard's rose, the bridge and three
    strings running up its long neck to the pegbox, bent back at the top right with its pegs."""
    O, body, bodyd, bodyl = hexc("3b2110"), hexc("a5652f"), hexc("7a4720"), hexc("c98a4b")
    board, boardl, boardd = hexc("e2be80"), hexc("f3dba6"), hexc("c49a5c")
    rose, rosel, neck, neckl, string, peg, bridge = hexc("3e2210"), hexc("8a5a2b"), hexc("5c3418"), hexc("80502a"), hexc("f1ead6"), hexc("e0c27a"), hexc("2a170a")
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    # The body: an oval along the diagonal, the soundboard lit from the top left, the bowl's rim showing at its far side.
    cx, cy = 5.6, 10.3
    ux, uy = 0.7071, -0.7071                 # along the neck
    for y in range(16):
        for x in range(16):
            dx, dy = x - cx, y - cy
            along = dx * ux + dy * uy
            across = -dx * uy + dy * ux
            e = (along / 5.0) ** 2 + (across / 4.1) ** 2
            if e > 1.0:
                continue
            if across > 2.6 and e > 0.55:
                c = bodyd if across > 3.3 else body       # the round back showing below the board's edge
            elif e < 0.35 and across < -0.6:
                c = boardl
            elif across > 1.6:
                c = boardd
            else:
                c = board
            im.putpixel((x, y), c)
    # The neck: two pixels wide up the diagonal from the body to the pegbox.
    for i in range(0, 8):
        x, y = 8 + i, 7 - i
        if 0 <= x < 16 and 0 <= y < 16:
            im.putpixel((x, y), neckl)
            if y + 1 < 16:
                im.putpixel((x, y + 1), neck)
    # The pegbox, bent back, and its pegs.
    for (x, y) in ((14, 0), (15, 0), (15, 1)):
        im.putpixel((x, y), neck)
    for (x, y) in ((13, 0), (15, 2)):
        im.putpixel((x, y), peg)
    # The rose: the soundhole, a dark ring round a carved star.
    for (x, y) in ((5, 9), (6, 9), (5, 10), (6, 10), (4, 10), (6, 8), (7, 9), (5, 11)):
        im.putpixel((x, y), rose)
    im.putpixel((5, 10), rosel)
    # The bridge across the strings, low on the board.
    for (x, y) in ((2, 12), (3, 13)):
        im.putpixel((x, y), bridge)
    im.putpixel((3, 12), bridge)
    # The strings: from the bridge, over the rose, up the neck to the pegs.
    for i in range(0, 12):
        x, y = 3 + i, 12 - i
        if im.getpixel((x, y))[3] != 0 and (x, y) not in ((5, 10),):
            if im.getpixel((x, y)) not in (bridge,):
                im.putpixel((x, y), string)
    return outline(im, O)


# ============================================================================ the kite

def kite_shape(top=0, cross=4, foot=12, wide=6.0):
    """The kite's diamond, as {(x, y): panel}: the cross a third of the way down; panels 0-3 (top left, top right,
    bottom left, bottom right)."""
    cells = {}
    for y in range(top, foot + 1):
        if y <= cross:
            half = 0.6 + (y - top) / (cross - top) * (wide - 0.6)
        else:
            half = wide - (y - cross) / (foot - cross) * (wide - 0.6)
        for x in range(16):
            if abs(x - 7.5) <= half:
                left = x < 8
                upper = y < cross
                cells[(x, y)] = (0 if left else 1) if upper else (2 if left else 3)
    return cells


def kite_cloth_item():
    """The paper of the kite in the hand, in greys: the game tints it its dye's colour (layer 0)."""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    shades = [240, 218, 204, 176]
    cells = kite_shape(0, 4, 12, 6.2)
    for (x, y), p in cells.items():
        v = shades[p]
        im.putpixel((x, y), (v, v, v, 255))
    edge = [(x, y) for (x, y) in cells if any((x + dx, y + dy) not in cells for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))]
    for (x, y) in edge:
        im.putpixel((x, y), (110, 110, 110, 255))
    return im


def kite_frame_item():
    """Over the paper, as it is: the two sticks, and the tail of bows off its foot, curling away (layer 1)."""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    stick, stickd = hexc("8a5a2b"), hexc("5e3a18")
    for y in range(1, 12):
        im.putpixel((7, y), stick)
        im.putpixel((8, y), stickd)
    for x in range(2, 14):
        im.putpixel((x, 4), stick)
    string, bow, bowd, knot = hexc("5a5a5a"), hexc("fbf2dc"), hexc("d4c39d"), hexc("c0392b")
    for (x, y) in ((8, 13), (9, 14), (11, 15), (12, 14), (14, 13)):
        im.putpixel((x, y), string)
    for (bx, by) in ((10, 14), (13, 13)):
        im.putpixel((bx, by), knot)
        im.putpixel((bx - 1, by - 1), bow)
        im.putpixel((bx + 1, by - 1), bow)
        im.putpixel((bx - 1, by + 1), bowd)
        im.putpixel((bx + 1, by + 1), bowd)
    return im


def kite_entity_cloth():
    """The kite in the sky, in greys for its dye: four panels in four shades, a dark hem, the paper's grain."""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    cells = kite_shape(0, 5, 15, 7.4)
    shades = [246, 224, 208, 184]
    for (x, y), p in cells.items():
        v = shades[p] - (7 if (x * 7 + y * 3) % 5 == 0 else 0)
        im.putpixel((x, y), (v, v, v, 255))
    edge = [(x, y) for (x, y) in cells if any((x + dx, y + dy) not in cells for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))]
    for (x, y) in edge:
        im.putpixel((x, y), (92, 92, 92, 255))
    return im


def kite_entity_frame():
    """The sticks over the paper, and a little painted sun where they cross."""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    stick, stickd = hexc("8a5a2b"), hexc("5e3a18")
    for y in range(1, 15):
        im.putpixel((7, y), stick)
        im.putpixel((8, y), stickd)
    for x in range(1, 15):
        im.putpixel((x, 5), stick)
    sun, sunl, sund = hexc("f2c230"), hexc("f9dc6a"), hexc("d79e1c")
    for (x, y) in ((6, 3), (9, 3), (5, 5), (10, 5), (6, 7), (9, 7), (7, 2), (8, 8)):
        im.putpixel((x, y), sund)
    for (x, y) in ((6, 4), (7, 4), (8, 4), (9, 4), (6, 5), (9, 5), (6, 6), (7, 6), (8, 6), (9, 6)):
        im.putpixel((x, y), sun)
    im.putpixel((7, 5), sunl)
    im.putpixel((8, 5), sunl)
    return im


def kite_bow():
    rows = [
        "................",
        "................",
        "..OO........OO..",
        ".OhhO......OhlO.",
        ".OhhhO....OhllO.",
        ".OhhlhOOOOhllmO.",
        ".OhhllOkkOllmmO.",
        ".OhllmOkkOlmmmO.",
        ".OhllmOkkOmmmmO.",
        ".OhlmmOOOOmmmdO.",
        ".OlmmmO..OmmddO.",
        ".OlmmO....OmddO.",
        ".OlmO......OddO.",
        "..OO........OO..",
        "................",
        "................",
    ]
    pal = {"O": (70, 70, 70, 255), "h": (255, 255, 255, 255), "l": (232, 232, 232, 255), "m": (205, 205, 205, 255),
           "d": (170, 170, 170, 255), "k": (130, 130, 130, 255)}
    return grid(rows, pal)


def kite_string():
    im = Image.new("RGBA", (16, 16), (236, 230, 214, 255))
    for x in range(16):
        im.putpixel((x, 0), (214, 206, 186, 255))
    return im


# ============================================================================ the slate and chalk

def slate_item():
    pal = {
        "O": hexc("2b1a0c"),   # outline
        "w": hexc("8b5a2b"),   # frame
        "W": hexc("b07a43"),   # frame lit
        "v": hexc("5e3a18"),   # frame shade
        "s": hexc("3b4146"),   # slate
        "S": hexc("4a5258"),   # slate lit
        "z": hexc("2c3135"),   # slate shade
        "c": hexc("e8e6dc"),   # chalk writing
        "k": hexc("f6f4ee"),   # the chalk stick
        "K": hexc("cfcbbe"),   # the chalk stick's shade
    }
    rows = [
        "................",
        ".OOOOOOOOOOOOO..",
        ".OWWWWWWWWWWWvO.",
        ".OWSSSSSSSSSsvO.",
        ".OWSccScSccssvO.",
        ".OWSsscsscsszvO.",
        ".OWSsssssssszvO.",
        ".OWSccccsscszvO.",
        ".OWSssssscszzvO.",
        ".OWSsccsssszzvO.",
        ".OWsssssszzzzvO.",
        ".OWvvvvvvvvvvvO.",
        ".OOOOOOOOOOOOkO.",
        "............OkKO",
        ".............OKO",
        "..............O.",
    ]
    return grid(rows, pal)


# ============================================================================ the quilt

PATCHES = ["c8423a", "3f73c4", "5a9a4a", "e0b03a", "e07aa8", "4fa3b8", "c9572a", "7d5bb0"]
CREAM = "efe2c0"
FLOWERS = ["d0503f", "4f8fd6", "e0b03a", "c75a9a"]


def patchwork(w=16, h=16, size=4, seed=0, binding=False):
    """Patches as a quilter lays them: plain and patterned cloth (dots, a stripe, a check) every other one, and
    between them cream patches sewn with a little flower; no two coloured ones alike side by side; each patch lit
    along its top, sunk to the stitched seam at its foot and right."""
    im = Image.new("RGBA", (w, h), (0, 0, 0, 255))
    warm = (255, 248, 230, 255)
    for py in range(0, h, size):
        for px in range(0, w, size):
            row, col = py // size, px // size
            cream = (row + col + seed) % 2 == 0
            k = (row * 3 + col * 5 + seed * 3) // 2
            base = hexc(CREAM) if cream else hexc(PATCHES[k % len(PATCHES)])
            flower = hexc(FLOWERS[(row + col * 3 + seed) % len(FLOWERS)])
            kind = "flower" if cream else ("plain", "dots", "stripe", "check")[(row * 2 + col + seed) % 4]
            pale = mix(base, (255, 255, 255, 255), 0.45)
            m = size // 2
            for y in range(size):
                for x in range(size):
                    c = base
                    if kind == "flower" and (x, y) in ((m - 1, m - 1), (m, m), (m - 1, m), (m, m - 1)):
                        c = flower if (x, y) != (m, m) else hexc("f2c230")
                    elif kind == "dots" and x % 2 == 1 and y % 2 == 1:
                        c = pale
                    elif kind == "stripe" and x == m - 1:
                        c = pale
                    elif kind == "check" and (x + y) % 2 == 0:
                        c = mix(base, pale, 0.5)
                    if y == 0:
                        c = mix(c, warm, 0.2)
                    if x == size - 1 or y == size - 1:
                        c = scale(c, 0.72)                    # the stitched seam to the next
                    if px + x < w and py + y < h:
                        im.putpixel((px + x, py + y), c)
    if binding:
        bind, bindd = hexc("efe3c2"), hexc("c9b88f")
        for x in range(w):
            im.putpixel((x, 0), bind)
            im.putpixel((x, 1), bindd)
    return im


def quilt_edge():
    """The quilt's sides, hanging down the bed: the patches, a cream binding along the top and a darker hem at the foot."""
    im = patchwork(seed=3)
    bind, bindd, hem = hexc("efe3c2"), hexc("c9b88f"), hexc("6b4a2f")
    for x in range(16):
        im.putpixel((x, 0), bind)
        im.putpixel((x, 15), hem)
        if x % 2 == 0:
            im.putpixel((x, 14), bindd)
    return im


def quilt_item():
    """The quilt folded in the hand, as a quilt is laid away: its top seen from above, six big patches (red, blue and
    green plain cloth between cream ones sewn with little flowers), a cream binding along its folded edge, and in front
    the two folds beneath, each a roll of patches over its shadow, rounded off at their ends."""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    warm = (255, 248, 230, 255)
    red, cream, blue, yellow, green = "c8423a", "efe2c0", "3f73c4", "e0b03a", "5a9a4a"
    top = [(red, None), (cream, "d0503f"), (blue, None), (cream, "4f8fd6"), (green, None), (cream, "e0b03a")]
    folds = [[blue, yellow, red], [red, green, blue]]
    cols, rows = (1, 5, 10, 15), (2, 5, 9)
    k = 0
    for j in range(2):
        for i in range(3):
            x0, x1, y0, y1 = cols[i], cols[i + 1], rows[j], rows[j + 1]
            base, flower = hexc(top[k][0]), top[k][1]
            k += 1
            w, h = x1 - x0, y1 - y0
            for y in range(h):
                for x in range(w):
                    c = base
                    if flower and (x, y) in ((w // 2, h // 2 - 1), (w // 2 - 1, h // 2), (w // 2 + 1, h // 2), (w // 2, h // 2 + 1)):
                        c = hexc(flower)                   # the petals
                    elif flower and (x, y) == (w // 2, h // 2):
                        c = hexc("f2c230")                 # the flower's middle
                    if y == 0:
                        c = mix(c, warm, 0.25)             # each patch puffs up toward the light
                    if y == h - 1:
                        c = scale(c, 0.76)                 # and sinks to its seam
                    im.putpixel((x0 + x, y0 + y), c)
    y = rows[-1]
    for x in range(cols[0], cols[-1]):
        im.putpixel((x, y), hexc("f6ecd2") if x % 3 else hexc("d6c49a"))     # the binding, stitched
    for f, colours in enumerate(folds):
        yy = y + 1 + f * 2
        for x in range(cols[0], cols[-1]):
            base = hexc(colours[sum(1 for c in cols[1:-1] if c <= x)])
            im.putpixel((x, yy), mix(base, warm, 0.18))
            im.putpixel((x, yy + 1), scale(base, 0.58))
        for x in (cols[0], cols[-1] - 1):
            im.putpixel((x, yy + 1), (0, 0, 0, 0))                           # each fold rounds off
    for x in (cols[0], cols[-1] - 1):
        im.putpixel((x, rows[0]), (0, 0, 0, 0))
    return outline(im, hexc("2a1a0e"))


# ============================================================================ the draughts board

def board_top():
    """The board's face: eight by eight, two pixels a square: buff and green, each square lit at its top left."""
    im = Image.new("RGBA", (16, 16))
    buff, buffd = hexc("eadcb4"), hexc("d4c294")
    green, greend, greenl = hexc("2f6b3b"), hexc("24552e"), hexc("3b7f48")
    for Z in range(8):
        for X in range(8):
            dark = (X + Z) % 2 == 1
            for dy in range(2):
                for dx in range(2):
                    if dark:
                        c = greenl if (dx, dy) == (0, 0) else greend if (dx, dy) == (1, 1) else green
                    else:
                        c = buff if (dx, dy) != (1, 1) else buffd
                    im.putpixel((X * 2 + dx, Z * 2 + dy), c)
    return im


def board_side():
    """The board's edge: two pixels of polished walnut at the top of the picture (the rest unseen)."""
    im = Image.new("RGBA", (16, 16), hexc("5a3519"))
    for x in range(16):
        im.putpixel((x, 14), hexc("8a5a2b") if x % 5 else hexc("7a4d24"))
        im.putpixel((x, 15), hexc("4a2b12"))
        im.putpixel((x, 0), hexc("8a5a2b"))
        im.putpixel((x, 1), hexc("6b4220"))
    return im


def pieces():
    """The men: red's top and side, black's top and side, and the gold of a king's crown."""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))

    def fill(x0, y0, w, h, rows):
        for y in range(h):
            for x in range(w):
                im.putpixel((x0 + x, y0 + y), rows[y][x])

    rl, rm, rd, rh = hexc("e2604c"), hexc("c0392b"), hexc("8e2318"), hexc("f5907c")
    bl, bm, bd, bh = hexc("4a4a4a"), hexc("2c2c2c"), hexc("161616"), hexc("6e6e6e")
    gl, gm, gd = hexc("ffe17a"), hexc("f2c230"), hexc("b98a1e")
    fill(0, 0, 4, 4, [[rh, rl, rl, rm], [rl, rm, rm, rm], [rl, rm, rd, rm], [rm, rm, rm, rd]])
    fill(0, 4, 4, 2, [[rl, rl, rm, rm], [rd, rd, rd, rd]])
    fill(8, 0, 4, 4, [[bh, bl, bl, bm], [bl, bm, bm, bm], [bl, bm, bd, bm], [bm, bm, bm, bd]])
    fill(8, 4, 4, 2, [[bl, bl, bm, bm], [bd, bd, bd, bd]])
    fill(0, 8, 4, 4, [[gl, gm, gl, gm], [gm, gl, gm, gd], [gl, gm, gm, gd], [gm, gd, gd, gd]])
    return im


# ============================================================================ the paper lanterns

DYES = {
    "white": "f9fffe", "orange": "f9801d", "magenta": "c74ebd", "light_blue": "3ab3da", "yellow": "fed83d", "lime": "80c71f",
    "pink": "f38baa", "gray": "474f52", "light_gray": "9d9d97", "cyan": "169c9c", "purple": "8932b8", "blue": "3c44aa",
    "brown": "835432", "green": "5e7c16", "red": "b02e26", "black": "1d1d21",
}
WARM = (255, 236, 170, 255)


def lantern_tones(rgb):
    """A lantern's paper lit from inside: its colour warmed and brightened at the middle, darker at the ribs."""
    base = hexc(rgb)
    # a dark dye's paper still glows: lift it toward the warm light behind it
    lum = (0.3 * base[0] + 0.59 * base[1] + 0.11 * base[2]) / 255
    lift = 0.18 if lum > 0.35 else 0.38
    glow = mix(mix(base, WARM, 0.5 + lift / 2), (255, 255, 255, 255), 0.1)
    lit = mix(base, WARM, 0.25 + lift)
    mid = mix(base, WARM, 0.08 + lift / 2)
    rib = scale(mix(base, (40, 24, 10, 255), 0.3), 0.75)
    return glow, lit, mid, rib


def lantern_block(rgb):
    """A lantern's paper, the side of the block (the model takes x 4-12, y 3-13 of it): a soft warm glow at the middle
    where the light is, the paper's colour toward its edges, two ribs of bamboo across it."""
    glow, lit, mid, rib = lantern_tones(rgb)
    ribl = mix(rib, glow, 0.45)
    im = Image.new("RGBA", (16, 16), mid)
    for y in range(16):
        for x in range(16):
            e = ((x - 7.5) / 3.4) ** 2 + ((y - 7.5) / 4.8) ** 2
            c = glow if e < 0.28 else lit if e < 0.75 else mid
            if y in (6, 9):
                c = ribl if e < 0.4 else rib                       # the ribs, through the lit paper
            if y in (4, 11):
                c = scale(mid, 0.8)                                # the shade's shoulder, top and bottom
            if x in (4, 5) or x in (10, 11):
                c = scale(c, 0.9)                                  # turning away at the sides
            im.putpixel((x, y), c)
    return im


def lantern_item(rgb):
    """The lantern in the hand: a round shade on a little loop, a dark cap top and bottom, two ribs across, its light
    glowing warm through the middle, a tassel under it."""
    glow, lit, mid, rib = lantern_tones(rgb)
    ribl = mix(rib, glow, 0.45)
    cap, capd, string, tassel = hexc("5a3a1e"), hexc("3a2412"), hexc("b5a47e"), mix(hexc(rgb), hexc("c0392b"), 0.4)
    O = hexc("24160b")
    pal = {"g": glow, "l": lit, "m": mid, "r": rib, "q": ribl, "c": cap, "C": capd, "s": string, "t": tassel, "O": O,
           "d": scale(mid, 0.8)}
    rows = [
        "......OOOO......",
        "......OssO......",
        ".....OOOOOO.....",
        ".....OccccO.....",
        "...OOddddddOO...",
        "..OmmlllllllmO..",
        ".OmmllgggglllmO.",
        ".OrrrqqqqqqrrrO.",
        ".OmllgggggglllO.",
        ".OmllggggggllmO.",
        ".OrrrqqqqqqrrrO.",
        "..OmmllllllmmO..",
        "...OOddddddOO...",
        ".....OCCCCO.....",
        "......OttO......",
        "......OttO......",
    ]
    return grid(rows, pal)


def lantern_bits():
    cap = Image.new("RGBA", (16, 16), hexc("4a2e18"))
    for x in range(16):
        for y in range(16):
            if (x + y * 3) % 7 == 0:
                cap.putpixel((x, y), hexc("5c3a1e"))
            if x in (0, 15) or y in (0, 15):
                cap.putpixel((x, y), hexc("2c1a0c"))
    string = Image.new("RGBA", (16, 16), hexc("b5a47e"))
    for y in range(0, 16, 2):
        for x in range(16):
            string.putpixel((x, y), hexc("9a8a6a"))
    tassel = Image.new("RGBA", (16, 16), hexc("c0392b"))
    for x in range(0, 16, 2):
        for y in range(16):
            tassel.putpixel((x, y), hexc("9e2a1f"))
    for x in range(16):
        tassel.putpixel((x, 0), hexc("f2c230"))
        tassel.putpixel((x, 1), hexc("b98a1e"))
    return cap, string, tassel


# ============================================================================ the lanterns' models and states

def lantern_models():
    """The paper lantern's two block models (standing, hanging), a template each with its paper as a texture, a model
    and a state for each colour that fill it in, and its item's model."""
    models = os.path.join(ASSETS, "models")

    def paper_face(uv):
        return {"texture": "#paper", "uv": uv}

    def lantern(lift, hanging):
        y = lift
        glow = {"block_light": 15, "sky_light": 15}
        els = [
            {"from": [5, y + 1, 5], "to": [11, y + 9, 11], "neoforge_data": glow,
             "faces": {d: paper_face([5, 4, 11, 12]) for d in ("north", "south", "east", "west")} |
                      {"up": paper_face([5, 5, 11, 11]), "down": paper_face([5, 5, 11, 11])}},
            {"from": [4.5, y + 2, 5.5], "to": [11.5, y + 8, 10.5], "neoforge_data": glow,
             "faces": {"north": paper_face([4.5, 5, 11.5, 11]), "south": paper_face([4.5, 5, 11.5, 11]),
                       "east": paper_face([5.5, 5, 10.5, 11]), "west": paper_face([5.5, 5, 10.5, 11]),
                       "up": paper_face([4.5, 5.5, 11.5, 10.5]), "down": paper_face([4.5, 5.5, 11.5, 10.5])}},
            {"from": [5.5, y + 2, 4.5], "to": [10.5, y + 8, 11.5], "neoforge_data": glow,
             "faces": {"north": paper_face([5.5, 5, 10.5, 11]), "south": paper_face([5.5, 5, 10.5, 11]),
                       "east": paper_face([4.5, 5, 11.5, 11]), "west": paper_face([4.5, 5, 11.5, 11]),
                       "up": paper_face([5.5, 4.5, 10.5, 11.5]), "down": paper_face([5.5, 4.5, 10.5, 11.5])}},
            {"from": [6, y + 9, 6], "to": [10, y + 10, 10],
             "faces": {d: {"texture": "#cap", "uv": [6, 6, 10, 10] if d in ("up", "down") else [6, 0, 10, 1]}
                       for d in ("north", "south", "east", "west", "up", "down")}},
            {"from": [6, y, 6], "to": [10, y + 1, 10],
             "faces": {d: {"texture": "#cap", "uv": [6, 6, 10, 10] if d in ("up", "down") else [6, 15, 10, 16]}
                       for d in ("north", "south", "east", "west", "up", "down")}},
        ]
        if hanging:
            els.append({"from": [7.5, y + 10, 7.5], "to": [8.5, 16, 8.5],
                        "faces": {d: {"texture": "#string", "uv": [7, 0, 8, 16 - (y + 10)]} for d in ("north", "south", "east", "west")}})
            els.append({"from": [7.5, 0.5, 7.5], "to": [8.5, y, 8.5],
                        "faces": {d: {"texture": "#tassel", "uv": [7, 0, 8, y - 0.5]} for d in ("north", "south", "east", "west")}
                        | {"down": {"texture": "#tassel", "uv": [7, 7, 8, 8]}}})
        else:
            # a little loop for the hand, on the cap
            els.append({"from": [7.5, y + 10, 7.5], "to": [8.5, y + 11.5, 8.5],
                        "faces": {d: {"texture": "#string", "uv": [7, 0, 8, 1.5]} for d in ("north", "south", "east", "west", "up")}})
        return {
            "parent": "minecraft:block/block",
            "ambientocclusion": False,
            "textures": {"particle": "#paper", "cap": "mc_assistant:block/paper_lantern_cap",
                         "string": "mc_assistant:block/paper_lantern_string", "tassel": "mc_assistant:block/paper_lantern_tassel"},
            "elements": els,
        }

    write_json(os.path.join(models, "block/template_paper_lantern.json"), lantern(0, False))
    write_json(os.path.join(models, "block/template_paper_lantern_hanging.json"), lantern(2, True))
    for colour in DYES:
        name = colour + "_paper_lantern"
        write_json(os.path.join(models, "block", name + ".json"),
                   {"parent": "mc_assistant:block/template_paper_lantern", "textures": {"paper": "mc_assistant:block/" + name}})
        write_json(os.path.join(models, "block", name + "_hanging.json"),
                   {"parent": "mc_assistant:block/template_paper_lantern_hanging", "textures": {"paper": "mc_assistant:block/" + name}})
        write_json(os.path.join(models, "item", name + ".json"),
                   {"parent": "minecraft:item/generated", "textures": {"layer0": "mc_assistant:item/" + name}})
        write_json(os.path.join(ASSETS, "blockstates", name + ".json"),
                   {"variants": {"hanging=false": {"model": "mc_assistant:block/" + name},
                                 "hanging=true": {"model": "mc_assistant:block/" + name + "_hanging"}}})


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def main():
    written = []
    written.append(save(football_item(), "item", "leather_football.png"))
    written.append(save(football_sheet(), "entity", "football.png"))
    written.append(save(lute_item(), "item", "lute.png"))
    written.append(save(kite_cloth_item(), "item", "kite.png"))
    written.append(save(kite_frame_item(), "item", "kite_frame.png"))
    written.append(save(kite_entity_cloth(), "entity", "kite_cloth.png"))
    written.append(save(kite_entity_frame(), "entity", "kite_frame.png"))
    written.append(save(kite_bow(), "entity", "kite_bow.png"))
    written.append(save(kite_string(), "entity", "kite_string.png"))
    written.append(save(slate_item(), "item", "slate_and_chalk.png"))
    written.append(save(patchwork(), "block", "patchwork_quilt.png"))
    written.append(save(quilt_edge(), "block", "patchwork_quilt_edge.png"))
    written.append(save(quilt_item(), "item", "patchwork_quilt.png"))
    written.append(save(board_top(), "block", "draughts_board_top.png"))
    written.append(save(board_side(), "block", "draughts_board_side.png"))
    written.append(save(pieces(), "block", "draughts_pieces.png"))
    written.append(save(pieces(), "entity", "draughts_pieces.png"))
    for colour, rgb in DYES.items():
        written.append(save(lantern_block(rgb), "block", colour + "_paper_lantern.png"))
        written.append(save(lantern_item(rgb), "item", colour + "_paper_lantern.png"))
    cap, string, tassel = lantern_bits()
    written.append(save(cap, "block", "paper_lantern_cap.png"))
    written.append(save(string, "block", "paper_lantern_string.png"))
    written.append(save(tassel, "block", "paper_lantern_tassel.png"))
    lantern_models()
    for w in written:
        print(os.path.relpath(w, ROOT))


if __name__ == "__main__":
    main()
