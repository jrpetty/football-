# Draws blueprints/townhall.txt, the leader's hall, layer by layer (run it to redraw the file).
W, D = 13, 23            # cols 0..12 (centre 6), rows 0..22 (centre 11): back at row 0, front (door) at row 22
LAYERS = range(-1, 25)
G = {h: [['.'] * W for _ in range(D)] for h in LAYERS}

def put(h, r, c, ch):
    G[h][r][c] = ch

BODY = (1, 11, 2, 16)    # c0, c1, r0, r1 (walls on the outline)
TOWER = (4, 8, 16, 20)

def outline(box):
    c0, c1, r0, r1 = box
    for r in range(r0, r1 + 1):
        for c in range(c0, c1 + 1):
            if r in (r0, r1) or c in (c0, c1):
                yield r, c

def inside(box):
    c0, c1, r0, r1 = box
    for r in range(r0 + 1, r1):
        for c in range(c0 + 1, c1):
            yield r, c

def in_tower(r, c):
    return TOWER[0] <= c <= TOWER[1] and TOWER[2] <= r <= TOWER[3]

# ---- footing and floors (layer -1)
for r, c in outline(BODY): put(-1, r, c, 'F')
for r, c in inside(BODY): put(-1, r, c, 'f')
for r, c in outline(TOWER): put(-1, r, c, 'F')
for r, c in inside(TOWER): put(-1, r, c, 'S')
for c in (5, 6, 7): put(-1, 16, c, 'S')          # the arch between porch and hall: paved
for r in range(5, 16): put(-1, r, 6, 'S')       # a stone aisle up the middle of the hall
for c in (5, 6, 7): put(-1, 21, c, 'k')          # steps up to the door
for c in (2, 3, 9, 10): put(-1, 21, c, 'd')      # flower beds either side

# ---- the ground storey: dressed stone, tall windows (layers 0..3)
corner = {(2, 1), (2, 11), (16, 1), (16, 11)}
for h in range(0, 4):
    for r, c in outline(BODY):
        if in_tower(r, c):
            continue
        ch = 'w' if h == 0 else 'S'
        side = c in (1, 11) and 3 <= r <= 15
        if side and h in (1, 2) and r % 4 != 1:       # pairs of tall windows between stone piers
            ch = 'G'
        if (r, c) in corner or (c in (1, 11) and r % 4 == 1):
            ch = 'S'
        put(h, r, c, ch)
    # back wall: a great window behind the leader's seat
    if h in (2, 3):
        for c in (5, 6, 7): put(h, 2, c, 'O')
# the tower's porch
for h in range(0, 4):
    for r, c in outline(TOWER):
        ch = 'w' if h == 0 else 'S'
        if r == 16 and c in (5, 6, 7):
            ch = '.' if h <= 2 else 'S'                # the archway into the hall
        if r == 18 and c in (4, 8) and h in (1, 2):
            ch = 'G'
        if r == 20 and c == 6:
            ch = 'D' if h == 0 else '.' if h == 1 else 'S'
        if r == 20 and c in (5, 7) and h in (1, 2):
            ch = 'G'
        put(h, r, c, ch)
put(0, 21, 4, 'l'); put(0, 21, 8, 'l')           # lanterns either side of the steps
for c in (2, 3, 9, 10): put(0, 21, c, '*')

# ---- the great hall's furnishings
for c in range(4, 9): put(0, 3, c, 'S')          # the dais
for c in range(4, 9): put(0, 4, c, 'S')
for c in (5, 6, 7): put(0, 5, c, 'k')            # steps up to it
put(1, 3, 6, '^')                                # the leader's seat, facing the hall
put(1, 3, 5, 'l'); put(1, 3, 7, 'l')             # lamps either side of it
put(1, 4, 4, 'r'); put(1, 4, 8, 'r')             # the clerks' lecterns
for h in (0, 1, 2):
    for c in (2, 3, 9): put(h, 3, c, 'K')        # walls of books
put(0, 3, 10, 'H'); put(1, 3, 10, 'H'); put(2, 3, 10, 'H'); put(3, 3, 10, 'H')   # the stair (ladder) up
for r in range(6, 16): put(0, r, 6, 'X')         # the red runner up the aisle
for r in (17, 18, 19): put(0, r, 6, 'X')
put(0, 16, 6, 'X')
for r in (7, 9, 11, 13):                         # benches facing the dais
    for c in (3, 4, 5, 7, 8, 9): put(0, r, c, 'v')
put(0, 15, 2, 'C'); put(0, 15, 10, 'C')          # the village's papers
put(0, 14, 2, 'Q'); put(0, 14, 10, 'Q')
for r, c in ((6, 6), (10, 6), (14, 6), (8, 3), (8, 9), (12, 3), (12, 9)):
    put(3, r, c, 'j')                            # lanterns hung from the beams
put(3, 18, 6, 'j')

# ---- the floor between the storeys (layer 4): beams and boards
for r, c in outline(BODY):
    if in_tower(r, c): continue
    put(4, r, c, 'L' if (r, c) in corner else '-' if r in (2, 16) else '|')
for r, c in inside(BODY): put(4, r, c, 'f')
put(4, 3, 10, 'H')
for r, c in outline(TOWER): put(4, r, c, 'S')
for r, c in inside(TOWER): put(4, r, c, 'f')
for c in (5, 6, 7): put(4, 16, c, 'f')
# the eaves of the lower storey: a skirt of roof round the stone hall
for r in range(1, 18):
    if not in_tower(r, 0): put(4, r, 0, '>')
    put(4, r, 12, '<')
for c in range(0, 13):
    if G[4][1][c] == '.': put(4, 1, c, 'v')
for c in list(range(0, 4)) + list(range(9, 13)):
    put(4, 17, c, '^')

# ---- the upper storey: timber frame on the stone (layers 5..7)
for h in range(5, 8):
    for r, c in outline(BODY):
        if in_tower(r, c): continue
        ch = 'W'
        if (r, c) in corner or (c in (1, 11) and r % 4 == 2) or (r in (2, 16) and c in (1, 4, 8, 11)):
            ch = 'L'
        elif h in (5, 6) and (c in (1, 11) and r % 2 == 1 or r == 2 and c in (3, 5, 6, 7, 9) or r == 16 and c in (2, 3, 9, 10)):
            ch = 'G'
        put(h, r, c, ch)
    for r, c in outline(TOWER):
        ch = 'S'
        if r == 16 and c in (5, 6, 7) and h in (5, 6): ch = '.'     # through to the council chamber
        if (r == 20 and c in (5, 6, 7) or c in (4, 8) and r == 18) and h in (5, 6): ch = 'G'
        put(h, r, c, ch)
# the leader's rooms at the back: a bed for two, the children's beds across the room
put(5, 4, 5, 'B'); put(5, 4, 6, 'B')
put(5, 3, 4, 'l'); put(5, 3, 7, 'l')
put(5, 3, 2, 'C')
for h in (5, 6): put(h, 3, 8, 'K'); put(h, 3, 9, 'K')
put(5, 3, 10, 'H'); put(6, 3, 10, 'H')
put(5, 8, 2, 'b'); put(5, 8, 3, 'b')             # the children's beds, the other end of the room, heads to the partition
for r in (6, 7):
    for c in (4, 5, 6, 7): put(5, r, c, 'X')
put(5, 8, 10, 'Q'); put(5, 9, 10, 'T')
for c in range(2, 11):                           # a partition, with a door in it
    for h in (5, 6, 7):
        put(h, 10, c, 'W' if c != 6 else ('D' if h == 5 else '.' if h == 6 else 'W'))
# the council chamber: a long table, the leader at its head
for r in (12, 13, 14):
    put(5, r, 6, 'P'); put(6, r, 6, 'X')
    put(5, r, 5, '>'); put(5, r, 7, '<')
put(5, 11, 6, '^')
put(6, 12, 6, 'l'); put(6, 14, 6, 'l')
put(5, 15, 2, 'K'); put(5, 15, 10, 'K'); put(5, 11, 2, 'C'); put(5, 11, 10, 'K')
# the study in the tower: the leader's desk
put(5, 19, 6, 'r'); put(5, 17, 5, 'K'); put(5, 17, 7, 'K'); put(5, 19, 5, 'l')

# ---- the main roof (layers 8..14): steep, the length of the hall
for r in range(1, 18):
    if in_tower(r, 6) and r >= 16: pass
for k, h in enumerate(range(8, 15)):
    lo, hi = k, 12 - k
    for r in range(1, 18):
        if lo < hi:
            if not in_tower(r, lo): put(h, r, lo, '>')
            if not in_tower(r, hi): put(h, r, hi, '<')
        elif lo == hi:
            if not in_tower(r, lo): put(h, r, lo, '_')
    # gables at both ends, between the slopes
    for r in (2, 16):
        for c in range(lo + 1, hi):
            if not in_tower(r, c): put(h, r, c, 'W' if not (h == 10 and c == 6) else 'G')
    if h == 8:
        for r, c in outline(BODY):
            if in_tower(r, c): continue
            if G[h][r][c] == '.': put(h, r, c, 'L' if (r, c) in corner else '-' if r in (2, 16) else '|')

# ---- the tower above the roof (layers 8..19)
for h in range(8, 20):
    for r, c in outline(TOWER):
        ch = 'S'
        if h in (17, 18) and (r in (16, 20) and c in (5, 6, 7) or c in (4, 8) and r in (17, 18, 19)):
            ch = '.'                                 # the open lantern-room at the top
        if h in (10, 14) and (r == 20 and c == 6 or c in (4, 8) and r == 18):
            ch = 'G'
        put(h, r, c, ch)
    for r, c in inside(TOWER):
        put(h, r, c, 'f' if h in (8, 12, 16) else 'S' if h == 19 else '.')
put(18, 18, 6, 'j')                              # the great lantern, seen from all round
for (r, c) in ((16, 4), (16, 8), (20, 4), (20, 8)):
    put(17, r, c, 'S'); put(18, r, c, 'S')
# the tower's spire
ring = [(20, 3, 9, 15, 21), (21, 4, 8, 16, 20), (22, 5, 7, 17, 19)]
for h, c0, c1, r0, r1 in ring:
    for r in range(r0, r1 + 1):
        for c in range(c0, c1 + 1):
            if r == r0: ch = 'v'
            elif r == r1: ch = '^'
            elif c == c0: ch = '>'
            elif c == c1: ch = '<'
            else: ch = 'R'
            put(h, r, c, ch)
put(23, 18, 6, 'R')
put(24, 18, 6, 'l')

out = ["# The leader's hall: the seat of whoever the village chose to lead it, and the best and biggest",
       "# building in the town. A great hall of dressed stone below, its walls tall windows between stone",
       "# piers, benches down either side of a red runner to the dais, the leader's seat under a great window",
       "# and walls of books behind it; a timber storey above, the leader's family's rooms at the back and the",
       "# council chamber at the front with its long table; and over the door a stone tower four storeys high,",
       "# the leader's study in it and a lantern-room at the top that can be seen from the fields.",
       "# Rows run from the back of the building (top) to the front (bottom, the door).",
       "name townhall"]
for h in LAYERS:
    rows = [''.join(row) for row in G[h]]
    if all(set(r) == {'.'} for r in rows):
        continue
    out.append(f"layer {h}")
    out.extend(rows)
import os
path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "data", "mc_assistant", "blueprints", "townhall.txt")
open(path, 'w').write('\n'.join(out) + '\n')
print("written", path)
