#!/usr/bin/env python3
"""
The village's building drawings, read and drawn.

Every building a village raises is drawn in a text file under
src/main/resources/data/mc_assistant/blueprints/ — one layer of the building to a
block of rows, back of the building at the top, front (the door) at the bottom,
one character to a block. The game reads the same files (Blueprints.java); this
reads them too, and draws each building in isometric so a design can be looked
at before the game ever runs.

    blueprints.py render <out_dir> [names...]   one picture per building
    blueprints.py sheet <out.png> [names...]    every building on one sheet
    blueprints.py check                          parse every drawing, report counts

The legend is the game's (Blueprints.LEGEND); keep the two in step.
"""
import math
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
DIR = os.path.join(HERE, "..", "src", "main", "resources", "data", "mc_assistant", "blueprints")

# char -> (material, shape, direction) ; direction in plan terms: B(ack) F(ront) L(eft) R(ight) U(p)
LEGEND = {
    "#": ("generic", "cube", None),
    "F": ("cobble", "cube", None),        # FOUNDATION
    "w": ("cobble", "cube", None),        # WALL_LOW
    "S": ("stonebrick", "cube", None),    # MASONRY
    "f": ("plank", "cube", None),         # FLOOR
    "W": ("plank", "cube", None),         # WALL
    "R": ("roof", "cube", None),          # ROOF_BLOCK
    "L": ("log", "cube", "U"),            # POST
    "-": ("log", "cube", "R"),            # BEAM across
    "|": ("log", "cube", "B"),            # BEAM along
    "^": ("roof", "stair", "B"), "v": ("roof", "stair", "F"),
    "<": ("roof", "stair", "L"), ">": ("roof", "stair", "R"),
    "n": ("roof", "stairtop", "B"), "u": ("roof", "stairtop", "F"),
    "{": ("roof", "stairtop", "L"), "}": ("roof", "stairtop", "R"),
    "_": ("roof", "slab", None), "=": ("roof", "slabtop", None),
    "s": ("cobble", "slab", None),        # stone slab
    "k": ("cobble", "stair", "B"),        # stone step, rising into the building
    "G": ("glass", "pane", None),
    "O": ("glass", "cube", None),
    "D": ("door", "door", "B"),
    "Y": ("door", "door", "R"),           # a door in a front-to-back wall (the flats' landings)
    "P": ("plank", "fence", None),
    "g": ("plank", "gate", None),
    "T": ("table", "cube", None),
    "U": ("furnace", "cube", "F"),
    "C": ("chest", "chest", "F"), "c": ("chest", "chest", "B"),
    "(": ("chest", "chest", "L"), ")": ("chest", "chest", "R"),
    "B": ("bed", "bed", "B"), "b": ("bed", "bed", "F"),
    "[": ("bed", "bed", "L"), "]": ("bed", "bed", "R"),
    "t": ("torch", "torch", None),
    "l": ("lantern", "lantern", None), "j": ("lantern", "hanging", None),
    "H": ("ladder", "ladder", "F"),
    "o": ("obsidian", "cube", None),
    "~": ("water", "water", None),
    "y": ("hay", "cube", None),
    "Q": ("barrel", "cube", None),
    "*": ("flower", "flower", None),
    "d": ("grass", "cube", None),
    "%": ("leaves", "cube", None),
    "a": ("anvil", "anvil", None),
    "e": ("bell", "bell", None),
    "p": ("path", "path", None),
    "K": ("bookshelf", "cube", None),
    "E": ("enchanting", "cube", None),
    "r": ("lectern", "cube", "F"),
    "I": ("brewing", "torch", None),
    "M": ("furnace", "cube", "F"),
    "N": ("loom", "cube", "F"),
    "V": ("grindstone", "cube", "F"),
    "h": ("campfire", "slab", None),
    "m": ("noteblock", "cube", None),
    "Z": ("brick", "cube", None),
    "z": ("brick", "slab", None),
    "q": ("quartz", "cube", None),
    "X": ("carpet", "carpet", None),
    "!": ("banner", "banner", "F"),
    "&": ("cauldron", "cube", None),
    "$": ("storehouse", "cube", None),    # a storehouse unit
}

COLOURS = {
    "generic": (128, 128, 128),
    "cobble": (122, 122, 122),
    "stonebrick": (138, 136, 132),
    "plank": (164, 128, 78),
    "roof": (96, 66, 42),
    "log": (102, 80, 50),
    "logtop": (176, 140, 90),
    "glass": (190, 225, 240),
    "door": (140, 104, 60),
    "table": (150, 100, 60),
    "furnace": (100, 100, 100),
    "chest": (170, 120, 50),
    "storehouse": (150, 109, 60),
    "bed": (180, 40, 40),
    "pillow": (235, 235, 235),
    "torch": (255, 200, 60),
    "lantern": (255, 190, 80),
    "ladder": (150, 115, 70),
    "obsidian": (40, 20, 60),
    "water": (60, 90, 220),
    "hay": (210, 180, 60),
    "barrel": (130, 95, 55),
    "flower": (220, 50, 60),
    "grass": (95, 160, 65),
    "leaves": (60, 120, 40),
    "anvil": (70, 70, 72),
    "bell": (230, 190, 40),
    "path": (150, 125, 80),
    "bookshelf": (120, 80, 50),
    "enchanting": (60, 30, 90),
    "lectern": (150, 110, 70),
    "brewing": (120, 110, 100),
    "loom": (190, 160, 120),
    "grindstone": (140, 140, 140),
    "campfire": (230, 120, 40),
    "noteblock": (110, 70, 45),
    "brick": (150, 75, 60),
    "quartz": (235, 230, 222),
    "carpet": (170, 40, 40),
    "banner": (200, 40, 40),
    "cauldron": (60, 60, 64),
    "gold": (240, 200, 60),
    "ground": (92, 140, 60),
}


def parse(path):
    """-> (name, cells) where cells is a list of (dx, h, dz, char).

    Rows run back to front; the middle row is dz=0 and the middle column dx=0."""
    name = os.path.splitext(os.path.basename(path))[0]
    cells = []
    layer = None
    rows = []

    def flush():
        if layer is None or not rows:
            return
        depth = len(rows)
        width = max(len(r) for r in rows)
        for i, row in enumerate(rows):
            dz = (depth - 1) // 2 - i
            for j, ch in enumerate(row):
                if ch in ". ":
                    continue
                if ch not in LEGEND:
                    raise ValueError("%s layer %s: unknown '%s'" % (name, layer, ch))
                dx = j - (width - 1) // 2
                cells.append((dx, layer, dz, ch))

    with open(path) as fh:
        for raw in fh:
            line = raw.rstrip("\n")
            if line.startswith("#") or not line.strip():
                continue
            if line.startswith("name "):
                name = line.split()[1]
                continue
            if line.startswith("layer "):
                flush()
                layer = int(line.split()[1])
                rows = []
                continue
            if line.split()[0] in ("half", "key", "lot"):
                continue
            rows.append(line)
    flush()
    return name, cells


# ----------------------------------------------------------------- drawing
N = 4  # sub-voxels a block


def voxels(cells):
    """-> dict (x, y, z) sub-voxel -> material, in sub-voxel units. x = dx, z = -dz (front toward viewer)."""
    out = {}

    def put(bx, by, bz, x0, x1, y0, y1, z0, z1, mat):
        for x in range(x0, x1):
            for y in range(y0, y1):
                for z in range(z0, z1):
                    out[(bx * N + x, by * N + y, bz * N + z)] = mat

    for dx, h, dz, ch in cells:
        mat, shape, d = LEGEND[ch]
        bx, by, bz = dx, h, -dz
        # plan direction -> sub-voxel half: B = toward back = -z here; F = +z; L = -x; R = +x
        if shape == "cube":
            if mat == "log" and d == "U":
                put(bx, by, bz, 0, N, 0, N, 0, N, "log")
            else:
                put(bx, by, bz, 0, N, 0, N, 0, N, mat)
        elif shape in ("stair", "stairtop"):
            lo, hi = (0, N // 2) if shape == "stair" else (N // 2, N)
            ulo, uhi = (N // 2, N) if shape == "stair" else (0, N // 2)
            put(bx, by, bz, 0, N, lo, hi, 0, N, mat)
            if d == "B":
                put(bx, by, bz, 0, N, ulo, uhi, 0, N // 2, mat)
            elif d == "F":
                put(bx, by, bz, 0, N, ulo, uhi, N // 2, N, mat)
            elif d == "L":
                put(bx, by, bz, 0, N // 2, ulo, uhi, 0, N, mat)
            else:
                put(bx, by, bz, N // 2, N, ulo, uhi, 0, N, mat)
        elif shape == "slab":
            put(bx, by, bz, 0, N, 0, N // 2, 0, N, mat)
        elif shape == "slabtop":
            put(bx, by, bz, 0, N, N // 2, N, 0, N, mat)
        elif shape == "pane":
            put(bx, by, bz, 0, N, 0, N, 1, 3, "glass")
        elif shape in ("fence", "gate"):
            put(bx, by, bz, 1, 3, 0, N, 1, 3, "plank" if mat == "plank" else mat)
        elif shape == "door":
            put(bx, by, bz, 0, N, 0, N, 1, 2, "door")
            put(bx, by + 1, bz, 0, N, 0, N, 1, 2, "door")
        elif shape == "chest":
            put(bx, by, bz, 0, N, 0, N - 1, 0, N, "chest")
        elif shape == "bed":
            hx, hz = {"B": (0, -1), "F": (0, 1), "L": (-1, 0), "R": (1, 0)}[d]
            put(bx, by, bz, 0, N, 0, N // 2, 0, N, "bed")
            put(bx + hx, by, bz + hz, 0, N, 0, N // 2, 0, N, "pillow")
        elif shape == "torch":
            put(bx, by, bz, 1, 3, 0, 3, 1, 3, "torch")
        elif shape == "lantern":
            put(bx, by, bz, 1, 3, 0, 2, 1, 3, "lantern")
        elif shape == "hanging":
            put(bx, by, bz, 1, 3, 1, 3, 1, 3, "lantern")
        elif shape == "ladder":
            put(bx, by, bz, 0, N, 0, N, 3 if d == "F" else 0, 4 if d == "F" else 1, "ladder")
        elif shape == "water":
            put(bx, by, bz, 0, N, 0, N - 1, 0, N, "water")
        elif shape == "flower":
            put(bx, by, bz, 1, 3, 0, 2, 1, 3, "flower")
        elif shape == "anvil":
            put(bx, by, bz, 0, N, 2, N, 1, 3, "anvil")
            put(bx, by, bz, 1, 3, 0, 2, 1, 3, "anvil")
        elif shape == "bell":
            put(bx, by, bz, 1, 3, 1, N, 1, 3, "bell")
        elif shape == "path":
            put(bx, by, bz, 0, N, 0, N - 1, 0, N, "path")
        elif shape == "carpet":
            put(bx, by, bz, 0, N, 0, 1, 0, N, "carpet")
        elif shape == "banner":
            put(bx, by, bz, 1, 3, 0, N, 3, 4, "banner")
        else:
            put(bx, by, bz, 0, N, 0, N, 0, N, mat)
    return out


def shade(rgb, k):
    return tuple(max(0, min(255, int(c * k))) for c in rgb)


def tint(mat, x, y, z, face):
    base = COLOURS.get(mat, (255, 0, 255))
    h = (x * 73856093 ^ y * 19349663 ^ z * 83492791) & 0xff
    k = 1.0
    if mat in ("cobble", "stonebrick", "generic", "path"):
        k = 0.85 + (h % 30) / 100.0
        if mat == "stonebrick" and (y % N == 0 or ((x + (y // N) * 2) % N == 0 and face != "top")):
            k *= 0.8
    elif mat in ("plank", "roof", "door", "barrel", "ladder", "table"):
        k = 0.92 + (h % 10) / 100.0
        if y % 2 == 0 and face != "top":
            k *= 0.93
        if face == "top" and x % N == 0:
            k *= 0.9
    elif mat == "log":
        k = 0.85 + (h % 15) / 100.0
        if face == "top":
            base = COLOURS["logtop"]
    elif mat in ("grass", "leaves"):
        k = 0.85 + (h % 25) / 100.0
    elif mat == "glass":
        k = 1.0 if (x + y) % 3 else 0.9
    elif mat == "brick":
        k = 0.9 + (h % 12) / 100.0
        if y % 2 == 0:
            k *= 0.8
    return shade(base, k)


def draw(cells, scale=7, ground=True, label=None):
    vox = voxels(cells)
    if not vox:
        return Image.new("RGB", (64, 64), (200, 220, 240))
    xs = [v[0] for v in vox]
    zs = [v[2] for v in vox]
    if ground:
        x0, x1 = min(xs) // N - 1, max(xs) // N + 1
        z0, z1 = min(zs) // N - 1, max(zs) // N + 1
        # the ground: the top of the block layer under the building, where the building is not
        for bx in range(x0, x1 + 1):
            for bz in range(z0, z1 + 1):
                for x in range(N):
                    for z in range(N):
                        for y in range(-N, 0):
                            vox.setdefault((bx * N + x, y, bz * N + z), "ground")
    # iso: screen x = (x - z) * a, screen y = (x + z) * b - y * c
    a, b, c = scale * math.cos(math.radians(30)), scale * 0.5, scale
    pts = []
    for (x, y, z) in vox:
        pts.append(((x - z) * a, (x + z) * b - y * c))
    minx = min(p[0] for p in pts) - 2 * a
    maxx = max(p[0] for p in pts) + 2 * a
    miny = min(p[1] for p in pts) - 2 * c
    maxy = max(p[1] for p in pts) + 3 * c
    W, H = int(maxx - minx) + 20, int(maxy - miny) + 20 + (24 if label else 0)
    img = Image.new("RGB", (W, H), (205, 225, 245))
    d = ImageDraw.Draw(img)

    def P(x, y, z):
        return ((x - z) * a - minx + 10, (x + z) * b - y * c - miny + 10)

    # painter: far (small x + z) first, then low y first
    for (x, y, z) in sorted(vox, key=lambda v: (v[0] + v[2], v[1], v[0])):
        mat = vox[(x, y, z)]
        if (x, y + 1, z) not in vox or vox.get((x, y + 1, z)) in ("glass", "water") and mat not in ("glass", "water"):
            top = [P(x, y + 1, z), P(x + 1, y + 1, z), P(x + 1, y + 1, z + 1), P(x, y + 1, z + 1)]
            d.polygon(top, fill=tint(mat, x, y, z, "top"))
        if (x + 1, y, z) not in vox or vox.get((x + 1, y, z)) in ("glass", "water") and mat not in ("glass", "water"):
            right = [P(x + 1, y, z), P(x + 1, y + 1, z), P(x + 1, y + 1, z + 1), P(x + 1, y, z + 1)]
            d.polygon(right, fill=shade(tint(mat, x, y, z, "side"), 0.7))
        if (x, y, z + 1) not in vox or vox.get((x, y, z + 1)) in ("glass", "water") and mat not in ("glass", "water"):
            front = [P(x, y, z + 1), P(x + 1, y, z + 1), P(x + 1, y + 1, z + 1), P(x, y + 1, z + 1)]
            d.polygon(front, fill=shade(tint(mat, x, y, z, "side"), 0.85))
    if label:
        d.text((10, H - 22), label, fill=(30, 30, 30))
    return img


def counts(cells):
    out = {}
    for _, _, _, ch in cells:
        out[ch] = out.get(ch, 0) + 1
    return out


def names(argv):
    files = sorted(f for f in os.listdir(DIR) if f.endswith(".txt"))
    if argv:
        files = [f for f in files if os.path.splitext(f)[0] in argv]
    return [os.path.join(DIR, f) for f in files]


def main(argv):
    if not argv or argv[0] == "check":
        for path in names(argv[1:]):
            name, cells = parse(path)
            c = counts(cells)
            print("%-11s %4d cells  %s" % (name, len(cells), " ".join("%s%d" % (k, v) for k, v in sorted(c.items()))))
        return
    if argv[0] == "render":
        out = argv[1]
        os.makedirs(out, exist_ok=True)
        for path in names(argv[2:]):
            name, cells = parse(path)
            draw(cells, label=name).save(os.path.join(out, name + ".png"))
            print("drew", name)
        return
    if argv[0] == "sheet":
        imgs = [draw(parse(p)[1], scale=5, label=parse(p)[0]) for p in names(argv[2:])]
        cols = 4
        w = max(i.width for i in imgs)
        h = max(i.height for i in imgs)
        rows = (len(imgs) + cols - 1) // cols
        sheet = Image.new("RGB", (cols * w, rows * h), (205, 225, 245))
        for k, im in enumerate(imgs):
            sheet.paste(im, ((k % cols) * w + (w - im.width) // 2, (k // cols) * h + (h - im.height)))
        sheet.save(argv[1])
        print("sheet of", len(imgs))


if __name__ == "__main__":
    main(sys.argv[1:])
