#!/usr/bin/env python3
"""[wagons] The trade wagon's model sheet (client/TradeWagonModel): 256 by 256, four bands, each box of the model
unwrapped inside the band of its stuff, so a box may sit anywhere in its band and still be all of one stuff:

    rows   0- 79  oak boards: the bed, the sides, the bench (planks four pixels deep, seams and grain)
    rows  80-127  dark seasoned wood: wheels, spokes, shafts, hoops, handles
    rows 128-207  canvas: near white with a weave (tinted in the town's colours when drawn; a sack's burlap too)
    rows 208-255  iron: tyres, fittings, the lamp's post

    python3 tools/wagon_art.py      # writes src/main/resources/assets/mc_assistant/textures/entity/trade_wagon.png
"""
import os
import random

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(os.path.dirname(HERE), "src/main/resources/assets/mc_assistant/textures/entity/trade_wagon.png")


def clamp(v):
    return max(0, min(255, int(v)))


def shade(c, f):
    return tuple(clamp(x * f) for x in c)


def main():
    rnd = random.Random(1789)
    img = Image.new("RGBA", (256, 256))
    px = img.load()
    oak = (166, 128, 80)
    dark = (92, 64, 40)
    canvas = (238, 232, 218)
    iron = (120, 122, 126)
    # Oak boards: planks four deep, a dark seam on each, a lighter top edge, grain streaks along each plank.
    for y in range(0, 80):
        row = y % 4
        plank = y // 4
        tone = 0.92 + 0.12 * ((plank * 37) % 7) / 6.0
        for x in range(256):
            f = tone
            if row == 3:
                f *= 0.68
            elif row == 0:
                f *= 1.07
            # Grain: a slow wave, broken now and then.
            if (x * 3 + plank * 11) % 17 < 2:
                f *= 0.9
            f *= 0.96 + rnd.random() * 0.08
            # A plank end every so often.
            if (x + plank * 13) % 32 == 0:
                f *= 0.7
            px[x, y] = shade(oak, f) + (255,)
    # Dark seasoned wood: tight grain, no seams.
    for y in range(80, 128):
        for x in range(256):
            f = 0.9 + 0.1 * ((x * 5 + y * 3) % 11) / 10.0
            if (x + y * 7) % 13 == 0:
                f *= 0.82
            f *= 0.95 + rnd.random() * 0.1
            px[x, y] = shade(dark, f) + (255,)
    # Canvas: a fine over-and-under weave, near white so the town's colours take.
    for y in range(128, 208):
        for x in range(256):
            f = 1.0 if (x + y) % 2 == 0 else 0.94
            if x % 8 == 0 or y % 8 == 0:
                f *= 0.96
            f *= 0.97 + rnd.random() * 0.05
            px[x, y] = shade(canvas, f) + (255,)
    # Iron: dull, with rivet dots and hammer marks.
    for y in range(208, 256):
        for x in range(256):
            f = 0.92 + rnd.random() * 0.12
            if x % 6 == 2 and y % 6 == 2:
                f = 1.25
            px[x, y] = shade(iron, f) + (255,)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    img.save(OUT)
    print("wrote", OUT)


if __name__ == "__main__":
    main()
