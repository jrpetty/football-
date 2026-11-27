#!/usr/bin/env python3
"""[dining] The place setting's art and models (block/PlaceSettingBlock): a white glazed plate with a blue rim, a cup,
a linen cloth, and a model for each kind of dish on the plate (stew in its bowl, a pie, a roast, fish, bread and cheese,
a slice of cake, a salad, cookies), each eaten down in three stages, with crumbs when it is gone. Writes the textures
(16x16, the vanilla way: an outline, two or three shades, a highlight), the block models, the multipart blockstate and
the two item models.

    python3 tools/dining_art.py          # from mc-assistant-neoforge/
"""
import json
import os
import random

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(os.path.dirname(HERE), "src/main/resources/assets/mc_assistant")
TEX = os.path.join(ASSETS, "textures")
MODELS = os.path.join(ASSETS, "models")
NS = "mc_assistant"


def clamp(v):
    return max(0, min(255, int(v)))


def shade(c, f):
    return tuple(clamp(x * f) for x in c[:3]) + (c[3] if len(c) > 3 else 255,)


def hexc(h):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255)


def new(fill=(0, 0, 0, 0)):
    return Image.new("RGBA", (16, 16), fill)


def save(img, rel):
    path = os.path.join(TEX, rel + ".png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def noise(img, base, spread, seed, box=(0, 0, 16, 16)):
    rnd = random.Random(seed)
    px = img.load()
    for y in range(box[1], box[3]):
        for x in range(box[0], box[2]):
            px[x, y] = shade(base, 1.0 - spread / 2 + rnd.random() * spread)


# ---------------------------------------------------------------------- textures

def plate_top():
    img = new()
    px = img.load()
    glaze, rim, line = hexc("f4f1ea"), hexc("dcd6ca"), hexc("3f6fb5")
    for y in range(16):
        for x in range(16):
            d = max(abs(x - 7.5), abs(y - 7.5))
            c = glaze
            if d > 6.5:
                c = rim
            elif d > 5.5:
                c = line
            elif d > 4.5:
                c = shade(glaze, 0.97)
            if x + y < 9 and d < 5:
                c = shade(c, 1.03)              # a glint of the glaze
            px[x, y] = c
    save(img, "block/dining/plate_top")


def plate_side():
    img = new(hexc("e6e1d6"))
    px = img.load()
    for x in range(16):
        px[x, 0] = hexc("f6f3ec")
        px[x, 15] = hexc("bdb6a8")
        px[x, 7] = hexc("3f6fb5")
    save(img, "block/dining/plate_side")


def cloth():
    img = new()
    noise(img, hexc("f2efe6"), 0.05, 11)
    px = img.load()
    for i in range(16):
        for (x, y) in ((i, 0), (i, 15), (0, i), (15, i)):
            px[x, y] = hexc("d9d2c2")           # the hem
        for (x, y) in ((i, 1), (i, 14), (1, i), (14, i)):
            px[x, y] = hexc("c9b48a") if i % 2 == 0 else hexc("e8e2d4")   # a stitched border
    save(img, "block/dining/cloth")


def cup_side():
    img = new(hexc("efe9dd"))
    px = img.load()
    for x in range(16):
        px[x, 0] = hexc("fbf8f1")
        px[x, 15] = hexc("c8bfae")
        for y in (5, 6):
            px[x, y] = hexc("3f6fb5")
    save(img, "block/dining/cup_side")


def cup_inside():
    img = new(hexc("b9b1a2"))
    save(img, "block/dining/cup_inside")


def drink():
    img = new()
    noise(img, hexc("a0622a"), 0.10, 21)
    px = img.load()
    for (x, y) in ((4, 4), (5, 4), (10, 9), (11, 10)):
        px[x, y] = hexc("d39b5b")               # a glint on the tea
    save(img, "block/dining/drink")


def bowl():
    img = new()
    noise(img, hexc("8a5a32"), 0.12, 31)
    px = img.load()
    for x in range(16):
        px[x, 0] = hexc("a87444")
        px[x, 15] = hexc("5e3b1f")
    save(img, "block/dining/bowl")


def stew():
    img = new()
    noise(img, hexc("7b4a26"), 0.12, 41)
    px = img.load()
    rnd = random.Random(42)
    for _ in range(14):
        x, y = rnd.randrange(1, 15), rnd.randrange(1, 15)
        c = rnd.choice([hexc("e08a2e"), hexc("d9c27a"), hexc("6a8f3a"), hexc("b5482e")])
        px[x, y] = c
        if x + 1 < 16:
            px[x + 1, y] = shade(c, 0.85)
    save(img, "block/dining/stew")


def crust(name, base, dark, lattice):
    img = new()
    noise(img, hexc(base), 0.10, hash(name) & 0xffff)
    px = img.load()
    if lattice:
        for i in range(16):
            for x in range(16):
                if (x + i) % 5 == 0:
                    px[x, i] = hexc(dark)
    save(img, "block/dining/" + name)


def pie_side():
    img = new()
    px = img.load()
    for y in range(16):
        for x in range(16):
            px[x, y] = hexc("d6a052") if y < 4 or y > 12 else hexc("e0812e")
            if y in (4, 12):
                px[x, y] = hexc("a86a2a")
    save(img, "block/dining/pie_side")


def roast():
    img = new()
    noise(img, hexc("8b4a2b"), 0.16, 51)
    px = img.load()
    for x in range(16):
        px[x, 0] = hexc("5b2c17")
        px[x, 15] = hexc("5b2c17")
        if x % 4 == 1:
            for y in range(3, 13):
                px[x, y] = shade(px[x, y], 0.82)    # the carving lines
    save(img, "block/dining/roast")


def roast_cut():
    img = new()
    noise(img, hexc("c98a74"), 0.10, 52)
    px = img.load()
    for i in range(16):
        px[i, 0] = hexc("7b3a1e")
        px[0, i] = hexc("7b3a1e")
        px[i, 15] = hexc("7b3a1e")
        px[15, i] = hexc("7b3a1e")
    save(img, "block/dining/roast_cut")


def bone():
    img = new(hexc("efe6d2"))
    save(img, "block/dining/bone")


def fish():
    img = new()
    noise(img, hexc("e79a7c"), 0.08, 61)
    px = img.load()
    for y in range(16):
        for x in range(16):
            if (x + y * 2) % 7 == 0:
                px[x, y] = hexc("f6c9b3")       # the flakes
            if y == 0 or y == 15:
                px[x, y] = hexc("b9a58a")       # the grilled skin
    save(img, "block/dining/fish")


def bread():
    img = new()
    noise(img, hexc("c78a45"), 0.10, 71)
    px = img.load()
    for x in range(16):
        px[x, 0] = hexc("8a5424")
        if x % 5 == 2:
            for y in range(2, 14):
                px[x, y] = hexc("e2b77a")       # the scores in the crust
    save(img, "block/dining/bread")


def bread_cut():
    img = new()
    noise(img, hexc("f1dcae"), 0.08, 72)
    px = img.load()
    for i in range(16):
        px[i, 0] = hexc("9c6330")
        px[0, i] = hexc("9c6330")
        px[i, 15] = hexc("9c6330")
        px[15, i] = hexc("9c6330")
    save(img, "block/dining/bread_cut")


def cheese():
    img = new()
    noise(img, hexc("f2cf55"), 0.08, 81)
    px = img.load()
    for (x, y) in ((3, 4), (9, 7), (12, 12), (6, 11)):
        px[x, y] = hexc("d8ad35")               # the holes
    save(img, "block/dining/cheese")


def cake_top():
    img = new(hexc("fbf7f0"))
    px = img.load()
    for (x, y) in ((3, 3), (8, 5), (12, 3), (5, 10), (11, 11)):
        px[x, y] = hexc("c8232c")               # the berries
    save(img, "block/dining/cake_top")


def cake_side():
    img = new()
    px = img.load()
    for y in range(16):
        for x in range(16):
            px[x, y] = hexc("fbf7f0") if y < 3 else hexc("f2d9a0") if y not in (8, 9) else hexc("b5313c")
    save(img, "block/dining/cake_side")


def salad():
    img = new()
    noise(img, hexc("5f9a35"), 0.22, 91)
    px = img.load()
    rnd = random.Random(92)
    for _ in range(12):
        x, y = rnd.randrange(16), rnd.randrange(16)
        px[x, y] = rnd.choice([hexc("b82a3a"), hexc("e8d24a"), hexc("8cc451")])
    save(img, "block/dining/salad")


def cookie():
    img = new()
    noise(img, hexc("c98d4a"), 0.10, 101)
    px = img.load()
    for (x, y) in ((3, 4), (7, 2), (11, 6), (5, 9), (10, 12), (13, 10)):
        px[x, y] = hexc("4a2a14")               # the chips
    save(img, "block/dining/cookie")


def crumbs():
    img = new()
    px = img.load()
    rnd = random.Random(111)
    for _ in range(18):
        x, y = rnd.randrange(2, 14), rnd.randrange(2, 14)
        px[x, y] = rnd.choice([hexc("b07a3e"), hexc("8a5a2a"), hexc("d9b27a")])
    save(img, "block/dining/crumbs")


def plate_item():
    img = new()
    px = img.load()
    for y in range(16):
        for x in range(16):
            dx, dy = (x - 7.5) / 7.0, (y - 8.5) / 4.6
            d = dx * dx + dy * dy
            if d <= 1.0:
                c = hexc("f4f1ea")
                if d > 0.82:
                    c = hexc("bdb6a8") if y > 8 else hexc("dcd6ca")
                elif d > 0.62:
                    c = hexc("3f6fb5")
                px[x, y] = c
    save(img, "item/plate")


def cup_item():
    img = new()
    px = img.load()
    for y in range(4, 14):
        for x in range(3, 11):
            c = hexc("efe9dd")
            if x == 3 or x == 10 or y == 13:
                c = hexc("b8ae9b")
            if y in (7, 8):
                c = hexc("3f6fb5")
            if y == 4:
                c = hexc("7b4a26") if 3 < x < 10 else hexc("b8ae9b")
            px[x, y] = c
    for y in range(6, 11):                      # the handle
        px[12, y] = hexc("b8ae9b")
    px[11, 6] = hexc("b8ae9b")
    px[11, 10] = hexc("b8ae9b")
    save(img, "item/cup")


# ---------------------------------------------------------------------- models

def tex(name):
    return NS + ":block/dining/" + name


def box(fr, to, faces):
    """An element: faces maps a side (or "all", "sides") to a texture key."""
    out = {}
    for side in ("north", "south", "east", "west", "up", "down"):
        key = faces.get(side) or (faces.get("sides") if side not in ("up", "down") else None) or faces.get("all")
        if key is None:
            continue
        x0, y0, z0 = fr
        x1, y1, z1 = to
        if side in ("up", "down"):
            uv = [x0, z0, x1, z1]
        elif side in ("north", "south"):
            uv = [x0, 16 - y1, x1, 16 - y0]
        else:
            uv = [z0, 16 - y1, z1, 16 - y0]
        uv = [max(0, min(16, round(v, 3))) for v in uv]
        if uv[0] == uv[2]:
            uv[2] = min(16, uv[0] + 1)
        if uv[1] == uv[3]:
            uv[3] = min(16, uv[1] + 1)
        out[side] = {"texture": "#" + key, "uv": uv}
    return {"from": list(fr), "to": list(to), "faces": out}


def lift(el, dy):
    e = json.loads(json.dumps(el))
    e["from"][1] += dy
    e["to"][1] += dy
    return e


def write_model(name, elements, textures):
    data = {"parent": "block/block", "render_type": "minecraft:cutout", "ambientocclusion": False,
            "textures": dict(textures, particle=textures.get("particle", tex("plate_top"))), "elements": elements}
    path = os.path.join(MODELS, "block/dining", name + ".json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


PLATE_TOP = 1.0   # the plate's top, over the table (the cloth lifts everything a pixel more)


def dishes():
    """For each dish, its textures and its elements at the three stages (3 whole, 2 begun, 1 nearly gone)."""
    t = PLATE_TOP
    d = {}
    # Stew in a wooden bowl: the walls stay, the stew sinks.
    walls = [box((5, t, 5), (11, t + 3, 6), {"all": "bowl"}), box((5, t, 10), (11, t + 3, 11), {"all": "bowl"}),
             box((5, t, 6), (6, t + 3, 10), {"all": "bowl"}), box((10, t, 6), (11, t + 3, 10), {"all": "bowl"})]
    d["stew"] = ({"bowl": tex("bowl"), "stew": tex("stew")},
                 {s: walls + [box((6, t, 6), (10, t + 0.6 + 0.7 * s, 10), {"up": "stew", "sides": "bowl", "down": "bowl"})] for s in (1, 2, 3)})
    # A pie, cut back a third at a time.
    d["pie"] = ({"top": tex("pie_top"), "side": tex("pie_side")},
                {s: [box((4, t, 4), (4 + 8 * s / 3, t + 2.5, 11), {"up": "top", "sides": "side", "down": "side"})] for s in (1, 2, 3)})
    # A roast joint and its bone, carved down.
    d["roast"] = ({"meat": tex("roast"), "cut": tex("roast_cut"), "bone": tex("bone")},
                  {s: [box((5, t, 5), (5 + 2 * s, t + 3, 10), {"up": "meat", "north": "meat", "south": "meat", "west": "meat",
                                                                 "east": "cut", "down": "meat"}),
                       box((5 + 2 * s, t + 1.2, 7), (6 + 2 * s + (1 if s == 3 else 0.5), t + 2, 8), {"all": "bone"})] for s in (1, 2, 3)})
    # A grilled fillet, its tail on.
    d["fish"] = ({"fish": tex("fish")},
                 {s: [box((4 + 2.6 * (3 - s), t, 6), (12, t + 1.5, 10), {"all": "fish"}),
                      box((12, t, 6.5), (13.5, t + 1, 9.5), {"all": "fish"})] for s in (1, 2, 3)})
    # Bread and cheese: the cheese goes first, then the bread.
    bread_el = lambda w: box((4, t, 5), (4 + w, t + 3, 10), {"up": "crust", "north": "crust", "south": "crust", "west": "crust",
                                                              "east": "cut", "down": "crust"})
    d["bread"] = ({"crust": tex("bread"), "cut": tex("bread_cut"), "cheese": tex("cheese")},
                  {3: [bread_el(5), box((9.5, t, 6), (12, t + 2, 9), {"all": "cheese"})],
                   2: [bread_el(5)],
                   1: [bread_el(2.5)]})
    # A slice of cake, eaten from its point.
    d["cake"] = ({"top": tex("cake_top"), "side": tex("cake_side")},
                 {s: [box((5, t, 5), (5 + 2 * s, t + 3.5, 9), {"up": "top", "sides": "side", "down": "side"})] for s in (1, 2, 3)})
    # A heaped salad, eaten down.
    d["salad"] = ({"leaf": tex("salad")},
                  {3: [box((5, t, 5), (11, t + 1.5, 11), {"all": "leaf"}), box((6, t + 1.5, 6), (10, t + 2.5, 10), {"all": "leaf"})],
                   2: [box((5, t, 5), (11, t + 1.5, 11), {"all": "leaf"})],
                   1: [box((6, t, 6), (10, t + 1, 10), {"all": "leaf"})]})
    # Three cookies: one gone a stage.
    cookies = [box((4, t, 5), (7, t + 0.8, 8), {"all": "cookie"}), box((8, t, 4), (11, t + 0.8, 7), {"all": "cookie"}),
               box((7, t, 9), (10, t + 0.8, 12), {"all": "cookie"})]
    d["sweets"] = ({"cookie": tex("cookie")}, {s: cookies[:s] for s in (1, 2, 3)})
    return d


def models():
    t = PLATE_TOP
    plate = [box((3, 0, 3), (13, t, 13), {"up": "plate", "sides": "rim", "down": "rim"})]
    for c, dy in (("", 0), ("_c", 1)):
        write_model("plate" + c, [lift(e, dy) for e in plate], {"plate": tex("plate_top"), "rim": tex("plate_side")})
        write_model("crumbs" + c, [lift(box((3.5, t, 3.5), (12.5, t + 0.05, 12.5), {"up": "crumbs"}), dy)], {"crumbs": tex("crumbs")})
        for name, (texs, stages) in dishes().items():
            for s, els in stages.items():
                write_model("%s_%d%s" % (name, s, c), [lift(e, dy) for e in els], dict(texs, particle=list(texs.values())[0]))
        # The cup: alone in the middle, or at the plate's corner; its drink at two levels.
        for where, (x0, z0) in (("center", (6, 6)), ("corner", (11, 1))):
            walls = [box((x0, 0, z0), (x0 + 4, 5, z0 + 0.6), {"sides": "cup", "up": "cup", "down": "cup"}),
                     box((x0, 0, z0 + 3.4), (x0 + 4, 5, z0 + 4), {"sides": "cup", "up": "cup", "down": "cup"}),
                     box((x0, 0, z0 + 0.6), (x0 + 0.6, 5, z0 + 3.4), {"sides": "cup", "up": "cup", "down": "cup"}),
                     box((x0 + 3.4, 0, z0 + 0.6), (x0 + 4, 5, z0 + 3.4), {"sides": "cup", "up": "cup", "down": "cup"}),
                     box((x0 + 0.6, 0, z0 + 0.6), (x0 + 3.4, 0.5, z0 + 3.4), {"up": "inside", "down": "cup"}),
                     box((x0 + 4, 1.5, z0 + 1.5), (x0 + 5, 3.5, z0 + 2.5), {"all": "cup"})]        # the handle
            write_model("cup_%s%s" % (where, c), [lift(e, dy) for e in walls], {"cup": tex("cup_side"), "inside": tex("cup_inside")})
            for lvl, h in (("half", 2.5), ("full", 4.3)):
                write_model("drink_%s_%s%s" % (lvl, where, c),
                            [lift(box((x0 + 0.6, 0.5, z0 + 0.6), (x0 + 3.4, h, z0 + 3.4), {"up": "drink"}), dy)], {"drink": tex("drink")})
    write_model("cloth", [box((0, 0, 0), (16, 1, 16), {"up": "cloth", "sides": "cloth", "down": "cloth"})], {"cloth": tex("cloth")})


def blockstate():
    parts = [{"when": {"cloth": "true"}, "apply": {"model": NS + ":block/dining/cloth"}}]
    plates = "empty|stew|pie|roast|fish|bread|cake|salad|sweets|other"
    eaten = "stew|pie|roast|fish|bread|cake|salad|sweets|other"
    for cl, c in (("false", ""), ("true", "_c")):
        parts.append({"when": {"dish": plates, "cloth": cl}, "apply": {"model": NS + ":block/dining/plate" + c}})
        parts.append({"when": {"dish": eaten, "bites": "0", "cloth": cl}, "apply": {"model": NS + ":block/dining/crumbs" + c}})
        for name in dishes():
            for s in (1, 2, 3):
                parts.append({"when": {"dish": name, "bites": str(s), "cloth": cl},
                              "apply": {"model": NS + ":block/dining/%s_%d%s" % (name, s, c)}})
        for where, dish in (("center", "none"), ("corner", plates)):
            parts.append({"when": {"cup": "empty|half|full", "dish": dish, "cloth": cl},
                          "apply": {"model": NS + ":block/dining/cup_%s%s" % (where, c)}})
            for lvl in ("half", "full"):
                parts.append({"when": {"cup": lvl, "dish": dish, "cloth": cl},
                              "apply": {"model": NS + ":block/dining/drink_%s_%s%s" % (lvl, where, c)}})
    path = os.path.join(ASSETS, "blockstates/place_setting.json")
    with open(path, "w") as f:
        json.dump({"multipart": parts}, f, indent=2)
        f.write("\n")
    for item in ("plate", "cup"):
        with open(os.path.join(MODELS, "item", item + ".json"), "w") as f:
            json.dump({"parent": "minecraft:item/generated", "textures": {"layer0": NS + ":item/" + item}}, f, indent=2)
            f.write("\n")


def main():
    for fn in (plate_top, plate_side, cloth, cup_side, cup_inside, drink, bowl, stew, pie_side, roast, roast_cut, bone, fish, bread,
               bread_cut, cheese, cake_top, cake_side, salad, cookie, crumbs, plate_item, cup_item):
        fn()
    crust("pie_top", "d9a24f", "b47a32", True)
    models()
    blockstate()


if __name__ == "__main__":
    main()
