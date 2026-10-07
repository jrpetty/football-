"""[police] The watch's pixel art: the Constable's Badge (16x16, the vanilla way: an outline, three shades and a
highlight) and the police kit the guards wear over their clothes (textures/entity/folk/watch_kit.png, 128x64, laid out
as client/WatchModel's boxes are: the constable's long coat, skirt, sleeves and collar in the watch's navy with brass
buttons; the sash and the armband drawn pale, to be tinted the town's colour; the belt with its brass buckle; the badge).

python3 watch_art.py <assets/mc_assistant dir> [preview dir]
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


def save(img, rel, scale):
    path = os.path.join(OUT, 'textures', rel + '.png')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    if PREVIEW:
        big = img.resize((img.width * scale, img.height * scale), Image.NEAREST)
        bg = Image.new('RGBA', big.size, (60, 60, 70, 255))
        bg.alpha_composite(big)
        bg.save(os.path.join(PREVIEW, rel.replace('/', '_') + '.png'))


# ---------------------------------------------------------------------------------------------- the badge
# An eight-pointed star of gold (the watch's star, as the old forces wore it), each point bevelled down its ridge so
# one half catches the light from the top left and the other falls in shadow; on it an iron boss with a dark rim, its
# highlight at the top left, and the town's little gold crown struck in the middle of it.

GOLD_OUT = hexc('#5c3b0b')
GOLD_DARK = hexc('#a8701a')
GOLD = hexc('#dda12c')
GOLD_LIGHT = hexc('#f6d266')
GOLD_GLINT = hexc('#fff3c2')
IRON_RIM = hexc('#4e545b')
IRON_DARK = hexc('#868d95')
IRON = hexc('#b4bac1')
IRON_LIGHT = hexc('#dfe4e8')


ENAMEL = hexc('#2b4a9c')
ENAMEL_LIGHT = hexc('#5d82d6')


# The star's shape, drawn by hand on fifteen by fifteen (its middle a pixel of its own): four long upright points, four
# shorter ones between, and the boss over the middle.
STAR = [
    ".......#.......",
    ".......#.......",
    "..#...###...#..",
    "...##.###.##...",
    "...#########...",
    "....#######....",
    "..###########..",
    "###############",
    "..###########..",
    "....#######....",
    "...#########...",
    "...##.###.##...",
    "..#...###...#..",
    ".......#.......",
    ".......#.......",
]
BOSS = [
    ".rrr.",
    "rHIIr",
    "rIbir",
    "rIiir",
    ".rrr.",
]


def badge():
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    ox, oy = 0, 0                       # the star's top left on the sixteen (a row and a column spare, bottom right)
    c = 7.0
    light = (-0.7071, -0.7071)
    mask = [[ch == '#' for ch in row] for row in STAR]
    for y in range(15):
        for x in range(15):
            if not mask[y][x]:
                continue
            px, py = x - c, y - c
            if px == 0 and py == 0:
                col = GOLD
            else:
                # Each point bevelled down its ridge: the half facing the light lit, the other in shade; on the ridge, between.
                a = math.atan2(py, px) + math.pi / 2
                step = math.pi / 4
                arm = round(a / step)
                ridge = arm * step - math.pi / 2
                off = a - arm * step
                if abs(off) < 0.12:
                    b = math.cos(ridge) * light[0] + math.sin(ridge) * light[1]
                    col = GOLD_GLINT if b > 0.6 else GOLD_LIGHT if b > -0.2 else GOLD
                else:
                    side = 1 if off > 0 else -1
                    n = (math.cos(ridge + side * math.pi / 2), math.sin(ridge + side * math.pi / 2))
                    b = n[0] * light[0] + n[1] * light[1]
                    col = GOLD_LIGHT if b > 0.3 else GOLD if b > -0.3 else GOLD_DARK
            img.putpixel((ox + x, oy + y), col)
    # The outline: the edge in shadow (below and to the right) dark; the lit edge (above and to the left) left lit.
    src = img.copy()

    def clear(nx, ny):
        return nx < 0 or ny < 0 or nx > 15 or ny > 15 or src.getpixel((nx, ny))[3] == 0

    for y in range(16):
        for x in range(16):
            if src.getpixel((x, y))[3] == 0:
                continue
            if clear(x + 1, y) or clear(x, y + 1):
                img.putpixel((x, y), GOLD_OUT)
    # The iron boss over the middle, and the town's blue enamel at its heart.
    pal = {'r': IRON_RIM, 'H': IRON_LIGHT, 'I': IRON, 'i': IRON_DARK, 'b': ENAMEL}
    for y, row in enumerate(BOSS):
        for x, ch in enumerate(row):
            if ch != '.':
                img.putpixel((ox + 5 + x, oy + 5 + y), pal[ch])
    return img


# ---------------------------------------------------------------------------------------------- the kit
# Box layout (Minecraft's): at (u, v) a box w x h x d has its top and bottom in the row v..v+d (at u+d and u+d+w), and
# its sides in the row v+d..v+d+h: the right side (u, d wide), the front (u+d, w), the left side (u+d+w, d), the back.

NAVY_DARK = hexc('#141b31')
NAVY = hexc('#1f2a4b')
NAVY_MID = hexc('#27355d')
NAVY_LIGHT = hexc('#33467a')
BRASS = hexc('#d8a535')
BRASS_LIGHT = hexc('#f4d679')
BRASS_DARK = hexc('#8c6418')
SILVER = hexc('#cfd6dc')
PALE = hexc('#f2f2f2')
PALE_LIGHT = hexc('#ffffff')
PALE_SHADE = hexc('#c9c9c9')
PALE_EDGE = hexc('#a4a4a4')
LEATHER_DARK = hexc('#24160d')
LEATHER = hexc('#3d2717')
LEATHER_LIGHT = hexc('#56381f')


def faces(u, v, w, h, d):
    """The box's faces: name -> (x0, y0, x1, y1), exclusive ends."""
    return {
        'top': (u + d, v, u + d + w, v + d),
        'bottom': (u + d + w, v, u + d + 2 * w, v + d),
        'right': (u, v + d, u + d, v + d + h),
        'front': (u + d, v + d, u + d + w, v + d + h),
        'left': (u + d + w, v + d, u + 2 * d + w, v + d + h),
        'back': (u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h),
    }


def fill(img, box, c):
    x0, y0, x1, y1 = box
    for y in range(y0, y1):
        for x in range(x0, x1):
            img.putpixel((x, y), c)


def cloth(img, box, base, dark, light, seed, vertical_fold=True):
    """A cloth face: its colour, a soft fold or two, the top row catching the light and the hem in shade."""
    x0, y0, x1, y1 = box
    fill(img, box, base)
    w = x1 - x0
    for x in range(x0, x1):
        img.putpixel((x, y0), light)
        img.putpixel((x, y1 - 1), dark)
    if vertical_fold and w >= 6:
        fx = x0 + 1 + (seed % (w - 2))
        for y in range(y0 + 2, y1 - 2):
            if (y + seed) % 4 != 0:
                img.putpixel((fx, y), NAVY_MID if base == NAVY else dark)


def coat(img):
    # The body: 8 x 12 x 6 at (0, 0). Double-breasted: two rows of brass buttons down the front, the lapels darker.
    f = faces(0, 0, 8, 12, 6)
    for name in ('right', 'left', 'back'):
        cloth(img, f[name], NAVY, NAVY_DARK, NAVY_LIGHT, 3 if name == 'back' else 1)
    fill(img, f['top'], NAVY_DARK)
    fill(img, f['bottom'], NAVY_DARK)
    x0, y0, x1, y1 = f['front']
    fill(img, f['front'], NAVY)
    for x in range(x0, x1):
        img.putpixel((x, y1 - 1), NAVY_DARK)
    # The lapels: a V from the shoulders to the chest.
    for i in range(4):
        img.putpixel((x0 + 1 + i, y0 + i), NAVY_DARK)
        img.putpixel((x1 - 2 - i, y0 + i), NAVY_DARK)
        if i < 3:
            img.putpixel((x0 + 2 + i, y0 + i), NAVY_MID)
            img.putpixel((x1 - 3 - i, y0 + i), NAVY_MID)
    # The opening down the middle, and the buttons either side of it.
    for y in range(y0 + 4, y1):
        img.putpixel((x0 + 4, y), NAVY_DARK)
    for row in (5, 7, 9):
        for bx in (x0 + 2, x1 - 3):
            img.putpixel((bx, y0 + row), BRASS)
    img.putpixel((x0 + 2, y0 + 5), BRASS_LIGHT)
    img.putpixel((x1 - 3, y0 + 5), BRASS_LIGHT)
    # A breast pocket's flap on the right breast (the badge goes on the left).
    for x in range(x0 + 1, x0 + 3):
        img.putpixel((x, y0 + 3), NAVY_DARK)
    # The skirt: 8 x 7 x 6 at (28, 0), split at the front and the back, the hem darker.
    s = faces(28, 0, 8, 7, 6)
    for name in ('right', 'left'):
        cloth(img, s[name], NAVY, NAVY_DARK, NAVY_LIGHT, 2)
    fill(img, s['top'], NAVY_DARK)
    fill(img, s['bottom'], NAVY_DARK)
    for name in ('front', 'back'):
        x0, y0, x1, y1 = s[name]
        fill(img, s[name], NAVY)
        for y in range(y0, y1):
            img.putpixel((x0 + 3, y), NAVY_DARK if name == 'front' else NAVY_MID)
            if name == 'front':
                img.putpixel((x0 + 4, y), NAVY_DARK)
        for x in range(x0, x1):
            img.putpixel((x, y1 - 1), NAVY_DARK)
        if name == 'front':
            img.putpixel((x0 + 2, y0 + 1), BRASS)
            img.putpixel((x1 - 3, y0 + 1), BRASS)
    # The sleeves: 4 x 10 x 4 at (56, 0) and (72, 0), the cuffs turned back a shade lighter with a button.
    for u in (56, 72):
        sl = faces(u, 0, 4, 10, 4)
        for name in ('right', 'front', 'left', 'back'):
            x0, y0, x1, y1 = sl[name]
            cloth(img, sl[name], NAVY, NAVY_DARK, NAVY_LIGHT, 1, vertical_fold=False)
            for x in range(x0, x1):
                img.putpixel((x, y1 - 3), NAVY_LIGHT)
                img.putpixel((x, y1 - 2), NAVY_MID)
            if name in ('front', 'left' if u == 56 else 'right'):
                img.putpixel((x0 + 1, y1 - 2), BRASS)
        fill(img, sl['top'], NAVY_DARK)
        fill(img, sl['bottom'], NAVY_DARK)
    # The collar: 9 x 2 x 7 at (88, 0), standing, with the watch's silver number at the front either side.
    c = faces(88, 0, 9, 2, 7)
    for name in ('right', 'front', 'left', 'back'):
        fill(img, c[name], NAVY_MID)
        x0, y0, x1, y1 = c[name]
        for x in range(x0, x1):
            img.putpixel((x, y1 - 1), NAVY_DARK)
    x0, y0, x1, y1 = c['front']
    img.putpixel((x0 + 1, y0), SILVER)
    img.putpixel((x1 - 2, y0), SILVER)
    for x in range(x0 + 3, x1 - 3):
        img.putpixel((x, y0), NAVY_DARK)
        img.putpixel((x, y0 + 1), NAVY_DARK)
    fill(img, c['top'], NAVY_DARK)
    fill(img, c['bottom'], NAVY_DARK)


def sash(img):
    # The sash: 2 x 14 x 7 at (0, 20), worn over the shoulder: pale, to be tinted the town's colour; a stitched edge
    # down each side and a fold every few rows.
    f = faces(0, 20, 2, 14, 7)
    for name, box in f.items():
        x0, y0, x1, y1 = box
        fill(img, box, PALE)
        if name in ('top', 'bottom'):
            continue
        for y in range(y0, y1):
            img.putpixel((x0, y), PALE_EDGE)
            img.putpixel((x1 - 1, y), PALE_EDGE)
            if (y - y0) % 4 == 2 and x1 - x0 > 2:
                for x in range(x0 + 1, x1 - 1):
                    img.putpixel((x, y), PALE_SHADE)
            if (y - y0) % 4 == 0 and x1 - x0 > 3:
                img.putpixel((x0 + 1, y), PALE_LIGHT)
    # The armband: 4 x 2 x 4 at (64, 20), pale with a darker stripe.
    a = faces(64, 20, 4, 2, 4)
    for name, box in a.items():
        x0, y0, x1, y1 = box
        fill(img, box, PALE)
        if name in ('top', 'bottom'):
            continue
        for x in range(x0, x1):
            img.putpixel((x, y0), PALE_LIGHT)
            img.putpixel((x, y1 - 1), PALE_SHADE)


def belt(img):
    # The belt: 8 x 2 x 6 at (20, 20), dark leather with a stitched line and a brass buckle at the front.
    f = faces(20, 20, 8, 2, 6)
    for name, box in f.items():
        x0, y0, x1, y1 = box
        fill(img, box, LEATHER)
        if name in ('top', 'bottom'):
            fill(img, box, LEATHER_DARK)
            continue
        for x in range(x0, x1):
            img.putpixel((x, y0), LEATHER_LIGHT)
            img.putpixel((x, y1 - 1), LEATHER_DARK)
    x0, y0, x1, y1 = f['front']
    img.putpixel((x0 + 3, y0), BRASS_LIGHT)
    img.putpixel((x0 + 4, y0), BRASS)
    img.putpixel((x0 + 3, y0 + 1), BRASS)
    img.putpixel((x0 + 4, y0 + 1), BRASS_DARK)
    # The truncheon's frog on the left hip.
    x0, y0, x1, y1 = f['left']
    img.putpixel((x0 + 2, y0 + 1), LEATHER_DARK)
    img.putpixel((x0 + 3, y0 + 1), LEATHER_DARK)


def badge_box(img):
    # The badge on the breast: 3 x 3 x 1 at (54, 20): a little gold star with its iron boss.
    f = faces(54, 20, 3, 3, 1)
    for name, box in f.items():
        fill(img, box, GOLD_DARK)
    x0, y0, x1, y1 = f['front']
    pattern = [[GOLD_DARK, GOLD_LIGHT, GOLD_DARK],
               [GOLD, IRON_LIGHT, GOLD],
               [GOLD_DARK, GOLD, GOLD_DARK]]
    for y in range(3):
        for x in range(3):
            img.putpixel((x0 + x, y0 + y), pattern[y][x])


def kit():
    img = Image.new('RGBA', (128, 64), (0, 0, 0, 0))
    coat(img)
    sash(img)
    belt(img)
    badge_box(img)
    return img


if __name__ == '__main__':
    save(badge(), 'item/constable_badge', 16)
    save(kit(), 'entity/folk/watch_kit', 6)
    print('drawn: item/constable_badge.png, entity/folk/watch_kit.png')
