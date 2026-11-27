#!/usr/bin/env python3
"""Draws blueprints/flats.txt (a block of flats, three storeys) and flats4.txt (four), walking their stair and their
flats first: run it to redraw them (python3 tools/flats_design.py [out_dir]). Entity/Flats reads the same cells.
(The first, smaller blocks' drawings, flats_old.txt and flats4_old.txt, are kept as they were for the blocks standing
to them; this does not draw those.)

Columns dx -5..5 (left to right), rows dz +9 (back, top) .. -9 (front, bottom). The walls stand at dx -5 and 5,
dz -8 and 8; the rows outside them (dz -9, 9) are for what stands out from the walls: the eaves, the cornice, the
balconies, the sills, the window boxes, the door's hood and its step. Eleven across the street (a town house's front,
as wide as a lot) and seventeen deep: it stands on a long lot (TownPlan), the two lots end to end beside an avenue.

Storey s: floor at layer 4s-1, rooms 4s..4s+2. The stair hall runs the length of the right-hand side, dx 3..4, behind
a wall at dx 2; the flats are dx -4..1, two to a landing: A at the front (dz -7..-1), B at the back (dz 1..7), a
party wall between them at dz 0. Each flat is two rooms: a living room and kitchen by its door off the landing (A's at
the front, dz -7..-5, with the front windows and, upstairs, the balcony; B's at the back), and through a doorway in
the wall at dz -4 (or 4) its bedroom (dz -3..-1, or 1..3) with the beds' heads to the outside wall, a chest between
them under the window, and a lamp. The living room has a stove in the corner by the window, a worktop and a barrel
beside it, a table with a chair either side, and a lamp over the table.

The stair: a straight flight up the hall's right-hand half (dx 4) toward the back, two steps, a landing, two steps,
from dz -3 to the top step at dz 1 in the floor above; each storey's flight over the one below, so every step has
three blocks of air over it (the floor above is open over the flight, and the flight above starts four up). A folk
comes up at the back of the hall (dz 2), walks forward along its left-hand half (dx 3) past the well to the flat doors
or the next flight's foot (dz -4). On the top landing, where no flight goes on up, a rail of fence posts stands at
the well's edge where it drops more than a step.

Outside: a slate plinth; stone walls with toothed brick quoins at the corners; windows two high in three bays
(dx -3, 0, 3) front and back; the front door in the right-hand bay under a fanlight and a little slate pediment with
a lantern hung at each end; window boxes under the ground floor's windows; a balcony on two brackets across each upper
flat's living room (its iron railings put up by Flats once the block stands); slate sills under the back windows and
the hall's; a bracketed cornice under the eaves; a mansard roof of slate: four courses up from the eaves front and
back to a flat top, a dormer in the front slope over the middle bay, the side walls carried up as stone gables under
it, and a brick chimney stack up the middle of each side wall. (Slate once the Iron Age's make-over comes round: the
roof's parts are built in wood and slated by Ages.)
"""
import os
import sys

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "data", "mc_assistant", "blueprints")

W, D, ROW = 5, 8, 9                        # the side walls at dx +-5, the front and back at dz -+8, the rows outside
HALL_WALL = 2                              # the wall between the flats and the stair hall
FLAT_DOOR = 6                              # each flat's door off the landing: dz -6 (A) and 6 (B), in the hall wall
INNER = 4                                  # the wall between a flat's living room and its bedroom: dz -4 and 4
DOORWAY = 1                                # the doorway in it, at dx 1 (by the hall wall)
STAIR_X = 4                                # the flight runs up dx 4, from dz -3 to its top step at dz 1
FOOT, TOP_STEP = -3, 1
WELL = (-3, -2, -1, 0)                     # where the floor above is open over the flight
BAYS = (-3, 0, 3)                          # the windows' columns, front and back
CORBELS = (-5, -4, -2, -1, 1, 2, 4, 5)     # the cornice's brackets: between the bays

def flat_types(s):
    # (front, back): 2 = a couple's flat (two beds), 1 = a single's
    return (2, 1) if s % 2 == 0 else (1, 2)

def draw(storeys):
    top = storeys - 1
    roof = 4 * top + 3                     # the top course, and the garret's floor
    layers = {}
    def put(h, dx, dz, c):
        layers.setdefault(h, {})[(dx, dz)] = c
    def drop(h, dx, dz):
        layers.get(h, {}).pop((dx, dz), None)
    def perim(dx, dz):
        return (abs(dx) == W and abs(dz) <= D) or (abs(dz) == D and abs(dx) <= W)
    def quoin(h, dx, dz):
        # Toothed: the corner every course, and one more beside it, along the front or back on an
        # even course and along the side on an odd one.
        if abs(dx) == W and abs(dz) == D:
            return True
        if h % 2 == 0:
            return abs(dx) == W - 1 and abs(dz) == D
        return abs(dx) == W and abs(dz) == D - 1
    def face(h, dx, dz):
        if h == 0:
            return 'R'                     # the plinth, in slate
        return 'Z' if quoin(h, dx, dz) else 'S'
    # the footing and the ground floor
    for dx in range(-W, W + 1):
        for dz in range(-D, D + 1):
            if perim(dx, dz) or dx >= HALL_WALL or dz in (0, -INNER, INNER):
                put(-1, dx, dz, 'F')       # the hall flagged in stone, and the partitions' footings
            else:
                put(-1, dx, dz, 'f')
    put(-1, 3, -ROW, 'k')                  # the step up to the door
    for s in range(storeys):
        base = 4 * s
        front, back = flat_types(s)
        for h in range(base, base + 3):
            k = h - base                   # 0, 1, 2 up the storey
            for dx in range(-W, W + 1):
                for dz in range(-D, D + 1):
                    if perim(dx, dz):
                        put(h, dx, dz, face(h, dx, dz))
                    elif dx == HALL_WALL:
                        put(h, dx, dz, 'W')
                    elif dx < HALL_WALL and dz in (0, -INNER, INNER):
                        put(h, dx, dz, 'W')
            # windows: two high, in three bays front and back, and on the sides: each living room and
            # bedroom, and the hall at either end
            if k in (1, 2):
                for dx in BAYS:
                    put(h, dx, D, 'G')
                    if dx != 3 or s > 0:
                        put(h, dx, -D, 'G')
                for dz in (-6, -2, 2, 6):
                    put(h, -W, dz, 'G')
                put(h, W, -6, 'G')
                put(h, W, 5, 'G')
            # the doors: the way in at the front, under its fanlight; each flat's off the landing; the bedrooms' doorways
            if s == 0 and k == 0:
                put(h, 3, -D, 'D')
            if s == 0 and k == 1:
                drop(h, 3, -D)             # the door's upper half
            if s == 0 and k == 2:
                put(h, 3, -D, 'G')         # the fanlight
            if k in (0, 1):
                drop(h, DOORWAY, -INNER)
                drop(h, DOORWAY, INNER)
            if s == 0 and k in (0, 1):
                # the ground floor's flat doors are hung once it stands (Flats): openings for now
                drop(h, HALL_WALL, -FLAT_DOOR)
                drop(h, HALL_WALL, FLAT_DOOR)
            if s > 0 and k == 0:
                put(h, HALL_WALL, -FLAT_DOOR, 'Y')
                put(h, HALL_WALL, FLAT_DOOR, 'Y')
            if s > 0 and k == 1:
                drop(h, HALL_WALL, -FLAT_DOOR)
                drop(h, HALL_WALL, FLAT_DOOR)
        # the stair: a flight to the storey above, two steps, a landing and two steps, toward the back
        if s < top:
            put(base, STAIR_X, FOOT, '^')
            put(base + 1, STAIR_X, FOOT + 1, '^')
            put(base + 1, STAIR_X, FOOT + 2, 'f')      # the landing
            put(base + 2, STAIR_X, FOOT + 3, '^')
        if s == 0:
            # under the first flight, filled
            for h, dz in ((0, -2), (0, -1), (0, 0), (1, 0), (0, 1), (1, 1), (2, 1)):
                put(h, STAIR_X, dz, 'S')
        if s == top and top > 0:
            # the top landing's rail, where the well drops more than a step (and clear of the headroom of the flight below)
            put(base, STAIR_X, FOOT, 'P')
            put(base + 1, STAIR_X, FOOT + 1, 'P')
            put(base + 1, STAIR_X, FOOT + 2, 'P')
        # the hall's lamps
        put(base + 2, 3, -6, 'j')
        put(base + 2, 3, -1, 'j')
        put(base + 2, 3, 5, 'j')
        # the flats: A at the front (side -1), B at the back (+1); the same, the one the other's mirror
        for side, beds in ((-1, front), (1, back)):
            # the living room and kitchen: a stove in the corner by the window, a worktop and a barrel beside it;
            # a table with a chair either side of it; a lamp over the table
            put(base, -4, 7 * side, '3')
            put(base, -4, 6 * side, 'T')
            put(base, -4, 5 * side, 'Q')
            put(base, -1, 6 * side, 'P'); put(base + 1, -1, 6 * side, 'X')
            put(base, -2, 6 * side, '<')
            put(base, 0, 6 * side, '>')
            put(base + 2, -1, 6 * side, 'j')
            # the bedroom: the beds' heads to the outside wall, a chest between them under the window, a lamp
            put(base, -3, 1 * side, '[')
            if beds == 2:
                put(base, -3, 3 * side, '[')
            put(base, -4, 2 * side, ')')
            put(base + 2, -1, 2 * side, 'j')
        # outside: the ground floor's window boxes; above it, each front flat's balcony on two brackets
        # (its railings are iron, put up by Flats), and a sill under each back window and the hall's
        if s == 0:
            for dx in (-3, 0):
                put(0, dx, -ROW, 'd'); put(1, dx, -ROW, '*')
            for dx in BAYS:
                put(0, dx, ROW, 'd'); put(1, dx, ROW, '*')
        else:
            for dx in range(-4, 2):
                put(base - 1, dx, -ROW, '=')
            put(base - 2, -4, -ROW, 'n')
            put(base - 2, 1, -ROW, 'n')
            if s > 1:
                put(base, 3, -ROW, '=')
            for dx in BAYS:
                put(base, dx, ROW, '=')
        # the floor above (or the garret's floor, under the roof)
        h = base + 3
        if s < top:
            for dx in range(-W, W + 1):
                for dz in range(-D, D + 1):
                    put(h, dx, dz, face(h, dx, dz) if perim(dx, dz) else 'f')
            for dz in WELL:
                drop(h, STAIR_X, dz)            # the floor open over the flight
            put(h, STAIR_X, TOP_STEP, '^')      # the flight's top step, in the floor
        else:
            for dx in range(-W, W + 1):
                for dz in range(-D, D + 1):
                    put(h, dx, dz, face(h, dx, dz) if perim(dx, dz) else 's')
    # the door's hood: a little pediment, a lantern hung under each end of it either side of the fanlight
    put(2, 2, -ROW, 'j'); put(2, 4, -ROW, 'j')
    put(3, 2, -ROW, '>'); put(3, 3, -ROW, 'R'); put(3, 4, -ROW, '<')
    put(4, 3, -ROW, '_')
    # the cornice: brackets under the eaves between the windows, front and back
    for dx in CORBELS:
        put(roof - 1, dx, -ROW, 'n')
        put(roof - 1, dx, ROW, 'u')
    # the roof: a mansard of slate, four courses up from the eaves front and back to a flat top
    for i in range(4):
        for dx in range(-W, W + 1):
            put(roof + i, dx, -ROW + i, '^')
            put(roof + i, dx, ROW - i, 'v')
    for dx in range(-W, W + 1):
        for dz in range(-(ROW - 4), ROW - 3):
            put(roof + 3, dx, dz, 'R')
    # the side walls carried up under it as stone gables
    for i in (1, 2):
        for cx in (-W, W):
            for dz in range(-(D - i), D - i + 1):
                put(roof + i, cx, dz, 'S')
    # a dormer in the front slope over the middle bay, its window two high and its own little pediment of a roof
    for dx, c in ((-1, 'S'), (0, 'G'), (1, 'S')):
        put(roof + 1, dx, -D, c)
        put(roof + 2, dx, -D, c)
    put(roof + 2, -1, -D + 1, 'S'); drop(roof + 2, 0, -D + 1); put(roof + 2, 1, -D + 1, 'S')
    for dz in range(-ROW, -ROW + 4):
        put(roof + 3, -1, dz, '>'); put(roof + 3, 0, dz, 'R'); put(roof + 3, 1, dz, '<')
        put(roof + 4, 0, dz, '_')
    # a brick chimney stack up the middle of each side wall and out over the roof
    for cx, inner in ((-W, -W + 1), (W, W - 1)):
        for h in range(roof + 1, roof + 7):
            put(h, cx, 0, 'Z')
        for h in range(roof + 4, roof + 7):
            put(h, inner, 0, 'Z')
    return layers

def text(name, storeys, layers):
    out = []
    word = {3: 'Three', 4: 'Four'}[storeys]
    out.append("# A block of flats (the Iron Age): %s storeys of flats for the young and the hard-up, two to a landing" % word.lower())
    out.append("# off a stair hall up the right-hand side, its stair a flight to each floor with a landing halfway. Each")
    out.append("# flat two rooms: a living room and kitchen by its door (a stove, a worktop, a barrel, a table and two")
    out.append("# chairs, a lamp) and through a doorway a bedroom (a bed, two for a couple's flat, a chest between them,")
    out.append("# a lamp); the ground floor's doors are hung once it stands, and the balconies' iron railings put up")
    out.append("# (Flats). Outside, a town house eleven wide and seventeen deep, for a long lot: a slate plinth, stone")
    out.append("# walls with toothed brick quoins, windows in three bays, window boxes, balconies, the front door under")
    out.append("# a fanlight and a pediment with a lantern either side, a bracketed cornice, a mansard roof of slate with")
    out.append("# a dormer, stone gables, and a brick chimney stack up each side.")
    out.append("# Drawn by tools/flats_design.py (redraw it there); rows run from the back of the building (top) to")
    out.append("# the front (bottom, the door).")
    out.append("name " + name)
    for h in sorted(layers):
        out.append("layer %d" % h)
        for dz in range(ROW, -ROW - 1, -1):
            row = ''.join(layers[h].get((dx, dz), '.') for dx in range(-W, W + 1))
            out.append(row)
    return '\n'.join(out) + '\n'

# ---------------------------------------------------------------- the walk

def at(L, h, dx, dz):
    return L.get(h, {}).get((dx, dz), '.')

def air(L, h, dx, dz):
    return at(L, h, dx, dz) in '.jX'   # a hanging lamp is high, a rug low: neither in a body's way where looked at here

def solid(c):
    return c not in '.jXP*' and c not in 'GDY'

def walk(n):
    """The stair and the landings walked: every step with three blocks of air over it; every move up it with room for a
    body two high over both places; from where each flight comes up, the way along the landing to both flats' doors and
    to the next flight's foot; and through each flat's door to its bed and its stove. Returns what is wrong (nothing,
    for a drawing to write)."""
    L = draw(n)
    bad = []
    for s in range(n - 1):
        base = 4 * s
        # the way up: the floor at the foot (stand at base), the steps, the landing, the top step in the floor above
        path = [((STAIR_X, FOOT - 1), base - 1), ((STAIR_X, FOOT), base), ((STAIR_X, FOOT + 1), base + 1),
                ((STAIR_X, FOOT + 2), base + 1), ((STAIR_X, FOOT + 3), base + 2), ((STAIR_X, TOP_STEP), base + 3),
                ((STAIR_X, TOP_STEP + 1), base + 3)]
        for i, (cell, step) in enumerate(path):
            c = at(L, step, *cell)
            if i in (0, len(path) - 1):
                if not solid(c):
                    bad.append(('nothing to stand on', s, cell, step, c))
            elif i == 3:
                if c != 'f':
                    bad.append(('no landing', s, cell, step, c))
            elif c != '^':
                bad.append(('no step', s, cell, step, c))
            if 0 < i < len(path) - 1:
                for k in (1, 2, 3):
                    if not air(L, step + k, *cell):
                        bad.append(('headroom', s, cell, step + k, at(L, step + k, *cell)))
        for (c0, s0), (c1, s1) in zip(path, path[1:]):
            # moving from c0 to c1: the body stands s1+1..s1+2 over both places
            for cc in (c0, c1):
                for k in (1, 2):
                    if not air(L, max(s0, s1) + k, *cc):
                        bad.append(('clearance', s, c0, c1, cc, max(s0, s1) + k, at(L, max(s0, s1) + k, *cc)))
    # each storey: from where a folk comes onto it (the front door, or the top of the flight), the landing to both
    # flat doors and the next flight's foot, then through each door to its bed's side and its stove's front
    for s in range(n):
        base = 4 * s
        stand, floor = base, base - 1
        def walkable(x, z):
            return solid(at(L, floor, x, z)) and air(L, stand, x, z) and air(L, stand + 1, x, z)
        start = (3, -D + 1) if s == 0 else (STAIR_X, TOP_STEP + 1)
        reach, todo = set(), [start]
        while todo:
            x, z = todo.pop()
            if (x, z) in reach or not (-W < x < W and -D < z < D):
                continue
            c = at(L, stand, x, z)
            door = c in 'DY' or (c == '.' and at(L, stand + 1, x, z) == '.' and (x, z) in ((HALL_WALL, -FLAT_DOOR), (HALL_WALL, FLAT_DOOR)))
            if not (walkable(x, z) or door and solid(at(L, floor, x, z))):
                continue
            reach.add((x, z))
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                todo.append((x + dx, z + dz))
        want = {'door A': (HALL_WALL, -FLAT_DOOR), 'door B': (HALL_WALL, FLAT_DOOR),
                'A bed side': (-2, -1), 'B bed side': (-2, 1), 'A stove front': (-3, -7), 'B stove front': (-3, 7),
                'A barrel front': (-3, -5), 'B barrel front': (-3, 5), 'A chest front': (-3, -2), 'B chest front': (-3, 2)}
        if s < n - 1:
            want['next flight'] = (STAIR_X, FOOT - 1)
        for what, cell in want.items():
            if cell not in reach:
                bad.append(('cannot get to', s, what, cell))
    return bad

if __name__ == '__main__':
    dest = sys.argv[1] if len(sys.argv) > 1 else OUT
    for name, n in (('flats', 3), ('flats4', 4)):
        wrong = walk(n)
        if wrong:
            sys.exit('%s: does not walk: %s' % (name, wrong))
        with open(os.path.join(dest, name + '.txt'), 'w') as f:
            f.write(text(name, n, draw(n)))
        counts = {}
        for cells in draw(n).values():
            for ch in cells.values():
                counts[ch] = counts.get(ch, 0) + 1
        print('wrote', name, sum(counts.values()), 'cells', sorted(counts.items()))
