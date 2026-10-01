#!/usr/bin/env python3
"""Authoring aid: builds the village's drawings and writes them as text files."""
import os
import sys

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "data", "mc_assistant", "blueprints")


class B:
    def __init__(self, name, hw, hd, notes):
        self.name, self.hw, self.hd, self.notes = name, hw, hd, notes
        self.layers = {}

    def set(self, dx, h, dz, ch):
        assert -self.hw <= dx <= self.hw and -self.hd <= dz <= self.hd, (self.name, dx, h, dz)
        self.layers.setdefault(h, {})[(dx, dz)] = ch

    def get(self, dx, h, dz):
        return self.layers.get(h, {}).get((dx, dz))

    def fill(self, x0, x1, h, z0, z1, ch):
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                self.set(x, h, z, ch)

    def ring(self, x0, x1, h, z0, z1, ch, corner=None):
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                if x in (x0, x1) or z in (z0, z1):
                    c = corner if corner and x in (x0, x1) and z in (z0, z1) else ch
                    self.set(x, h, z, c)

    def write(self):
        lines = ["# " + l for l in self.notes.strip().split("\n")]
        lines.append("# Rows run from the back of the building (top) to the front (bottom, the door).")
        lines.append("name " + self.name)
        for h in sorted(self.layers):
            lines.append("layer %d" % h)
            for dz in range(self.hd, -self.hd - 1, -1):
                lines.append("".join(self.layers[h].get((dx, dz), ".") for dx in range(-self.hw, self.hw + 1)))
        with open(os.path.join(OUT, self.name + ".txt"), "w") as fh:
            fh.write("\n".join(lines) + "\n")


def gable_x(b, x0, x1, z0, z1, h0, gable_x0=None, gable_x1=None, gable="W", window=None, ridge="_"):
    """A roof whose ridge runs across (along dx), sloping to the front and the back.
    Eaves at z0 (front) and z1 (back) at height h0; each row one in and one up.
    Gable walls at dx = gable_x0 / gable_x1 fill the triangle below the roof."""
    h = h0
    lo, hi = z0, z1
    while lo < hi:
        for x in range(x0, x1 + 1):
            b.set(x, h, lo, "^")
            b.set(x, h, hi, "v")
        if h > h0:
            for gx in (gable_x0, gable_x1):
                if gx is None:
                    continue
                for z in range(lo + 1, hi):
                    b.set(gx, h, z, gable)
        lo += 1
        hi -= 1
        h += 1
    if lo == hi:
        for x in range(x0, x1 + 1):
            b.set(x, h, lo, ridge)
        if window and h - 1 > h0:
            pass
    return h


def gable_z(b, x0, x1, z0, z1, h0, gable_z0=None, gable_z1=None, gable="W", ridge="_"):
    """A roof whose ridge runs front to back (along dz), sloping left and right."""
    h = h0
    lo, hi = x0, x1
    while lo < hi:
        for z in range(z0, z1 + 1):
            b.set(lo, h, z, ">")
            b.set(hi, h, z, "<")
        if h > h0:
            for gz in (gable_z0, gable_z1):
                if gz is None:
                    continue
                for x in range(lo + 1, hi):
                    b.set(x, h, gz, gable)
        lo += 1
        hi -= 1
        h += 1
    if lo == hi:
        for z in range(z0, z1 + 1):
            b.set(lo, h, z, ridge)
    return h


def hip(b, x0, x1, z0, z1, h0, top="_"):
    """A hipped roof: every side slopes up to a ridge or a point."""
    h = h0
    while x0 < x1 and z0 < z1:
        for x in range(x0, x1 + 1):
            b.set(x, h, z0, "^")
            b.set(x, h, z1, "v")
        for z in range(z0 + 1, z1):
            b.set(x0, h, z, ">")
            b.set(x1, h, z, "<")
        x0 += 1
        x1 -= 1
        z0 += 1
        z1 -= 1
        h += 1
    if x0 <= x1 and z0 <= z1:
        b.fill(x0, x1, h, z0, z1, top)
    return h


# ------------------------------------------------------------------ the house
def house():
    b = B("house", 4, 4, """The family house: a timber-framed cottage on a stone footing, a steep pitched
roof with a stone chimney, glass in the windows, lanterns either side of the
door, and four beds: a family sleeps under one roof.""")
    b.ring(-3, 3, -1, -3, 3, "F")
    b.fill(-2, 2, -1, -2, 2, "f")
    b.set(0, -1, -4, "k")
    for h in (0, 1, 2):
        b.ring(-3, 3, h, -3, 3, "w" if h == 0 else "W", corner="L")
    b.set(0, 0, -3, "D")
    b.set(0, 1, -3, ".")
    for x in (-2, 2):
        b.set(x, 1, -3, "G")
    for x in (-1, 1):
        b.set(x, 1, 3, "G")
    for z in (-1, 1):
        b.set(-3, 1, z, "G")
        b.set(3, 1, z, "G")
    # inside: four beds along the back, a chest between, bench and furnace by the door
    for x in (-2, -1, 1, 2):
        b.set(x, 0, 1, "B")
    b.set(0, 0, 2, "C")
    b.set(-2, 0, -2, "T")
    b.set(2, 0, -2, "U")
    b.set(-2, 1, -2, "t")
    # the top plate: beams, and a boarded ceiling
    b.ring(-3, 3, 3, -3, 3, "-", corner="L")
    for z in range(-2, 3):
        b.set(-3, 3, z, "|")
        b.set(3, 3, z, "|")
    b.fill(-2, 2, 3, -2, 2, "f")
    gable_x(b, -4, 4, -4, 4, 3, gable_x0=-3, gable_x1=3)
    b.set(-3, 5, 0, "G")
    b.set(3, 5, 0, "G")
    b.set(0, 2, 0, "j")
    for x in (-2, 2):
        b.set(x, 2, -4, "j")
    # the chimney, up the right-hand gable from the furnace's side
    for h in range(0, 9):
        b.set(4, h, -1, "S")
    b.set(4, -1, -1, "F")
    b.write()


def guesthouse():
    b = B("guesthouse", 4, 5, """The house a village builds for a player it has taken to its heart: the family
cottage made finer, with a porch on posts, flowers by the door, a rug, a lamp,
and one bed: theirs. Nobody in the village will sleep in it.""")
    b.ring(-3, 3, -1, -3, 3, "F")
    b.fill(-2, 2, -1, -2, 2, "f")
    b.fill(-3, 3, -1, -5, -4, "F")
    for h in (0, 1, 2):
        b.ring(-3, 3, h, -3, 3, "w" if h == 0 else "W", corner="L")
    b.set(0, 0, -3, "D")
    b.set(0, 1, -3, ".")
    for x in (-2, 2):
        b.set(x, 1, -3, "G")
    for x in (-1, 1):
        b.set(x, 1, 3, "G")
    for z in (-1, 1):
        b.set(-3, 1, z, "G")
        b.set(3, 1, z, "G")
    b.set(0, 0, 1, "B")
    b.set(-2, 0, 2, "C")
    b.set(2, 0, 2, "C")
    b.set(-2, 0, -2, "T")
    b.set(2, 0, -2, "U")
    b.fill(-1, 1, 0, -1, -1, "X")
    b.set(0, 0, 0, "X")
    b.set(-2, 1, -2, "t")
    b.ring(-3, 3, 3, -3, 3, "-", corner="L")
    for z in range(-2, 3):
        b.set(-3, 3, z, "|")
        b.set(3, 3, z, "|")
    b.fill(-2, 2, 3, -2, 2, "f")
    gable_x(b, -4, 4, -4, 4, 3, gable_x0=-3, gable_x1=3)
    b.set(-3, 5, 0, "G")
    b.set(3, 5, 0, "G")
    b.set(0, 2, 0, "j")
    # the porch: posts at the front corners, a lean-to roof, flowers in boxes
    for x in (-3, 3):
        for h in (0, 1, 2):
            b.set(x, h, -5, "P")
    for x in range(-4, 5):
        b.set(x, 3, -5, "^")
    b.set(-3, 3, -5, "-")
    b.set(3, 3, -5, "-")
    for x in range(-2, 3):
        b.set(x, 3, -5, "-")
    for x in range(-4, 5):
        b.set(x, 4, -5, ".")
    for x in (-2, -1, 1, 2):
        b.set(x, 0, -5, "d")
        b.set(x, 1, -5, "*")
    b.set(0, 2, -4, "j")
    b.write()


def storage():
    b = B("storage", 3, 4, """The storehouse: the village's stores under one roof. A small log-framed
store on a stone footing, four chests round the walls, barrels by the door.""")
    b.ring(-2, 2, -1, -3, 3, "F")
    b.fill(-1, 1, -1, -2, 2, "f")
    for h in (0, 1, 2):
        b.ring(-2, 2, h, -3, 3, "w" if h == 0 else "W", corner="L")
        b.set(-2, h, 0, "L")
        b.set(2, h, 0, "L")
    b.set(0, 0, -3, "D")
    b.set(0, 1, -3, ".")
    b.set(-2, 1, -1, "G")
    b.set(2, 1, -1, "G")
    b.set(0, 1, 3, "G")
    b.set(-1, 0, 2, "C")
    b.set(1, 0, 2, "C")
    b.set(-1, 0, 1, ")")
    b.set(1, 0, 1, "(")
    b.set(-1, 0, -2, "Q")
    b.set(1, 0, -2, "Q")
    b.set(0, 0, 2, "t")
    b.ring(-2, 2, 3, -3, 3, "|", corner="L")
    for x in (-1, 0, 1):
        b.set(x, 3, -3, "-")
        b.set(x, 3, 3, "-")
    b.fill(-1, 1, 3, -2, 2, "f")
    gable_z(b, -3, 3, -4, 4, 3, gable_z0=-3, gable_z1=3)
    b.set(0, 4, -3, "G")
    b.set(0, 2, -4, "j")
    b.write()


def shelter():
    b = B("shelter", 3, 3, """The shelter: the first roof a new village puts up. Four log posts, a low
stone wall on three sides, a pitched roof, and a fire-pit's worth of room.""")
    b.fill(-2, 2, -1, -2, 2, "F")
    for x in (-2, 2):
        for z in (-2, 2):
            for h in (0, 1, 2):
                b.set(x, h, z, "L")
    for x in (-1, 0, 1):
        b.set(x, 0, 2, "w")
    for z in (-1, 0, 1):
        b.set(-2, 0, z, "w")
        b.set(2, 0, z, "w")
    b.ring(-2, 2, 3, -2, 2, "-", corner="L")
    b.set(-2, 3, -1, "|")
    b.set(-2, 3, 0, "|")
    b.set(-2, 3, 1, "|")
    b.set(2, 3, -1, "|")
    b.set(2, 3, 0, "|")
    b.set(2, 3, 1, "|")
    gable_x(b, -3, 3, -3, 3, 3, gable_x0=-2, gable_x1=2)
    b.set(0, 0, 1, "T")
    b.set(1, 0, 1, "C")
    b.set(0, 2, 0, "j")
    b.write()


def well():
    b = B("well", 2, 2, """The well: a stone curb round water, four posts and a little pitched roof, a
lantern hung inside. The middle of village life.""")
    b.fill(-2, 2, -1, -2, 2, "F")
    b.ring(-1, 1, 0, -1, 1, "S")
    b.set(0, 0, 0, "~")
    b.set(0, -1, 0, "F")
    for x in (-1, 1):
        for z in (-1, 1):
            b.set(x, 1, z, "P")
            b.set(x, 2, z, "P")
    for x in (-2, -1, 0, 1, 2):
        b.set(x, 3, -2, "^")
        b.set(x, 3, 2, "v")
    for x in (-2, -1, 0, 1, 2):
        b.set(x, 3, -1, ".")
    b.ring(-1, 1, 3, -1, 1, "-")
    b.set(-1, 3, 0, "|")
    b.set(1, 3, 0, "|")
    b.set(0, 3, 0, "W")
    for x in (-2, -1, 0, 1, 2):
        b.set(x, 4, -1, "^")
        b.set(x, 4, 1, "v")
        b.set(x, 5, 0, "_")
    b.set(-1, 4, 0, "W")
    b.set(1, 4, 0, "W")
    b.set(0, 4, 0, ".")
    b.set(0, 2, 0, "j")
    b.write()


def smeltery():
    b = B("smeltery", 4, 4, """The smeltery: a stone forge, open to the street between two log pillars,
three furnaces along the back wall under a broad chimney, an anvil, and the
smelter's bench and chests.""")
    b.fill(-3, 3, -1, -3, 3, "F")
    b.fill(-2, 2, -1, -2, 2, "S")
    for h in (0, 1, 2):
        b.ring(-3, 3, h, -3, 3, "S", corner="L")
        for x in (-2, -1, 0, 1, 2):
            b.set(x, h, -3, ".")
    for h in (0, 1, 2):
        b.set(-3, h, -3, "L")
        b.set(3, h, -3, "L")
    b.set(-3, 1, 0, "G")
    b.set(3, 1, 0, "G")
    for x in (-1, 0, 1):
        b.set(x, 0, 2, "U")
    b.set(-2, 0, 2, "C")
    b.set(2, 0, 2, "C")
    b.set(-2, 0, -1, "T")
    b.set(1, 0, -1, "a")
    b.set(2, 0, 0, "&")
    b.ring(-3, 3, 3, -3, 3, "-", corner="L")
    for z in range(-2, 3):
        b.set(-3, 3, z, "|")
        b.set(3, 3, z, "|")
    b.fill(-2, 2, 3, -2, 2, "f")
    gable_x(b, -4, 4, -4, 4, 3, gable_x0=-3, gable_x1=3)
    # the chimney over the furnaces
    for h in range(3, 9):
        for x in (-1, 0, 1):
            if b.get(x, h, 3) in (None, ".", "v", "W", "f", "-"):
                b.set(x, h, 3, "Z" if h < 8 else "z")
        b.set(-1, h, 2, "Z") if h >= 4 else None
        b.set(1, h, 2, "Z") if h >= 4 else None
        b.set(0, h, 2, "Z") if h >= 4 else None
    for x in (-1, 0, 1):
        for z in (2, 3):
            b.set(x, 8, z, "z")
    b.set(0, 1, -2, "j") if False else None
    b.set(-2, 2, -4, "j")
    b.set(2, 2, -4, "j")
    b.write()


def workshop():
    b = B("workshop", 4, 4, """The workshop: a long timber workroom with a wide door to the street, two
benches, a furnace, the stores of the trade, barrels and a hayloft above.""")
    b.ring(-3, 3, -1, -3, 3, "F")
    b.fill(-2, 2, -1, -2, 2, "f")
    for h in (0, 1, 2):
        b.ring(-3, 3, h, -3, 3, "w" if h == 0 else "W", corner="L")
    for x in (-1, 0, 1):
        b.set(x, 0, -3, ".")
        b.set(x, 1, -3, ".")
    b.set(-1, 2, -3, "-")
    b.set(0, 2, -3, "-")
    b.set(1, 2, -3, "-")
    for z in (-1, 1):
        b.set(-3, 1, z, "G")
        b.set(3, 1, z, "G")
    b.set(-1, 1, 3, "G")
    b.set(1, 1, 3, "G")
    b.set(-2, 0, 2, "T")
    b.set(-1, 0, 2, "T")
    b.set(0, 0, 2, "U")
    b.set(1, 0, 2, "C")
    b.set(2, 0, 2, "Q")
    b.set(2, 0, 1, "Q")
    b.set(-2, 0, -2, "y")
    b.set(2, 0, -2, "&")
    b.ring(-3, 3, 3, -3, 3, "-", corner="L")
    for z in range(-2, 3):
        b.set(-3, 3, z, "|")
        b.set(3, 3, z, "|")
    b.fill(-2, 2, 3, -2, 2, "f")
    b.set(-2, 4, 2, "y")
    b.set(-1, 4, 2, "y")
    b.set(2, 4, 2, "y")
    gable_x(b, -4, 4, -4, 4, 3, gable_x0=-3, gable_x1=3)
    b.set(0, 2, 0, "j")
    b.set(-2, 2, -4, "j")
    b.set(2, 2, -4, "j")
    b.write()


def hall():
    b = B("hall", 5, 9, """The meeting hall: the village's great room. A long timber hall on a stone
plinth, four walls of tall windows between log pillars, a high roof the
length of it, a long table down the middle, the elder's chair at the far end,
chests and a bench, lanterns overhead, and steps up to a double door.""")
    W_, D_ = 4, 7
    b.fill(-W_, W_, -1, -D_, D_, "F")
    b.fill(-W_ + 1, W_ - 1, -1, -D_ + 1, D_ - 1, "f")
    for x in (-1, 0, 1):
        b.set(x, -1, -D_ - 1, "k")
    for h in (0, 1, 2, 3):
        b.ring(-W_, W_, h, -D_, D_, "w" if h == 0 else "W", corner="L")
        for z in range(-D_ + 2, D_, 3):
            b.set(-W_, h, z, "L")
            b.set(W_, h, z, "L")
        for x in (-2, 2):
            b.set(x, h, -D_, "L")
            b.set(x, h, D_, "L")
    for z in range(-D_ + 2, D_ - 1, 3):
        for zz in (z + 1, z + 2):
            if zz < D_:
                for h in (1, 2):
                    b.set(-W_, h, zz, "G")
                    b.set(W_, h, zz, "G")
    for x in (-1, 1):
        for h in (1, 2):
            b.set(x, h, D_, "G")
    for x in (-1, 0, 1):
        b.set(x, 0, -D_, ".")
        b.set(x, 1, -D_, ".")
    b.set(-1, 0, -D_, "D")
    b.set(1, 0, -D_, "D")
    b.set(0, 0, -D_, ".")
    b.set(-3, 1, -D_, "G")
    b.set(3, 1, -D_, "G")
    b.set(-3, 2, -D_, "G")
    b.set(3, 2, -D_, "G")
    # inside: a long table of planks on posts, benches either side, the elder's seat
    for z in range(-3, 4):
        b.set(0, 0, z, "P")
        b.set(-1, 0, z, ">")
        b.set(1, 0, z, "<")
    for z in range(-3, 4):
        b.set(0, 1, z, "X")
    b.set(0, 0, 5, "^")
    b.set(-3, 0, 6, "C")
    b.set(3, 0, 6, "C")
    b.set(-3, 0, 5, "T")
    for z in (-4, 0, 4):
        b.set(0, 3, z, "j")
    b.ring(-W_, W_, 4, -D_, D_, "|", corner="L")
    for x in range(-W_ + 1, W_):
        b.set(x, 4, -D_, "-")
        b.set(x, 4, D_, "-")
    gable_z(b, -W_ - 1, W_ + 1, -D_ - 1, D_ + 1, 4, gable_z0=-D_, gable_z1=D_)
    b.set(0, 6, -D_, "G")
    b.set(0, 7, -D_, "G")
    b.set(0, 6, D_, "G")
    b.set(-3, 3, -D_ - 1, "j")
    b.set(3, 3, -D_ - 1, "j")
    b.write()


def market():
    b = B("market", 5, 5, """The market: an open hall on log posts under a broad roof, stalls along
both sides with their goods out (hay, barrels, the chests of the traders), a
bench and a furnace for whatever needs making on the spot, lanterns hung
from the beams.""")
    b.fill(-4, 4, -1, -4, 4, "F")
    b.fill(-3, 3, -1, -3, 3, "S")
    for x in (-4, 0, 4):
        for z in (-4, 0, 4):
            if (x, z) == (0, 0):
                continue
            for h in (0, 1, 2, 3):
                b.set(x, h, z, "L")
    # stalls along the sides: a counter of slabs on barrels, wares on top
    for z in (-3, -2, -1, 1, 2, 3):
        b.set(-3, 0, z, "Q" if z % 2 else "y")
        b.set(3, 0, z, "Q" if z % 2 == 0 else "y")
    b.set(-3, 0, 2, "C")
    b.set(3, 0, -2, "C")
    b.set(-3, 0, -2, "T")
    b.set(3, 0, 2, "T")
    b.set(0, 0, 3, "U")
    b.set(0, 0, 0, "~")
    b.ring(-1, 1, 0, -1, 1, "S")
    b.set(0, 0, -1, "S")
    b.ring(-4, 4, 4, -4, 4, "-", corner="L")
    for z in range(-3, 4):
        b.set(-4, 4, z, "|")
        b.set(4, 4, z, "|")
        b.set(0, 4, z, "|")
    hip(b, -5, 5, -5, 5, 4)
    for (x, z) in ((-2, -2), (2, -2), (-2, 2), (2, 2)):
        b.set(x, 3, z, "j")
    b.write()


def watchtower():
    b = B("watchtower", 3, 3, """The watchtower: a stone tower of three storeys, a ladder up the inside, a
battlemented deck with a pitched lookout roof, and lanterns for the watch.""")
    b.fill(-2, 2, -1, -2, 2, "F")
    for h in range(0, 8):
        b.ring(-2, 2, h, -2, 2, "S", corner="L" if h < 7 else "S")
    b.set(0, 0, -2, "D")
    b.set(0, 1, -2, ".")
    for h in (3, 5):
        b.set(0, h, -2, "G")
        b.set(-2, h, 0, "G")
        b.set(2, h, 0, "G")
    for h in range(0, 8):
        b.set(0, h, 1, "H")
    b.fill(-1, 1, 7, -1, 1, "f")
    b.set(0, 7, 1, "H")
    b.set(0, 7, 0, ".")
    # the deck overhangs, battlemented
    b.ring(-3, 3, 8, -3, 3, "S")
    b.fill(-2, 2, 8, -2, 2, "f")
    b.set(0, 8, 1, "H")
    for x in range(-3, 4):
        for z in (-3, 3):
            if (x + z) % 2 == 0:
                b.set(x, 9, z, "S")
    for z in range(-2, 3):
        for x in (-3, 3):
            if (x + z) % 2 == 0:
                b.set(x, 9, z, "S")
    for x in (-2, 2):
        for z in (-2, 2):
            for h in (9, 10):
                b.set(x, h, z, "P")
    hip(b, -3, 3, -3, 3, 11)
    b.set(0, 10, 0, "j")
    b.write()


def lighthouse():
    b = B("lighthouse", 3, 3, """The lighthouse: a tall banded stone tower, a ladder all the way up, a glass
lamp room at the top with its fire burning, and a pointed roof over it.""")
    b.fill(-3, 3, -1, -3, 3, "F")
    for h in range(0, 15):
        band = "S" if (h // 3) % 2 == 0 else "Z"
        b.ring(-2, 2, h, -2, 2, band)
        for (x, z) in ((-2, -2), (-2, 2), (2, -2), (2, 2)):
            b.set(x, h, z, ".")
        for (x, z) in ((-1, -2), (1, -2), (-1, 2), (1, 2), (-2, -1), (-2, 1), (2, -1), (2, 1)):
            b.set(x, h, z, band)
    b.set(0, 0, -2, "D")
    b.set(0, 1, -2, ".")
    for h in (4, 8, 12):
        b.set(0, h, -2, "G")
        b.set(-2, h, 0, "G")
        b.set(2, h, 0, "G")
    for h in range(0, 15):
        b.set(0, h, 1, "H")
    b.fill(-1, 1, 15, -1, 1, "f")
    b.set(0, 15, 1, "H")
    b.ring(-3, 3, 15, -3, 3, "S")
    for (x, z) in ((-3, -3), (-3, 3), (3, -3), (3, 3)):
        b.set(x, 15, z, ".")
    b.ring(-2, 2, 16, -2, 2, "P")
    # the lamp room
    for h in (16, 17, 18):
        for (x, z) in ((-1, -1), (-1, 1), (1, -1), (1, 1)):
            b.set(x, h, z, "L")
        for (x, z) in ((0, -1), (0, 1), (-1, 0), (1, 0)):
            b.set(x, h, z, "O")
    b.set(0, 16, 0, "&")
    b.set(0, 17, 0, "j")
    b.ring(-2, 2, 19, -2, 2, "S")
    b.fill(-1, 1, 19, -1, 1, "S")
    hip(b, -2, 2, -2, 2, 20)
    b.set(0, 22, 0, "P")
    b.write()


def chapel():
    b = B("chapel", 4, 10, """The chapel: a stone nave with tall windows, a bell tower over the door, a
steep roof, pews in rows facing the altar, and candles of light either side.""")
    W_, D_ = 3, 7
    b.fill(-W_, W_, -1, -D_, D_, "F")
    b.fill(-W_ + 1, W_ - 1, -1, -D_ + 1, D_ - 1, "S")
    for h in range(0, 5):
        b.ring(-W_, W_, h, -D_, D_, "S")
    for z in range(-D_ + 2, D_ - 1, 2):
        for h in (1, 2, 3):
            b.set(-W_, h, z, "G")
            b.set(W_, h, z, "G")
    for h in (1, 2, 3):
        b.set(0, h, D_, "G")
    b.set(-1, 2, D_, "G")
    b.set(1, 2, D_, "G")
    # the tower over the door
    for h in range(0, 12):
        b.ring(-2, 2, h, -D_ - 2, -D_ + 2, "S")
    for h in range(0, 12):
        b.set(0, h, -D_ + 2, "S" if h > 4 else ".")
        b.set(-1, h, -D_ + 2, "S" if h > 4 else ".")
        b.set(1, h, -D_ + 2, "S" if h > 4 else ".")
    b.set(0, 0, -D_ - 2, "D")
    b.set(0, 1, -D_ - 2, ".")
    b.set(0, 2, -D_ - 2, ".")
    b.set(0, -1, -D_ - 3, "k")
    for h in (9, 10):
        for (x, z) in ((0, -D_ - 2), (0, -D_ + 2), (-2, -D_), (2, -D_)):
            b.set(x, h, z, ".")
    b.fill(-1, 1, 8, -D_ - 1, -D_ + 1, "f")
    b.set(0, 9, -D_, "e")
    b.set(0, 5, -D_ - 2, "G")
    b.set(0, 6, -D_ - 2, "G")
    hip(b, -2, 2, -D_ - 2, -D_ + 2, 12)
    b.set(0, 14, -D_, "P")
    b.set(0, 15, -D_, "P")
    # inside: pews facing the altar, the altar, lights
    for z in range(-D_ + 3, 3):
        if z % 2 == 0:
            b.set(-2, 0, z, "^")
            b.set(-1, 0, z, "^")
            b.set(1, 0, z, "^")
            b.set(2, 0, z, "^")
    b.fill(-1, 1, 0, 5, 6, "X")
    b.set(0, 0, 6, "T")
    b.set(-2, 0, 6, "l")
    b.set(2, 0, 6, "l")
    b.set(0, 4, 0, "j")
    b.set(0, 4, 4, "j")
    b.set(0, 4, -3, "j")
    b.ring(-W_, W_, 5, -D_ + 3, D_, "S")
    gable_z(b, -W_ - 1, W_ + 1, -D_ + 3, D_ + 1, 5, gable_z1=D_)
    b.write()


def gateway():
    b = B("gateway", 4, 2, """The gateway: the obsidian frame the Nether Age asks for, on a stone dais
between two stone pillars with lanterns, steps up to it front and back.""")
    b.fill(-4, 4, -1, -2, 2, "F")
    b.fill(-3, 3, 0, -1, 1, "S")
    for x in range(-3, 4):
        b.set(x, 0, -2, "s")
        b.set(x, 0, 2, "s")
    for x in (-2, -1, 0, 1):
        b.set(x, 1, 0, "o")
        b.set(x, 5, 0, "o")
    for h in (2, 3, 4):
        b.set(-2, h, 0, "o")
        b.set(1, h, 0, "o")
    b.set(-2, 1, 0, "S")
    b.set(1, 1, 0, "S")
    b.set(-2, 5, 0, "S")
    b.set(1, 5, 0, "S")
    for x in (-4, 3):
        for h in range(0, 6):
            b.set(x, h, 0, "S")
        b.set(x, 6, 0, "l")
    b.write()


def granary():
    b = B("granary", 3, 3, """The granary: a squat round-shouldered store on a stone base, hay stacked to
the rafters, three chests of grain and a conical roof.""")
    b.fill(-2, 2, -1, -2, 2, "F")
    b.set(-3, -1, 0, "F")
    b.set(3, -1, 0, "F")
    b.set(0, -1, -3, "F")
    b.set(0, -1, 3, "F")
    for h in (0, 1, 2, 3):
        b.ring(-2, 2, h, -2, 2, "w" if h < 2 else "W")
        for (x, z) in ((-2, -2), (-2, 2), (2, -2), (2, 2)):
            b.set(x, h, z, "L")
    b.set(0, 0, -2, "D")
    b.set(0, 1, -2, ".")
    b.set(0, 2, 2, "G")
    b.set(-1, 0, 1, "C")
    b.set(0, 0, 1, "C")
    b.set(1, 0, 1, "C")
    b.set(-1, 0, -1, "y")
    b.set(1, 0, -1, "y")
    b.fill(-1, 1, 1, 1, 1, "y")
    b.fill(-1, 1, 2, 0, 1, "y")
    hip(b, -3, 3, -3, 3, 4)
    b.write()


def barracks():
    b = B("barracks", 4, 8, """The barracks: a long stone-and-timber dormitory for the watch, six bunks down
the walls, the watch's chests and bench, an armoury rack, lanterns, and a
battlemented roof walk at the front.""")
    W_, D_ = 3, 6
    b.fill(-W_, W_, -1, -D_, D_, "F")
    b.fill(-W_ + 1, W_ - 1, -1, -D_ + 1, D_ - 1, "f")
    for h in (0, 1, 2):
        b.ring(-W_, W_, h, -D_, D_, "S" if h == 0 else "W", corner="L")
        for z in (-2, 2):
            b.set(-W_, h, z, "L")
            b.set(W_, h, z, "L")
    b.set(0, 0, -D_, "D")
    b.set(0, 1, -D_, ".")
    for z in (-4, 0, 4):
        b.set(-W_, 1, z, "G")
        b.set(W_, 1, z, "G")
    for z in (-3, -1, 1, 3, 5):
        pass
    for z in (-4, 0, 4):
        b.set(-2, 0, z, "]")
        b.set(2, 0, z, "[")
    b.set(-2, 0, 5, "C")
    b.set(2, 0, 5, "C")
    b.set(0, 0, 5, "T")
    b.set(-2, 0, -5, "a")
    b.set(0, 2, -3, "j")
    b.set(0, 2, 3, "j")
    b.ring(-W_, W_, 3, -D_, D_, "|", corner="L")
    for x in range(-W_ + 1, W_):
        b.set(x, 3, -D_, "-")
        b.set(x, 3, D_, "-")
    b.fill(-W_ + 1, W_ - 1, 3, -D_ + 1, D_ - 1, "f")
    gable_z(b, -W_ - 1, W_ + 1, -D_ - 1, D_ + 1, 3, gable_z0=-D_, gable_z1=D_)
    b.set(0, 5, -D_, "G")
    b.set(-2, 2, -D_ - 1, "j")
    b.set(2, 2, -D_ - 1, "j")
    b.write()


def monument():
    b = B("monument", 3, 3, """The monument: the village's pride in stone. A stepped plinth, a pillar with
banded stone, a crown of light, and lanterns at the four corners.""")
    b.fill(-3, 3, -1, -3, 3, "F")
    b.fill(-2, 2, 0, -2, 2, "S")
    for (x, z) in ((-3, -3), (-3, 3), (3, -3), (3, 3)):
        b.set(x, 0, z, "l")
    for x in range(-2, 3):
        b.set(x, 0, -3, "s")
        b.set(x, 0, 3, "s")
    for z in range(-2, 3):
        b.set(-3, 0, z, "s")
        b.set(3, 0, z, "s")
    b.fill(-1, 1, 1, -1, 1, "S")
    for h in range(2, 7):
        b.set(0, h, 0, "Z" if h % 2 else "S")
    for (x, z) in ((0, -1), (0, 1), (-1, 0), (1, 0)):
        b.set(x, 2, z, "s")
    b.ring(-1, 1, 7, -1, 1, "s")
    b.set(0, 7, 0, "S")
    b.set(0, 8, 0, "l")
    b.write()


def smithy():
    b = B("smithy", 4, 4, """The smithy: a stone forge open to the street between two log pillars, two
furnaces under a brick hood, the anvil before them, a grindstone, a quenching
tub, the smith's bench and its chests of finished tools.""")
    b.fill(-3, 3, -1, -3, 3, "F")
    b.fill(-2, 2, -1, -2, 2, "S")
    for h in (0, 1, 2):
        b.ring(-3, 3, h, -3, 3, "S" if h == 0 else "W", corner="L")
        for x in (-2, -1, 0, 1, 2):
            b.set(x, h, -3, ".")
    for h in (0, 1, 2):
        b.set(-3, h, -3, "L")
        b.set(3, h, -3, "L")
    b.set(-3, 1, 1, "G")
    b.set(3, 1, 1, "G")
    b.set(-1, 0, 2, "U")
    b.set(1, 0, 2, "U")
    b.set(0, 0, 2, "Z")
    b.set(0, 0, 0, "a")
    b.set(2, 0, -1, "V")
    b.set(2, 0, 1, "&")
    b.set(-2, 0, 1, "C")
    b.set(-2, 0, -1, "T")
    b.set(-2, 1, 1, "t")
    b.ring(-3, 3, 3, -3, 3, "-", corner="L")
    for z in range(-2, 3):
        b.set(-3, 3, z, "|")
        b.set(3, 3, z, "|")
    b.fill(-2, 2, 3, -2, 2, "f")
    hip(b, -4, 4, -4, 4, 3)
    for h in range(1, 9):                                   # the forge's brick hood and chimney
        for x in (-1, 0, 1):
            if h >= 4 or x == 0 or h >= 2:
                cur = b.get(x, h, 2)
                if h >= 1 and (cur in (None, ".", "f", "-", "^", "v", "<", ">", "_") and h >= 3):
                    b.set(x, h, 2, "Z")
        b.set(0, h, 2, "Z") if h >= 1 else None
    b.set(-1, 1, 2, "Z")
    b.set(1, 1, 2, "Z")
    b.set(-2, 2, -4, "j")
    b.set(2, 2, -4, "j")
    b.write()


def brewery():
    b = B("brewery", 4, 4, """The brewery: a timber still-house on a stone footing, two brewing stands on a
stone bench, cauldrons of water, casks along the wall, a chest of finished
brews, a glass window to the street, lanterns hung low over the work.""")
    b.ring(-3, 3, -1, -3, 3, "F")
    b.fill(-2, 2, -1, -2, 2, "f")
    for h in (0, 1, 2):
        b.ring(-3, 3, h, -3, 3, "w" if h == 0 else "W", corner="L")
    b.set(0, 0, -3, "D")
    b.set(0, 1, -3, ".")
    for x in (-2, 2):
        b.set(x, 1, -3, "G")
    for z in (-1, 1):
        b.set(-3, 1, z, "G")
        b.set(3, 1, z, "G")
    for x in (-1, 1):
        b.set(x, 0, 2, "S")                                 # the stone bench
        b.set(x, 1, 2, "I")                                 # brewing stands on it
    b.set(0, 0, 2, "&")
    b.set(2, 0, 2, "&")
    for z in (-1, 0, 1):
        b.set(-2, 0, z, "Q")                                # the casks
    b.set(-2, 1, 0, "Q")
    b.set(2, 0, 0, "C")
    b.set(2, 0, -2, "T")
    b.ring(-3, 3, 3, -3, 3, "-", corner="L")
    for z in range(-2, 3):
        b.set(-3, 3, z, "|")
        b.set(3, 3, z, "|")
    b.fill(-2, 2, 3, -2, 2, "f")
    gable_x(b, -4, 4, -4, 4, 3, gable_x0=-3, gable_x1=3)
    b.set(0, 2, 0, "j")
    b.set(-2, 2, -4, "j")
    b.set(2, 2, -4, "j")
    for h in range(0, 8):                                   # a chimney for the stills
        b.set(4, h, 1, "S")
    b.set(4, -1, 1, "F")
    b.write()


def library():
    b = B("library", 4, 5, """The library: a stone hall with tall windows, its walls lined with bookshelves,
an enchanting table in the middle of a carpet, lecterns either side of it,
and lanterns hung from the beams. The enchanter works here.""")
    b.ring(-3, 3, -1, -4, 4, "F")
    b.fill(-2, 2, -1, -3, 3, "f")
    for h in (0, 1, 2, 3):
        b.ring(-3, 3, h, -4, 4, "S" if h == 0 else "W", corner="L")
    b.set(0, 0, -4, "D")
    b.set(0, 1, -4, ".")
    for z in (-2, 0, 2):
        for h in (1, 2):
            b.set(-3, h, z, "G")
            b.set(3, h, z, "G")
    for x in (-2, -1, 1, 2):
        for h in (0, 1):
            b.set(x, h, 3, "K")                             # shelves along the back wall
    for z in (-3, -1, 1):
        for h in (0, 1):
            pass
    for z in (-1, 1, 3):
        b.set(-2, 0, z, "K")
        b.set(2, 0, z, "K")
    b.set(-2, 1, 1, "K")
    b.set(2, 1, 1, "K")
    for x in (-1, 0, 1):
        for z in (-1, 0, 1):
            b.set(x, 0, z, "X")                             # the carpet
    b.set(0, 0, 0, "E")
    b.set(-1, 0, -2, "r")
    b.set(1, 0, -2, "r")
    b.ring(-3, 3, 4, -4, 4, "-", corner="L")
    for z in range(-3, 4):
        b.set(-3, 4, z, "|")
        b.set(3, 4, z, "|")
    b.fill(-2, 2, 4, -3, 3, "f")
    gable_z(b, -4, 4, -5, 5, 4, gable_z0=-4, gable_z1=4)
    for z in (-2, 2):
        b.set(0, 3, z, "j")
    b.set(-2, 2, -5, "j")
    b.set(2, 2, -5, "j")
    b.write()


def cafe():
    b = B("cafe", 4, 4, """The cafe: a bright timber room with big windows on the street, a counter of
casks where the cook serves, a smoker and a bench behind it, little tables
with cloths and chairs round them, flowers by the door, lanterns overhead.""")
    b.ring(-3, 3, -1, -3, 3, "F")
    b.fill(-2, 2, -1, -2, 2, "f")
    for h in (0, 1, 2):
        b.ring(-3, 3, h, -3, 3, "w" if h == 0 else "W", corner="L")
    b.set(0, 0, -3, "D")
    b.set(0, 1, -3, ".")
    for x in (-2, -1, 1, 2):
        b.set(x, 1, -3, "G")                                # the big front windows
    for z in (-1, 0, 1):
        b.set(-3, 1, z, "G")
        b.set(3, 1, z, "G")
    for x in (-2, -1, 0, 1):
        b.set(x, 0, 1, "Q")                                 # the counter
    b.set(-2, 0, 2, "M")                                    # the smoker behind it
    b.set(0, 0, 2, "T")
    b.set(1, 0, 2, "C")
    b.set(2, 0, 2, "C")
    b.set(2, 0, -1, "P")                                    # a table with its cloth
    b.set(2, 1, -1, "X")
    b.set(2, 0, -2, "<")                                    # and its chairs
    b.set(-2, 0, -1, "P")
    b.set(-2, 1, -1, "X")
    b.set(-2, 0, -2, ">")
    b.ring(-3, 3, 3, -3, 3, "-", corner="L")
    for z in range(-2, 3):
        b.set(-3, 3, z, "|")
        b.set(3, 3, z, "|")
    b.fill(-2, 2, 3, -2, 2, "f")
    gable_x(b, -4, 4, -4, 4, 3, gable_x0=-3, gable_x1=3)
    b.set(0, 2, -1, "j")
    b.set(0, 2, 1, "j")
    b.set(-2, 2, -4, "j")
    b.set(2, 2, -4, "j")
    b.set(-2, -1, -4, "d")                                  # flowers either side of the door
    b.set(-2, 0, -4, "*")
    b.set(2, -1, -4, "d")
    b.set(2, 0, -4, "*")
    for h in range(0, 8):
        b.set(4, h, 2, "S")
    b.set(4, -1, 2, "F")
    b.write()


def shop():
    b = B("shop", 4, 4, """The shop: a timber shopfront with a wide window either side of the door, a
counter of casks across the room with the day's goods laid out on it, shelves
of barrels and chests behind it, and a lantern over the door.""")
    b.ring(-3, 3, -1, -3, 3, "F")
    b.fill(-2, 2, -1, -2, 2, "f")
    for h in (0, 1, 2):
        b.ring(-3, 3, h, -3, 3, "w" if h == 0 else "W", corner="L")
    b.set(0, 0, -3, "D")
    b.set(0, 1, -3, ".")
    for x in (-2, -1, 1, 2):
        b.set(x, 1, -3, "G")
    for z in (-1, 1):
        b.set(-3, 1, z, "G")
        b.set(3, 1, z, "G")
    for x in (-2, -1, 1, 2):
        b.set(x, 0, 0, "Q")                                 # the counter, a gap to get behind
    for x in (-2, -1, 0, 1, 2):
        b.set(x, 0, 2, "Q" if x % 2 else "C")               # shelves of barrels and chests
        b.set(x, 1, 2, "Q")
    b.set(0, 1, 0, "l")
    b.ring(-3, 3, 3, -3, 3, "-", corner="L")
    for z in range(-2, 3):
        b.set(-3, 3, z, "|")
        b.set(3, 3, z, "|")
    b.fill(-2, 2, 3, -2, 2, "f")
    gable_x(b, -4, 4, -4, 4, 3, gable_x0=-3, gable_x1=3)
    b.set(0, 2, -4, "j")
    b.set(-2, 2, 1, "j")
    b.set(2, 2, 1, "j")
    b.write()


ALL = [house, guesthouse, storage, shelter, well, smeltery, workshop, hall, market, watchtower,
       smithy, brewery, library, cafe, shop,
       lighthouse, chapel, gateway, granary, barracks, monument]

if __name__ == "__main__":
    want = sys.argv[1:]
    for f in ALL:
        if not want or f.__name__ in want:
            f()
            print("wrote", f.__name__)
