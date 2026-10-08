#!/usr/bin/env python3
"""
Pictures of the town the hundred days made.

The hundred days (soak.py hundred) leave a saved world behind. This is run beside a server
that has loaded it and a real client (software OpenGL on a virtual display), as smoke.py is:
the player goes over as a camera and takes the town from the air on four sides, straight down,
in its square and down a street, and again by night with its windows lit. The town's own
account of itself (/village status) goes in the report beside the pictures.

    photo.py [spot file] [name prefix]
                    (the server and the client are already starting; the spot file, by default
                    hundred-spot.txt, says where the town is; the pictures are named
                    smoke-<prefix><shot>.png, by default smoke-hundred-*.png)

Every line of output starts [REAL], like soak.py.
"""
import math
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from soak import Rcon, say  # noqa: E402
from smoke import USER, client_alive, position, shot  # noqa: E402


def main():
    spotfile = sys.argv[1] if len(sys.argv) > 1 else "hundred-spot.txt"
    prefix = sys.argv[2] if len(sys.argv) > 2 else "hundred-"
    r = Rcon()
    try:
        with open(spotfile) as fh:
            cx, cz = (int(v) for v in fh.read().split())
    except (OSError, ValueError):
        say("FAIL no %s: nothing to photograph" % spotfile)
        return
    say("connected; the town is at %d, %d; waiting for the client to join" % (cx, cz))
    deadline = time.time() + 900
    joined = False
    while time.time() < deadline:
        if USER in r.cmd("list"):
            joined = True
            break
        if not client_alive():
            say("FAIL the client process ended before it joined")
            break
        time.sleep(5)
    if not joined:
        say("FAIL the client never joined the server")
        try:
            r.cmd("stop")
        except Exception:  # noqa: BLE001
            pass
        return
    for c in ("gamerule doDaylightCycle false", "time set 6000", "weather clear 1000000",
              "gamerule doMobSpawning false", "gamemode creative %s" % USER):
        r.cmd(c)
    # Stand on the ground at the heart first, to find how high it is.
    say("moving the player: " + r.cmd("spreadplayers %d %d 0 2 false %s" % (cx, cz, USER)))
    time.sleep(25)
    pos = position(r)
    gy = int(pos[1]) if pos else 70
    say("the heart is at %d, %d, %d" % (cx, gy, cz))
    for line in r.cmd("execute positioned %d %d %d run village status" % (cx, gy, cz)).split("\n"):
        if line.strip():
            say("STATUS " + line.strip())
    say("village list: " + r.cmd("village list").replace("\n", " | "))
    r.cmd("gamemode spectator %s" % USER)

    def look(label, x, y, z, tx, ty, tz, wait=10):
        # A camera: yaw 0 looks south (+z), a positive pitch looks down, eyes 1.62 above the feet.
        dx, dz = tx - x, tz - z
        yaw = math.degrees(math.atan2(-dx, dz))
        pitch = -math.degrees(math.atan2(ty - (y + 1.62), math.hypot(dx, dz)))
        r.cmd("tp %s %.2f %.2f %.2f %.1f %.1f" % (USER, x, y, z, yaw, pitch))
        time.sleep(wait)
        shot(prefix + label)

    # From the air on four sides (below the clouds), then straight down on the heart.
    look("1-air-southeast", cx + 70, gy + 55, cz + 80, cx, gy, cz, wait=20)
    look("2-air-northwest", cx - 70, gy + 55, cz - 80, cx, gy, cz, wait=16)
    look("3-air-northeast", cx + 80, gy + 50, cz - 70, cx, gy, cz, wait=16)
    look("4-air-southwest", cx - 80, gy + 50, cz + 70, cx, gy, cz, wait=16)
    look("5-overhead", cx + 0.5, min(gy + 110, 185), cz + 1.5, cx + 0.5, gy, cz + 0.5, wait=16)
    look("6-wide", cx + 150, gy + 70, cz + 150, cx, gy, cz, wait=20)
    # In the square, and down a street at head height.
    look("7-square", cx + 12, gy + 6, cz + 14, cx, gy + 1, cz, wait=10)
    look("8-street", cx + 1, gy + 2, cz + 40, cx, gy + 2, cz, wait=10)
    look("9-street-east", cx + 40, gy + 2, cz + 1, cx, gy + 2, cz, wait=10)
    say("alive after the day: %s" % client_alive())
    # By night: the windows lit, the lamps along the streets.
    r.cmd("time set 18000")
    look("10-night-air", cx + 70, gy + 55, cz + 80, cx, gy, cz, wait=14)
    look("11-night-square", cx + 12, gy + 6, cz + 14, cx, gy + 1, cz, wait=10)
    r.cmd("time set 6000")
    alive = client_alive()
    say("alive at the end: %s" % alive)
    say("PASS the town was photographed" if alive else "FAIL the client died while photographing the town")
    try:
        r.cmd("stop")
    except Exception:  # noqa: BLE001
        pass


if __name__ == "__main__":
    try:
        main()
    except (EOFError, OSError) as e:
        say("DIED: the server went away (%s)" % e)
        sys.exit(3)
