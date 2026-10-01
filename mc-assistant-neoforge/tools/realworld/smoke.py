#!/usr/bin/env python3
"""
Does the game CLIENT draw a village?

Everything else in CI runs a headless server, and a headless server never
draws anything: the profession smocks, the armour layer, the icon over a
folk's head and the model underneath all live in client code that has never
once been run. A crash there is a crash on the first screen the player sees.

This runs beside a real dedicated server with a real client (software OpenGL
on a virtual display), puts a village in front of the player over RCON, dresses
a few of the folk in armour, and takes screenshots. Only a client that dies is
a hard failure; the pictures are read by a person.

    smoke.py        (the server and the client are already starting)

Every line of output starts [REAL], like soak.py.
"""
import math
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from soak import Rcon, say, where  # noqa: E402

USER = "Dev"
FOLK = "mc_assistant:village_folk"


def shot(name):
    path = "smoke-%s.png" % name
    try:
        out = subprocess.run(["import", "-window", "root", path], capture_output=True, text=True, timeout=60)
        say("screenshot %s: %s" % (name, "taken" if out.returncode == 0 else out.stderr.strip()[:200]))
    except Exception as e:  # noqa: BLE001
        say("screenshot %s failed: %s" % (name, e))


def client_alive():
    out = subprocess.run(["pgrep", "-f", "runSmoke"], capture_output=True, text=True)
    return bool(out.stdout.strip())


def position(r):
    out = r.cmd("data get entity %s Pos" % USER)
    m = re.search(r"\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]", out)
    if not m:
        say("could not read the player's position: %s" % out[:200])
        return None
    return float(m.group(1)), float(m.group(2)), float(m.group(3))


def showcase(r, cx, cz, look):
    """Every building a village raises, each on a stage of its own (a picture of each,
    from the front, a little to one side); then a whole town laid out to the plan, from
    the air by day and by night, and down an avenue into the square."""
    r.cmd("time set 6000")
    r.cmd("gamemode spectator %s" % USER)
    bx, by, bz = cx - 300, 150, cz + 200
    r.cmd("tp %s %d %d %d" % (USER, bx + 20, by + 20, bz + 30))
    time.sleep(10)
    out = r.cmd("execute positioned %d %d %d run village showcase buildings" % (bx, by, bz))
    say("showcase: " + out[:900])
    found = re.findall(r"(\w+) (-?\d+) (-?\d+) (-?\d+)", out.replace("SHOWCASE", ""))
    for i, (name, x, y, z) in enumerate(found):
        x, y, z = int(x), int(y), int(z)
        tall = {"lighthouse": 22, "watchtower": 13, "chapel": 15, "hall": 10, "barracks": 8}.get(name, 7)
        back = {"hall": 26, "chapel": 28, "barracks": 22, "lighthouse": 24, "watchtower": 18}.get(name, 15)
        look("b%02d-%s" % (i + 1, name), x + back * 0.45, y + tall * 0.55 + 2, z + back,
             x, y + tall * 0.4, z, wait=5 if i else 9)
    say("alive after the buildings: %s" % client_alive())
    # The town.
    tx, ty, tz = cx - 300, 150, cz - 200
    r.cmd("tp %s %d %d %d" % (USER, tx, ty + 60, tz + 60))
    time.sleep(10)
    out = r.cmd("execute positioned %d %d %d run village showcase town" % (tx, ty, tz))
    say("town: " + out[:300])
    time.sleep(6)
    look("t1-town-air", tx + 62, ty + 58, tz + 74, tx, ty, tz, wait=12)
    look("t2-town-high", tx + 4, ty + 95, tz + 40, tx, ty, tz - 4, wait=8)
    look("t3-town-avenue", tx + 1, ty + 1, tz + 48, tx, ty + 3, tz, wait=8)
    look("t4-town-square", tx + 9, ty + 9, tz + 11, tx - 18, ty + 2, tz - 5, wait=8)
    look("t5-town-homes", tx + 52, ty + 14, tz + 52, tx + 26, ty + 2, tz + 26, wait=8)
    r.cmd("time set 18000")
    look("t6-town-night", tx + 62, ty + 58, tz + 74, tx, ty, tz, wait=10)
    r.cmd("time set 6000")
    say("alive after the town: %s" % client_alive())


def main():
    r = Rcon()
    say("connected; waiting for the client to join")
    deadline = time.time() + 900
    joined = False
    while time.time() < deadline:
        out = r.cmd("list")
        if USER in out:
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
    say("the client joined: %s" % r.cmd("list"))
    for c in ("gamerule doDaylightCycle false", "time set 6000", "weather clear 1000000",
              "gamerule doMobSpawning false", "gamemode creative %s" % USER):
        r.cmd(c)
    # Open ground, so the pictures are of the folk and not of a tree: a savanna.
    spot = where(r, "biome minecraft:savanna") or where(r, "biome minecraft:plains") or (0, 0)
    cx, cz = spot
    say("the village goes at %d, %d" % (cx, cz))
    say("moving the player: " + r.cmd("spreadplayers %d %d 0 2 false %s" % (cx - 14, cz, USER)))
    time.sleep(20)                                    # the ground arrives at the client
    say("spawn: " + r.cmd("village spawnat %d %d 12" % (cx, cz)))
    time.sleep(30)
    say("folk in the world: " + r.cmd("execute if entity @e[type=%s]" % FOLK))
    pos = position(r)
    py = int(pos[1]) if pos else 70

    def look_at_the_village(label):
        # From fourteen blocks off and a little above, at the middle of it.
        r.cmd("execute positioned %d %d %d run tp %s ~-14 ~3 ~ facing ~ ~1 ~" % (cx, py, cz, USER))
        time.sleep(8)
        shot(label)
        say("alive after %s: %s" % (label, client_alive()))

    look_at_the_village("1-village")

    # Armour on the three nearest, every slot, so the armour layer has something to draw.
    near = "execute at %s run item replace entity @e[type=%s,limit=3,sort=nearest] " % (USER, FOLK)
    for slot, item in (("armor.head", "minecraft:iron_helmet"), ("armor.chest", "minecraft:diamond_chestplate"),
                       ("armor.legs", "minecraft:iron_leggings"), ("armor.feet", "minecraft:diamond_boots")):
        r.cmd(near + "%s with %s" % (slot, item))
    time.sleep(3)
    # In front of one of the dressed ones, at its own height, looking it in the face.
    r.cmd("execute at %s as @e[type=%s,limit=1,sort=nearest] at @s run tp %s ^ ^ ^4 facing ~ ~1.6 ~" % (USER, FOLK, USER))
    time.sleep(8)
    shot("2-close")
    say("alive after the close look: %s" % client_alive())
    # And one that is not dressed: the smock alone.
    r.cmd("execute at %s as @e[type=%s,limit=1,sort=furthest] at @s run tp %s ^ ^ ^4 facing ~ ~1.6 ~" % (USER, FOLK, USER))
    time.sleep(8)
    shot("3-another")
    # A quarter of an hour of the day later, from the other side.
    time.sleep(20)
    r.cmd("execute positioned %d %d %d run tp %s ~14 ~3 ~ facing ~ ~1 ~" % (cx, py, cz, USER))
    time.sleep(8)
    shot("4-other-side")

    # The lineup: one folk of every trade in its clothes, holding its tool, on a
    # stone stage built in clear air (so the pictures are of the folk, not of
    # whatever the terrain happened to be) — from the front, close up three at a
    # time, from behind (the packs, the shield, the logs, the creel), and the
    # miner's lamp at night.
    sx, sy, sz = cx + 60, 150, cz
    r.cmd("tp %s %d %d %d 0 0" % (USER, sx, sy + 1, sz))
    time.sleep(10)                                    # the stage's chunks arrive
    say("stage: " + r.cmd("fill %d %d %d %d %d %d minecraft:smooth_stone" % (sx - 12, sy, sz - 6, sx + 12, sy, sz + 12)))
    r.cmd("fill %d %d %d %d %d %d minecraft:air" % (sx - 12, sy + 1, sz - 6, sx + 12, sy + 12, sz + 12))
    r.cmd("tp %s %d.5 %d %d.5 0 0" % (USER, sx, sy + 1, sz))
    time.sleep(3)
    say("lineup: " + r.cmd("execute as %s at @s run village lineup" % USER))
    say("lineup folk: " + r.cmd("execute if entity @e[tag=folk_lineup]"))
    px, py, pz = sx + 0.5, sy + 1.0, sz + 0.5
    line = pz + 4.0

    def look(label, x, y, z, tx, ty, tz, wait=6):
        # A camera, not a player: spectator mode (nothing falls, no hand in the
        # picture) and the angles worked out here — yaw 0 looks south (+z), a
        # positive pitch looks down, from eyes 1.62 above the feet.
        dx, dz = tx - x, tz - z
        yaw = math.degrees(math.atan2(-dx, dz))
        pitch = -math.degrees(math.atan2(ty - (y + 1.62), math.hypot(dx, dz)))
        r.cmd("tp %s %.2f %.2f %.2f %.1f %.1f" % (USER, x, y, z, yaw, pitch))
        time.sleep(wait)
        shot(label)

    r.cmd("gamemode spectator %s" % USER)
    look("5-lineup", px, py + 0.4, line - 11.0, px, py + 1.0, line, wait=8)
    look("6-lineup-left", px + 5.6, py - 0.4, line - 3.4, px + 5.6, py + 1.1, line)
    look("7-lineup-middle", px, py - 0.4, line - 3.4, px, py + 1.1, line)
    look("8-lineup-right", px - 5.6, py - 0.4, line - 3.4, px - 5.6, py + 1.1, line)
    look("9-lineup-back", px, py + 0.2, line + 6.5, px, py + 1.0, line)
    r.cmd("time set 18000")
    miner = px + (4.5 - 3) * 1.6          # the fourth in the row is the miner
    look("10-lamp-at-night", miner, py - 0.5, line - 2.6, miner, py + 1.4, line, wait=8)
    r.cmd("time set 6000")
    r.cmd("gamemode creative %s" % USER)

    # Talking with a folk: right-click opens a conversation (here /village talk does
    # the same for the nearest one). The farmer, second in the row, face to face.
    farmer = px + (4.5 - 1) * 1.6
    look("11-talk-hello", farmer, py, line - 2.2, farmer, py + 1.4, line, wait=4)
    say("talk: " + r.cmd("execute as %s at @s run village talk" % USER))
    time.sleep(4)
    shot("11-talk-hello")
    say("talk: " + r.cmd("execute as %s at @s run village talk how are you" % USER))
    time.sleep(3)
    shot("12-talk-how-are-you")
    say("talk: " + r.cmd("execute as %s at @s run village talk tell me about yourself" % USER))
    time.sleep(3)
    shot("13-talk-about")
    say("talk: " + r.cmd("execute as %s at @s run village talk can I help with anything" % USER))
    time.sleep(3)
    shot("14-talk-help")
    say("talk: " + r.cmd("execute as %s at @s run village talk got anything to trade" % USER))
    time.sleep(3)
    shot("15-talk-trade")
    say("alive after talking: %s" % client_alive())
    say("alive after the lineup: %s" % client_alive())
    try:
        showcase(r, cx, cz, look)
    except Exception as e:  # noqa: BLE001
        say("showcase failed: %s" % e)
    alive = client_alive()
    say("alive at the end: %s" % alive)
    say("PASS the client drew the village and kept running" if alive else "FAIL the client died while drawing the village")
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
