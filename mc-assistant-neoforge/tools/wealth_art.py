#!/usr/bin/env python3
"""What a folk's wealth looks like on it: four pictures laid over its trade's clothes
(client/FolkRenderer's Finery layer), one for each standing but "getting by", which wears
its clothes as they are.

  wealth_0  poor        patched elbows and a patched coat, a frayed hem at the shoulders
  wealth_2  comfortable a good leather belt with a brass buckle
  wealth_3  well off    the belt, a dyed collar and a row of polished buttons
  wealth_4  wealthy     a velvet collar trimmed with gold, gold buttons, gold cuffs, and a
                        gold chain across the chest

Only the upper coat and the sleeves are painted: every skin paints them, so nothing here
can ever hang in the air where a trade's clothes leave the coat open.

    python3 tools/wealth_art.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import folk_art as fa  # noqa: E402

GOLD, GOLD_D = (232, 190, 72), (170, 128, 40)
BRASS = (204, 172, 82)
STRAP = (88, 58, 34)


def poor():
    cv = fa.Canvas()
    coat = fa.Box(cv, "coat")
    patch = (126, 104, 74)
    stitch = (68, 52, 36)
    for (x0, y0) in ((1, 6), (5, 2)):
        for y in range(y0, y0 + 2):
            for x in range(x0, x0 + 2):
                coat.put("front", x, y, fa.grain(patch, x, y, 6, 3))
        coat.put("front", x0 - 1, y0, stitch)
        coat.put("front", x0 + 2, y0 + 1, stitch)
    coat.put("back", 3, 4, fa.grain(patch, 3, 4, 6, 4))
    coat.put("back", 4, 4, fa.grain(patch, 4, 4, 6, 5))
    coat.put("back", 4, 5, stitch)
    for arm in ("right_arm", "left_arm"):
        a = fa.Box(cv, arm)
        for y in (5, 6):
            for x in (1, 2):
                a.put("front", x, y, fa.grain(patch, x, y, 6, 7))
        a.put("front", 0, 5, stitch)
    return cv


def belt_on(cv, buckle=BRASS):
    coat = fa.Box(cv, "coat")
    fa.belt(coat, 10, strap=STRAP, buckle=buckle)
    return coat


def comfortable():
    cv = fa.Canvas()
    belt_on(cv)
    return cv


def well_off():
    cv = fa.Canvas()
    coat = belt_on(cv)
    collar = (46, 66, 128)
    coat.row(0, lambda x: fa.grain(collar, x, 0, 4, 11))
    coat.fill("top", lambda x, y, w, h: fa.grain(collar, x, y, 4, 12) if y in (0, h - 1) or x in (0, w - 1) else None)
    for y in (2, 5, 8):
        coat.put("front", 4, y, BRASS)
    return cv


def wealthy():
    cv = fa.Canvas()
    coat = belt_on(cv, buckle=GOLD)
    velvet = (128, 26, 40)
    coat.row(0, lambda x: fa.grain(velvet, x, 0, 4, 13))
    coat.row(1, lambda x: GOLD if x % 2 == 0 else GOLD_D)
    coat.fill("top", lambda x, y, w, h: fa.grain(velvet, x, y, 4, 14) if y in (0, h - 1) or x in (0, w - 1) else None)
    for y in (3, 5, 7):
        coat.put("front", 4, y, GOLD)
    # The chain: from one shoulder, down across the chest, to a fob at the waist.
    for (x, y) in ((1, 2), (1, 3), (2, 4), (2, 5), (3, 6), (4, 6), (5, 7), (5, 8), (6, 9)):
        coat.put("front", x, y, GOLD if (x + y) % 2 == 0 else GOLD_D)
    coat.put("front", 6, 10, GOLD)
    for arm in ("right_arm", "left_arm"):
        a = fa.Box(cv, arm)
        a.row(7, lambda x: GOLD if x % 2 == 0 else GOLD_D)
    return cv


def main():
    out = {"wealth_0.png": poor(), "wealth_2.png": comfortable(), "wealth_3.png": well_off(), "wealth_4.png": wealthy()}
    for name, cv in out.items():
        with open(os.path.join(fa.TEX_DIR, name), "wb") as fh:
            fh.write(cv.png())
    print("wrote: " + ", ".join(out))


if __name__ == "__main__":
    main()
