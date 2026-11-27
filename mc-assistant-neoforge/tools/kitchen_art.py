"""[kitchen] The kitchen's pixel art: 16x16, the vanilla way (an outline, two or three shades, a highlight).

python3 kitchen_art.py <assets/mc_assistant dir> [preview dir]
"""
import math
import os
import sys

from PIL import Image

OUT = sys.argv[1]
PREVIEW = sys.argv[2] if len(sys.argv) > 2 else None


def hexc(h, a=255):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def grid(rows, pal):
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        assert len(row) == 16, (y, row, len(row))
        for x, ch in enumerate(row):
            if ch == '.':
                continue
            img.putpixel((x, y), hexc(pal[ch]))
    return img


def save(img, rel):
    path = os.path.join(OUT, 'textures', rel + '.png')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    if PREVIEW:
        big = img.resize((256, 256), Image.NEAREST)
        bg = Image.new('RGBA', big.size, (60, 60, 70, 255))
        bg.alpha_composite(big)
        bg.save(os.path.join(PREVIEW, rel.replace('/', '_') + '.png'))


def outline(img, dark):
    """Every opaque pixel touching transparency (or the edge) gets its region's dark shade."""
    w, h = img.size
    src = img.copy()
    for y in range(h):
        for x in range(w):
            p = src.getpixel((x, y))
            if p[3] == 0:
                continue
            edge = False
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if nx < 0 or ny < 0 or nx >= w or ny >= h or src.getpixel((nx, ny))[3] == 0:
                    edge = True
            if edge:
                img.putpixel((x, y), dark(p))
    return img


def darker(p, f=0.55):
    return (int(p[0] * f), int(p[1] * f), int(p[2] * f), 255)


# ---------------------------------------------------------------------------------------------- the packed lunch
# A little cloth bundle, red and cream gingham, gathered at the top into two ears and tied with string.
LUNCH_MASK = [
    "................",
    "....##....##....",
    "...####..####...",
    "...#####.####...",
    "....########....",
    "......####......",
    ".....######.....",
    "...##########...",
    "..############..",
    ".##############.",
    ".##############.",
    ".##############.",
    ".##############.",
    "..############..",
    "...##########...",
    "................",
]


def packed_lunch():
    red = [hexc('#e05a48'), hexc('#c23a2c'), hexc('#8e2519')]
    cream = [hexc('#fffbef'), hexc('#efe4cc'), hexc('#c9b996')]
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(LUNCH_MASK):
        for x, ch in enumerate(row):
            if ch != '#':
                continue
            check = ((x // 2) + (y // 2)) % 2 == 0
            # Light from the upper left: lighter there, darker to the lower right; the gathers darker.
            light = (x + y) / 30.0
            shade = 0 if light < 0.45 else 1 if light < 0.85 else 2
            if y >= 6 and x in (5, 10) and y <= 9:
                shade = min(2, shade + 1)                # a gather in the cloth
            if y in (12, 13):
                shade = max(shade, 1)
            pal = red if check else cream
            img.putpixel((x, y), pal[shade])
    # The string round the neck, and its knot and tails.
    for x in range(5, 11):
        img.putpixel((x, 5), hexc('#e2cd92') if x % 2 else hexc('#b89a5c'))
    img.putpixel((10, 5), hexc('#e2cd92'))
    img.putpixel((11, 6), hexc('#b89a5c'))
    img.putpixel((12, 7), hexc('#e2cd92'))
    img = outline(img, lambda p: hexc('#5c1d14') if p[0] > p[2] + 40 and p[1] < 150 else hexc('#6a5a40') if p[1] > 150 else hexc('#5c4a2a'))
    # The string's knot over the outline, and a highlight on the bundle's shoulder.
    img.putpixel((5, 5), hexc('#8a6e3a'))
    img.putpixel((10, 5), hexc('#8a6e3a'))
    img.putpixel((3, 8), hexc('#ffffff'))
    img.putpixel((4, 8), hexc('#fff2d0'))
    return img


# ---------------------------------------------------------------------------------------------- cylinders
def cylinder(cx, cy, rx, ry, height, notch=None):
    """Which face each pixel shows of a disc rx by ry (on screen), height tall, seen from the front and above:
    'top', 'side' (with its angle on the rim), 'cutL', 'cutR' (the faces of a wedge cut out of the front between the
    angles in notch, in degrees, 90 being straight to the front), or None."""
    faces = {}
    for py in range(16):
        for px in range(16):
            x, y = px + 0.5, py + 0.5
            dx = (x - cx) / rx
            dy = (y - cy) / ry
            in_top = dx * dx + dy * dy <= 1.0
            ang = math.degrees(math.atan2(dy, dx))
            notched = notch is not None and notch[0] <= ang <= notch[1]
            if in_top and not notched:
                faces[(px, py)] = ('top', ang, math.sqrt(dx * dx + dy * dy))
                continue
            # The side: below the front arc, height deep.
            if abs(dx) <= 1.0:
                front = cy + ry * math.sqrt(max(0.0, 1.0 - dx * dx))
                t = y - front
                if 0 <= t <= height and not in_top:
                    a = math.degrees(math.atan2(math.sqrt(max(0.0, 1.0 - dx * dx)), dx))
                    if not (notch is not None and notch[0] <= a <= notch[1]):
                        faces[(px, py)] = ('side', a, t / height)
                        continue
            if notch is not None:
                for name, deg in (('cutL', notch[1]), ('cutR', notch[0])):
                    ex, ey = math.cos(math.radians(deg)), math.sin(math.radians(deg))
                    # A point on the radius to the rim at this angle, s along it, t down from the top.
                    if abs(ex) < 1e-6:
                        continue
                    s = (x - cx) / (rx * ex)
                    if 0 <= s <= 1:
                        t = y - (cy + ry * ey * s)
                        if 0 <= t <= height:
                            faces[(px, py)] = (name, s, t / height)
                            break
    return faces


def cheese_wheel_item():
    # A wheel seen from the front and above, a wedge cut out of its front right: the waxed top, the rind round it, and
    # the pale cheese and its holes where it was cut.
    rows = [
        "................",
        "................",
        "....OOOOOOOO....",
        "..OOrTTTTttrOO..",
        ".OrTTTtttttttrO.",
        "OrTTttttttOOOOOO",
        "OrtttttttOiIIIIO",
        "OrttttttOkiIhIIO",
        "OsrrrrrrOkiIIIhO",
        "OSSssssOkkiIhIIO",
        "OSsssssOkkiIIIIO",
        "OsssssdOkkRRRRRO",
        ".OddddOkROOOOOO.",
        "..OOOOOOO.......",
        "................",
        "................",
    ]
    pal = {
        'O': '#5e3a12', 'r': '#e0a532', 't': '#f6c84a', 'T': '#ffe07e',
        's': '#d68a26', 'S': '#eca83a', 'd': '#a8661c',
        'i': '#fbe49a', 'I': '#fff3c4', 'h': '#d6ac4e', 'k': '#e8c470', 'R': '#c88228',
    }
    return grid(rows, pal)


def honey_cake():
    # A round cake, golden with honey, the glaze running down its sides; a slice out of the front right shows the
    # sponge and the cream between its layers.
    rows = [
        "................",
        "................",
        "....OOOOOOOO....",
        "..OOrGbbbggrOO..",
        ".OrGGbhBhbgggrO.",
        "OrGGgbbbggOOOOOO",
        "OrgggggggOyyyyyO",
        "OrrgggggOYSSSSSO",
        "OyrrryrrOkcccccO",
        "OyyYyyyOkkSsSSsO",
        "OcyccycOkkSSsSSO",
        "OsssysdOkkdddddO",
        ".OddsdOkdOOOOOO.",
        "..OOOOOOO.......",
        "................",
        "................",
    ]
    pal = {
        'O': '#5a2e08', 'r': '#d8891c', 'g': '#f4a82a', 'G': '#ffd468', 'H': '#fff2b8',
        'y': '#f0a020', 'Y': '#c97a12',
        's': '#efc376', 'S': '#fbdc9c', 'd': '#c08e44', 'c': '#fff3d6', 'k': '#d9a85a',
        'b': '#a86a10', 'h': '#ffcc40', 'B': '#ffe890',
    }
    return grid(rows, pal)


def fish_pie():
    # A pie in its earthenware dish: a crimped edge, and a lattice of pastry strips over the fish and its sauce.
    rows = [
        "................",
        "................",
        "................",
        ".....OOOOOO.....",
        "...OOcCcCcCOO...",
        "..OcCfLffLfCcO..",
        ".OcLLLLLLLLLLcO.",
        "OcfgLffLfpLffLcO",
        "OcffLfpLffLfgLcO",
        "OcLLLLLLLLLLLLcO",
        "OCffLgfLpfLffLCO",
        ".OcCcCcCcCcCcCO.",
        ".ODddddddddddDO.",
        "..OOOOOOOOOOOO..",
        "................",
        "................",
    ]
    pal = {
        'O': '#4e2210', 'c': '#c4823a', 'C': '#eebc5c', 'L': '#e2a646',
        'f': '#f2b8a0', 'p': '#e07a5a', 'g': '#7aa850',
        'D': '#a44a28', 'd': '#c8643a',
    }
    img = grid(rows, pal)
    # The light from the upper left on the lattice, and the strips' shadow on the filling below them.
    for (x, y) in ((4, 5), (3, 6), (4, 6), (5, 6), (6, 6), (4, 7), (2, 9), (3, 9), (4, 9), (5, 9)):
        if img.getpixel((x, y))[:3] == hexc('#e2a646')[:3]:
            img.putpixel((x, y), hexc('#ffe4a0'))
    for x in range(2, 14):
        if img.getpixel((x, 7))[:3] == hexc('#f2b8a0')[:3]:
            img.putpixel((x, 7), hexc('#d99682'))
        if img.getpixel((x, 10))[:3] == hexc('#f2b8a0')[:3]:
            img.putpixel((x, 10), hexc('#d99682'))
    return img


# ---------------------------------------------------------------------------------------------- the cheese slice
def cheese_slice():
    rows = [
        "................",
        "................",
        "................",
        "................",
        "..........Oo....",
        "........OOrrO...",
        "......OOtttrrO..",
        "....OOtttttTrrO.",
        "..OOttttttttTrO.",
        ".OttttttttttTrO.",
        ".OcCccccchccCrO.",
        ".OccchccccccCrO.",
        ".OcccccchcccCRO.",
        "..OOOOOOOOOOOOO.",
        "................",
        "................",
    ]
    pal = {
        'O': '#6e4216', 'o': '#a86a1c', 'r': '#e09a2c', 'R': '#a86a1c',
        't': '#fbe79a', 'T': '#efcf6e',
        'c': '#f7df8a', 'C': '#fff3c0', 'h': '#c9a148',
    }
    return grid(rows, pal)


# ---------------------------------------------------------------------------------------------- the bottles
def mead():
    rows = [
        "................",
        "......####......",
        "......#cC#......",
        "......#cc#......",
        ".....OWnnnO.....",
        "......OnnO......",
        ".....ssssss.....",
        ".....OanaaO.....",
        "....OaAaaaaO....",
        "...OaAAaaaadO...",
        "...OaAaaaaadO...",
        "...OaaabBaadO...",
        "...OaaaaaaddO...",
        "....OaadddddO...",
        ".....OOOOOO.....",
        "................",
    ]
    pal = {
        '#': '#5c3d22', 'c': '#b88a5a', 'C': '#d9b07a',
        'O': '#4e2606', 'W': '#ffe6b0', 'n': '#c97a1e',
        's': '#efe0b0',
        'a': '#e0901e', 'A': '#ffd47a', 'd': '#a85c0c',
        'b': '#ffe8a8', 'B': '#f2c25a',
    }
    return grid(rows, pal)


def cider():
    rows = [
        "................",
        ".......##.......",
        "......#cC#......",
        "......OggO......",
        "......OgGO......",
        "......OggO......",
        ".....OyyyyO.....",
        "....OyYyyyyO....",
        "....OyYyyyyO....",
        "....OyrrLrdO....",
        "....OyrRrrdO....",
        "....OyrrrrdO....",
        "....OyYyyydO....",
        "....OyyyyddO....",
        ".....OOOOOO.....",
        "................",
    ]
    pal = {
        '#': '#5c3d22', 'c': '#b88a5a', 'C': '#d9b07a',
        'O': '#5e5418', 'g': '#d6e2b0', 'G': '#f6fff0',
        'y': '#efd66a', 'Y': '#fff6c0', 'd': '#c4a83a',
        'r': '#c8382c', 'R': '#ff7a5e', 'L': '#4f9a3a',
    }
    return grid(rows, pal)


def herbal_tea():
    rows = [
        "................",
        "..........LL....",
        "......#cC#LlL...",
        "......#cc#l.....",
        "......OwwO......",
        "......OwWO......",
        ".....OttttO.....",
        "....OtTtLttO....",
        "...OtTtttLttO...",
        "...OtTtlttttO...",
        "...OttttttdtO...",
        "...OtttLttddO...",
        "...OttttdddO....",
        "....OtddddO.....",
        ".....OOOOO......",
        "................",
    ]
    pal = {
        '#': '#5c3d22', 'c': '#b88a5a', 'C': '#d9b07a',
        'O': '#2a2a10', 'w': '#cfd8c8', 'W': '#f4fff0',
        't': '#7d7a32', 'T': '#b4b05a', 'd': '#504a1a',
        'L': '#6cb048', 'l': '#3e7a2a',
    }
    return grid(rows, pal)


# ---------------------------------------------------------------------------------------------- the bandage
def bandage():
    """A white roll stood on its end: the wound cloth seen as rings on top, a hole at its heart, and its loose end
    unwinding off the side and down."""
    white, light, line, shade, dark, hole = (hexc('#fbf9f2'), hexc('#ffffff'), hexc('#d6d1c2'), hexc('#c4bfae'),
                                             hexc('#9c9686'), hexc('#7a7466'))
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    cx, cy, rx, ry, h = 6.5, 5.0, 5.6, 2.9, 5.0
    for py in range(16):
        for px in range(16):
            x, y = px + 0.5, py + 0.5
            dx, dy = (x - cx) / rx, (y - cy) / ry
            r = math.sqrt(dx * dx + dy * dy)
            if r <= 1.0:
                # The top: the cloth's turns, a line between every other, and the hole in the middle.
                if r < 0.2:
                    c = hole
                elif r < 0.3:
                    c = shade
                elif 0.55 < r < 0.64 or 0.82 < r < 0.9:
                    c = line
                else:
                    c = light if (dx < -0.1 and dy < 0.1) else white
                img.putpixel((px, py), c)
                continue
            if abs(dx) <= 1.0:
                front = cy + ry * math.sqrt(1.0 - dx * dx)
                t = y - front
                if 0 <= t <= h:
                    # The side: lit from the left, a crease of shade low down, the gauze's weave in faint dots.
                    c = white if dx < 0.35 else line
                    if dx < -0.6:
                        c = light
                    if t > h - 1.2:
                        c = shade
                    elif (px + py) % 3 == 0 and 0.2 < t < h - 1.5:
                        c = line if c != line else shade
                    img.putpixel((px, py), c)
    # The loose end, off the right of the roll and down to the corner, frayed at its tip.
    for (x, y, c) in ((12, 7, white), (12, 8, white), (13, 8, white), (12, 9, line), (13, 9, white), (13, 10, white),
                      (14, 10, white), (13, 11, line), (14, 11, white), (14, 12, white), (13, 12, line), (14, 13, line),
                      (15, 12, white), (15, 13, white)):
        img.putpixel((x, y), c)
    img = outline(img, lambda p: hexc('#6a665c'))
    # The fray: a thread or two loose.
    img.putpixel((15, 14), hexc('#9c9686'))
    img.putpixel((13, 14), hexc('#9c9686'))
    return img


# ---------------------------------------------------------------------------------------------- the wheel's block faces
def wheel_top():
    """The wheel's waxed top (and its underside), the octagon's x and z read straight off it."""
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    base, hi, rim, dark, stamp = hexc('#f2b83c'), hexc('#ffd768'), hexc('#d9952a'), hexc('#a86a1c'), hexc('#c98a26')
    for y in range(16):
        for x in range(16):
            # The octagon (chamfered two): 1..14, cut corners.
            dx, dy = abs(x - 7.5), abs(y - 7.5)
            inside = dx <= 6.5 and dy <= 6.5 and dx + dy <= 11.0
            if not inside:
                img.putpixel((x, y), rim)
                continue
            edge = dx > 5.5 or dy > 5.5 or dx + dy > 10.0
            c = rim if edge else base
            if not edge and (x * 7 + y * 5) % 13 == 0:
                c = hi
            if not edge and x + y < 9:
                c = hi if (x + y) % 3 else base
            img.putpixel((x, y), c)
    # The dairy's stamp in the middle: a ring.
    for (x, y) in ((7, 5), (8, 5), (6, 6), (9, 6), (5, 7), (10, 7), (5, 8), (10, 8), (6, 9), (9, 9), (7, 10), (8, 10)):
        img.putpixel((x, y), stamp)
    img.putpixel((7, 7), stamp)
    img.putpixel((8, 8), stamp)
    return img


def wheel_side():
    """The rind round the wheel: rows 9 to 14 are its six pixels of height (the model's default UV)."""
    img = Image.new('RGBA', (16, 16), hexc('#e0a532'))
    for x in range(16):
        img.putpixel((x, 9), hexc('#d9952a'))           # the top edge, a darker crease
        img.putpixel((x, 10), hexc('#ffd768'))          # the light along the shoulder
        img.putpixel((x, 11), hexc('#f2b83c'))
        img.putpixel((x, 12), hexc('#f2b83c') if x % 5 else hexc('#e0a532'))
        img.putpixel((x, 13), hexc('#d9952a'))
        img.putpixel((x, 14), hexc('#a86a1c'))          # the bottom edge
        img.putpixel((x, 15), hexc('#7a4a14'))
        for y in range(0, 9):
            img.putpixel((x, y), hexc('#e0a532'))
    return img


def wheel_inner():
    """The cut face: the pale cheese and its holes, the rind a pixel thick at top and bottom (rows 9 to 14)."""
    img = Image.new('RGBA', (16, 16), hexc('#f7df8a'))
    for x in range(16):
        img.putpixel((x, 9), hexc('#e09a2c'))
        img.putpixel((x, 14), hexc('#b8782a'))
        img.putpixel((x, 15), hexc('#8a5a1c'))
        img.putpixel((x, 10), hexc('#fff3c0'))
    for (x, y) in ((2, 11), (3, 11), (6, 12), (10, 11), (11, 12), (13, 11), (8, 13), (4, 13), (14, 13)):
        img.putpixel((x, y), hexc('#c9a148'))
    for (x, y) in ((2, 12), (10, 12), (13, 12)):
        img.putpixel((x, y), hexc('#e6c264'))
    return img


def wheel_board():
    """The board under it: scrubbed pale wood."""
    img = Image.new('RGBA', (16, 16), hexc('#c8a070'))
    for y in range(16):
        for x in range(16):
            c = hexc('#c8a070')
            if y % 4 == 0:
                c = hexc('#a8805a')
            elif (x * 3 + y * 7) % 9 == 0:
                c = hexc('#d8b486')
            img.putpixel((x, y), c)
    for x in range(16):
        img.putpixel((x, 15), hexc('#8a6440'))
    return img


def main():
    if PREVIEW:
        os.makedirs(PREVIEW, exist_ok=True)
    save(packed_lunch(), 'item/packed_lunch')
    save(cheese_wheel_item(), 'item/cheese_wheel')
    save(cheese_slice(), 'item/cheese_slice')
    save(honey_cake(), 'item/honey_cake')
    save(mead(), 'item/mead')
    save(cider(), 'item/cider')
    save(fish_pie(), 'item/fish_pie')
    save(herbal_tea(), 'item/herbal_tea')
    save(bandage(), 'item/bandage')
    save(wheel_top(), 'block/cheese_wheel_top')
    save(wheel_side(), 'block/cheese_wheel_side')
    save(wheel_inner(), 'block/cheese_wheel_inner')
    save(wheel_board(), 'block/cheese_wheel_board')
    # A sheet of the items, side by side, for looking at.
    if PREVIEW:
        names = ['packed_lunch', 'cheese_wheel', 'cheese_slice', 'honey_cake', 'mead', 'cider', 'fish_pie', 'herbal_tea', 'bandage']
        sheet = Image.new('RGBA', (len(names) * 72 + 8, 80), (139, 139, 139, 255))
        for i, n in enumerate(names):
            im = Image.open(os.path.join(OUT, 'textures', 'item', n + '.png')).resize((64, 64), Image.NEAREST)
            sheet.alpha_composite(im, (8 + i * 72, 8))
        sheet.save(os.path.join(PREVIEW, 'sheet.png'))


main()
