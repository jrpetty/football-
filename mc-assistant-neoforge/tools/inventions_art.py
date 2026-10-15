"""[tech-tree] The inventions' pixel art: steel (an ingot, a sword, the four pieces of armour and their worn layers), the
pike, the halberd, the war hammer, the longbow and the arbalest (with their drawn and spanned frames), and the scholars'
working model. 16x16 for the items, the vanilla way: a dark outline, three shades and a highlight off the top left;
64x32 for the worn armour, laid out as the game's armour model reads it (textures/models/armor/steel_layer_1/2.png).

python3 inventions_art.py <assets/mc_assistant dir>
"""
import os
import sys

from PIL import Image

OUT = sys.argv[1]


def hexc(h, a=255):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


CLEAR = (0, 0, 0, 0)
# Steel: cooler and bluer than iron, a little darker in the shadow.
S_OUT = hexc('#1f2630')
S_DARK = hexc('#56606e')
S_MID = hexc('#8c97a6')
S_LIGHT = hexc('#c3cbd6')
S_GLINT = hexc('#eef3f8')
# Iron, for the pike's head and the hammer.
I_DARK = hexc('#6b6b6b')
I_MID = hexc('#a8a8a8')
I_LIGHT = hexc('#d8d8d8')
# Wood and leather.
W_OUT = hexc('#2e1d0e')
W_DARK = hexc('#5a3a1c')
W_MID = hexc('#7f5530')
W_LIGHT = hexc('#a57444')
GOLD = hexc('#d9a62e')
STRING = hexc('#e8e2d0')


def save(img, rel):
    path = os.path.join(OUT, 'textures', rel + '.png')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def new(w=16, h=16):
    return Image.new('RGBA', (w, h), CLEAR)


def put(img, x, y, c):
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((x, y), c)


def line(img, x0, y0, x1, y1, c):
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
    err = dx + dy
    while True:
        put(img, x0, y0, c)
        if x0 == x1 and y0 == y1:
            break
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x0 += sx
        if e2 <= dx:
            err += dx
            y0 += sy


def thick(img, x0, y0, x1, y1, c):
    """A diagonal line made solid (a Bresenham diagonal alone is a chequer when laid beside another)."""
    line(img, x0, y0, x1, y1, c)
    line(img, x0 + 1, y0, x1 + 1, y1, c)


def outline(img, col=S_OUT):
    """A dark outline round whatever is drawn (the vanilla look)."""
    src = img.copy()
    for y in range(img.height):
        for x in range(img.width):
            if src.getpixel((x, y))[3] != 0:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < img.width and 0 <= ny < img.height and src.getpixel((nx, ny))[3] != 0 and src.getpixel((nx, ny)) != col:
                    img.putpixel((x, y), col)
                    break


def from_mask(rows, palette):
    img = new(len(rows[0]), len(rows))
    for y, r in enumerate(rows):
        for x, ch in enumerate(r):
            if ch in palette:
                img.putpixel((x, y), palette[ch])
    return img


STEEL = {'o': S_OUT, 'd': S_DARK, 'm': S_MID, 'l': S_LIGHT, 'g': S_GLINT, 'w': W_MID, 'W': W_DARK, 'y': GOLD}

# ---------------------------------------------------------------------------------------------- steel ingot
save(from_mask([
    "................",
    "................",
    "................",
    "................",
    "................",
    "......oooooo....",
    ".....oglllllo...",
    "....oglmmmmldo..",
    "...oglmmmmmldo..",
    "..oglmmmmmmldo..",
    "..olllllllldo...",
    "..oddddddddo....",
    "...oooooooo.....",
    "................",
    "................",
    "................",
], STEEL), 'item/steel_ingot')

# ---------------------------------------------------------------------------------------------- the steel sword
save(from_mask([
    ".............ooo",
    "............oglo",
    "...........oglmo",
    "..........oglmo.",
    ".........oglmo..",
    "........oglmo...",
    ".......oglmo....",
    "......oglmo.....",
    ".oo..oglmo......",
    ".oyooglmo.......",
    "..oyylmo........",
    "...oyyo.........",
    "..owoyyo........",
    ".owWo.oyo.......",
    "oWWo...oo.......",
    "ooo.............",
], STEEL), 'item/steel_sword')

# ---------------------------------------------------------------------------------------------- the steel armour, as items
save(from_mask([
    "................",
    "................",
    "................",
    "....oooooooo....",
    "...ogllllllmo...",
    "..oglmmmmmmmdo..",
    "..olmmmmmmmmdo..",
    "..olmooooooodo..",
    "..olmo....odmo..",
    "..olmo....odmo..",
    "..oooo....oooo..",
    "................",
    "................",
    "................",
    "................",
    "................",
], STEEL), 'item/steel_helmet')
save(from_mask([
    "................",
    "..oooo....oooo..",
    ".oglmo....omldo.",
    ".olmmooooommmdo.",
    ".olmmmglmmmmmdo.",
    ".oooolmmmmmdooo.",
    "....olmmmmmdo...",
    "....olmmmmmdo...",
    "....olmyymmdo...",
    "....olmmmmmdo...",
    "....olmmmmmdo...",
    "....oddddddddo..",
    "....ooooooooo...",
    "................",
    "................",
    "................",
], STEEL), 'item/steel_chestplate')
save(from_mask([
    "................",
    "................",
    "...oooooooooo...",
    "...oglllllldo...",
    "...olmmmmmmdo...",
    "...olmdooomdo...",
    "...olmo..olmo...",
    "...olmo..olmo...",
    "...olmo..olmo...",
    "...olmo..olmo...",
    "...olmo..olmo...",
    "...oddo..oddo...",
    "...oooo..oooo...",
    "................",
    "................",
    "................",
], STEEL), 'item/steel_leggings')
save(from_mask([
    "................",
    "................",
    "................",
    "................",
    "................",
    "...oooo..oooo...",
    "...olmo..olmo...",
    "...olmo..olmo...",
    "...olmo..olmo...",
    "..oglmo..oglmo..",
    ".oglmmo..olmmmo.",
    ".oddddo..oddddo.",
    ".oooooo..oooooo.",
    "................",
    "................",
    "................",
], STEEL), 'item/steel_boots')

# ---------------------------------------------------------------------------------------------- the pike, the halberd, the hammer
WOOD = {'o': W_OUT, 'w': W_MID, 'W': W_DARK, 'l': W_LIGHT, 'i': I_MID, 'I': I_LIGHT, 'j': I_DARK, 'd': S_DARK, 'm': S_MID,
        'L': S_LIGHT, 'g': S_GLINT}
save(from_mask([
    "..............oo",
    ".............oIo",
    "............oIio",
    "...........oIijo",
    "..........oIjjo.",
    "...........oojo.",
    "..........olwo..",
    ".........olwo...",
    "........olwo....",
    ".......olwo.....",
    "......olwo......",
    ".....olwo.......",
    "....olwo........",
    "...olwo.........",
    "..olwo..........",
    "..ooo...........",
], WOOD), 'item/pike')
def pole(top_x, top_y):
    """A haft from the bottom left to (top_x, top_y), lit along its upper edge."""
    img = new()
    line(img, 1, 14, top_x, top_y, W_MID)
    line(img, 2, 14, top_x + 1, top_y, W_DARK)
    line(img, 1, 13, top_x - 1, top_y + 1, W_LIGHT)
    return img


# The halberd: a steel axe blade, a spike above it and a hook behind, on a long haft.
hal = pole(10, 5)
for (x0, y0, x1, y1) in ((8, 3, 11, 0), (9, 4, 12, 1), (10, 5, 13, 2)):     # the blade, across the haft's head
    thick(hal, x0, y0, x1, y1, S_MID)
line(hal, 8, 3, 11, 0, S_LIGHT)
put(hal, 8, 2, S_GLINT)
line(hal, 11, 4, 14, 1, S_DARK)                                             # the spike on up
put(hal, 15, 0, S_LIGHT)
put(hal, 12, 6, S_DARK)                                                     # the hook behind
put(hal, 13, 6, S_DARK)
outline(hal, S_OUT)
save(hal, 'item/halberd')

# The war hammer: a square iron head across the top of a stout haft, a spike behind it.
ham = pole(9, 6)
for (x0, y0, x1, y1) in ((7, 3, 11, 7), (8, 2, 12, 6), (9, 1, 13, 5)):       # the head, across the haft
    thick(ham, x0, y0, x1, y1, I_MID)
line(ham, 9, 1, 13, 5, I_LIGHT)
line(ham, 7, 3, 11, 7, I_DARK)
put(ham, 14, 3, I_DARK)                                                     # the spike
outline(ham, S_OUT)
save(ham, 'item/war_hammer')


# ---------------------------------------------------------------------------------------------- the longbow
def bow(pull):
    """A tall yew stave, its ends nearly to the corners; the string drawn back {pull} pixels."""
    img = new()
    stave = [(3, 1), (4, 1), (5, 2), (6, 3), (7, 4), (8, 5), (9, 6), (10, 7), (11, 8), (12, 9), (13, 10), (14, 11), (14, 12)]
    # the stave curves: a gentle arc from the top left to the bottom right
    pts = [(2, 0), (3, 0), (4, 1), (6, 1), (8, 2), (10, 3), (11, 4), (12, 6), (13, 8), (14, 10), (14, 12), (15, 13)]
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        line(img, x0, y0, x1, y1, W_MID)
    for (x, y) in pts[1:-1]:
        put(img, x, y + 1, W_DARK)
    line(img, 3, 1, 3 - pull // 2, 1, STRING)
    # the string, from nock to nock, pulled back toward the bottom left
    mx, my = 7 - pull, 7 + pull // 2
    line(img, 3, 1, mx, my, STRING)
    line(img, mx, my, 14, 13, STRING)
    if pull > 0:
        # the arrow on the string
        line(img, mx, my, mx + 7, my - 7, W_LIGHT)
        put(img, mx + 7, my - 7, I_LIGHT)
        put(img, mx + 8, my - 8, I_MID)
    outline(img, W_OUT)
    return img


save(bow(0), 'item/longbow')
for k, p in enumerate((1, 2, 3)):
    save(bow(p), 'item/longbow_pulling_%d' % k)


# ---------------------------------------------------------------------------------------------- the arbalest
def arbalest(pull, loaded):
    """A heavy crossbow: a dark stock from the bottom left, a steel prod across the top, a windlass at the butt."""
    img = new()
    line(img, 2, 13, 11, 4, W_MID)          # the stock
    line(img, 3, 13, 12, 4, W_DARK)
    for (x, y) in ((1, 13), (2, 14), (1, 14)):
        put(img, x, y, W_DARK)              # the butt
    put(img, 3, 14, S_MID)                  # the windlass
    put(img, 4, 14, S_DARK)
    # the steel prod, bent back as it is spanned
    bend = pull
    line(img, 6 - bend // 2, 3, 13, 10 - bend // 2, S_MID)
    line(img, 7 - bend // 2, 3, 13, 9 - bend // 2, S_LIGHT)
    put(img, 6 - bend // 2, 2, S_GLINT)
    put(img, 14, 10 - bend // 2, S_DARK)
    # the string
    sx, sy = 9 - pull, 7 + pull
    line(img, 6 - bend // 2, 3, sx, sy, STRING)
    line(img, sx, sy, 13, 10 - bend // 2, STRING)
    if loaded:
        line(img, 8, 8, 13, 3, W_LIGHT)
        put(img, 13, 3, I_LIGHT)
        put(img, 14, 2, I_MID)
    outline(img, S_OUT)
    return img


save(arbalest(0, False), 'item/arbalest')
for k, p in enumerate((1, 2, 3)):
    save(arbalest(p, False), 'item/arbalest_pulling_%d' % k)
save(arbalest(3, True), 'item/arbalest_arrow')

# ---------------------------------------------------------------------------------------------- the working model
# A little mill on a wooden base: a gear wheel, its sails, a brass axle.
save(from_mask([
    "................",
    ".......o........",
    "......oyo..o....",
    "..o...oyo.oyo...",
    ".oyo..oyo.oyo...",
    "..oyo.oyooyo....",
    "...oyoyyyyo.....",
    "....oyymyyoooo..",
    ".oooyyyyyyyyyyo.",
    "..o.oyymyyo.oo..",
    "....oyoyyyo.....",
    "...oyo.oyyo.....",
    "..oyo..oyo......",
    ".owwwwwwwwwwwwo.",
    ".oWWWWWWWWWWWWo.",
    "..oooooooooooo..",
], {'o': W_OUT, 'y': GOLD, 'm': S_MID, 'w': W_MID, 'W': W_DARK}), 'item/working_model')


# ---------------------------------------------------------------------------------------------- the worn armour
def plate(img, x0, y0, w, h, rivets=True):
    """One face of a steel plate: lit from the top left, a dark rim, a rivet or two."""
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            t = (x - x0) / max(1, w - 1) * 0.5 + (y - y0) / max(1, h - 1) * 0.5
            c = S_LIGHT if t < 0.3 else S_MID if t < 0.7 else S_DARK
            img.putpixel((x, y), c)
    for x in range(x0, x0 + w):
        img.putpixel((x, y0 + h - 1), S_OUT)
    for y in range(y0, y0 + h):
        img.putpixel((x0, y), S_DARK)
        img.putpixel((x0 + w - 1, y), S_OUT)
    if rivets and w >= 4 and h >= 4:
        img.putpixel((x0 + 1, y0 + 1), S_GLINT)
        img.putpixel((x0 + w - 2, y0 + 1), S_GLINT)


def box(img, u, v, w, h, d, rivets=True):
    """A model box's six faces at its texture offset (u, v): w wide, h high, d deep (the game's box layout)."""
    plate(img, u + d, v, w, d, False)              # top
    plate(img, u + d + w, v, w, d, False)          # bottom
    plate(img, u, v + d, d, h, rivets)             # right
    plate(img, u + d, v + d, w, h, rivets)         # front
    plate(img, u + d + w, v + d, d, h, rivets)     # left
    plate(img, u + 2 * d + w, v + d, w, h, rivets) # back


one = new(64, 32)
box(one, 0, 0, 8, 8, 8)                            # the helmet
# its face open below the brow: the front face cut away but for the brow and the cheek guards
for y in range(8 + 3, 16):
    for x in range(8 + 2, 16 - 2):
        one.putpixel((x, y), CLEAR)
box(one, 16, 16, 8, 12, 4)                         # the breastplate
box(one, 40, 16, 4, 12, 4)                         # the arms (the vambraces)
# the sabatons: the legs' lower part
for v in range(16, 32):
    for u in range(0, 16):
        one.putpixel((u, v), CLEAR)
box(one, 0, 16, 4, 12, 4)
for v in range(20, 27):
    for u in range(0, 16):
        one.putpixel((u, v), CLEAR)
save(one, 'models/armor/steel_layer_1')

two = new(64, 32)
box(two, 0, 16, 4, 12, 4)                          # the leg plates
box(two, 16, 16, 8, 12, 4)                         # the fauld at the waist
for v in range(20, 27):
    for u in range(20, 28):
        two.putpixel((u, v), CLEAR)                # only the waist band shows above the belt
save(two, 'models/armor/steel_layer_2')
print("inventions art written")
