#!/usr/bin/env python3
"""Draws the terraces: blueprints/terrace.txt (a workers' terrace), terrace2.txt (a bay-fronted terrace) and
terrace3.txt (a grand terrace of town houses). Run it to redraw them (python3 tools/terrace_design.py [out_dir]).
Entity/TerraceRows reads the same houses out of them (its Tier: the party walls, each house's door and rooms).

A terrace is a row of houses that share their side walls, built as one along a street: on a long lot by an avenue
turned to face it, or on two lots side by side facing the same street (village/TerraceLots). Columns run left to
right across the street front (dx), rows from the back of the lot (dz +5, top) to the street (dz -5, bottom). A
house's own columns are c0.. from its left party wall; a house drawn the other way round (mirror) has c0 on its
right, so two neighbours share their stair wall or their chimney wall as real pairs do.

The workers' terrace (twenty-one across): four two-up two-downs on a five-block pitch, in mirrored pairs (their front
doors side by side by the wall between them). Each: a front door straight onto the street with a fanlight over it, the
parlour's window beside it, a stone string course between the storeys; inside, a hall with the stair rising straight
up beside the party wall to the back, the front parlour (an armchair at a table, a dresser) and through a doorway
the back kitchen (the range, the bench, the copper, the chest under the landing) with the back door to the yard;
upstairs the front bedroom (the parents' pair of beds, a chest) and the back bedroom (the children's two, a chest),
a banister at the stairhead. A brick yard behind each, walled from its neighbours', a coal bunker, a privy, a water
butt and a gate to the back lane. A pitched roof along the row, the party walls carried up as gables and a brick
stack up every one of them, shared by the houses either side. The end houses have windows in their end walls.

The bay-fronted terrace (twenty-two across, dx -10..11): three taller houses on a seven-block pitch, each with a square
bay window under a lean-to roof that runs on over its porch, a front garden behind a low wall and a gate, a hall up
the side from the front door to the back door, the stair across the middle of the house, the parlour in front (the
bay, two armchairs at a table, shelves) and the kitchen behind; upstairs two bedrooms (the children's in front, the
parents' pair at the back) and a dormer in the roof. A yard behind.

The grand terrace (twenty-two across): three Georgian town houses on a seven-block pitch, four storeys high: iron
railings along the street (put up in iron by TerraceRows once it stands) round a paved area, steps up to the front
door under a fanlight, tall windows in three bays, a stone ground floor, brick above, a stone band at every floor
with iron balconettes before the first floor's windows, a cornice and a parapet with a low slate roof behind it, and
the stacks up the party walls. Inside, the hall up the side, the stair in two flights up the back, the dining room
(a long table under a chandelier) and the kitchen on the raised ground floor, the master bedroom on the first, and
the children's and a servant's room on the second.
"""
import os
import sys

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "data", "mc_assistant", "blueprints")

MIRROR = {'<': '>', '>': '<', '{': '}', '}': '{', '[': ']', ']': '[', '(': ')', ')': '(', 'J': 'i', 'i': 'J',
          'Y': '7', '7': 'Y', '2': '3', '3': '2', '5': '6', '6': '5'}


class Drawing:
    def __init__(self, x0, x1, z0=-5, z1=5):
        self.x0, self.x1, self.z0, self.z1 = x0, x1, z0, z1
        self.c = {}

    def put(self, dx, h, dz, ch):
        assert self.x0 <= dx <= self.x1 and self.z0 <= dz <= self.z1, (dx, h, dz, ch)
        self.c[(dx, h, dz)] = ch

    def drop(self, dx, h, dz):
        self.c.pop((dx, h, dz), None)

    def get(self, dx, h, dz):
        return self.c.get((dx, h, dz))

    def text(self, name, header):
        out = ["# " + l if l else "#" for l in header.strip("\n").split("\n")]
        out.append("# Drawn by tools/terrace_design.py (redraw it there); rows run from the back of the lot (top) to the street")
        out.append("# (bottom, the doors).")
        out.append("name " + name)
        hs = [h for (_, h, _) in self.c]
        for h in range(min(hs), max(hs) + 1):
            out.append("layer %d" % h)
            for dz in range(self.z1, self.z0 - 1, -1):
                out.append("".join(self.c.get((dx, h, dz), '.') for dx in range(self.x0, self.x1 + 1)))
        return "\n".join(out) + "\n"


class House:
    """One house of a row: its own columns c0.. from its left party wall (or its right, mirrored)."""

    def __init__(self, d, left, right, mirror):
        self.d, self.L, self.R, self.m = d, left, right, mirror

    def x(self, c):
        return self.R - c if self.m else self.L + c

    def put(self, c, h, z, ch):
        self.d.put(self.x(c), h, z, MIRROR.get(ch, ch) if self.m else ch)

    def drop(self, c, h, z):
        self.d.drop(self.x(c), h, z)


# A house turned to front the row's right side (turn 1): its back toward -dx, its front toward +dx. Each way-bearing key
# turns with it: its back to the row's left, its left to the row's front, its front to the right, its right to the back.
TURN = {}
for ring in ("^<v>", "n{u}", "D78Y", "c(C)", "B[b]", "12U3", "45r6", "g9!0"):
    for k, ch in enumerate(ring):
        TURN[ch] = ring[(k + 1) % 4]
TURN['-'], TURN['|'] = '|', '-'


class View:
    """A drawing seen through a house's frame (entity/TerraceRows.frame): its own columns u across and rows w toward its
    back, shifted by (ox, oz) and, turn 1, turned to front the row's right side: (u, w) -> (ox - w, oz + u)."""

    def __init__(self, d, ox=0, oz=0, turn=0):
        self.d, self.ox, self.oz, self.turn = d, ox, oz, turn

    def at(self, u, w):
        return (self.ox - w, self.oz + u) if self.turn else (self.ox + u, self.oz + w)

    def put(self, u, h, w, ch):
        x, z = self.at(u, w)
        self.d.put(x, h, z, TURN.get(ch, ch) if self.turn else ch)

    def drop(self, u, h, w):
        x, z = self.at(u, w)
        self.d.drop(x, h, z)

    def get(self, u, h, w):
        x, z = self.at(u, w)
        return self.d.get(x, h, z)


def roof(d, front, back, eaves, ridge_slab):
    """A pitched roof along the row, ridge along the street: a course of stairs each side from the eaves (over the
    front and back walls) stepping in to the ridge; the party walls' gables are drawn by the row."""
    h, zf, zb = eaves, front, back
    while zf < zb:
        for x in range(d.x0, d.x1 + 1):
            d.put(x, h, zf, '^')
            d.put(x, h, zb, 'v')
        h, zf, zb = h + 1, zf + 1, zb - 1
    if zf == zb and ridge_slab:
        for x in range(d.x0, d.x1 + 1):
            d.put(x, h - 1 if False else h, zf, '_')
    return h


def gable(d, x, front, back, eaves, ch='Z'):
    """A party wall carried up under the roof's slopes."""
    h, zf, zb = eaves, front + 1, back - 1
    while zf <= zb:
        for z in range(zf, zb + 1):
            d.put(x, h, z, ch)
        h, zf, zb = h + 1, zf + 1, zb - 1


# ============================================================ the workers' terrace

W_FRONT, W_BACK, W_REAR, W_EAVES = -5, 2, 5, 7


def workers_wall(d, x, yard=True, coping=False):
    """A party wall of the workers' terrace: its footing, the wall to the eaves, its gable, the yard wall behind (coped
    with a slab, {@code coping}, as the lane's wall is)."""
    FRONT, BACK, REAR, EAVES = W_FRONT, W_BACK, W_REAR, W_EAVES
    for z in range(FRONT, REAR + 1):
        d.put(x, -1, z, 'F')
    for z in range(FRONT, BACK + 1):
        for h in range(0, EAVES):
            d.put(x, h, z, 'Z')
    gable(d, x, FRONT, BACK, EAVES)
    if yard:
        for z in range(BACK + 1, REAR + 1):                      # the yard walls between neighbours
            d.put(x, 0, z, 'Z')
            d.put(x, 1, z, 's' if coping else 'Z')


def workers_quoins(d, x):
    """Toothed stone quoins up an end of the row, front and back."""
    for z in (W_FRONT, W_BACK):
        for h in range(0, W_EAVES):
            d.put(x, h, z, 'S')
            if h % 2 == 1:
                d.put(x, h, z + (1 if z == W_FRONT else -1), 'S')


def workers_house(d, H, end=None):
    """One two-up two-down of the workers' terrace (H: its House); {@code end}: the column of its end wall, if it is an
    end house, its windows in it."""
    FRONT, BACK, REAR, EAVES = W_FRONT, W_BACK, W_REAR, W_EAVES
    for c in range(4):
        # the footing and the floors: boards in the parlour, flags in the kitchen, a brick-paved yard
        H.put(c, -1, FRONT, 'F')
        H.put(c, -1, BACK, 'F')
        for z in range(FRONT + 1, BACK):
            H.put(c, -1, z, 'f' if z < 0 else 'S')
        for z in range(BACK + 1, REAR + 1):
            H.put(c, -1, z, 'F')
        # the floor upstairs and the ceiling under the roof
        for z in range(FRONT + 1, BACK):
            H.put(c, 3, z, 'f')
            H.put(c, EAVES, z, 'f')
        # the front: a stone string course at the floor, brick over and under
        H.put(c, 3, FRONT, 'S')
        H.put(c, 6, FRONT, 'Z')
        H.put(c, 3, BACK, 'Z')
        H.put(c, 6, BACK, 'Z')
    # the front wall: the parlour's window (stone sill), a pier, the door with a fanlight over it
    H.put(0, 0, FRONT, 'S'); H.put(1, 0, FRONT, 'S'); H.put(2, 0, FRONT, 'Z'); H.put(3, 0, FRONT, 'D')
    for h in (1, 2):
        H.put(0, h, FRONT, 'G'); H.put(1, h, FRONT, 'G'); H.put(2, h, FRONT, 'Z')
    H.put(3, 2, FRONT, 'G')
    for h in (4, 5):
        H.put(0, h, FRONT, 'Z'); H.put(1, h, FRONT, 'G'); H.put(2, h, FRONT, 'G'); H.put(3, h, FRONT, 'Z')
    # the back wall: the kitchen's window, the back door, the back bedroom's window
    H.put(0, 0, BACK, 'Z'); H.put(1, 0, BACK, 'S'); H.put(2, 0, BACK, '8'); H.put(3, 0, BACK, 'Z')
    H.put(0, 1, BACK, 'Z'); H.put(1, 1, BACK, 'G'); H.put(3, 1, BACK, 'Z')
    H.put(0, 2, BACK, 'Z'); H.put(1, 2, BACK, 'G'); H.put(2, 2, BACK, 'Z'); H.put(3, 2, BACK, 'Z')
    for h in (4, 5):
        H.put(0, h, BACK, 'Z'); H.put(1, h, BACK, 'Z'); H.put(2, h, BACK, 'G'); H.put(3, h, BACK, 'Z')
    # the stair: straight up beside the party wall from the hall to the landing at the back
    for k, z in enumerate((-3, -2, -1, 0)):
        H.put(3, k, z, '^')
        if k < 3:
            H.drop(3, 3, z)
    H.put(3, 4, -3, 'P')                                    # the banister at the stairhead
    # the parlour: an armchair at a table with a cloth on it, the dresser; the hall's lamp lights the door
    H.put(0, 0, -4, '<'); H.put(1, 0, -4, 'P'); H.put(1, 1, -4, 'X'); H.put(0, 0, -2, 'K')
    H.put(1, 2, -3, 'j'); H.put(3, 2, -4, 'j')
    for c, z in ((1, -3), (2, -3), (1, -2), (2, -2)):
        H.put(c, 0, z, 'X')                                  # the parlour's rug
    # the wall between the parlour and the kitchen, a doorway through it
    for h in (0, 1, 2, 4, 5, 6):
        H.put(0, h, -1, 'W'); H.put(1, h, -1, 'W')
    H.put(2, 2, -1, 'W'); H.put(2, 6, -1, 'W')
    # the kitchen: the bench, the range on the chimney wall, the copper under the stair, the chest under the landing
    H.put(0, 0, 0, 'T'); H.put(0, 0, 1, '3'); H.put(3, 0, 0, '&'); H.put(3, 0, 1, '(')
    H.put(1, 2, 1, 'j')
    # the front bedroom: the parents' pair of beds, heads to the party wall, a chest by the stairhead
    H.put(1, 4, -3, '['); H.put(1, 4, -2, '['); H.put(3, 4, -4, '('); H.put(2, 6, -3, 'j')
    # the back bedroom: the children's two, a chest
    H.put(1, 4, 1, '['); H.put(1, 4, 0, '['); H.put(3, 4, 1, '('); H.put(2, 6, 0, 'j')
    H.put(2, 4, -3, 'X'); H.put(2, 4, -2, 'X'); H.put(2, 4, 0, 'X')   # rag rugs by the beds
    # the yard: the wall to the lane with its gate, the coal bunker, the privy, the water butt
    H.put(0, 0, REAR, 'Z'); H.put(1, 0, REAR, 'Z'); H.put(2, 0, REAR, 'g'); H.put(3, 0, REAR, 'Z')
    H.put(0, 1, REAR, 's'); H.put(1, 1, REAR, 's'); H.put(3, 1, REAR, 's')
    H.put(0, 0, 4, 'Q'); H.put(0, 1, 4, '_')
    H.put(3, 0, 4, 'W'); H.put(3, 1, 4, 'W'); H.put(3, 2, 4, '_')
    H.put(3, 0, 3, '&')
    if end is not None:
        # the end house's windows in its end wall
        for h in (1, 2):
            H.d.put(end, h, -3, 'G')
            H.d.put(end, h, 0, 'G')
        for h in (4, 5):
            H.d.put(end, h, -4, 'G')


def workers():
    d = Drawing(-10, 10)
    walls = [-10, -5, 0, 5, 10]
    houses = [House(d, -9, -6, False), House(d, -4, -1, True), House(d, 1, 4, False), House(d, 6, 9, True)]
    for x in walls:
        workers_wall(d, x)
    # toothed stone quoins at the row's four corners
    for x in (-10, 10):
        workers_quoins(d, x)
    for i, H in enumerate(houses):
        workers_house(d, H, -10 if i == 0 else 10 if i == len(houses) - 1 else None)
    roof(d, W_FRONT, W_BACK, W_EAVES, False)
    # a brick stack up every party wall through the ridge, shared by the houses either side
    for x in walls:
        for z in (-2, -1):
            for h in (10, 11, 12):
                d.put(x, h, z, 'Z')
    return d


# ============================================================ the bay-fronted terrace

def bayfronted():
    d = Drawing(-10, 11)
    walls = [-10, -3, 4, 11]
    houses = [House(d, -9, -4, False), House(d, -2, 3, False), House(d, 5, 10, False)]
    GARDEN, BAY, FRONT, BACK, YARD, UP, EAVES = -5, -3, -2, 4, 5, 3, 8
    for x in walls:
        for z in range(GARDEN, YARD + 1):
            d.put(x, -1, z, 'F')
        for z in range(FRONT, BACK + 1):
            for h in range(0, EAVES):
                d.put(x, h, z, 'Z')
        gable(d, x, FRONT, BACK, EAVES)
        # the pier between the porches under the lean-to, the garden walls between neighbours, the yards'
        for h in range(0, 4):
            d.put(x, h, BAY, 'S')
        d.put(x, 4, BAY, '^')
        d.put(x, 0, -4, 'Z')
        d.put(x, 0, GARDEN, 'Z'); d.put(x, 1, GARDEN, 'S')
        d.put(x, 0, YARD, 'Z')
    for x in (-10, 11):
        for z in (FRONT, BACK):
            for h in range(0, EAVES):
                d.put(x, h, z, 'S')
                if h % 2 == 1:
                    d.put(x, h, z + (1 if z == FRONT else -1), 'S')
    for i, H in enumerate(houses):
        end = i == 0 or i == len(houses) - 1
        for c in range(6):
            H.put(c, -1, FRONT, 'F')
            H.put(c, -1, BACK, 'F')
            H.put(c, -1, YARD, 'F')
            for z in range(FRONT + 1, BACK):
                H.put(c, -1, z, 'f' if z < 1 else 'S')
                H.put(c, UP, z, 'f')
                H.put(c, EAVES, z, 'f')
            for h in range(0, EAVES):
                H.put(c, h, FRONT, 'Z')
                H.put(c, h, BACK, 'Z')
            H.put(c, UP, FRONT, 'S')                             # the string course at the floor
            H.put(c, -1, BAY, 'F')
            H.put(c, -1, -4, 'F' if c == 5 else 'd')
            H.put(c, -1, GARDEN, 'F')
        # the bay: stone piers, a stone sill and head, three lights between, its floor the parlour's; the opening into it
        for h in range(0, 4):
            H.put(0, h, BAY, 'S'); H.put(4, h, BAY, 'S')
        for c in (1, 2, 3):
            H.put(c, -1, FRONT, 'f')
            H.put(c, 0, BAY, 'S'); H.put(c, 1, BAY, 'G'); H.put(c, 2, BAY, 'G'); H.put(c, 3, BAY, 'S')
            for h in (0, 1, 2):
                H.drop(c, h, FRONT)
        # the front door with a fanlight over it, the porch before it under the lean-to, its lamp
        H.put(5, 0, FRONT, 'D'); H.drop(5, 1, FRONT); H.put(5, 2, FRONT, 'G')
        H.put(5, 3, BAY, 'j')
        for c in range(6):
            H.put(c, 4, BAY, '^')
        # the front garden: earth and flowers before the bay, the path to the gate, the low wall with its coping
        for c in (0, 1, 2, 3, 4):
            H.put(c, 0, -4, '*')
            H.put(c, 0, GARDEN, 'Z'); H.put(c, 1, GARDEN, 's')
        H.put(5, 0, GARDEN, 'g')
        # upstairs: sashes over the bay and the door
        for h in (5, 6):
            for c in (1, 2, 3, 5):
                H.put(c, h, FRONT, 'G')
        # the back: the kitchen's window, the back door; upstairs two windows and the landing's
        for c in (1, 2):
            H.put(c, 1, BACK, 'G'); H.put(c, 2, BACK, 'G')
            H.put(c, 5, BACK, 'G'); H.put(c, 6, BACK, 'G')
        H.put(5, 0, BACK, '8'); H.drop(5, 1, BACK)
        H.put(5, 5, BACK, 'G'); H.put(5, 6, BACK, 'G')
        # the stair up beside the hall, rising to the back; a banister at its head
        for k, z in enumerate((0, 1, 2, 3)):
            H.put(4, k, z, '^')
            if k < 3:
                H.drop(4, UP, z)
        H.put(4, 4, 0, 'P')
        # the wall between the front rooms and the back, a doorway through it by the stair, upstairs and down
        for h in (0, 1, 2, 4, 5, 6, 7):
            for c in (0, 1, 2):
                H.put(c, h, 1, 'W')
        H.put(3, 2, 1, 'W'); H.put(3, 6, 1, 'W'); H.put(3, 7, 1, 'W')
        # the parlour: a table in the bay with an armchair either side, shelves, a lamp; the hall's lamps
        H.put(2, 0, -2, 'P'); H.put(2, 1, -2, 'X'); H.put(1, 0, -2, '<'); H.put(3, 0, -2, '>')
        H.put(0, 0, 0, 'K'); H.put(0, 1, 0, 'K')
        H.put(2, 2, -1, 'j'); H.put(5, 2, 0, 'j'); H.put(5, 2, 2, 'j')
        for c in (1, 2, 3):
            H.put(c, 0, -1, 'X'); H.put(c, 0, 0, 'X')            # the parlour's carpet
        # the kitchen: the range, the bench, a cask, the chest; a lamp
        H.put(0, 0, 3, 'U'); H.put(1, 0, 3, 'T'); H.put(2, 0, 3, 'Q'); H.put(3, 0, 3, 'C')
        H.put(1, 2, 2, 'j')
        # the front bedroom: the parents' pair, heads to the party wall, a chest
        H.put(1, 4, -1, '['); H.put(1, 4, 0, '['); H.put(3, 4, 0, 'C'); H.put(2, 7, 0, 'j')
        # the back bedroom: the children's two, a chest; the landing's lamp
        H.put(1, 4, 2, '['); H.put(1, 4, 3, '['); H.put(3, 4, 3, 'C'); H.put(2, 7, 2, 'j'); H.put(5, 7, 1, 'j')
        for c, z in ((2, -1), (2, 0), (3, -1), (2, 2), (2, 3)):
            H.put(c, 4, z, 'X')                                  # rugs in the bedrooms
        if end:
            x = -10 if i == 0 else 11
            for h in (1, 2):
                d.put(x, h, -1, 'G')
                d.put(x, h, 2, 'G')                              # the end house's kitchen lit from the side street too
            for h in (5, 6):
                d.put(x, h, 3 if i == 0 else 0, 'G')
    roof(d, FRONT, BACK, EAVES, True)
    # a dormer in the front slope over each bay
    for H in houses:
        H.put(1, 9, -1, 'Z'); H.put(2, 9, -1, 'G'); H.put(3, 9, -1, 'Z')
        for c in (1, 2, 3):
            H.put(c, 10, -1, '^')
    for x in walls:
        d.put(x, 10, 0, 'Z'); d.put(x, 10, 2, 'Z')
        for z in (0, 1, 2):
            for h in (11, 12, 13):
                d.put(x, h, z, 'Z')
    return d


# ============================================================ the grand terrace

def grand(mirror_last=False):
    d = Drawing(-10, 11)
    walls = [-10, -3, 4, 11]
    houses = [House(d, -9, -4, False), House(d, -2, 3, False), House(d, 5, 10, mirror_last)]
    RAIL, FRONT, BACK, GARDEN = -5, -2, 4, 5
    F1, F2, F3, DECK = 1, 6, 11, 15                             # the floors: raised ground, first, second; the roof deck
    for x in walls:
        for z in range(RAIL, GARDEN + 1):
            d.put(x, -1, z, 'F')
        for z in range(FRONT, BACK + 1):
            for h in range(0, DECK + 2):
                d.put(x, h, z, 'S' if h <= F2 - 1 or h == DECK + 1 and z == FRONT else 'Z')
        d.put(x, 0, -4, 'S'); d.put(x, 0, -3, 'S'); d.put(x, 0, RAIL, 'S'); d.put(x, 1, RAIL, 'S')
        d.put(x, F2, -3, 'S'); d.put(x, F2 + 1, -3, 'S'); d.put(x, DECK, -3, 'S'); d.put(x, DECK + 2, FRONT, 's')
    for i, H in enumerate(houses):
        end = i == 0 or i == len(houses) - 1
        for c in range(6):
            H.put(c, -1, FRONT, 'F'); H.put(c, -1, BACK, 'F')
            for z in range(FRONT + 1, BACK):
                H.put(c, -1, z, 'F')
                H.put(c, 0, z, 'S')                              # the raised ground floor's solid plinth
                H.put(c, F1, z, 'f')
                H.put(c, F2, z, 'f')
                H.put(c, F3, z, 'f')
                H.put(c, DECK, z, 'R')
            for z in (-4, -3):
                H.put(c, -1, z, 'S')                             # the area, flagged
            H.put(c, -1, RAIL, 'F')
            # the front and back walls: stone to the first floor, brick above, a stone band at every floor
            for h in range(0, DECK + 2):
                H.put(c, h, FRONT, 'S' if h <= F2 or h in (F3, DECK, DECK + 1) else 'Z')
                H.put(c, h, BACK, 'S' if h < F2 else 'Z')
            H.put(c, -1, GARDEN, 'F')
        # tall windows in three bays: the raised ground floor's two and the door, three over each floor
        for c in (1, 3):
            for h in (3, 4, 5):
                H.put(c, h, FRONT, 'G')
        H.put(5, 2, FRONT, 'D'); H.drop(5, 3, FRONT); H.put(5, 4, FRONT, 'G'); H.put(5, 5, FRONT, 'G')
        for c in (1, 3, 5):
            for h in (7, 8, 9):
                H.put(c, h, FRONT, 'G')
            for h in (12, 13):
                H.put(c, h, FRONT, 'G')
        # the back: windows to each floor
        for c in (1, 3):
            for h in (3, 4):
                H.put(c, h, BACK, 'G')
            for h in (8, 9):
                H.put(c, h, BACK, 'G')
            for h in (12, 13):
                H.put(c, h, BACK, 'G')
        # the steps up to the door over the area, the railings along the street and up the steps, a lamp at the gate
        H.put(5, 0, -3, 'S'); H.put(5, 1, -3, 'k'); H.put(5, 0, -4, 'k')
        H.put(0, 0, RAIL, 'S')                                   # the lamp's stone pier
        for c in range(1, 5):
            H.put(c, 0, RAIL, 'P')
        H.put(4, 1, -4, 'P'); H.put(4, 2, -3, 'P')
        H.put(0, 1, RAIL, 'l')
        # the band projecting before the first floor (a canopy over the steps) and the balcony along it: an iron railing the
        # width of the house, between the party walls' stone piers, before the tall first-floor windows
        for c in range(6):
            H.put(c, F2, -3, 'S')
            H.put(c, F2 + 1, -3, 'P')
        H.put(5, 5, -3, 'j')
        # the cornice and the parapet
        for c in range(6):
            H.put(c, DECK, -3, 'S')                              # the cornice, projecting
            H.put(c, DECK + 2, FRONT, 's')
        # the stair: two flights up the back, rising from the hall to the left
        for k, c in enumerate((5, 4, 3, 2, 1)):
            H.put(c, F1 + 1 + k, 3, '<')
        for k, c in enumerate((4, 3, 2, 1, 0)):
            H.put(c, F2 + 1 + k, 3, '<')
        for c in (2, 3, 4):
            H.drop(c, F2, 3)
        for c in (1, 2, 3):
            H.drop(c, F3, 3)
        H.put(3, F3 + 1, 3, 'P')                                 # the banister at the top
        # the raised ground floor: the dining room's long table and four chairs under a chandelier, shelves; the kitchen
        H.put(2, 2, -1, 'P'); H.put(2, 3, -1, 'X'); H.put(2, 2, 0, 'P'); H.put(2, 3, 0, 'X')
        H.put(1, 2, -1, '<'); H.put(3, 2, -1, '>'); H.put(1, 2, 0, '<'); H.put(3, 2, 0, '>')
        H.put(0, 2, -1, 'K'); H.put(0, 3, -1, 'K')
        H.put(2, 5, 0, 'j'); H.put(5, 5, -1, 'j')
        for c in (1, 2, 3):
            H.put(c, 2, 1, 'X')                                  # the dining room's carpet
        H.put(0, 2, 3, 'U'); H.put(1, 2, 3, 'C'); H.put(2, 2, 3, 'Q'); H.put(3, 2, 3, 'T')
        H.put(1, 5, 2, 'j')
        # the first floor: the master bedroom, the pair of beds heads to the party wall, a chest, a desk and shelves
        H.put(1, 7, 1, '['); H.put(1, 7, 0, '['); H.put(2, 7, 1, 'C')
        H.put(4, 7, -1, 'K'); H.put(4, 8, -1, 'K'); H.put(3, 10, 0, 'j'); H.put(5, 10, 1, 'j'); H.put(1, 10, 2, 'j')
        for c, z in ((2, -1), (3, -1), (2, 0), (3, 0), (4, 0)):
            H.put(c, 7, z, 'X')                                  # the bedroom's carpet
        # the second floor: the children's two, a servant's bed, a chest
        H.put(1, 12, 1, '['); H.put(1, 12, 0, '['); H.put(4, 12, 1, ']'); H.put(2, 12, 1, 'C')
        H.put(2, 14, -1, 'j'); H.put(3, 14, 2, 'j')
        for c, z in ((2, -1), (3, -1), (2, 0), (3, 0)):
            H.put(c, 12, z, 'X')
        if end:
            x = -10 if i == 0 else 11
            for h in (3, 4, 5):
                d.put(x, h, 1, 'G')
            for h in (8, 9):
                d.put(x, h, 2, 'G')
            for h in (12, 13):
                d.put(x, h, 2, 'G')
    # the low roof behind the parapet, the party walls' gables under it, the stacks
    h = DECK + 1
    for z0, z1 in ((-1, 3), (0, 2)):
        for x in range(d.x0, d.x1 + 1):
            d.put(x, h, z0, '^')
            d.put(x, h, z1, 'v')
        h += 1
    for x in range(d.x0, d.x1 + 1):
        d.put(x, h, 1, '_')
    for x in walls:
        for z in (0, 1, 2):
            d.put(x, DECK + 1, z, 'Z')
        d.put(x, DECK + 2, 1, 'Z')
        for z in (0, 1, 2):
            for hh in (DECK + 3, DECK + 4, DECK + 5):
                d.put(x, hh, z, 'Z')
        d.put(x, DECK + 2, 0, 'Z'); d.put(x, DECK + 2, 2, 'Z')
    for x in (-10, 11):
        for z in (FRONT, BACK):
            for hh in range(0, DECK + 2):
                if hh % 2 == 0:
                    d.put(x, hh, z, 'S')
    return d


# ============================================================ the corner terrace

def corner():
    """The corner terrace (twenty-two square, the ground of three lots where two streets meet): three cottages along the
    first street, the corner house on the corner with a front door on each street (a shop window round the corner, its
    rooms deeper than its neighbours'), and two cottages round the corner along the side street, turned to face it. One
    roof turns the corner: the two ridges meet over the corner house, hipped down to both streets at the outer corner
    and in a valley over the yards. The fourth lot of the block (behind the first street's cottages, beside the side
    street's) is left alone; the yards back onto it."""
    # (drawn from dz -16: a drawing's rows are read about its middle, so the street side is padded as deep as its back)
    d = Drawing(-10, 11, -16, 16)
    FRONT, BACK, REAR, EAVES = W_FRONT, W_BACK, W_REAR, W_EAVES
    side = View(d, 6, 0, 1)                 # the side street's cottages: their fronts on dx 11, their yards to dx 1
    # the first street: walls and three cottages, the end one's windows in its end wall
    for x in (-10, -5, 0):
        workers_wall(d, x)
    workers_quoins(d, -10)
    first = [House(d, -9, -6, False), House(d, -4, -1, True), House(d, 1, 4, False)]
    for i, H in enumerate(first):
        workers_house(d, H, -10 if i == 0 else None)
    # (the third's yard gate opens into the first side cottage's yard, and through its gate to the lane: a back passage)
    # the side street: walls (the first shared with the corner house's back) and two cottages turned to it
    for u in (5, 10, 15):
        workers_wall(side, u, coping=True)     # the yards' walls coped low, so the corner's yards are not shut in
    workers_quoins(side, 15)
    for i, H in enumerate((House(side, 6, 9, False), House(side, 11, 14, True))):
        workers_house(side, H, 15 if i == 1 else None)
    # a back passage through the yards from the third cottage's to the end of the side street: gates in the side yards'
    # wall between them and in the end yard's wall
    d.put(3, 0, REAR, 'g'); d.drop(3, 1, REAR)     # (the third cottage's own gate, its side neighbour's wall drawn over it)
    d.put(1, 0, 10, 'g'); d.drop(1, 1, 10)
    d.put(3, 0, 15, 'g'); d.drop(3, 1, 15)
    # the corner house: its party wall with the third cottage (dx 5), its two street fronts (dz -5 and dx 11), its back
    # the first side cottage's wall (dz 5)
    for z in range(FRONT, REAR + 1):
        for x in (5, 11):
            d.put(x, -1, z, 'F')
            for h in range(0, EAVES):
                d.put(x, h, z, 'Z')
    gable(d, 5, FRONT, BACK, EAVES)
    for x in range(6, 11):
        d.put(x, -1, FRONT, 'F')
        for h in range(0, EAVES):
            d.put(x, h, FRONT, 'Z')
        for z in range(FRONT + 1, REAR):
            d.put(x, -1, z, 'f' if z < 0 else 'S')       # boards in the shop, flags in the kitchen
            d.put(x, 3, z, 'f')
            d.put(x, EAVES, z, 'f')
    # a stone string course round both fronts, and quoins up the corner
    for x in range(6, 11):
        d.put(x, 3, FRONT, 'S')
    for z in range(FRONT + 1, REAR):
        d.put(11, 3, z, 'S')
    for h in range(0, EAVES):
        d.put(11, h, FRONT, 'S')
        if h % 2 == 1:
            d.put(10, h, FRONT, 'S'); d.put(11, h, FRONT + 1, 'S')
    # the first street: the front door with a fanlight, the shop window, the windows over
    d.put(7, 0, FRONT, 'D'); d.drop(7, 1, FRONT); d.put(7, 2, FRONT, 'G')
    for x in (8, 9, 10):
        d.put(x, 0, FRONT, 'S'); d.put(x, 1, FRONT, 'G'); d.put(x, 2, FRONT, 'G')
    for x in (7, 9):
        d.put(x, 4, FRONT, 'G'); d.put(x, 5, FRONT, 'G')
    # the side street: the shop window carried round the corner, the side door with its fanlight, the kitchen's window
    for z in (-4, -3):
        d.put(11, 0, z, 'S'); d.put(11, 1, z, 'G'); d.put(11, 2, z, 'G')
    d.put(11, 0, -1, '7'); d.drop(11, 1, -1); d.put(11, 2, -1, 'G')
    d.put(11, 0, 2, 'S'); d.put(11, 1, 2, 'G'); d.put(11, 2, 2, 'G')
    for z in (-3, -1, 1, 4):
        d.put(11, 4, z, 'G'); d.put(11, 5, z, 'G')
    # the wall between the shop and the kitchen, a doorway through it, upstairs and down
    for x in range(8, 11):
        for h in (0, 1, 2):
            d.put(x, h, 0, 'W')
    d.put(7, 2, 0, 'W')
    for x in (6, 7, 9, 10):
        for h in (4, 5, 6):
            d.put(x, h, 0, 'W')
    d.put(8, 6, 0, 'W')
    # the stair up the party wall from the back of the shop to the landing at the back
    for k, z in enumerate((1, 2, 3, 4)):
        d.put(6, k, z, '^')
        if k < 3:
            d.drop(6, 3, z)
    for z in (1, 2, 3):
        d.put(7, 4, z, 'P')                                # the banister along the stair's well
    # the shop: the counter of casks, the shelves, a table and an armchair by the window, the lamps (one by each door)
    d.put(8, 0, -1, 'Q'); d.put(9, 0, -1, 'Q'); d.put(6, 0, -4, 'K'); d.put(6, 1, -4, 'K')
    d.put(9, 0, -3, 'P'); d.put(9, 1, -3, 'X'); d.put(10, 0, -3, '>')
    d.put(7, 2, -4, 'j'); d.put(10, 2, -1, 'j')
    for x, z in ((7, -3), (8, -3), (7, -2), (8, -2)):
        d.put(x, 0, z, 'X')
    # the kitchen: the range, the bench, the chest, the copper
    d.put(10, 0, 3, '2'); d.put(10, 0, 4, 'T'); d.put(8, 0, 4, 'C'); d.put(7, 0, 4, '&')
    d.put(9, 2, 2, 'j')
    # the front bedroom over the shop: the parents' pair, heads to the party wall, a chest; the back bedroom: the
    # children's two, heads to the side street's wall, a chest
    d.put(7, 4, -3, '['); d.put(7, 4, -2, '['); d.put(10, 4, -4, '('); d.put(9, 6, -3, 'j')
    d.put(9, 4, 2, ']'); d.put(9, 4, 3, ']'); d.put(10, 4, 4, '('); d.put(8, 6, 2, 'j')
    for x, z in ((8, -3), (8, -2), (9, -3)):
        d.put(x, 4, z, 'X')
    # the roof: the first street's ridge along dx, the side street's along dz, hipped at the outer corner, a valley at
    # the inner, over the corner house
    def f(z):
        return EAVES + min(z - FRONT, BACK - z)

    def g(x):
        return EAVES + min(11 - x, x - 4)
    for x in range(-10, 12):
        for z in range(FRONT, 16):
            a, b = z <= BACK, 4 <= x <= 11
            if not a and not b:
                continue
            fa, ca = f(z), ('^' if z <= -2 else 'v')
            gb, cb = g(x), ('<' if x >= 8 else '>')
            if a and not b:
                h, ch = fa, ca
            elif b and not a:
                h, ch = gb, cb
            elif x >= 8 and z <= -2:                      # the hip: both slopes down to the streets
                h, ch = (fa, ca) if fa <= gb else (gb, cb)
            elif x <= 7 and z >= -1:                      # the valley: both slopes down to it
                h, ch = (fa, ca) if fa >= gb else (gb, cb)
            elif x <= 7:
                h, ch = fa, ca
            else:
                h, ch = gb, cb
            d.put(x, h, z, ch)
    # the stacks up the party walls through the ridges, the corner house's shared with its neighbours
    for x in (-10, -5, 0, 5):
        for z in (-2, -1):
            for h in (10, 11, 12):
                d.put(x, h, z, 'Z')
    for u in (5, 10, 15):
        for w in (-2, -1):
            for h in (10, 11, 12):
                side.put(u, h, w, 'Z')
    return d


# ============================================================ the crescent

# How far each column of the crescent's front stands back from its ends: a shallow arc, a block at a time.
ARC = {-10: 0, -9: 0, -8: 1, -7: 1, -6: 1, -5: 2, -4: 2, -3: 2, -2: 3, -1: 3, 0: 3,
       1: 3, 2: 3, 3: 3, 4: 2, 5: 2, 6: 2, 7: 1, 8: 1, 9: 1, 10: 0, 11: 0}
LAWN, ARC_MOST = 5, 3


def crescent():
    """A Regency crescent (twenty-two across, the ground of a whole block): three grand town houses on a shallow arc
    round a lawn, their fronts stepping back a block at a time toward the middle, the railings, the area, the bands,
    the balconies, the cornice and the parapet all following the curve; the rooms behind as the grand terrace's (the
    last house drawn the other way round, so the three stand as a mirror about the middle), deeper toward the ends.
    Before them a lawn with a flower border, iron railings along the street with two gates, paths to the pavement
    before the houses, and lamps on piers at its corners."""
    src = grand(mirror_last=True)
    d = Drawing(-10, 11, -13, 13)                # (padded on the street side: rows are read about the middle)
    skin = set('FSZfR')
    for (x, h, z), ch in src.c.items():
        d.put(x, h, z + LAWN + (ARC[x] if z <= -2 else ARC_MOST), ch)
    # where a column's front stands forward of the rooms, its floors, its plinth, its deck and its walls carried forward
    for x in range(-10, 12):
        for k in range(1, ARC_MOST - ARC[x] + 1):
            z = -2 + LAWN + ARC[x] + k
            for h in range(-1, 20):
                ch = src.get(x, h, -1)
                if ch in skin:
                    d.put(x, h, z, ch)
    # the end houses' rooms run deeper toward the street: a lamp for each, downstairs and up
    for x in (-8, 9):
        z = -2 + LAWN + ARC[x] + 1
        d.put(x, 5, z, 'j'); d.put(x, 10, z, 'j')
    # the lawn: its railings along the street on a footing, two gates, the corner piers with their lamps
    for x in range(-10, 12):
        d.put(x, -1, -5, 'F')
        d.put(x, 0, -5, 'g' if x in (-6, 7) else 'P')
    for x in (-10, 11):
        d.put(x, 0, -5, 'S'); d.put(x, 1, -5, 'l')
    for x in range(-10, 12):
        rail = -5 + LAWN + ARC[x]
        d.put(x, -1, rail - 1, 'S')                          # the pavement before the railings, curving with them
        if x in (-6, 7):
            for z in range(-4, rail - 1):
                d.put(x, -1, z, 'S')                         # the paths from the gates
        elif rail - 2 > -4 and x not in (-10, 11):
            d.put(x, -1, rail - 2, 'd'); d.put(x, 0, rail - 2, '*')   # the flower border round the lawn's back
    return d


HEAD = {
    "terrace": """
A workers' terrace (entity/TerraceRows): four two-up two-down houses in a row, sharing their side walls, in mirrored
pairs. Each a front door straight onto the street, the parlour and the kitchen down, two bedrooms up, the stair beside
the party wall, a brick yard behind with a coal bunker, a privy and a gate to the lane; a brick stack up every party
wall. Twenty-one across, eleven deep: a long lot turned to its street, or two lots side by side (village/TerraceLots).
""",
    "terrace2": """
A bay-fronted terrace (entity/TerraceRows): three taller houses in a row on a seven-block pitch, each with a bay window
under a lean-to that runs on over its porch, a front garden behind a low wall, a hall up the side, the stair across
the middle, the parlour and the kitchen down, two bedrooms up and a dormer in the roof. Twenty-two across.
""",
    "terrace4": """
A corner terrace (entity/TerraceRows): an L of six homes where two streets meet, three workers' cottages along the
first, the corner house with a front door on each street and a shop window round the corner, and two cottages round
the corner along the side street; one roof turning the corner, hipped over it and in a valley behind. Twenty-two square
(three lots of a block; the fourth left to the yards' backs).
""",
    "terrace5": """
A Regency crescent (entity/TerraceRows): three grand town houses on a shallow arc round a lawn, their fronts stepping
back a block at a time toward the middle, railings, area, bands, balconies, cornice and parapet following the curve;
the lawn railed along the street with two gates and a flower border. Twenty-two across, a whole block deep.
""",
    "terrace3": """
A grand terrace (entity/TerraceRows): three Georgian town houses four storeys high, iron railings round the area, steps
up to the door, tall windows in three bays, stone below and brick above with a band at every floor, balconettes, a
cornice and parapet, the stair in two flights up the back; dining room and kitchen, the master bedroom, and the
children's and a servant's room. Twenty-two across.
""",
}


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else OUT
    for name, fn in (("terrace", workers), ("terrace2", bayfronted), ("terrace3", grand), ("terrace4", corner),
                     ("terrace5", crescent)):
        with open(os.path.join(out, name + ".txt"), "w") as f:
            f.write(fn().text(name, HEAD[name]))
        print("drew", name)


if __name__ == "__main__":
    main()
