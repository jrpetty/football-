#!/usr/bin/env python3
"""The folk's own clothes: the garments a tailor makes and a folk buys and wears.

A folk's trade dresses it (tools/folk_art.py: the smith's apron, the farmer's straw
hat). What it buys for itself goes over the top: a long coat, a leather jacket, a
shawl or a waistcoat; a felt hat, a flat cap or a top hat for its time off; a scarf,
a brooch, the show's rosette and a feather in its hat. Each is a box or two of its
own on a model of its own (client/FashionModel), posed limb for limb with the folk
under it, so a coat swings with the arm in it.

Three pictures, laid out alike:

  * fashion_cloth.png   the cloth, in greys: the game tints it with the garment's
                        own dye (a red coat, a blue scarf).
  * fashion_trim.png    the trimmings, in greys: cuffs and lapels, a hat's band, a
                        scarf's stripes, a shawl's fringe and a waistcoat's back,
                        tinted with the wearer's second colour (its accent).
  * fashion_fixed.png   what no dye touches: brass buttons, a jacket's zip, the gold
                        and the stone of a brooch, a rosette's button, a feather.

    python3 tools/fashion_art.py            # write the pictures and the Java
    python3 tools/fashion_art.py --check    # only say what would change

The boxes are laid out on the pictures by a shelf packer, so a box can change size
here without anybody working out where its faces go: change it here, never in
FashionModel's GENERATED blocks.
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import folk_art as fa  # noqa: E402  (the canvas, the noise and the cloths are folk_art's)

ROOT = os.path.dirname(HERE)
TEX_DIR = fa.TEX_DIR
JAVA = os.path.join(ROOT, "src/main/java/com/jrpetty/mcassistant/client/FashionModel.java")
SIZE = fa.SIZE

# --------------------------------------------------------------------- the model
#
# (name, parent, pivot, rotation, cubes, piece)
# cube: (x, y, z, w, h, d, inflate) - the texture place is worked out below.
# piece: the garment (item/Garment's name) the part belongs to, or "" for the
# four bare parts that only follow the folk's own head, body and arms.
#
# Model space is the folk's (FolkModel): y grows down, the face looks toward -z,
# -x is the folk's own right, and the feet stand on y = 24. Over everything a
# trade wears: a coat's front is open, so an apron or a ledger still shows.

PARTS = [
    ("head", None, (0, 0, 0), (0, 0, 0), [], ""),
    ("body", None, (0, 0, 0), (0, 0, 0), [], ""),
    ("right_arm", None, (-5, 2, 0), (0, 0, 0), [], ""),
    ("left_arm", None, (5, 2, 0), (0, 0, 0), [], ""),

    # The long coat: to the knee, open down the front, its sleeves to the wrist.
    ("coat_body", "body", (0, 0, 0), (0, 0, 0), [(-4, 0, -3, 8, 12, 6, 0.8)], "LONG_COAT"),
    ("coat_skirt", "body", (0, 0, 0), (0, 0, 0), [(-4, 12, -3, 8, 7, 6, 0.8)], "LONG_COAT"),
    ("coat_sleeve_right", "right_arm", (0, 0, 0), (0, 0, 0), [(-3, -2, -2, 4, 10, 4, 0.6)], "LONG_COAT"),
    ("coat_sleeve_left", "left_arm", (0, 0, 0), (0, 0, 0), [(-1, -2, -2, 4, 10, 4, 0.6)], "LONG_COAT"),

    # The leather jacket: to the waist, zipped, a collar and cuffs.
    ("jacket_body", "body", (0, 0, 0), (0, 0, 0), [(-4, 0, -3, 8, 12, 6, 0.7)], "LEATHER_JACKET"),
    ("jacket_sleeve_right", "right_arm", (0, 0, 0), (0, 0, 0), [(-3, -2, -2, 4, 10, 4, 0.55)], "LEATHER_JACKET"),
    ("jacket_sleeve_left", "left_arm", (0, 0, 0), (0, 0, 0), [(-1, -2, -2, 4, 10, 4, 0.55)], "LEATHER_JACKET"),

    # The shawl: a knitted wrap round the shoulders, a fringe at its edge.
    ("shawl_wrap", "body", (0, 0, 0), (0, 0, 0), [(-5, -1, -4, 10, 5, 8, 0.25)], "WOOL_SHAWL"),

    # The waistcoat: a V at the neck, a row of gilt buttons, its back in the second colour.
    ("waistcoat_body", "body", (0, 0, 0), (0, 0, 0), [(-4, 0, -3, 8, 11, 6, 0.65)], "WAISTCOAT"),

    # The felt hat: a crown with a band, a broad brim.
    ("felt_crown", "head", (0, 0, 0), (0, 0, 0), [(-4, -12, -4, 8, 4, 8, 0.6)], "FELT_HAT"),
    ("felt_brim", "head", (0, 0, 0), (0, 0, 0), [(-7, -8.2, -7, 14, 1, 14, 0)], "FELT_HAT"),

    # The flat cap: a soft crown pulled forward, and a peak.
    ("cap_top", "head", (0, 0, 0), (0, 0, 0), [(-4, -10.5, -4.5, 8, 2, 9, 0.6)], "FLAT_CAP"),
    ("cap_peak", "head", (0, 0, 0), (0, 0, 0), [(-4, -8.4, -7.6, 8, 1, 3, 0)], "FLAT_CAP"),

    # The top hat: tall, a band round its foot, a narrow brim.
    ("top_crown", "head", (0, 0, 0), (0, 0, 0), [(-3.5, -17, -3.5, 7, 7, 7, 0.1)], "TOP_HAT"),
    ("top_brim", "head", (0, 0, 0), (0, 0, 0), [(-6, -10.6, -6, 12, 1, 12, 0)], "TOP_HAT"),

    # The scarf: round the neck, one end hanging down in front of everything.
    ("scarf_wrap", "body", (0, 0, 0), (0, 0, 0), [(-4.5, -1, -4.5, 9, 2, 9, 0.2)], "WOOL_SCARF"),
    ("scarf_tail", "body", (0, 0, 0), (0, 0, 0), [(1, 0.5, -5.6, 3, 7, 1, 0)], "WOOL_SCARF"),

    # The brooch, high on the right breast, above any apron's bib.
    ("brooch", "body", (0, 0, 0), (0, 0, 0), [(-3, 0.3, -4.7, 2, 2, 1, 0)], "BROOCH"),

    # The rosette won at the show, on the left breast: a pleated disc and two tails.
    ("rosette", "body", (0, 0, 0), (0, 0, 0), [(1, -0.2, -4.9, 3, 3, 1, 0), (1.5, 2.6, -4.7, 2, 2, 1, 0)], "ROSETTE"),

    # A feather in a felt hat's band.
    ("feather", "head", (4.4, -9.6, 1.5), (0.0, 0.0, 0.45), [(-0.5, -5, -0.5, 1, 5, 1, 0)], "FEATHER"),
]


def pack():
    """Every cube a place on the pictures: shelves, the tallest first. Returns {(part, i): (u, v, w, h, d)}."""
    cubes = []
    for name, parent, pivot, rot, cs, piece in PARTS:
        for i, c in enumerate(cs):
            w, h, d = (int(round(k)) for k in c[3:6])
            cubes.append(((name, i), w, h, d, 2 * (d + w), d + h))
    cubes.sort(key=lambda c: (-c[5], -c[4]))
    out = {}
    x = y = shelf = 0
    for key, w, h, d, tw, th in cubes:
        if x + tw > SIZE:
            x, y, shelf = 0, y + shelf, 0
        if y + th > SIZE:
            raise SystemExit("the garments do not fit on a %d picture" % SIZE)
        out[key] = (x, y, w, h, d)
        x += tw
        shelf = max(shelf, th)
    return out


PLACES = pack()


class Box(fa.Box):
    """A garment's box on a canvas (folk_art.Box, by our own layout)."""

    def __init__(self, canvas, name, i=0):
        self.cv = canvas
        self.u, self.v, self.w, self.h, self.d = PLACES[(name, i)]
        self.f = fa.faces(self.u, self.v, self.w, self.h, self.d)


# ------------------------------------------------------------------ the cloths
#
# Greys, for the game to tint: the lightest a little under white so the weave and
# the folds still show on a white coat, the darkest a fold's shadow.

def weave(base=212, seed=1):
    def f(x, y):
        c = base + (6 if (x + y) % 2 == 0 else -4)               # a plain weave's tiny check
        return fa.grain((c, c, c), x, y, 5, seed)
    return f


def knit(base=208, seed=2):
    def f(x, y):
        c = base if x % 2 == 0 else base - 18                    # the ribs
        if (y + x // 2) % 3 == 0:
            c += 8
        return fa.grain((c, c, c), x, y, 4, seed)
    return f


def hide(base=196, seed=3):
    def f(x, y):
        n = fa.noise(x, y, seed)
        c = base + (14 if n > 0.75 else -18 if n < -0.8 else 0)  # the scuffs and creases of leather
        return fa.grain((c, c, c), x, y, 3, seed + 1)
    return f


def felt(base=200, seed=4):
    def f(x, y):
        return fa.grain((base, base, base), x, y, 4, seed)
    return f


def silk(base=190, seed=5):
    def f(x, y):
        c = base + (16 if (x - y) % 7 == 0 else 0)               # a sheen across it
        return fa.grain((c, c, c), x, y, 2, seed)
    return f


def grey(g):
    return (g, g, g)


# -------------------------------------------------------------------- painting
#
# Each garment paints its three pictures at once: cloth (cv), trim (tr) and the
# fixed things (fx). A pixel left alone is clear.

def shade_box(b, f, top=1.06, bottom=0.72, sides=0.92, which=("top", "bottom", "right", "front", "left", "back")):
    for face in which:
        k = {"top": top, "bottom": bottom, "right": sides, "left": sides}.get(face, 1.0)
        b.fill(face, lambda x, y, w, h, k=k: fa.lit(f(x, y), k))


def long_coat(cv, tr, fx):
    f = weave(214, 11)
    body = Box(cv, "coat_body")
    shade_box(body, f, which=("top", "right", "left", "back"))
    # Open down the front: a lapel each side, wide at the chest and narrowing to the waist.
    def front(x, y, w, h):
        lapel = 3 if y < 4 else 2
        if x < lapel or x >= w - lapel:
            return f(x, y)
        return False
    body.fill("front", front)
    body.fill("bottom", lambda x, y, w, h: False)
    for y in range(body.h):                                       # the back's seam
        body.put("back", 3, y, fa.lit(f(3, y), 0.86))
    t = Box(tr, "coat_body")
    for y in range(t.h):                                          # the lapels' facing, in the second colour
        lapel = 3 if y < 4 else 2
        t.put("front", lapel - 1, y, grey(222))
        t.put("front", t.w - lapel, y, grey(222))
    for x in range(t.w):
        if x < 3 or x >= t.w - 3:
            t.put("front", x, 0, grey(214))                        # the collar
    t.fill("top", lambda x, y, w, h: grey(214) if y in (0, h - 1) or x in (0, w - 1) else None)
    b = Box(fx, "coat_body")
    for y in (3, 6, 9):                                           # brass buttons down the right lapel
        b.put("front", b.w - 2, y, (214, 176, 84))
        b.put("front", b.w - 2, y + 1, (150, 116, 52))
    skirt = Box(cv, "coat_skirt")
    shade_box(skirt, f, which=("right", "left", "back", "bottom"))
    skirt.fill("top", lambda x, y, w, h: False)
    skirt.fill("front", lambda x, y, w, h: f(x, y) if x < 2 or x >= w - 2 else False)
    for y in range(2, skirt.h):                                    # the back vent
        skirt.put("back", 3, y, fa.lit(f(3, y), 0.7))
        skirt.put("back", 4, y, fa.lit(f(4, y), 0.84))
    ts = Box(tr, "coat_skirt")
    ts.row(ts.h - 1, grey(206))                                    # the hem, bound in the second colour
    ts.fill("bottom", lambda x, y, w, h: grey(190))
    for y in range(ts.h):
        ts.put("front", 1, y, grey(222))
        ts.put("front", ts.w - 2, y, grey(222))
    for name in ("coat_sleeve_right", "coat_sleeve_left"):
        s = Box(cv, name)
        shade_box(s, f, which=("top", "right", "front", "left", "back"))
        s.fill("bottom", lambda x, y, w, h: False)                 # the hand comes out of it
        st = Box(tr, name)
        st.row(st.h - 1, grey(214))                                # turned-back cuffs
        st.row(st.h - 2, grey(200))


def leather_jacket(cv, tr, fx):
    f = hide(200, 21)
    body = Box(cv, "jacket_body")
    shade_box(body, f, which=("top", "right", "front", "left", "back"))
    body.fill("bottom", lambda x, y, w, h: False)
    for face in ("front",):                                        # two pockets, stitched
        for (px, py) in ((1, 7), (5, 7)):
            for dx in range(2):
                body.put(face, px + dx, py, fa.lit(f(px + dx, py), 0.78))
    body.row(body.h - 1, lambda x: fa.lit(f(x, 11), 0.8))          # the waistband
    t = Box(tr, "jacket_body")
    t.row(0, grey(214))                                            # the collar, in the second colour
    t.fill("top", lambda x, y, w, h: grey(214) if y in (0, h - 1) or x in (0, w - 1) else None)
    b = Box(fx, "jacket_body")
    for y in range(1, b.h - 1):                                    # the zip
        b.put("front", 3, y, (172, 176, 184) if y % 2 else (120, 124, 132))
    b.put("front", 3, 1, (210, 212, 218))                          # its pull
    for name in ("jacket_sleeve_right", "jacket_sleeve_left"):
        s = Box(cv, name)
        shade_box(s, f, which=("top", "right", "front", "left", "back"))
        s.fill("bottom", lambda x, y, w, h: False)
        s.row(4, lambda x: fa.lit(f(x, 4), 0.86))                  # a crease at the elbow
        st = Box(tr, name)
        st.row(st.h - 1, grey(206))


def wool_shawl(cv, tr, fx):
    f = knit(214, 31)
    b = Box(cv, "shawl_wrap")
    shade_box(b, f, which=("top", "right", "front", "left", "back"))
    b.fill("bottom", lambda x, y, w, h: False)
    t = Box(tr, "shawl_wrap")
    for face in Box.SIDES:
        w, h = t.size(face)
        for x in range(w):
            t.put(face, x, h - 2, grey(214))                       # a stripe in the second colour
            if x % 2 == 0:
                t.put(face, x, h - 1, grey(200))                   # and the fringe of it
    for face in Box.SIDES:                                         # the fringe: every other stitch hangs free
        fx_, fy_, fw, fh = b.f[face]
        for x in range(fw):
            cv.set(fx_ + x, fy_ + fh - 1, None)
            if x % 2 == 1:
                tr.set(fx_ + x, fy_ + fh - 1, None)
            else:
                cv.set(fx_ + x, fy_ + fh - 1, grey(200))


def waistcoat(cv, tr, fx):
    f = silk(204, 41)
    b = Box(cv, "waistcoat_body")
    shade_box(b, f, which=("top", "right", "left"))
    b.fill("bottom", lambda x, y, w, h: False)
    # A V at the neck: the shirt shows above the first button.
    def front(x, y, w, h):
        mid = abs(x - 3.5)
        if y < 4 and mid < 4 - y:
            return False
        if y >= h - 1 and x in (3, 4):
            return False                                            # the points at the foot
        return f(x, y)
    b.fill("front", front)
    for (px, py) in ((1, 7), (5, 7)):                               # welt pockets
        b.put("front", px, py, fa.lit(f(px, py), 0.7))
        b.put("front", px + 1, py, fa.lit(f(px + 1, py), 0.7))
    t = Box(tr, "waistcoat_body")
    t.fill("back", lambda x, y, w, h: grey(206) if (x + y) % 5 else grey(196))    # its back in the second colour
    k = Box(fx, "waistcoat_body")
    for y in (4, 6, 8):                                             # gilt buttons
        k.put("front", 4, y, (226, 190, 86))
        k.put("front", 3, y, (166, 128, 54))


def felt_hat(cv, tr, fx):
    f = felt(198, 51)
    c = Box(cv, "felt_crown")
    shade_box(c, f)
    c.fill("top", lambda x, y, w, h: fa.lit(f(x, y), 0.86 if 2 <= x <= 5 and 3 <= y <= 4 else 1.04))   # the pinch
    c.fill("bottom", lambda x, y, w, h: False)
    t = Box(tr, "felt_crown")
    t.row(t.h - 1, grey(214))                                       # the band
    t.row(t.h - 2, grey(196))
    br = Box(cv, "felt_brim")
    fa.brim(br, f, fa.lit(grey(198), 0.8))
    for face in ("top", "bottom"):                                  # the brim's middle sits on the head
        br.fill(face, lambda x, y, w, h: None)


def flat_cap(cv, tr, fx):
    f = fa.tweed(grey(200))
    c = Box(cv, "cap_top")
    shade_box(c, f)
    c.fill("bottom", lambda x, y, w, h: False)
    p = Box(cv, "cap_peak")
    shade_box(p, f, top=0.96, bottom=0.62)
    t = Box(tr, "cap_top")
    t.put("top", 3, 4, grey(214))                                   # a button on the crown
    t.put("top", 4, 4, grey(214))


def top_hat(cv, tr, fx):
    f = silk(176, 61)
    c = Box(cv, "top_crown")
    shade_box(c, f, top=1.1)
    c.fill("bottom", lambda x, y, w, h: False)
    t = Box(tr, "top_crown")
    t.row(t.h - 1, grey(214))                                       # a broad band
    t.row(t.h - 2, grey(204))
    br = Box(cv, "top_brim")
    fa.brim(br, f, fa.lit(grey(176), 0.78))


def wool_scarf(cv, tr, fx):
    f = knit(216, 71)
    w = Box(cv, "scarf_wrap")
    shade_box(w, f)
    t = Box(tr, "scarf_wrap")
    for face in Box.SIDES:                                          # stripes in the second colour, round it
        fw, fh = t.size(face)
        for x in range(fw):
            if x % 4 in (1, 2):
                for y in range(fh):
                    t.put(face, x, y, grey(210))
    tail = Box(cv, "scarf_tail")
    shade_box(tail, f)
    tail.row(tail.h - 1, lambda x: None)
    for face in Box.SIDES:                                          # the fringe at its end
        fw, fh = tail.size(face)
        for x in range(fw):
            if x % 2 == 1:
                fx_, fy_, _, _ = tail.f[face]
                cv.set(fx_ + x, fy_ + fh - 1, None)
    tt = Box(tr, "scarf_tail")
    for face in Box.SIDES:
        fw, fh = tt.size(face)
        for x in range(fw):
            for y in (1, 2, 4, 5):
                tt.put(face, x, y, grey(210))


def brooch(cv, tr, fx):
    b = Box(fx, "brooch")
    gold, deep = (226, 186, 78), (150, 112, 40)
    b.all(lambda face, x, y, w, h: deep)
    b.fill("front", lambda x, y, w, h: (70, 120, 210) if (x, y) == (0, 1) else (120, 170, 240) if (x, y) == (1, 0) else gold)


def rosette(cv, tr, fx):
    f = weave(220, 81)
    d = Box(cv, "rosette", 0)
    shade_box(d, f)
    d.fill("front", lambda x, y, w, h: f(x, y) if (x + y) % 2 == 0 else fa.lit(f(x, y), 0.84))   # the pleats
    tails = Box(cv, "rosette", 1)
    shade_box(tails, f, top=1.0)
    tails.fill("front", lambda x, y, w, h: False if y == h - 1 and x == 0 else f(x, y))   # a swallowtail
    k = Box(fx, "rosette", 0)
    k.put("front", 1, 1, (232, 196, 90))                           # the gilt button in the middle


def feather(cv, tr, fx):
    b = Box(fx, "feather")
    def px(face, x, y, w, h):
        c = (238, 236, 228) if y < h - 1 else (196, 180, 150)
        if face in ("front", "back") and y in (1, 3):
            c = (206, 204, 196)
        return c
    b.all(px)


GARMENTS = [long_coat, leather_jacket, wool_shawl, waistcoat, felt_hat, flat_cap, top_hat, wool_scarf, brooch, rosette, feather]


# ------------------------------------------------------------------------ Java

def java_geometry():
    out = ["        MeshDefinition mesh = new MeshDefinition();",
           "        PartDefinition root = mesh.getRoot();"]
    made = {None: "root"}
    for name, parent, pivot, rot, cubes, piece in PARTS:
        var = re.sub(r"_(\w)", lambda m: m.group(1).upper(), name)
        builder = "CubeListBuilder.create()"
        for i, (x, y, z, w, h, d, g) in enumerate(cubes):
            u, v = PLACES[(name, i)][:2]
            builder += ".texOffs(%d, %d).addBox(%s, %s, %s, %s, %s, %s%s)" % (
                u, v, fa.fnum(x), fa.fnum(y), fa.fnum(z), fa.fnum(w), fa.fnum(h), fa.fnum(d),
                (", new CubeDeformation(%s)" % fa.fnum(g)) if g else "")
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


def java_pieces(trim, fixed):
    lines = []
    for name, parent, pivot, rot, cubes, piece in PARTS:
        if not piece:
            continue
        lines.append("        {\"%s\", \"%s\", \"%s\", \"%s\"}," % (
            name, parent, piece, ("t" if name in trim else "") + ("f" if name in fixed else "") + ("c" if name in CLOTHED else "")))
    return "\n".join(lines)


CLOTHED = set()


def main():
    check = "--check" in sys.argv
    cloth, trim, fixed = fa.Canvas(), fa.Canvas(), fa.Canvas()
    for g in GARMENTS:
        g(cloth, trim, fixed)
    # Which parts have anything on each picture: a pass with nothing on it is not drawn at all.
    has_trim, has_fixed = set(), set()
    for name, parent, pivot, rot, cubes, piece in PARTS:
        for i, c in enumerate(cubes):
            u, v, w, h, d = PLACES[(name, i)]
            for yy in range(v, v + d + h):
                for xx in range(u, u + 2 * (d + w)):
                    if trim.get(xx, yy) is not None:
                        has_trim.add(name)
                    if fixed.get(xx, yy) is not None:
                        has_fixed.add(name)
                    if cloth.get(xx, yy) is not None:
                        CLOTHED.add(name)
    pictures = {"fashion_cloth.png": cloth.png(), "fashion_trim.png": trim.png(), "fashion_fixed.png": fixed.png()}
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
    new = fa.splice(new, "PIECES", java_pieces(has_trim, has_fixed))
    if new != text:
        changed.append(os.path.basename(JAVA))
        if not check:
            open(JAVA, "w").write(new)
    print(("would change: " if check else "wrote: ") + (", ".join(changed) if changed else "nothing"))


if __name__ == "__main__":
    main()
