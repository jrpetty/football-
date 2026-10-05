#!/usr/bin/env python3
"""Draws blueprints/flats.txt (a block of flats, three storeys) and flats4.txt (four), walking their stair
first: run it to redraw them (python3 tools/flats_design.py [out_dir]). Entity/Flats reads the same cells.

Columns dx -5..5 (left to right), rows dz +5 (back, top) .. -5 (front, bottom).
Storey s: floor at layer 4s-1, rooms 4s..4s+2. The stair hall is dx 3..4; the spiral in its
back end: A=(3,3) B=(4,3) C=(4,2) D=(3,2). Flats: dx -4..1, front dz -3..-1 (A), back dz 1..3 (B).
The walls stand at dz -4 and 4, dx -5 and 5; the front and back rows (dz -5, 5) are for what stands
out from them: the eaves, the cornice, the balconies, the sills, the window boxes, the door's hood.

Outside: a slate plinth; stone walls with toothed brick quoins at the corners; windows two high
in three bays (dx -3, 0, 3) front and back; the front door in the right-hand bay under a fanlight
and a little slate pediment with a lantern hung at each end; window boxes under the ground floor's
windows; a balcony on two brackets across each upper flat's front (its iron railings are put up by
Flats once the block stands); slate sills under the back windows and the hall's; a bracketed
cornice under the eaves; a pitched slate roof from front to back with a dormer over the middle bay,
stone gables at the ends, and a brick chimney stack rising out of each gable. (Slate once the Iron
Age's make-over comes round: the roof's parts are built in wood and slated by Ages.)
"""
import os
import sys

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "data", "mc_assistant", "blueprints")

def flat_types(s):
    # (front, back): 2 = a couple's flat (two beds), 1 = a single's
    return (2, 1) if s % 2 == 0 else (1, 2)

BAYS = (-3, 0, 3)                          # the windows' columns, front and back
CORBELS = (-5, -4, -2, -1, 1, 2, 4, 5)     # the cornice's brackets: between the bays

def draw(storeys):
    top = storeys - 1
    roof = 4 * top + 3                     # the top course, and the garret's floor
    layers = {}
    def put(h, dx, dz, c):
        layers.setdefault(h, {})[(dx, dz)] = c
    def drop(h, dx, dz):
        layers.get(h, {}).pop((dx, dz), None)
    def perim(dx, dz):
        return (abs(dx) == 5 and abs(dz) <= 4) or (abs(dz) == 4 and abs(dx) <= 5)
    def quoin(h, dx, dz):
        # Toothed: the corner every course, and one more beside it, along the front or back on an
        # even course and along the side on an odd one.
        if abs(dx) == 5 and abs(dz) == 4:
            return True
        if h % 2 == 0:
            return abs(dx) == 4 and abs(dz) == 4
        return abs(dx) == 5 and abs(dz) == 3
    def face(h, dx, dz):
        if h == 0:
            return 'R'                     # the plinth, in slate
        return 'Z' if quoin(h, dx, dz) else 'S'
    A, B, C, D = (3, 3), (4, 3), (4, 2), (3, 2)
    # the footing and the ground floor
    for dx in range(-5, 6):
        for dz in range(-4, 5):
            if perim(dx, dz):
                put(-1, dx, dz, 'F')
            elif dx >= 2:
                put(-1, dx, dz, 'F')       # the hall flagged in stone, and the partition's footing
            elif dz == 0:
                put(-1, dx, dz, 'F')
            else:
                put(-1, dx, dz, 'f')
    put(-1, 3, -5, 'k')                    # the step up to the door
    for s in range(storeys):
        base = 4 * s
        front, back = flat_types(s)
        for h in range(base, base + 3):
            k = h - base                   # 0, 1, 2 up the storey
            for dx in range(-5, 6):
                for dz in range(-4, 5):
                    if perim(dx, dz):
                        put(h, dx, dz, face(h, dx, dz))
                    elif dx == 2 and abs(dz) <= 3:
                        put(h, dx, dz, 'W')
                    elif dz == 0 and -4 <= dx <= 1:
                        put(h, dx, dz, 'W')
            # windows: two high, in three bays front and back, and on the sides
            if k in (1, 2):
                for dx in BAYS:
                    put(h, dx, 4, 'G')
                    if dx != 3 or s > 0:
                        put(h, dx, -4, 'G')
                put(h, -5, -2, 'G')
                put(h, -5, 2, 'G')
                put(h, 5, -2, 'G')
                put(h, 5, 1, 'G')
            # the doors: the way in at the front, under its fanlight, and each flat's off the landing
            if s == 0 and k == 0:
                put(h, 3, -4, 'D')
            if s == 0 and k == 1:
                drop(h, 3, -4)             # the door's upper half
            if s == 0 and k == 2:
                put(h, 3, -4, 'G')         # the fanlight
            if s == 0 and k in (0, 1):
                # the ground floor's flat doors are hung once the block stands (Flats): openings for now
                drop(h, 2, -2)
                drop(h, 2, 1)
            if s > 0 and k == 0:
                put(h, 2, -2, 'Y')
                put(h, 2, 1, 'Y')
            if s > 0 and k == 1:
                drop(h, 2, -2)
                drop(h, 2, 1)
        # the stair: a flight to the storey above
        if s < top:
            put(base, *A, '^')
            put(base + 1, *B, '>')
            put(base + 2, *C, 'v')
        if s == 0:
            put(0, *B, 'S')                 # under the first flight, filled
            put(0, *C, 'S')
            put(1, *C, 'S')
        if s > 0:
            put(base, 4, 1, 'P')            # a rail at the stairwell's edge
        if s == top and top > 0:
            put(base, *A, 'P')
        # the hall's lantern
        put(base + 2, 4, -1, 'j')
        # the flats' furniture, and a lantern over each bed's head
        if front == 2:
            put(base, -4, -2, 'B'); put(base, -3, -2, 'B')
        else:
            put(base, -4, -2, 'B')
        put(base, -4, -3, 'c')
        put(base, 0, -3, 'P'); put(base + 1, 0, -3, 'X')
        put(base + 2, -4, -1, 'j')
        if back == 2:
            put(base, -4, 2, 'B'); put(base, -3, 2, 'B')
            put(base, -2, 3, 'C')
        else:
            put(base, -4, 2, 'B')
            put(base, -3, 3, 'C')
        put(base, 0, 3, 'P'); put(base + 1, 0, 3, 'X')
        put(base + 2, -4, 3, 'j')
        # outside: the ground floor's window boxes; above it, each front flat's balcony on two brackets
        # (its railings are iron, put up by Flats), and a sill under each back window and the hall's
        if s == 0:
            for dx in (-3, 0):
                put(0, dx, -5, 'd'); put(1, dx, -5, '*')
            for dx in BAYS:
                put(0, dx, 5, 'd'); put(1, dx, 5, '*')
        else:
            for dx in range(-4, 2):
                put(base - 1, dx, -5, '=')
            put(base - 2, -4, -5, 'n')
            put(base - 2, 1, -5, 'n')
            if s > 1:
                put(base, 3, -5, '=')
            for dx in BAYS:
                put(base, dx, 5, '=')
        # the floor above (or the garret's floor, under the roof)
        h = base + 3
        if s < top:
            for dx in range(-5, 6):
                for dz in range(-4, 5):
                    put(h, dx, dz, face(h, dx, dz) if perim(dx, dz) else 'f')
            for cell in (A, B, C):
                layers[h].pop(cell, None)       # the stairwell's open well
            put(h, *D, '<')                     # the flight's top step, in the floor
        else:
            for dx in range(-5, 6):
                for dz in range(-4, 5):
                    put(h, dx, dz, face(h, dx, dz) if perim(dx, dz) else 's')
    # the door's hood: a little pediment, a lantern hung under each end of it either side of the fanlight
    put(2, 2, -5, 'j'); put(2, 4, -5, 'j')
    put(3, 2, -5, '>'); put(3, 3, -5, 'R'); put(3, 4, -5, '<')
    put(4, 3, -5, '_')
    # the cornice: brackets under the eaves between the windows, front and back
    for dx in CORBELS:
        put(roof - 1, dx, -5, 'n')
        put(roof - 1, dx, 5, 'u')
    # the roof: slate, pitched front and back from the eaves to a ridge over the middle
    for i in range(5):
        for dx in range(-5, 6):
            put(roof + i, dx, -5 + i, '^')
            put(roof + i, dx, 5 - i, 'v')
    for dx in range(-5, 6):
        put(roof + 4, dx, 0, 'R')
        put(roof + 5, dx, 0, '_')
    # a dormer over the middle bay, its window two high and its own little pediment of a roof
    for dx, c in ((-1, 'S'), (0, 'G'), (1, 'S')):
        put(roof + 1, dx, -4, c)
        put(roof + 2, dx, -4, c)
    put(roof + 2, -1, -3, 'S'); drop(roof + 2, 0, -3); put(roof + 2, 1, -3, 'S')
    for dz in range(-5, -1):
        put(roof + 3, -1, dz, '>'); put(roof + 3, 0, dz, 'R'); put(roof + 3, 1, dz, '<')
        put(roof + 4, 0, dz, '_')
    # the gables, stone, and a brick chimney stack up the middle of each and out over the ridge
    for i in range(1, 4):
        for cx in (-5, 5):
            for dz in range(-(4 - i), 5 - i):
                put(roof + i, cx, dz, 'S')
    for cx, inner in ((-5, -4), (5, 4)):
        for h in range(roof + 1, roof + 8):
            put(h, cx, 0, 'Z')
        for h in range(roof + 4, roof + 8):
            put(h, inner, 0, 'Z')
    return layers

def text(name, storeys, layers):
    out = []
    word = {3: 'Three', 4: 'Four'}[storeys]
    out.append("# A block of flats (the Iron Age): %s storeys of small flats for the young and the hard-up," % word.lower())
    out.append("# two to a landing, off a stair that winds up the back of the hall. Each flat a room with a bed (two")
    out.append("# for a couple's flat), a chest, a table and a lantern; the ground floor's doors are hung once it")
    out.append("# stands, and the balconies' iron railings put up (Flats). Outside, a town house: a slate plinth,")
    out.append("# stone walls with toothed brick quoins, windows in three bays, window boxes, balconies, the front")
    out.append("# door under a fanlight and a pediment with a lantern either side, a bracketed cornice, a pitched")
    out.append("# slate roof with a dormer, stone gables, and a brick chimney stack out of each gable.")
    out.append("# Drawn by tools/flats_design.py (redraw it there); rows run from the back of the building (top) to")
    out.append("# the front (bottom, the door).")
    out.append("name " + name)
    for h in sorted(layers):
        out.append("layer %d" % h)
        for dz in range(5, -6, -1):
            row = ''.join(layers[h].get((dx, dz), '.') for dx in range(-5, 6))
            out.append(row)
    return '\n'.join(out) + '\n'

def at(L, h, dx, dz):
    return L.get(h, {}).get((dx, dz), '.')

def air(L, h, dx, dz):
    c = at(L, h, dx, dz)
    return c in '.jX'   # lanterns hang high, carpets lie low: neither in the way here (checked separately)

def walk(n):
    """The stair walked: every step's headroom and every move's clearance up it, and each landing to its
    flats' doors. Returns what is wrong (nothing, for a drawing to write)."""
    L = draw(n)
    A, B, C, D = (3, 3), (4, 3), (4, 2), (3, 2)
    bad = []
    for s in range(n - 1):
        base = 4 * s
        # the path: D (floor, stand at base) -> A (base) -> B (base+1) -> C (base+2) -> D (base+3)
        path = [(D, base - 1), (A, base), (B, base + 1), (C, base + 2), (D, base + 3)]
        for (cell, step) in path:
            c = at(L, step, *cell)
            if s == 0 and cell == D and step == -1:
                if c not in 'fF':
                    bad.append(('ground under D', c))
            elif c not in '<>^v':
                bad.append(('no step', s, cell, step, c))
            for k in (1, 2):
                if not air(L, step + k, *cell):
                    bad.append(('headroom', s, cell, step + k, at(L, step + k, *cell)))
        for (c0, s0), (c1, s1) in zip(path, path[1:]):
            # moving up from c0 to c1: the body rises to s1+1..s1+2 over both columns
            for cc in (c0, c1):
                for k in (1, 2):
                    if not air(L, s1 + k, *cc):
                        bad.append(('clearance', s, c0, c1, cc, s1 + k, at(L, s1 + k, *cc)))
    # each storey: the landing reaches both doors, and D
    for s in range(n):
        base = 4 * s
        stand = base
        floor = base - 1
        reach = set()
        start = (3, 1)
        todo = [start]
        while todo:
            x, z = todo.pop()
            if (x, z) in reach:
                continue
            if not (3 <= x <= 4 and -3 <= z <= 3):
                continue
            under = at(L, floor, x, z)
            if under == '.' and not (s == 0):
                continue
            if not (air(L, stand, x, z) and air(L, stand + 1, x, z)) and (x, z) != D:
                continue
            reach.add((x, z))
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                todo.append((x + dx, z + dz))
        for door in ((3, -2), (3, 1)):
            if door not in reach:
                bad.append(('door unreachable', s, door, sorted(reach)))
        if s < n - 1 and D not in reach and not (s == 0):
            bad.append(('no way onto the stair', s))
    return bad

if __name__ == '__main__':
    dest = sys.argv[1] if len(sys.argv) > 1 else OUT
    for name, n in (('flats', 3), ('flats4', 4)):
        wrong = walk(n)
        if wrong:
            sys.exit('%s: the stair does not walk: %s' % (name, wrong))
        with open(os.path.join(dest, name + '.txt'), 'w') as f:
            f.write(text(name, n, draw(n)))
        counts = {}
        for cells in draw(n).values():
            for ch in cells.values():
                counts[ch] = counts.get(ch, 0) + 1
        print('wrote', name, sorted(counts.items()))
