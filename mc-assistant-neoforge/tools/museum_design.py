#!/usr/bin/env python3
"""Draws blueprints/museum.txt, the museum and the archive: run it to redraw it
(python3 tools/museum_design.py [out_dir]). Entity/Museum and entity/Archive read the same places.

Columns dx -5..6 (left to right; the drawing is twelve wide so that its middle column, dx 0, is the
left-hand leaf of the double door and the building is the same either side of dx 0.5), rows dz +5
(back, top) .. -5 (front, bottom). Nothing stands out past dx -4..5 on the ground, so the building
fits a civic lot (eleven square).

The hall is Museum's own: its floor one block up on the plinth (layer 0, walked on at layer 1) and set
one block back to leave room for the portico (Museum.HALL_UP, HALL_BACK). In the hall's own terms its
walls stand at dx -4 and 5, dz -4 and 4, its floor is hall-height -1 and it is five high, and every
place something goes on show (Museum.PLACES), the lectern and the archive's shelves are where they
always were.

Outside:
* a plinth of rough stone (dressed once the Iron Age's make-over comes round, mossed in the Diamond
  Age's), the hall's floor of dressed stone with two glass cases let into it;
* a portico across the front: four columns of dressed stone five high on a stylobate, a stair four
  wide up between the middle two, a lantern on a stone post either side of it, two lanterns hung from
  the entablature over the door;
* the double door under a glass fanlight with a window either side as high as it, and over the door
  the museum's name; a banner in the town's colours either side (Museum puts those up, out of the stores);
* tall windows down each side, three panes high in a stone frame, between the frames inside;
* a flat roof with a skylight over the middle of the hall and a parapet round it, stone slabs between
  piers at the corners; over the portico a low pediment, half a block a step, its face of stone and its
  raking edge of the town's roofing.
"""
import os
import sys

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "data", "mc_assistant", "blueprints")

X0, X1 = -5, 6            # the drawing's columns
Z1, Z0 = 5, -5            # its rows, back to front
LEFT, RIGHT = -4, 5       # the side walls
BACK, FRONT = 5, -3       # the back wall and the front wall
PORCH, STYLOBATE = -4, -5
TOP = 6                   # the ceiling and the entablature: the hall is five high (layers 1..5)
COLUMNS = (-4, -2, 3, 5)
POSTS = (-3, 4)           # the lanterns on stone posts, either side of the stair
STAIR = (-1, 0, 1, 2)
DOOR = (0, 1)
WINDOWS = (2, 0, -2)      # down each side, between the frames (dz 3, 1, -1 inside)
FRONT_WINDOWS = (-1, 2)   # either side of the door, as high as its fanlight
CASES = ((-2, 1), (3, 1))
SKYLIGHT = ((-1, 2), (0, 2))   # dx range, dz range (inclusive)
LIGHTS = ((-2, 3), (3, 3), (-2, -1), (3, -1))   # hung inside, under the ceiling
PORCH_LIGHTS = (-1, 2)


def draw():
    layers = {}

    def put(h, dx, dz, c):
        layers.setdefault(h, {})[(dx, dz)] = c

    def ring(dx, dz):
        return (dx in (LEFT, RIGHT) and FRONT <= dz <= BACK) or (dz in (FRONT, BACK) and LEFT <= dx <= RIGHT)

    # The plinth and the floor; the porch and the stylobate with its stair. (It wants no footing under
    # it: the builders make the lot's ground up to the plinth's foot before they lay a stone.)
    for dx in range(LEFT, RIGHT + 1):
        for dz in range(FRONT, BACK + 1):
            put(0, dx, dz, 'F' if ring(dx, dz) else ('O' if (dx, dz) in CASES else 'S'))
        put(0, dx, PORCH, 'F')
        put(0, dx, STYLOBATE, 'k' if dx in STAIR else 'F')
    # The hall's walls, five courses.
    for h in range(1, TOP):
        for dx in range(LEFT, RIGHT + 1):
            for dz in range(FRONT, BACK + 1):
                if not ring(dx, dz):
                    continue
                c = 'S'
                if dz == FRONT and dx in DOOR:
                    c = 'D' if h == 1 else None if h == 2 else 'O' if h == 3 else 'S'
                elif dx in (LEFT, RIGHT) and dz in WINDOWS and 2 <= h <= 4:
                    c = 'G'
                elif dz == FRONT and dx in FRONT_WINDOWS and 2 <= h <= 3:
                    c = 'G'
                if c:
                    put(h, dx, dz, c)
    # The portico: the columns, the lanterns on their posts, the lanterns over the door.
    for dx in COLUMNS:
        for h in range(1, TOP):
            put(h, dx, STYLOBATE, 'S')
    for dx in POSTS:
        put(1, dx, STYLOBATE, 'S')
        put(2, dx, STYLOBATE, 'l')
    for dx in PORCH_LIGHTS:
        put(TOP - 1, dx, PORCH, 'j')
    # Inside: the lectern, and the lanterns hung from the ceiling.
    put(1, 0, 4, 'r')
    for dx, dz in LIGHTS:
        put(TOP - 1, dx, dz, 'j')
    # The ceiling and roof, with its skylight; the entablature over the portico.
    for dx in range(LEFT, RIGHT + 1):
        for dz in range(STYLOBATE, BACK + 1):
            sky = SKYLIGHT[0][0] <= dx <= SKYLIGHT[0][1] and SKYLIGHT[1][0] <= dz <= SKYLIGHT[1][1]
            put(TOP, dx, dz, 'O' if sky else 'S')
    # The parapet round the roof: piers at the back corners, slabs between (it meets the pediment in front).
    for dx in range(LEFT, RIGHT + 1):
        for dz in range(FRONT + 1, BACK + 1):
            if not (dx in (LEFT, RIGHT) or dz == BACK):
                continue
            post = dz == BACK and dx in (LEFT, RIGHT)
            put(TOP + 1, dx, dz, 'S' if post else 's')
    # The pediment over the portico and the front wall: a low gable, half a block a step, its face of
    # stone and its raking edge of the town's roofing (a slab where the step is half a block, a whole
    # block where it is a whole one), so that it reads against the sky as a roof over the portico.
    for dz in (STYLOBATE, PORCH, FRONT):
        for dx in range(LEFT, RIGHT + 1):
            rise = min(dx - LEFT, RIGHT - dx)            # 0 at the ends, 4 in the middle two
            # Its height in half blocks over the entablature: 1, 2, 3, 4, 5 from the ends in.
            half = rise + 1
            whole = half // 2
            for k in range(whole):
                put(TOP + 1 + k, dx, dz, 'S' if (k < whole - 1 or half % 2) else 'R')
            if half % 2:
                put(TOP + 1 + whole, dx, dz, '_')
    return layers


def text(layers):
    lines = ["# " + l if l else "#" for l in [
        "The museum and the archive (drawn by tools/museum_design.py: change that, and run it).",
        "A hall of dressed stone on a plinth behind a portico of four columns under a low pediment, a",
        "stair up between the middle two, a lantern on a post either side; a double door under a",
        "fanlight with a window either side, tall windows down the sides, a flat roof with a skylight",
        "and a parapet. Entity/Museum puts the museum's name up over the door and a banner in the town's",
        "colours either side, out of the stores. Inside",
        "(entity/Museum hangs the frames and the labels, stands the stands, sets the jukebox and the",
        "cases as they are wanted): two glass cases let into the floor, the lectern at the back where the",
        "latest volume of the chronicle lies open, and the archive's shelves either side of it.",
        "Rows run from the back of the building (top) to the front (bottom, the door)."]]
    lines.append("name museum")
    for h in sorted(layers):
        lines.append("layer %d" % h)
        for dz in range(Z1, Z0 - 1, -1):
            row = ''.join(layers[h].get((dx, dz), '.') for dx in range(X0, X1 + 1))
            lines.append(row)
    return '\n'.join(lines) + '\n'


if __name__ == '__main__':
    dest = sys.argv[1] if len(sys.argv) > 1 else OUT
    with open(os.path.join(dest, 'museum.txt'), 'w') as f:
        f.write(text(draw()))
    print("museum.txt written to", os.path.abspath(dest))
