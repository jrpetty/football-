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
        tall = {"lighthouse": 22, "watchtower": 13, "chapel": 15, "hall": 10, "barracks": 8, "belltower": 18,
                "manor": 14}.get(name, 7)
        back = {"hall": 26, "chapel": 28, "barracks": 22, "lighthouse": 24, "watchtower": 18, "belltower": 24,
                "manor": 28}.get(name, 15)
        look("b%02d-%s" % (i + 1, name), x + back * 0.45, y + tall * 0.55 + 2, z + back,
             x, y + tall * 0.4, z, wait=5 if i else 9)
    say("alive after the buildings: %s" % client_alive())
    # The ages: a few buildings as a village has them in the Wood, Stone, Iron and Diamond Ages,
    # side by side (Grow and Ages), one row a building.
    ax, ay, az = cx - 300, 150, cz + 300
    r.cmd("tp %s %d %d %d" % (USER, ax + 40, ay + 20, az + 30))
    time.sleep(8)
    out = r.cmd("execute positioned %d %d %d run village showcase ages" % (ax, ay, az))
    say("ages: " + out[:600])
    for name, x, y, z, width in re.findall(r"(\w+) (-?\d+) (-?\d+) (-?\d+) (\d+)", out.replace("AGES", "")):
        x, y, z, width = int(x), int(y), int(z), int(width)
        back = min(55, max(22, width * 0.7))
        r.cmd("tp %s %d %d %d" % (USER, x, y + 14, z + back))
        time.sleep(4)
        look("a-%s-ages" % name, x, y + 14, z + back, x, y + 3, z, wait=8)
    say("alive after the ages: %s" % client_alive())
    # The town.
    # Below the clouds (they are at 192): a camera above them photographs clouds.
    tx, ty, tz = cx - 300, 118, cz - 200
    r.cmd("tp %s %d %d %d" % (USER, tx, ty + 60, tz + 60))
    time.sleep(10)
    out = r.cmd("execute positioned %d %d %d run village showcase town" % (tx, ty, tz))
    say("town: " + out[:300])
    time.sleep(6)
    look("t1-town-air", tx + 62, ty + 58, tz + 74, tx, ty, tz, wait=12)
    look("t2-town-high", tx + 4, ty + 68, tz + 34, tx, ty, tz - 4, wait=8)
    look("t3-town-avenue", tx + 1, ty + 1, tz + 48, tx, ty + 3, tz, wait=8)
    look("t4-town-square", tx + 9, ty + 9, tz + 11, tx - 18, ty + 2, tz - 5, wait=8)
    look("t5-town-homes", tx + 52, ty + 14, tz + 52, tx + 26, ty + 2, tz + 26, wait=8)
    # The town's life, close up: a stall on the square, a street sign, a house's number,
    # the washing, a scarecrow, the chimneys' smoke.
    views = re.findall(r"VIEW (\S+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+)", out)
    say("views: %s" % ", ".join(v[0] for v in views))
    for name, x, y, z, ax, ay, az in views:
        if "night" in name:
            continue
        look(name, int(x) + 0.5, int(y), int(z) + 0.5, int(ax) + 0.5, int(ay) + 0.5, int(az) + 0.5, wait=6)
    # By night, every window lit.
    r.cmd("time set 18000")
    say("lights: " + r.cmd("village showcase lights on"))
    look("t6-town-night", tx + 62, ty + 58, tz + 74, tx, ty, tz, wait=10)
    for name, x, y, z, ax, ay, az in views:
        if "night" in name:
            look(name, int(x) + 0.5, int(y), int(z) + 0.5, int(ax) + 0.5, int(ay) + 0.5, int(az) + 0.5, wait=8)
    look("t14-night-homes", tx + 52, ty + 14, tz + 52, tx + 26, ty + 2, tz + 26, wait=8)
    # A raid on the town by night: the watch on the north wall, a band at the gate.
    raid = r.cmd("execute positioned %d %d %d run village showcase raid" % (tx, ty, tz))
    say("raid: " + raid[:300])
    for name, x, y, z, ax, ay, az in re.findall(r"VIEW (\S+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+)", raid):
        look(name, int(x) + 0.5, int(y), int(z) + 0.5, int(ax) + 0.5, int(ay) + 0.5, int(az) + 0.5, wait=8)
    r.cmd("kill @e[tag=folk_lineup,type=!player]")
    r.cmd("village showcase lights off")
    r.cmd("time set 6000")
    say("alive after the town: %s" % client_alive())


def found_village(r, cx, cz, look):
    """A village founded the way a player founds one, photographed: the board a spawner puts up,
    on ground made rough on purpose whatever the seed gave (a hill across the edge, a knoll, a pit,
    a pond, trees); the founding screen with a count chosen; then, after Confirm and spawn, the
    ground levelled with its edges sloped into the land, and the folk come, from high up."""
    count = 40
    fx, fz = cx + 280, cz + 40
    r.cmd("gamemode spectator %s" % USER)
    r.cmd("time set 6000")
    r.cmd("tp %s %d 140 %d" % (USER, fx - 10, fz - 30))
    time.sleep(15)                                     # the ground arrives at the client
    out = r.cmd("village found board %d %d" % (fx, fz))
    say("found board: " + out[:300])
    m = re.search(r"FOUND-BOARD (-?\d+) (-?\d+) (-?\d+) facing (\w+) for a village at (-?\d+) (-?\d+) (-?\d+)", out)
    if not m:
        say("no founding board went up; nothing to photograph")
        return
    bx, by, bz = int(m.group(1)), int(m.group(2)), int(m.group(3))
    facing = m.group(4)
    hx, hy, hz = int(m.group(5)), int(m.group(6)), int(m.group(7))
    # The board runs to its reader's right from its first panel; it faces the heart, from the side it stands on.
    step = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}
    right = {"north": "west", "west": "south", "south": "east", "east": "north"}[facing]
    mid_x = bx + 0.5 + step[right][0] * 4.5
    mid_z = bz + 0.5 + step[right][1] * 4.5
    away = step[facing]                                # from the board towards the heart, and on past it
    side = step[right]

    def at(f, l):
        # So far on past the heart from the board, and so far to the board's right: the rough
        # ground goes on the far side of the heart, where it cannot fall on the board.
        return hx + away[0] * f + side[0] * l, hz + away[1] * f + side[1] * l

    # Rough ground: a grassy hill across the edge, a stone knoll and a pit inside, a pond, trees.
    radius = 39                                        # FoundingPlan.coreRadius(40)
    hill_x, hill_z = at(radius - 2, 10)
    for k in range(10):
        h = 14 - k
        r.cmd("fill %d %d %d %d %d %d minecraft:grass_block" % (hill_x - h, hy + k - 1, hill_z - h, hill_x + h, hy + k - 1, hill_z + h))
    kx, kz = at(12, -12)
    for k in range(4):
        h = 5 - k
        r.cmd("fill %d %d %d %d %d %d minecraft:stone" % (kx - h, hy + k - 1, kz - h, kx + h, hy + k - 1, kz + h))
    px, pz = at(6, 10)
    r.cmd("fill %d %d %d %d %d %d minecraft:air" % (px - 2, hy - 3, pz - 2, px + 2, hy - 1, pz + 2))
    wx, wz = at(-2, -18)
    r.cmd("fill %d %d %d %d %d %d minecraft:water" % (wx - 2, hy - 1, wz - 2, wx + 1, hy - 1, wz + 1))
    for f, l in ((18, 4), (22, -20), (-2, 16), (6, -4)):
        tx, tz = at(f, l)
        say("tree: " + r.cmd("place feature minecraft:oak %d %d %d" % (tx, hy, tz))[:120])
    time.sleep(4)
    # The waiting board, from across the heart.
    look("17-found-1-board", hx + 0.5 + away[0] * 6, hy + 2, hz + 0.5 + away[1] * 6, mid_x, by + 2.5, mid_z, wait=8)
    vx, vz = at(radius + 30, 40)
    cx2, cz2 = at(14, 4)
    look("17-found-1b-rough-ground", vx, hy + 30, vz, cx2, hy, cz2, wait=6)
    # The founding screen, as right-clicking the board opens it, with forty chosen.
    r.cmd("tp %s %.1f %d %.1f" % (USER, mid_x + away[0] * 4, hy + 1, mid_z + away[1] * 4))
    time.sleep(3)
    say("found screen: " + r.cmd("execute as %s at @s run village found screen %d" % (USER, count)))
    time.sleep(5)
    shot("17-found-2-screen")
    say("alive after the founding screen: %s" % client_alive())
    # Confirm and spawn (the command does exactly what the screen's button does; it closes the screen).
    say("found: " + r.cmd("village found %d %d %d" % (count, fx, fz))[:300])
    level_y = hy - 1
    started = time.time()
    shot_mid = False
    while time.time() - started < 240:
        status = r.cmd("village found status")
        mm = re.search(r"FOUNDING -?\d+ (-?\d+) -?\d+ (\w+) folk (\d+)/(\d+) ground (\d+)%", status)
        if not mm:
            say("founding done after %d s" % (time.time() - started))
            break
        level_y = int(mm.group(1)) - 1
        say("founding: %s, folk %s/%s, ground %s%%" % (mm.group(2), mm.group(3), mm.group(4), mm.group(5)))
        if not shot_mid and int(mm.group(5)) >= 30:
            look("17-found-3-levelling", fx + 70, level_y + 55, fz + 70, fx, level_y, fz, wait=4)
            shot_mid = True
        time.sleep(4)
    say("village: " + r.cmd("execute positioned %d %d %d run village status" % (fx, level_y + 1, fz))[:300])
    # The levelled ground and its sloped edges from high up, the hill's cut face, and the folk at their camp.
    look("17-found-4-done-air", fx + 75, level_y + 65, fz + 75, fx, level_y, fz, wait=12)
    look("17-found-5-done-overhead", fx + 4, level_y + 95, fz + 10, fx, level_y, fz, wait=8)
    ex, ez = at(radius + 34, 46)
    look("17-found-6-edge", ex, level_y + 22, ez, hill_x, level_y + 4, hill_z, wait=8)
    look("17-found-7-folk", fx + 10, level_y + 6, fz + 10, fx, level_y + 1, fz, wait=8)
    say("folk founded: " + r.cmd("execute positioned %d %d %d run village list" % (fx, level_y + 1, fz))[:400])


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
    # Seventeen of them now, 1.4 apart: five close-ups of three or four at a time.
    look("5-lineup", px, py + 0.6, line - 14.0, px, py + 1.0, line, wait=8)
    look("6-lineup-left", px + 4.9, py - 0.4, line - 3.4, px + 4.9, py + 1.1, line)
    look("6b-lineup-far-left", px + 9.8, py - 0.4, line - 3.4, px + 9.8, py + 1.1, line)
    look("7-lineup-middle", px, py - 0.4, line - 3.4, px, py + 1.1, line)
    look("8-lineup-right", px - 4.9, py - 0.4, line - 3.4, px - 4.9, py + 1.1, line)
    look("8b-lineup-far-right", px - 9.8, py - 0.4, line - 3.4, px - 9.8, py + 1.1, line)
    look("9-lineup-back", px, py + 0.4, line + 9.0, px, py + 1.0, line)
    # What a folk says out loud, in the bubble over its head: it must read by day against
    # the sky, and by night.
    r.cmd("tp %s %.2f %.2f %.2f 0 0" % (USER, px, py + 1, line - 3.0))
    say("bubble: " + r.cmd("execute as %s at @s run village say Good morning! Lovely day for it, isn't it?" % USER))
    look("9c-bubble-day", px, py + 0.2, line - 3.6, px, py + 2.6, line, wait=2)
    r.cmd("time set 18000")
    miner = px + (8 - 3) * 1.4            # the fourth in the row is the miner
    look("10-lamp-at-night", miner, py - 0.5, line - 2.6, miner, py + 1.4, line, wait=8)
    say("bubble: " + r.cmd("execute as %s at @s run village say Still up? Mind how you go in the dark." % USER))
    look("10b-bubble-night", miner, py + 0.2, line - 3.6, miner, py + 2.6, line, wait=2)
    r.cmd("time set 6000")
    r.cmd("gamemode creative %s" % USER)

    # Talking with a folk: right-click opens a conversation (here /village talk does
    # the same for the nearest one). The farmer, second in the row, face to face.
    farmer = px + (8 - 1) * 1.4            # second in a row of seventeen, 1.4 apart
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
    # The town's books (the analytics screen the village board opens): eight mornings
    # first, so there are days in them, with an hour or so of the village's work in each
    # (four times the speed for fifteen seconds), then a picture of each page.
    try:
        say("speed: " + r.cmd("village speed 4"))
        for d in range(8):
            r.cmd("time add 24000")
            time.sleep(15)
        say("speed: " + r.cmd("village speed 1"))
        say("stats: " + r.cmd("village stats"))
        for page, name in ((0, "overview"), (1, "growth"), (2, "money"), (3, "production"), (4, "shops"), (5, "jobs"),
                           (6, "folk"), (7, "society"), (8, "leader"), (9, "homes"), (10, "buildings"), (11, "stores"),
                           (12, "why"), (13, "trends"), (14, "records"), (15, "news")):
            say("stats %s: %s" % (name, r.cmd("execute as %s at @s run village stats %d" % (USER, page))))
            time.sleep(3)
            shot("16-stats-%d-%s" % (page, name))
        r.cmd("time set 6000")
    except Exception as e:  # noqa: BLE001
        say("stats failed: %s" % e)
    say("alive after the lineup: %s" % client_alive())
    try:
        found_village(r, cx, cz, look)
    except Exception as e:  # noqa: BLE001
        say("founding failed: %s" % e)
    r.cmd("gamemode spectator %s" % USER)
    say("alive after the founding: %s" % client_alive())
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
