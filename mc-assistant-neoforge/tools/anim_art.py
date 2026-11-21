#!/usr/bin/env python3
"""
[anim] The pictures the folk's animations draw with, painted here (no image library needed):

  textures/entity/folk/marks.png      the little pictures over a folk's head (FolkAction.Mark, in its order), each in a
                                      white thought bubble with a dark rim and a tail: an idea, a coin, a question, a
                                      shout, a heart, a note, a temper, a tear, a star, a bead of sweat, a handshake
  textures/entity/folk/face/*.png     the faces drawn over a folk's own (client/FolkFaceLayer): its eyes shut (one for each
                                      eye shape, matched to the eyes folk_looks paints), a smile, a frown, a mouth open,
                                      brows knit in temper, brows raised in sorrow, a blush

Run it from mc-assistant-neoforge/: python3 tools/anim_art.py
"""
import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "src", "main", "resources", "assets", "mc_assistant", "textures", "entity", "folk")


def write_png(path, w, h, px):
    raw = b"".join(b"\x00" + bytes(c for p in row for c in p) for row in px)

    def chunk(t, b):
        return struct.pack(">I", len(b)) + t + b + struct.pack(">I", zlib.crc32(t + b) & 0xFFFFFFFF)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def read_png(path):
    data = open(path, "rb").read()
    i = 8
    idat = b""
    w = h = ct = 0
    plte = trns = None
    while i < len(data):
        ln = struct.unpack(">I", data[i:i + 4])[0]
        typ = data[i + 4:i + 8]
        body = data[i + 8:i + 8 + ln]
        i += 12 + ln
        if typ == b"IHDR":
            w, h, bd, ct, _, _, il = struct.unpack(">IIBBBBB", body)
        elif typ == b"IDAT":
            idat += body
        elif typ == b"PLTE":
            plte = body
        elif typ == b"tRNS":
            trns = body
    raw = zlib.decompress(idat)
    bpp = {6: 4, 2: 3, 0: 1, 4: 2, 3: 1}[ct]
    stride = w * bpp
    out = []
    prev = bytearray(stride)
    p = 0
    for _ in range(h):
        flt = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if flt == 1:
                line[x] = (line[x] + a) & 255
            elif flt == 2:
                line[x] = (line[x] + b) & 255
            elif flt == 3:
                line[x] = (line[x] + ((a + b) >> 1)) & 255
            elif flt == 4:
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        prev = line
        row = []
        for x in range(w):
            if ct == 6:
                row.append(tuple(line[x * 4:x * 4 + 4]))
            elif ct == 2:
                row.append(tuple(line[x * 3:x * 3 + 3]) + (255,))
            elif ct == 3:
                k = line[x]
                row.append(tuple(plte[k * 3:k * 3 + 3]) + ((trns[k] if trns and k < len(trns) else 255),))
            else:
                row.append((line[x],) * 3 + (255,))
        out.append(row)
    return w, h, out


CLEAR = (0, 0, 0, 0)
RIM = (40, 34, 40, 255)
PAPER = (250, 248, 240, 255)

# The glyphs, ten wide and nine high, inside each bubble: a letter a colour.
INK = {
    "y": (250, 214, 60, 255), "Y": (255, 246, 170, 255), "g": (150, 150, 150, 255), "o": (196, 140, 30, 255),
    "b": (60, 100, 200, 255), "B": (150, 190, 255, 255), "r": (210, 44, 50, 255), "R": (255, 130, 130, 255),
    "k": (30, 28, 36, 255), "s": (240, 196, 160, 255), "S": (200, 150, 110, 255), "w": (255, 255, 255, 255),
}
GLYPHS = [
    None,
    ["...yyyy...", "..yYYyyy..", "..yYyyyy..", "..yyyyyy..", "...yyyy...", "....yy....", "...gggg...", "...gggg...", "....gg...."],   # idea
    ["...oooo...", "..oYYyyo..", ".oYyyyyyo.", ".oYyooyyo.", ".oYyooyyo.", ".oyyyyyyo.", "..oyyyyo..", "...oooo...", ".........."],  # coin
    ["...bbbb...", "..bb..bb..", "......bb..", ".....bb...", "....bb....", "....bb....", "..........", "....bb....", "....bb...."],  # question
    ["....rr....", "....rr....", "....rr....", "....rr....", "....rr....", "....rr....", "..........", "....rr....", "....rr...."],  # exclaim
    ["..........", ".rr...rr..", "rRrr.rrrr.", "rRrrrrrrr.", "rrrrrrrrr.", ".rrrrrrr..", "..rrrrr...", "...rrr....", "....r....."],  # heart
    ["....kkkkk.", "....k...k.", "....k...k.", "....k...k.", "....k...k.", "..kkk.kkk.", ".kkkkkkkk.", ".kkk.kkk..", ".........."],  # note
    ["..r....r..", "..rr..rr..", "...r..r...", "rr......rr", "..........", "rr......rr", "...r..r...", "..rr..rr..", "..r....r.."],  # anger
    ["....b.....", "....b.....", "...bBb....", "...bBb....", "..bBbbb...", "..bBbbb...", "..bbbbb...", "...bbb....", ".........."],  # tear
    ["....yy....", "....yy....", "yyyyYYyyyy", ".yyyYYyyy.", "..yyyyyy..", "..yyyyyy..", ".yyy..yyy.", ".yy....yy.", ".........."],  # star
    ["......B...", ".....BB...", "....BBbb..", "....Bbbb..", "...BBbbbb.", "...Bbbbbb.", "...bbbbbb.", "....bbbb..", ".........."],  # sweat
    ["..........", ".SS....ss.", "SSSS..ssss", "SSSSssssss", ".SSSsssss.", "..SSssss..", "...Sss....", "..........", ".........."],  # handshake
]


def marks():
    w = 16 * len(GLYPHS)
    px = [[CLEAR] * w for _ in range(16)]
    for i, g in enumerate(GLYPHS):
        if g is None:
            continue
        x0 = i * 16
        # The bubble: a rounded box with a rim and a tail under it.
        for y in range(1, 13):
            for x in range(1, 15):
                corner = (x in (1, 14)) and (y in (1, 12))
                if corner:
                    continue
                edge = x in (1, 14) or y in (1, 12) or (x in (2, 13) and y in (2, 11) and False)
                px[y][x0 + x] = RIM if edge else PAPER
        for (x, y) in ((6, 13), (7, 13), (8, 13), (7, 14)):
            px[y][x0 + x] = RIM
        px[12][x0 + 7] = PAPER
        for gy, line in enumerate(g):
            for gx, ch in enumerate(line):
                if ch != ".":
                    px[2 + gy][x0 + 3 + gx] = INK[ch]
    write_png(os.path.join(OUT, "marks.png"), w, 16, px)


# ------------------------------------------------------------------ faces
# A head's front is the box's face at u 8..16, v 8..18 of a 64x32 sheet (the folk's head is 8x10x8, its picture's front
# at the same place on its 128x128 one). Grey pixels are tinted with the folk's skin when drawn; dark ones stay dark.

LID = (255, 255, 255, 255)        # tinted to the skin
LASH = (110, 96, 92, 255)         # tinted: a darker line of the skin
DARK = (52, 30, 30, 255)
BLUSH = (232, 120, 120, 150)


def face_sheet():
    return [[CLEAR] * 64 for _ in range(32)]


def faces():
    d = os.path.join(OUT, "face")
    os.makedirs(d, exist_ok=True)
    look = os.path.join(OUT, "look")
    # Eyes shut, one for each eye shape: the eye's own pixels covered with a lid, its lowest row a lash line.
    for shape in range(6):
        _, _, eye = read_png(os.path.join(look, "eyes_%d.png" % shape))
        sheet = face_sheet()
        cols = {}
        for y in range(8, 18):
            for x in range(8, 16):
                if eye[y][x][3] > 0:
                    sheet[y][x] = LID
                    cols[x] = max(cols.get(x, 0), y)
        for x, y in cols.items():
            sheet[y][x] = LASH
        write_png(os.path.join(d, "shut_%d.png" % shape), 64, 32, sheet)
    # The mouth, either side of the nose (cols 11-12 rows 15-17 are behind it): a smile's corners up, a frown's down,
    # a mouth open (talking, laughing, cheering).
    for name, pts in (("smile", [(9, 16), (10, 17), (13, 17), (14, 16)]),
                      ("frown", [(9, 17), (10, 16), (13, 16), (14, 17)]),
                      ("open", [(10, 16), (10, 17), (13, 16), (13, 17), (9, 17), (14, 17)])):
        sheet = face_sheet()
        for x, y in pts:
            sheet[y][x] = DARK
        write_png(os.path.join(d, name + ".png"), 64, 32, sheet)
    # The brows: knit (the inner ends down, a temper) and raised (the inner ends up, sorrow or fright).
    for name, pts in (("cross", [(9, 10), (10, 11), (13, 11), (14, 10)]), ("sorry", [(9, 11), (10, 10), (13, 10), (14, 11)])):
        sheet = face_sheet()
        for x, y in pts:
            sheet[y][x] = DARK
        write_png(os.path.join(d, name + ".png"), 64, 32, sheet)
    sheet = face_sheet()
    for x, y in ((8, 14), (9, 14), (14, 14), (15, 14)):
        sheet[y][x] = BLUSH
    write_png(os.path.join(d, "blush.png"), 64, 32, sheet)


if __name__ == "__main__":
    marks()
    faces()
    print("anim art written")
