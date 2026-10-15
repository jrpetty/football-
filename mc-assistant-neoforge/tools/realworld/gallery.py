#!/usr/bin/env python3
"""
A photograph of every building the mod can raise, outside and in.

Run beside a real dedicated server and a real client (software OpenGL on a virtual display), as smoke.py is
(.github/workflows/building-gallery.yml). Every drawing (data/mc_assistant/blueprints/*.txt) is put up on a
stage in the sky, twelve to a row, as a town of the age asked for puts it up: in that town's own woods and
stones, made over for its age, furnished out of its stores and dressed (/village showcase gallery, Gallery.java).
The server also says where to stand for each: outside, three-quarters on from the front, far enough back for the
whole of it with its roof, the front door in sight; and inside, a step or two in from the door looking into the
room, both with the camera's eyes in open air. Then at noon the player goes round as a camera and takes the two.

    gallery.py [age]   (the server and the client are already starting; age is stone, iron (the default) or
                       diamond) the pictures, gallery-<name>-out.png and gallery-<name>-in.png, and gallery.txt,
                       the index: a line a building, where it stood, how each picture was taken
    gallery.py sheets  a contact sheet a row of twelve (gallery-sheet-<n>.png) of the pictures already taken,
                       each building's outside and inside side by side under its name; listed in gallery.txt

Only a client that dies is a hard failure; the pictures are read by a person. Every line of output starts [REAL].
"""
import glob
import math
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from soak import Rcon, say, where  # noqa: E402
from smoke import USER, clear_camera, client_alive, shot, wait_for_join  # noqa: E402

PER_ROW = 12                # buildings to a row on the stage, and to a contact sheet
STAGE_Y = 150               # in the sky: no hill or tree in any picture, under the clouds (192)
EYES = 1.62                 # a camera's eyes over its feet
INDEX = "gallery.txt"

_F = r"(-?\d+(?:\.\d+)?)"
# One building of /village showcase gallery <row> (Gallery.Shot.line).
BUILDING = re.compile(r"BUILDING (\S+) (\d+) (-?\d+) (-?\d+) (-?\d+) door (\S+) (\S+) (\S+) out "
                      + " ".join([_F] * 9) + r" (seen|hidden|nodoor) in " + " ".join([_F] * 9)
                      + r" (\w+) pieces (-?\d+)")
LISTED = re.compile(r"GALLERY (\d+) drawings in (\d+) rows of (\d+) \|(.*)")


def take(r, label, feet, aim, check, wait):
    """A picture named <label>.png: the camera's feet where the server put them (asked again, as every shot of
    smoke.py's is, that its eyes are in open air and see what they should: clear_camera), looking at aim."""
    x, y, z = clear_camera(r, feet[0], feet[1], feet[2], check[0], check[1], check[2])
    dx, dz = aim[0] - x, aim[2] - z
    yaw = math.degrees(math.atan2(-dx, dz))                      # yaw 0 looks south (+z)
    pitch = -math.degrees(math.atan2(aim[1] - (y + EYES), math.hypot(dx, dz)))
    r.cmd("tp %s %.2f %.2f %.2f %.1f %.1f" % (USER, x, y, z, yaw, pitch))
    time.sleep(wait)
    shot(label)                                                  # smoke-<label>.png, as smoke.py names its own
    if os.path.exists("smoke-%s.png" % label):
        os.replace("smoke-%s.png" % label, "%s.png" % label)
        return True
    return False


def photograph(age):
    r = Rcon()
    say("connected; waiting for the client to join")
    if not wait_for_join(r):
        return 1
    say("the client joined: %s" % r.cmd("list"))
    for c in ("gamerule doDaylightCycle false", "time set 6000", "weather clear 1000000", "gamerule doMobSpawning false",
              "gamerule doFireTick false", "gamemode spectator %s" % USER):
        r.cmd(c)
    listed = LISTED.search(r.cmd("village showcase gallery list"))
    if not listed:
        say("FAIL the server has no /village showcase gallery")
        r.cmd("stop")
        return 1
    count, rows = int(listed.group(1)), int(listed.group(2))
    say("%d drawings in %d rows: %s" % (count, rows, listed.group(4).strip()))
    # Over open, low country (the stage's shadow and the green beyond it), as smoke.py sets its village down.
    spot = where(r, "biome minecraft:plains") or where(r, "biome minecraft:savanna") or (0, 0)
    x0, z0 = spot[0] - 140, spot[1]
    middle = (x0 + 140, STAGE_Y + 30, z0 + 45)
    say("the stage at %d %d %d" % (x0, STAGE_Y, z0))
    r.cmd("tp %s %d %d %d" % (USER, middle[0], middle[1], middle[2]))
    time.sleep(25)                                               # the country round the stage arrives at the client
    index = ["# The building gallery: every drawing put up as a town of the %s Age puts it up (its woods, its age's"
             " make-over, its furnishing), at noon. Taken %s." % (age.capitalize(), time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime())),
             "# outside: seen = the front door in sight, hidden = it could not be got in sight, nodoor = it has none.",
             "# inside: door = a step or two in from the front door; cramped = as near as the room lets; open = from"
             " its open front; above = looking down into it (no way in on foot).",
             "# name\trow\tanchor\tdoor\toutside\tinside\tpieces furnished\tpictures"]
    taken = missing = 0
    began = time.time()
    for row in range(rows):
        r.cmd("tp %s %d %d %d" % (USER, middle[0], middle[1], middle[2]))
        time.sleep(2)
        t = time.time()
        out = r.cmd("execute positioned %d %d %d run village showcase gallery %d %s" % (x0, STAGE_Y, z0, row, age))
        shots = BUILDING.findall(out)
        say("row %d of %d: %d buildings up in %.1f s (%s)" % (row + 1, rows, len(shots), time.time() - t,
                                                               ", ".join(s[0] for s in shots)))
        if not shots:
            say("row %d: %s" % (row, out[:400]))
            continue
        time.sleep(8)                                            # the row's blocks arrive at the client
        for i, s in enumerate(shots):
            name = s[0]
            n = [float(v) for v in s[8:17]] + [float(v) for v in s[18:27]]
            outside, inside = s[17], s[27]
            out_feet, out_aim, out_check = n[0:3], n[3:6], n[6:9]
            in_feet, in_aim, in_check = n[9:12], n[12:15], n[15:18]
            got = [take(r, "gallery-%s-out" % name, out_feet, out_aim, out_check, wait=9 if i == 0 else 5),
                   take(r, "gallery-%s-in" % name, in_feet, in_aim, in_check, wait=5)]
            taken += sum(got)
            missing += 2 - sum(got)
            door = "-" if s[5] == "-" else "%s %s %s" % (s[5], s[6], s[7])
            index.append("\t".join([name, str(row), "%s %s %s" % (s[2], s[3], s[4]), door, outside, inside, s[28],
                                    "gallery-%s-out.png gallery-%s-in.png" % (name, name)]))
            if not client_alive():
                break
        alive = client_alive()
        say("alive after row %d: %s (%d pictures, %.0f minutes in)" % (row + 1, alive, taken, (time.time() - began) / 60))
        if not alive:
            break
    with open(INDEX, "w") as fh:
        fh.write("\n".join(index) + "\n")
    alive = client_alive()
    say("%d pictures taken, %d missing, of %d buildings" % (taken, missing, count))
    say("PASS the gallery was photographed" if alive and taken > 0 else "FAIL the client died while photographing the gallery")
    try:
        r.cmd("stop")
    except Exception:  # noqa: BLE001
        pass
    return 0 if alive else 2


# ---------------------------------------------------------------------------------------------
# gallery.py sheets: the contact sheets, a row of twelve to a sheet.
# ---------------------------------------------------------------------------------------------

THUMB = (400, 225)          # a picture on a sheet (1280x720 to scale)
LABEL = 28                  # the name over each pair
COLUMNS = 2                 # buildings across a sheet: outside and inside side by side, so four pictures


def names_in_order():
    """The buildings in the index's order (the stage's), or by the pictures there are."""
    names = []
    try:
        with open(INDEX) as fh:
            for line in fh:
                if line.strip() and not line.startswith("#"):
                    names.append(line.split("\t")[0])
    except OSError:
        pass
    if not names:
        names = sorted({os.path.basename(p)[len("gallery-"):-len("-out.png")] for p in glob.glob("gallery-*-out.png")})
    return names


def sheets():
    from PIL import Image, ImageDraw, ImageFont
    names = names_in_order()
    if not names:
        say("no pictures to make sheets of")
        return 1
    try:
        font = ImageFont.load_default(size=18)
    except TypeError:                                            # an older Pillow: its one small font
        font = ImageFont.load_default()
    made = []
    for k in range(0, len(names), PER_ROW):
        group = names[k:k + PER_ROW]
        rows = (len(group) + COLUMNS - 1) // COLUMNS
        cell_w, cell_h = 2 * THUMB[0] + 8, THUMB[1] + LABEL + 8
        sheet = Image.new("RGB", (COLUMNS * cell_w + 8, rows * cell_h + 8), (24, 24, 28))
        draw = ImageDraw.Draw(sheet)
        for i, name in enumerate(group):
            x, y = 8 + (i % COLUMNS) * cell_w, 8 + (i // COLUMNS) * cell_h
            draw.text((x + 2, y + 4), "%d. %s (outside | inside)" % (k + i + 1, name), fill=(235, 235, 235), font=font)
            for j, side in enumerate(("out", "in")):
                at = (x + j * (THUMB[0] + 4), y + LABEL)
                path = "gallery-%s-%s.png" % (name, side)
                try:
                    with Image.open(path) as im:
                        sheet.paste(im.convert("RGB").resize(THUMB), at)
                except (OSError, ValueError):
                    draw.rectangle([at, (at[0] + THUMB[0] - 1, at[1] + THUMB[1] - 1)], fill=(70, 70, 76))
                    draw.text((at[0] + 8, at[1] + 8), "no picture", fill=(235, 235, 235), font=font)
        out = "gallery-sheet-%d.png" % (k // PER_ROW + 1)
        sheet.save(out)
        made.append(out)
        say("sheet %s: %s" % (out, ", ".join(group)))
    with open(INDEX, "a") as fh:
        for i, out in enumerate(made):
            fh.write("# %s: %s\n" % (out, ", ".join(names[i * PER_ROW:(i + 1) * PER_ROW])))
    return 0


if __name__ == "__main__":
    try:
        if len(sys.argv) > 1 and sys.argv[1] == "sheets":
            sys.exit(sheets())
        sys.exit(photograph(sys.argv[1] if len(sys.argv) > 1 else "iron"))
    except (EOFError, OSError) as e:
        say("DIED: the server went away (%s)" % e)
        sys.exit(3)
