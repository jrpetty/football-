#!/usr/bin/env python3
"""
Draws the Village Storehouse: the loose storehouse unit (a crate), and the joined
storehouse's three big faces (side, front with its door, top), each 48x48 cut into
nine 16x16 tiles, one per unit, so 27 units read as one building. Also writes the
blockstate (every way the cube can face, every unit in it) and the block models.

    python3 tools/storehouse_art.py        (from mc-assistant-neoforge/)
"""
import json, os, random
from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "assets", "mc_assistant")
TEX = os.path.join(ROOT, "textures", "block", "storehouse")
MODELS = os.path.join(ROOT, "models", "block", "storehouse")
os.makedirs(TEX, exist_ok=True)
os.makedirs(MODELS, exist_ok=True)

PLANK = (150, 109, 60); PLANK_D = (112, 79, 42); PLANK_L = (172, 128, 74); SEAM = (86, 60, 31)
FRAME = (63, 41, 20); FRAME_L = (88, 59, 29); FRAME_D = (44, 28, 13)
IRON = (150, 150, 156); IRON_D = (86, 86, 94); IRON_L = (206, 206, 212)
GOLD = (226, 184, 58); GOLD_D = (150, 110, 28); GEM = (46, 196, 96); GEM_D = (20, 118, 56); GEM_L = (150, 240, 180)
DOOR = (92, 62, 32); DOOR_D = (64, 42, 20); INK = (52, 34, 16); SIGN = (196, 152, 92)


def planks(img, x0, y0, w, h, seed, across=True, board=4):
    rnd = random.Random(seed)
    px = img.load()
    for y in range(h):
        for x in range(w):
            k = (y if across else x)
            c = PLANK
            r = rnd.random()
            if r < 0.10: c = PLANK_D
            elif r < 0.18: c = PLANK_L
            if k % board == board - 1: c = SEAM
            # a nail or a knot here and there
            px[x0 + x, y0 + y] = c
    # end joints of the boards, staggered
    for b in range(0, h if across else w, board):
        j = rnd.randrange(6, 30) if (across and w > 16) or (not across and h > 16) else None
        if j is None: continue
        for t in range(board - 1):
            if across and j < w: px[x0 + j, y0 + b + t] = SEAM
            if not across and j < h: px[x0 + b + t, y0 + j] = SEAM


def rect(img, x0, y0, x1, y1, c):
    px = img.load()
    for y in range(y0, y1):
        for x in range(x0, x1):
            if 0 <= x < img.width and 0 <= y < img.height:
                px[x, y] = c


def frame(img, t):
    w, h = img.size
    rect(img, 0, 0, w, t, FRAME); rect(img, 0, h - t, w, h, FRAME)
    rect(img, 0, 0, t, h, FRAME); rect(img, w - t, 0, w, h, FRAME)
    rect(img, 0, 0, w, 1, FRAME_L); rect(img, 0, 0, 1, h, FRAME_L)
    rect(img, 0, h - 1, w, h, FRAME_D); rect(img, w - 1, 0, w, h, FRAME_D)
    rect(img, t, t, w - t, t + 1, FRAME_D); rect(img, t, t, t + 1, h - t, FRAME_D)


def brace(img, t, x0, y0, x1, y1):
    """A diagonal beam X from corner to corner of the box (symmetric both ways)."""
    px = img.load()
    w, h = x1 - x0, y1 - y0
    for i in range(max(w, h)):
        fx = x0 + i * w / max(w, h)
        fy = y0 + i * h / max(w, h)
        for d in range(-(t // 2), t - t // 2):
            for (x, y) in ((int(fx) + d, int(fy)), (int(x1 - 1 - (fx - x0)) + d, int(fy))):
                if x0 <= x < x1 and y0 <= y < y1:
                    px[x, y] = FRAME if d != -(t // 2) else FRAME_L


def corners(img, t, size):
    w, h = img.size
    px = img.load()
    for (cx, cy, sx, sy) in ((0, 0, 1, 1), (w - 1, 0, -1, 1), (0, h - 1, 1, -1), (w - 1, h - 1, -1, -1)):
        for i in range(size):
            for j in range(t):
                for (x, y) in ((cx + sx * i, cy + sy * j), (cx + sx * j, cy + sy * i)):
                    px[x, y] = IRON if (i + j) % 5 else IRON_D
        for (x, y) in ((cx + sx * 1, cy + sy * 1), (cx + sx * (size - 2), cy + sy * 1), (cx + sx * 1, cy + sy * (size - 2))):
            px[x, y] = IRON_L


def emblem(img, cx, cy, r):
    """A gold ring with an emerald in it: the village's mark."""
    px = img.load()
    for y in range(cy - r, cy + r + 1):
        for x in range(cx - r, cx + r + 1):
            d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
            if r - 1.6 <= d <= r + 0.4:
                px[x, y] = GOLD if (x + y) % 3 else GOLD_D
            elif d < r - 1.6:
                px[x, y] = FRAME_D
    g = r - 3
    for y in range(cy - g, cy + g + 1):
        for x in range(cx - g, cx + g + 1):
            if abs(x - cx) + abs(y - cy) <= g:
                px[x, y] = GEM if (x - cx) + (y - cy) > -2 else GEM_L
                if abs(x - cx) + abs(y - cy) == g: px[x, y] = GEM_D


GLYPHS = {  # 3x5 letters for the sign
    "S": ["111", "100", "111", "001", "111"], "T": ["111", "010", "010", "010", "010"],
    "O": ["111", "101", "101", "101", "111"], "R": ["110", "101", "110", "101", "101"],
    "E": ["111", "100", "110", "100", "111"],
}


def sign(img, x0, y0, w, h, word):
    rect(img, x0, y0, x0 + w, y0 + h, SIGN)
    rect(img, x0, y0, x0 + w, y0 + 1, PLANK_L); rect(img, x0, y0 + h - 1, x0 + w, y0 + h, PLANK_D)
    rect(img, x0, y0, x0 + 1, y0 + h, PLANK_D); rect(img, x0 + w - 1, y0, x0 + w, y0 + h, PLANK_D)
    px = img.load()
    total = len(word) * 4 - 1
    sx = x0 + (w - total) // 2
    sy = y0 + (h - 5) // 2
    for i, ch in enumerate(word):
        for yy, row in enumerate(GLYPHS[ch]):
            for xx, bit in enumerate(row):
                if bit == "1": px[sx + i * 4 + xx, sy + yy] = INK


def door(img, x0, y0, x1, y1):
    rect(img, x0 - 1, y0 - 1, x1 + 1, y1, FRAME_D)
    mid = (x0 + x1) // 2
    for (a, b) in ((x0, mid), (mid, x1)):
        rect(img, a, y0, b, y1, DOOR)
        for x in range(a, b):
            if (x - a) % 4 == 3: rect(img, x, y0, x + 1, y1, DOOR_D)
        rect(img, a, y0 + 3, b, y0 + 5, IRON_D)            # hinges, top and bottom
        rect(img, a, y1 - 6, b, y1 - 4, IRON_D)
    rect(img, mid - 1, y0, mid + 1, y1, FRAME_D)
    rect(img, mid - 3, (y0 + y1) // 2, mid - 1, (y0 + y1) // 2 + 2, GOLD)     # handles
    rect(img, mid + 1, (y0 + y1) // 2, mid + 3, (y0 + y1) // 2 + 2, GOLD)


def side_face(seed):
    img = Image.new("RGB", (48, 48))
    planks(img, 0, 0, 48, 48, seed)
    brace(img, 3, 3, 3, 45, 45)
    frame(img, 3)
    corners(img, 2, 7)
    emblem(img, 24, 24, 7)
    return img


def front_face():
    img = Image.new("RGB", (48, 48))
    planks(img, 0, 0, 48, 48, 7)
    frame(img, 3)
    corners(img, 2, 7)
    sign(img, 11, 7, 26, 9, "STORE")
    door(img, 16, 22, 32, 45)
    return img


def top_face():
    img = Image.new("RGB", (48, 48))
    planks(img, 0, 0, 48, 48, 11, across=False)
    frame(img, 3)
    corners(img, 2, 7)
    rect(img, 17, 17, 31, 31, FRAME)                      # a hatch in the roof
    planks(img, 19, 19, 10, 10, 12, across=True, board=3)
    rect(img, 22, 23, 26, 25, IRON)
    return img


def bottom_face():
    img = Image.new("RGB", (16, 16))
    planks(img, 0, 0, 16, 16, 13)
    frame(img, 1)
    return img


def unit_face(seed, top=False):
    img = Image.new("RGB", (16, 16))
    planks(img, 0, 0, 16, 16, seed, across=not top)
    if not top: brace(img, 2, 2, 2, 14, 14)
    frame(img, 2)
    corners(img, 1, 4)
    if top: emblem(img, 8, 8, 4)
    return img


def tiles(img, name):
    for r in range(3):
        for c in range(3):
            img.crop((c * 16, r * 16, c * 16 + 16, r * 16 + 16)).save(os.path.join(TEX, "%s_%d%d.png" % (name, c, r)))


tiles(side_face(3), "side")
tiles(front_face(), "front")
tiles(top_face(), "top")
bottom_face().save(os.path.join(TEX, "bottom.png"))
unit_face(21).save(os.path.join(TEX, "unit_side.png"))
unit_face(22, top=True).save(os.path.join(TEX, "unit_top.png"))
# a big picture of each face, for looking at
sheet = Image.new("RGB", (48 * 3 + 8, 48))
sheet.paste(side_face(3), (0, 0)); sheet.paste(front_face(), (52, 0)); sheet.paste(top_face(), (104, 0))
sheet = sheet.resize((sheet.width * 4, sheet.height * 4), Image.NEAREST)
sheet.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "storehouse_faces.png"))

# ---- models and the blockstate
T = "mc_assistant:block/storehouse/"


def write_model(name, faces):
    els = {"from": [0, 0, 0], "to": [16, 16, 16], "faces": {}}
    tex = {"particle": T + "unit_side"}
    for i, (d, t) in enumerate(faces.items()):
        tex[d] = T + t
        els["faces"][d] = {"texture": "#" + d, "cullface": d}
    with open(os.path.join(MODELS, name + ".json"), "w") as f:
        json.dump({"parent": "block/block", "textures": tex, "elements": [els]}, f, indent=1)


write_model("unit", {"north": "unit_side", "south": "unit_side", "east": "unit_side", "west": "unit_side",
                     "up": "unit_top", "down": "bottom"})

variants = {"formed=false": {"model": "mc_assistant:block/storehouse/unit"}}
seen = {}
for facing in ("north", "south", "west", "east"):
    for px_ in range(3):
        for py_ in range(3):
            for pz_ in range(3):
                row = 2 - py_
                f = {}
                def face(side, col):
                    return ("front" if facing == side else "side") + "_%d%d" % (col, row)
                f["north"] = face("north", 2 - px_) if pz_ == 0 else "inner"
                f["south"] = face("south", px_) if pz_ == 2 else "inner"
                f["west"] = face("west", pz_) if px_ == 0 else "inner"
                f["east"] = face("east", 2 - pz_) if px_ == 2 else "inner"
                f["up"] = "top_%d%d" % (px_, pz_) if py_ == 2 else "inner"
                f["down"] = "bottom" if py_ == 0 else "inner"
                f = {k: ("bottom" if v == "inner" else v) for k, v in f.items()}
                key = tuple(sorted(f.items()))
                if key not in seen:
                    seen[key] = "joined_%d" % len(seen)
                    write_model(seen[key], f)
                variants["facing=%s,formed=true,px=%d,py=%d,pz=%d" % (facing, px_, py_, pz_)] = {
                    "model": "mc_assistant:block/storehouse/" + seen[key]}

with open(os.path.join(ROOT, "blockstates", "storehouse_unit.json"), "w") as f:
    json.dump({"variants": variants}, f, indent=1)
with open(os.path.join(ROOT, "models", "item", "storehouse_unit.json"), "w") as f:
    json.dump({"parent": "mc_assistant:block/storehouse/unit"}, f, indent=1)
print("textures:", len(os.listdir(TEX)), "models:", len(seen) + 1, "variants:", len(variants))
