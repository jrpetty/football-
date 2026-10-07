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
    smoke.py mature (the same, on a world the hundred days saved: the biggest town in it
                    from the air, then every page of its books; smoke-mature-*.png)

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
                "manor": 14, "museum": 10}.get(name, 7)
        back = {"hall": 26, "chapel": 28, "barracks": 22, "lighthouse": 24, "watchtower": 18, "belltower": 24,
                "manor": 28, "museum": 22}.get(name, 15)
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


def decor_stage(r, look, cx, cz):
    """A home that shows its trade: a smith and a farmer wed and living in a house furnished out of the
    stores (Decor, Luxuries) — the smith's anvil, the farmer's composter and sack of seed, a rug and a
    banner in their favourite colours, and the luxuries they bought (paintings, candles, a pot of flowers,
    a lantern, a carpet) — from the street, from inside by day, and at dusk with its candles lit."""
    x, z = cx - 340, cz + 520
    r.cmd("gamemode spectator %s" % USER)
    r.cmd("time set 6000")
    r.cmd("tp %s %d 140 %d" % (USER, x, z + 12))
    time.sleep(12)                                     # the ground arrives at the server and the client
    out = r.cmd("execute positioned %d 100 %d run village decor showcase" % (x, z))
    say("decor: " + out[:900])
    # Each view: where the camera's feet stand and what it looks at, already in the middle of their blocks.
    num = r"(-?\d+(?:\.\d+)?)"
    views = re.findall(r"VIEW (\S+) " + " ".join([num] * 6), out)
    if not views:
        say("no furnished home was set out; nothing to photograph")
        return
    for name, ex, ey, ez, ax, ay, az in views:
        if "dusk" in name:
            continue
        look(name, float(ex), float(ey), float(ez), float(ax), float(ay), float(az), wait=8)
    # Dusk: the household's candles lit, seen from outside through the window they stand in.
    r.cmd("time set 12700")
    say("decor at dusk: " + r.cmd("execute positioned %d 100 %d run village decor now" % (x, z))[:600])
    for name, ex, ey, ez, ax, ay, az in views:
        if "dusk" in name:
            look(name, float(ex), float(ey), float(ez), float(ax), float(ay), float(az), wait=8)
    r.cmd("time set 6000")
    say("alive after the furnished home: %s" % client_alive())


def flats_stage(r, look, cx, cz):
    """A block of flats, furnished, on a stage of its own (/village flats stage): from across the street,
    the whole town house (three storeys of stone with brick quoins, the balconies and their railings, the
    slate roof with its dormer and the two chimneys smoking), the stair hall from just inside the front
    door (the flats' doors and numbers, the stair winding up the back), a couple's flat on the first floor
    from its door (two beds, the chest, the table, the lantern), and the front door close to (the pediment
    and its lanterns, the fanlight, the name by the door, the window boxes, the lamp posts)."""
    bx, by, bz = cx + 140, 150, cz - 60
    r.cmd("time set 6000")
    r.cmd("gamemode spectator %s" % USER)
    r.cmd("tp %s %d %d %d" % (USER, bx, by + 12, bz + 30))
    time.sleep(10)                                    # the stage's chunks arrive
    out = r.cmd("execute positioned %d %d %d run village flats stage" % (bx, by, bz))
    say("flats stage: " + out[:200])
    m = re.search(r"FLATSTAGE (-?\d+) (-?\d+) (-?\d+)", out)
    if m:
        bx, by, bz = int(m.group(1)), int(m.group(2)), int(m.group(3))
    # Its back is to the north: the front door at x+3, z+4, the street to the south. A block (dx, h, dz)
    # of its drawing is at x + dx, y + h, z - dz; it stands nineteen high to its chimney tops.
    look("flats-1-front", bx - 13.5, by + 5, bz + 24.5, bx + 0.5, by + 8, bz + 0.5, wait=8)
    look("flats-2-hall", bx + 3.5, by, bz + 3.5, bx + 3.5, by + 2.5, bz - 2.5, wait=6)
    look("flats-3-flat", bx + 1.5, by + 4, bz - 1.5, bx - 3.5, by + 4.2, bz - 2.5, wait=6)
    look("flats-4-door", bx - 1.5, by + 2, bz + 12.5, bx + 2.5, by + 3, bz + 5, wait=6)
    say("alive after the flats: %s" % client_alive())


def blueprints_stage(r, look, cx, cz):
    """The drawings put right (tools/blueprint_designs.py, BlueprintSoundnessTest), by night, when the
    lanterns tell: the meeting hall from just inside its doors (its lanterns hung from the rafters down
    both sides), the market from among its stalls (its lanterns hung from the eave plates over them), and
    the lighthouse's railed gallery from the back, where the ladder comes up beside the lamp room and the
    lamp hangs from the cap over its brazier."""
    bx, by, bz = cx - 300, 150, cz + 200              # where the showcase sets its buildings out
    r.cmd("gamemode spectator %s" % USER)
    r.cmd("time set 6000")
    r.cmd("tp %s %d %d %d" % (USER, bx + 20, by + 20, bz + 30))
    time.sleep(10)                                    # the stage's chunks arrive
    out = r.cmd("execute positioned %d %d %d run village showcase buildings" % (bx, by, bz))
    at = {name: (int(x), int(y), int(z))
          for name, x, y, z in re.findall(r"(\w+) (-?\d+) (-?\d+) (-?\d+)", out.replace("SHOWCASE", ""))}
    say("blueprints stage: " + ", ".join(n for n in ("hall", "market", "lighthouse") if n in at))
    r.cmd("time set 18000")
    # Each building's back is to the north: a block (dx, h, dz) of its drawing is at x + dx, y + h, z - dz.
    if "hall" in at:
        x, y, z = at["hall"]
        look("bp-1-hall-rafters", x + 0.5, y, z + 5.5, x + 0.5, y + 4.5, z - 3.5, wait=8)
    if "market" in at:
        x, y, z = at["market"]
        look("bp-2-market-eaves", x + 0.5, y, z + 3.5, x + 0.5, y + 3.0, z - 2.5, wait=6)
    if "lighthouse" in at:
        x, y, z = at["lighthouse"]
        look("bp-3-lighthouse-gallery", x + 2.5, y + 16, z - 1.5, x + 0.5, y + 18.5, z + 0.5, wait=6)
    r.cmd("time set 6000")
    say("alive after the drawings: %s" % client_alive())


def horses_stage(r, look, cx, cz):
    """The stable (Stables, Riding): a stable stood up on its own ground, from the front with its
    gates and the courier on horseback at its door, and from the doorway in along the stalls where
    a saddled horse and a donkey with a chest stand; then cleared away."""
    sx, sz = cx + 140, cz - 240
    r.cmd("gamemode spectator %s" % USER)
    r.cmd("time set 6000")
    r.cmd("tp %s %d 150 %d" % (USER, sx + 10, sz + 30))
    time.sleep(12)                                     # the ground arrives at the client
    out = r.cmd("execute positioned %d 0 %d positioned over motion_blocking_no_leaves run village horses showcase" % (sx, sz))
    say("stable: " + out[:400])
    views = re.findall(r"VIEW (\S+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+)", out)
    for i, (name, x, y, z, ax, ay, az) in enumerate(views):
        look(name, int(x) + 0.5, int(y), int(z) + 0.5, int(ax) + 0.5, int(ay) + 0.5, int(az) + 0.5, wait=10 if i == 0 else 6)
    say("alive after the stable: %s" % client_alive())
    r.cmd("kill @e[tag=folk_lineup,type=!player]")


def townlook_stage(r, look, cx, cz):
    """The town's look (TownLook), set out on a stage of its own (/village townlook showcase): the
    windmill with its sails hung, the orchard's oaks and the allotments' crops by it, from above the
    farmland; the bakery's window boxes and the inn's sign along a stretch of avenue lined with trees
    between the lamp posts, a bench and the notice board across the road; and the windmill close to,
    the axle and the four sails on the front of its stone and timber tower."""
    sx, sz = cx + 300, cz + 360
    r.cmd("gamemode spectator %s" % USER)
    r.cmd("time set 6000")
    r.cmd("tp %s %d 150 %d" % (USER, sx + 30, sz + 30))
    time.sleep(12)                                     # the ground arrives at the server and the client
    out = r.cmd("execute positioned %d 0 %d positioned over motion_blocking_no_leaves run village townlook showcase" % (sx, sz))
    say("town's look: " + out[:400])
    views = re.findall(r"VIEW (\S+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+)", out)
    for i, (name, x, y, z, ax, ay, az) in enumerate(views):
        look(name, int(x) + 0.5, int(y), int(z) + 0.5, int(ax) + 0.5, int(ay) + 0.5, int(az) + 0.5, wait=10 if i == 0 else 6)
    say("alive after the town's look: %s" % client_alive())


def found_village(r, cx, cz, look):
    """A village founded the way a player founds one, photographed: the board a spawner puts up,
    on ground made rough on purpose whatever the seed gave (a hill across the edge, a knoll, a pit,
    a pond, trees); the founding screen with a count chosen; then, after Confirm and spawn, the
    ground levelled with its edges sloped into the land, and the folk come, from high up."""
    count = 40
    fx, fz = cx + 280, cz + 40
    # The books may still be open from the stats stage: shut them, or the board's photograph is of them.
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
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
    radius = 55                                        # FoundingPlan.coreRadius(40): forty percent wider than the rings need
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
    # Measured: the square inside its wobbling edge (radius less four) should all stand at one height.
    say("ground check: " + r.cmd("village found ground %d %d %d" % (fx, fz, radius - 4))[:900])
    # The levelled ground and its sloped edges from high up, the hill's cut face, and the folk at their camp.
    # (Inside the client's sight: ten chunks, its fog closing in from about a hundred and forty blocks.
    # From higher up the square was lost in the fog and the overhead saw nothing but sky.)
    # (Long waits: at ten chunks a whole square's chunks take a while to draw, and a chunk not yet drawn shows
    # as a hole of sky where the ground is whole.)
    look("17-found-4-done-air", fx + 78, level_y + 58, fz + 78, fx, level_y, fz, wait=35)
    look("17-found-5-done-overhead", fx + 2, level_y + 82, fz + 6, fx, level_y, fz, wait=30)
    ex, ez = at(radius + 34, 46)
    look("17-found-6-edge", ex, level_y + 22, ez, hill_x, level_y + 4, hill_z, wait=8)
    look("17-found-7-folk", fx + 10, level_y + 6, fz + 10, fx, level_y + 1, fz, wait=8)
    say("folk founded: " + r.cmd("execute positioned %d %d %d run village list" % (fx, level_y + 1, fz))[:400])


def jungle_stage(r, look):
    """A village founded in a jungle (entity/Founding, VillageBoards, Terraform): the nearest jungle found,
    the trunks photographed from the air, then twelve founded there as /village spawn, a charter or the world
    itself now founds one, the board put up among the trunks and the ground levelled and cleared before the
    folk come; the same view after. (The board once had nowhere to stand in a jungle, and a town founded any
    other way stood among the trees on its hillside.)"""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("gamemode spectator %s" % USER)
    r.cmd("time set 6000")
    out = r.cmd("locate biome minecraft:jungle")
    say("jungle: " + out[:200])
    m = re.search(r"\[(-?\d+), [^,\]]+, (-?\d+)\]", out)
    if not m:
        say("no jungle within reach of this seed; nothing to photograph")
        return
    jx, jz = int(m.group(1)) + 24, int(m.group(2)) + 24
    r.cmd("tp %s %d 170 %d" % (USER, jx, jz))
    time.sleep(30)                                     # the jungle is generated and arrives at the client
    gy = ground_height(r, jx, jz)
    look("24-jungle-1-before", jx + 46, gy + 38, jz + 46, jx, gy, jz, wait=20)
    out = r.cmd("village found 12 %d %d" % (jx, jz))
    say("jungle found: " + out[:300])
    if "FOUNDING" not in out and "being made ready" not in out:
        say("FAIL the jungle founding did not begin: " + out[:200])
        return
    started = time.time()
    level_y = gy - 1
    while time.time() - started < 300:
        status = r.cmd("village found status")
        mm = re.search(r"FOUNDING -?\d+ (-?\d+) -?\d+ (\w+) folk (\d+)/(\d+) ground (\d+)%", status)
        if not mm:
            say("jungle founding done after %d s" % (time.time() - started))
            break
        level_y = int(mm.group(1)) - 1
        say("jungle founding: %s, folk %s/%s, ground %s%%" % (mm.group(2), mm.group(3), mm.group(4), mm.group(5)))
        time.sleep(5)
    say("jungle ground check: " + r.cmd("village found ground %d %d 24" % (jx, jz))[:600])
    say("jungle village: " + r.cmd("execute positioned %d %d %d run village status" % (jx, level_y + 1, jz))[:300])
    look("24-jungle-2-levelled", jx + 46, level_y + 38, jz + 46, jx, level_y, jz, wait=30)
    look("24-jungle-3-square", jx + 14, level_y + 6, jz + 14, jx, level_y + 1, jz, wait=10)
    say("alive after the jungle: %s" % client_alive())


def sweeper_stage(r, look, cx, cz):
    """The street sweeper (entity/Sweepers): the folk nearest the heart made the storehouse's sweeper
    (whatever the size of the town), saplings, seed, eggs, wool, cobblestone and bones dropped about the
    square, and the sweeper going among them; then the Stores page, with what it swept in and how much is
    lying about the town. After the books, so the town has had the days to put its storehouse up."""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("time set 6000")
    r.cmd("gamemode spectator %s" % USER)
    out = r.cmd("execute positioned %d 100 %d run village sweeper appoint" % (cx, cz))
    say("sweeper: " + out[:500])
    m = re.search(r"SWEEPER (.+?) (-?\d+) (-?\d+) (-?\d+)", out)
    if not m:
        say("nobody took up the broom; nothing to photograph")
        return
    y = int(m.group(3))
    r.cmd("tp %s %d %d %d" % (USER, cx + 18, y + 10, cz + 18))
    time.sleep(6)
    litter = (("oak_sapling", 3), ("wheat_seeds", 6), ("egg", 2), ("white_wool", 3), ("cobblestone", 8),
              ("bone", 2), ("birch_sapling", 2), ("string", 3))
    for i, (item, n) in enumerate(litter):
        a = i * math.pi / 4
        x, z = cx + 7 * math.cos(a), cz + 7 * math.sin(a)
        r.cmd('summon minecraft:item %.1f %d %.1f {Item:{id:"minecraft:%s",count:%d}}' % (x, y + 2, z, item, n))
    look("18-sweeper-1-litter", cx + 16, y + 9, cz + 16, cx, y, cz, wait=6)
    # A little later: the sweeper among the heaps (it lets them settle a few seconds first).
    time.sleep(15)
    pos = r.cmd("data get entity @e[tag=mca_sweeper,limit=1] Pos")
    pm = re.search(r"\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]", pos)
    if pm:
        fx, fy, fz = float(pm.group(1)), float(pm.group(2)), float(pm.group(3))
        look("18-sweeper-2-at-work", fx + 5, fy + 2, fz + 5, fx, fy + 1, fz, wait=5)
    else:
        say("the sweeper is not to be found: %s" % pos[:200])
    time.sleep(30)
    say("sweeper: " + r.cmd("execute positioned %d 100 %d run village sweeper" % (cx, cz))[:700])
    say("stats stores: " + r.cmd("execute as %s at @s run village stats 11" % USER))
    time.sleep(3)
    shot("18-sweeper-3-stores")
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    say("alive after the sweeper: %s" % client_alive())
def school_stage(r, look, cx, cz):
    """The village school mid-lesson: a schoolhouse set out on the land out past the village (/village
    school stage levels a lot to the ground's own height there and slopes its edges back into the land),
    the blackboard up, the teacher at the lectern and six children at their desks; from the street, then
    from the back of the aisle over the children's heads to the teacher and the blackboard while the
    teacher says a line of the lesson; and the School page of the nearest village's books."""
    sx, sy, sz = cx - 90, 150, cz + 90                 # (the height is only a fallback: the stage finds the ground)
    r.cmd("gamemode spectator %s" % USER)
    r.cmd("time set 2500")
    r.cmd("tp %s %d %d %d" % (USER, sx + 7, sy + 6, sz + 16))
    time.sleep(10)                                     # the stage's chunks arrive
    out = r.cmd("execute positioned %d %d %d run village school stage" % (sx, sy, sz))
    say("school stage: " + out[:400])
    for name, x, y, z, ax, ay, az in re.findall(r"VIEW (\S+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+)", out):
        x, y, z, ax, ay, az = int(x), int(y), int(z), int(ax), int(ay), int(az)
        if name == "school-lesson":
            # In close first, so the teacher's words reach the camera; its bubble lasts a few seconds.
            r.cmd("tp %s %d %d %d" % (USER, x, y, z))
            time.sleep(2)
            say("lesson: " + r.cmd("execute positioned %d %d %d run village school say" % (x, y, z))[:200])
        look("20-%s" % name, x + 0.5, y, z + 0.5, ax + 0.5, ay + 0.5, az + 0.5, wait=3 if name == "school-lesson" else 8)
    r.cmd("kill @e[tag=folk_lineup,type=!player]")
    # The School page of the books (the last page; opened by its name).
    say("school page: " + r.cmd("execute as %s at @s run village school page" % USER)[:200])
    time.sleep(4)
    shot("20-school-page")
    r.cmd("execute as %s run village stats close" % USER)
    say("school: " + r.cmd("execute as %s at @s run village school" % USER)[:400])
    say("alive after the school: %s" % client_alive())


def market_stall_stage(r, look, cx, cz):
    """A market stall of the player's own (entity/PlayerStalls), photographed: rented on the square of the
    village at cx, cz (the booth out of the stores, or the player's own barrel, sign, fences and wool),
    stocked with bread at the going price, apples cheap and pumpkin pies far too dear, then market day at
    it (the nearest folk with coin come, buy what is fair and say what is not); the booth with its buyers,
    the stall's screen (the till, the wares against the going price, who bought what), and the Shops page
    of the town's books on the players' stalls."""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("gamemode creative %s" % USER)
    midday(r)
    say("to the square: " + r.cmd("execute positioned %d 0 %d positioned over motion_blocking_no_leaves run tp %s ~2 ~ ~2"
                                  % (cx, cz, USER)))
    time.sleep(5)
    for item, n in (("mc_assistant:village_coin", 40), ("minecraft:barrel", 1), ("minecraft:oak_sign", 1),
                    ("minecraft:spruce_fence", 4), ("minecraft:red_wool", 2), ("minecraft:white_wool", 1)):
        r.cmd("give %s %s %d" % (USER, item, n))
    out = r.cmd("execute as %s at @s run village stall rent" % USER)
    say("stall rent: " + out[:500])
    m = re.search(r"STALL (-?\d+) (-?\d+) (-?\d+) facing (\w+)", out)
    if not m:
        say("no stall was rented; nothing to photograph")
        return
    bx, by, bz, facing = int(m.group(1)), int(m.group(2)), int(m.group(3)), m.group(4)
    for slot, item, n in ((0, "minecraft:bread", 32), (1, "minecraft:apple", 16), (2, "minecraft:pumpkin_pie", 4)):
        r.cmd("item replace block %d %d %d container.%d with %s %d" % (bx, by, bz, slot, item, n))
    say("price: " + r.cmd("execute as %s at @s run village stall price -1 bread" % USER))         # the going price
    say("price: " + r.cmd("execute as %s at @s run village stall price 2 apple" % USER))          # a bargain
    say("price: " + r.cmd("execute as %s at @s run village stall price 40 pumpkin pie" % USER))   # far too dear
    front = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}[facing]
    # Market day at it, then at once the picture: the buyers in front, their words over their heads.
    say("market: " + r.cmd("execute as %s at @s run village stall market" % USER)[:700])
    r.cmd("gamemode spectator %s" % USER)
    ex = bx + 0.5 + front[0] * 6 + front[1] * 2
    ez = bz + 0.5 + front[1] * 6 - front[0] * 2
    look("18-stall-1-booth", ex, by + 1.5, ez, bx + 0.5, by + 1.0, bz + 0.5, wait=3)
    # The stall's screen, from in front of it.
    r.cmd("gamemode creative %s" % USER)
    r.cmd("tp %s %.1f %d %.1f" % (USER, bx + 0.5 + front[0] * 3, by, bz + 0.5 + front[1] * 3))
    time.sleep(2)
    say("stall screen: " + r.cmd("execute as %s at @s run village stall screen" % USER))
    time.sleep(4)
    shot("18-stall-2-screen")
    # The town's books, the Shops page, on the players' stalls.
    say("stall books: " + r.cmd("execute as %s at @s run village stall books" % USER))
    time.sleep(4)
    shot("18-stall-3-books")
    say("stalls: " + r.cmd("execute as %s at @s run village stall" % USER)[:700])
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    say("alive after the stall: %s" % client_alive())
def bell_stage(r, look, cx, cz):
    """The town's calendar (TownBell, BellFrame, Birthdays, FoundingDay), photographed in the village spawned at
    cx, cz: the town bell in its own frame on the square, a few blocks from the board, from the front, its
    ringer at it ringing the dawn bell while the town gathers before the board; the town gathered for Founding
    Day hearing its year's chronicle read out, the frame beside it; and the town's calendar (today's bells,
    the next Founding Day, the week's birthdays) on the News page of its books. If the frame is not up yet
    its makings go into the stores (logs, planks, two lanterns, and a bell if the town has none), as a player
    would bring them, and the town's works build it."""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("gamemode spectator %s" % USER)
    where = "execute positioned %d 100 %d run " % (cx, cz)
    out = r.cmd(where + "village bell")
    say("bell: " + out[:900])
    board = re.search(r"BOARD (-?\d+) (-?\d+) (-?\d+)", out)
    if re.search(r"FRAME-AT", out) and " DONE" not in out:
        st = re.search(r"STORES (-?\d+) (-?\d+) (-?\d+)", out)
        if st:
            sx, sy, sz = (int(v) for v in st.groups())
            goods = [(26, "minecraft:oak_log 16"), (25, "minecraft:oak_planks 16"), (24, "minecraft:lantern 2")]
            if "BELL-AT" not in out:
                goods.append((23, "minecraft:bell 1"))
            for slot, item in goods:
                say("stores: " + r.cmd("item replace block %d %d %d container.%d with %s" % (sx, sy, sz, slot, item)))
        # The town's works look at the frame once a minute until a hand is called to it, then every couple
        # of seconds: up to four minutes, saying each time what it waits on; given up early if it is waiting on
        # something the stores cannot settle (a first building, a hand that never comes) after two.
        started = time.time()
        last = ""
        while time.time() - started < 240:
            time.sleep(6)
            out = r.cmd(where + "village bell")
            fm = re.search(r"FRAME-AT [^.]*\.( WAITING for [^.]*\.)?", out)
            line = fm.group(0) if fm else out[:200]
            if line != last:
                say("frame (%ds): %s" % (time.time() - started, line))
                last = line
            if " DONE" in out:
                break
            if time.time() - started > 120 and "not begun" in out and "WAITING for the timber" not in out:
                say("the frame has not begun after two minutes; going on without it")
                break
    frame = re.search(r"FRAME-AT (-?\d+) (-?\d+) (-?\d+) ALONG (\w+) FACING (\w+) DONE \(\d+/\d+ pieces, (\w+)", out)
    step = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}
    if frame:
        fx, fy, fz = int(frame.group(1)), int(frame.group(2)), int(frame.group(3))
        front = step.get(frame.group(5), (0, 1))
        bell_y = fy + (3 if frame.group(6) == "stone" else 2)
        # The dawn bell called for: its ringer walks to the frame and rings it, as the town gathers before the
        # board for Founding Day, a few blocks off.
        say("call: " + r.cmd(where + "village bell call dawn"))
        say("founding: " + r.cmd(where + "village founding now"))
        started = time.time()
        while time.time() - started < 40:
            time.sleep(2)
            if "Dawn bell rang at" in r.cmd(where + "village bell"):
                break
        # From the square, before the frame: its posts, roof and lanterns, the bell swinging, its ringer.
        look("18-bell-1-dawn", fx + 0.5 + front[0] * 8, fy + 2.2, fz + 0.5 + front[1] * 8, fx + 0.5, bell_y, fz + 0.5, wait=1)
    else:
        say("no bell frame done; the bell where it hangs")
        m = re.search(r"BELL-AT (-?\d+) (-?\d+) (-?\d+)", out)
        say("founding: " + r.cmd(where + "village founding now"))
        if m:
            bx, by, bz = (int(v) for v in m.groups())
            say("ring: " + r.cmd(where + "village bell ring dawn"))
            look("18-bell-1-dawn", bx + 4.5, by + 1, bz + 4.5, bx + 0.5, by + 0.5, bz + 0.5, wait=2)
    time.sleep(25)                                     # they gather; the first lines of the year are read
    # Founding Day before the board: the crowd, the year's chronicle read out over the leader's head, the frame by it.
    if board:
        lx, ly, lz = (int(v) for v in board.groups())
        if frame:
            mx, mz = (lx + fx) / 2.0, (lz + fz) / 2.0
            look("18-bell-2-founding", mx + 0.5 + front[0] * 14, ly + 7, mz + 0.5 + front[1] * 14, mx + 0.5, ly + 1.5, mz + 0.5, wait=3)
        else:
            look("18-bell-2-founding", lx + 9.5, ly + 6, lz + 9.5, lx + 0.5, ly + 1.5, lz + 0.5, wait=3)
    else:
        look("18-bell-2-founding", cx + 9.5, 100, cz + 9.5, cx + 0.5, 95, cz + 0.5, wait=3)
    say("founding: " + r.cmd(where + "village founding")[:500])
    # The town's books, the News page: the town's calendar above the chronicle.
    say("stats news: " + r.cmd("execute as %s at @s run village stats 17" % USER))
    time.sleep(3)
    shot("18-bell-3-news")
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    say("alive after the bell: %s" % client_alive())


def bank_stage(r, look, cx, cz):
    """The bank (entity/Bank): put up beside the village on ground cleared for it and opened
    (/village bank showcase), its banker held at the counter facing the door; from the street; then
    from the front corner inside, the ledger open on its lectern, the banker at the counter and the
    vault behind its iron bars; then a word with the banker across the counter about the account the
    player has just opened (the talk screen); and the books' Money and Homes pages with the bank on
    them. Best called after the stats stage, so the books have days in them."""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("gamemode spectator %s" % USER)
    r.cmd("time set 6000")
    gy = ground_height(r, cx - 40, cz)
    # West of the heart, looking west (yaw 90): the bank goes up ten blocks ahead, its door toward the village.
    r.cmd("tp %s %d %d %d 90 0" % (USER, cx - 40, gy + 1, cz))
    time.sleep(8)
    out = r.cmd("execute as %s at @s run village bank showcase" % USER)
    say("bank showcase: " + out[:400])
    m = re.search(r"stands at (-?\d+), (-?\d+), (-?\d+), facing (\w+)", out)
    if not m:
        say("no bank went up; nothing to photograph")
        return
    ax, ay, az, facing = int(m.group(1)), int(m.group(2)), int(m.group(3)), m.group(4).lower()
    step = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}
    back = step.get(facing, (0, -1))                     # the vault's end of the building
    right = (-back[1], back[0])                          # to the right, looking in from the door

    def at(across, deep, up=0.0):
        # A point of the drawing (blueprints/bank.txt): so far to the right of the middle, looking in
        # from the door, so far toward the back from the counter's banker (the anchor), so far up from
        # the floor. The banker stands at (0, 0); the counter is a row in front (-1), the vault's bars a
        # row behind (+1), the lectern at (2, -2), the door at (0, -4).
        return (ax + 0.5 + right[0] * across + back[0] * deep, ay + up, az + 0.5 + right[1] * across + back[1] * deep)

    def face(x, y, z, tx, ty, tz):
        # The yaw and pitch from feet at (x, y, z), eyes 1.62 above them, to (tx, ty, tz).
        dx, dz = tx - x, tz - z
        return math.degrees(math.atan2(-dx, dz)), -math.degrees(math.atan2(ty - (y + 1.62), math.hypot(dx, dz)))

    time.sleep(4)
    # From the street: the stone front, its door and windows.
    look("bank-1-front", *at(6, -15, 4), *at(0, 0, 2), wait=8)
    # Inside, from the front corner by the window: the ledger open on its lectern at the right, the
    # banker behind the counter, and behind it the vault, its strongboxes behind the barred gate and grille.
    say("banker: " + r.cmd("execute as %s at @s run village bank showcase" % USER)[:200])
    look("bank-2-vault", *at(-2, -3, 0.3), *at(0.6, 0.4, 1.0), wait=6)
    # An account: coin in, then a word with the banker across the counter, the talk screen open on it.
    r.cmd("gamemode creative %s" % USER)
    r.cmd("give %s mc_assistant:village_coin 40" % USER)
    say("bank deposit: " + r.cmd("execute as %s at @s run village bank deposit 40" % USER))
    px, py, pz = at(0, -2)
    yaw, pitch = face(px, py, pz, *at(0, 0, 1.5))
    r.cmd("tp %s %.2f %.2f %.2f %.1f %.1f" % (USER, px, py, pz, yaw, pitch))
    time.sleep(10)                                       # the chat's game-mode and deposit lines fade
    say("bank talk: " + r.cmd("execute as %s at @s run village talk my account" % USER))
    time.sleep(4)
    shot("bank-3-talk-account")
    say("bank books: " + r.cmd("execute positioned %d %d %d run village bank" % (ax, ay, az))[:600])
    # The town's books: the Money page, the bank's panel where the in-and-out bars were; then the Homes page.
    say("stats money: " + r.cmd("execute as %s at @s run village stats 2" % USER))
    time.sleep(4)
    shot("bank-4-money-page")
    say("stats homes: " + r.cmd("execute as %s at @s run village stats 9" % USER))
    time.sleep(3)
    shot("bank-5-homes-page")
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    say("banker free: " + r.cmd("execute as %s at @s run village bank showcase done" % USER))
    r.cmd("gamemode spectator %s" % USER)
    say("alive after the bank: %s" % client_alive())


def housing_stage(r, look, cx, cz):
    """A folk's own house going up (entity/HousingMarket), on ground made ready for it: east of the heart, clear of
    the town's buildings, a plot levelled (/village house market stage: the makings of a villa delivered into the
    stores and what the best-placed household lacks of the bill granted it, every grant in the chronicle), the villa
    commissioned and paid for as any house is. The builders lay a third of it out of the stores (/village house
    market build): photographed from its street, going up; then the books' Homes page with the market strip (the
    index, prices and rents, the villa going up with its bill); then the rest laid, and the finished villa from the
    street with its owner held at its door. Best called after the stats stage, so the books have days in them."""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("gamemode spectator %s" % USER)
    midday(r)
    sx, sz = cx + 60, cz + 20
    r.cmd("tp %s %d %d %d" % (USER, sx, ground_height(r, sx, sz) + 24, sz + 30))
    time.sleep(10)                                       # the plot's chunks arrive
    out = r.cmd("execute positioned %d %d %d run village house market stage" % (sx, ground_height(r, sx, sz) + 1, sz))
    say("housing stage: " + out[:500])
    m = re.search(r"BUILD (.+?) (cottage|family house|town house|villa) at (-?\d+) (-?\d+) (-?\d+) facing (\w+) bill (\d+).*?"
                  r"lead ([0-9a-f-]+) DOOR (-?\d+) (-?\d+) (-?\d+)", out)
    if not m:
        say("no house of a folk's own commissioned; nothing to photograph")
        return
    ax, ay, az = int(m.group(3)), int(m.group(4)), int(m.group(5))
    lead, dx, dy, dz = m.group(8), int(m.group(9)), int(m.group(10)), int(m.group(11))
    # Its back is to the north: the door, the porch and the street to the south.
    ex, ez = ax + 7.5, az + 18.5
    r.cmd("tp %s %.1f %d %.1f" % (USER, ex, ay + 8, ez))
    time.sleep(6)
    say("housing build: " + r.cmd("execute positioned %d %d %d run village house market build %d" % (ax, ay, az, 180))[:400])
    look("housing-1-going-up", ex, ay + 7, ez, ax + 0.5, ay + 3, az + 0.5, wait=8)
    r.cmd("gamemode creative %s" % USER)
    r.cmd("tp %s %d %d %d" % (USER, cx, ground_height(r, cx, cz) + 1, cz))
    time.sleep(3)
    say("housing market: " + r.cmd("execute positioned %d %d %d run village house market" % (ax, ay, az))[:900])
    say("stats homes: " + r.cmd("execute as %s at @s run village stats 9" % USER))
    time.sleep(4)
    shot("housing-2-homes-page")
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("gamemode spectator %s" % USER)
    r.cmd("tp %s %.1f %d %.1f" % (USER, ex, ay + 8, ez))
    time.sleep(4)
    say("housing finish: " + r.cmd("execute positioned %d %d %d run village house market build %d" % (ax, ay, az, 2000))[:400])
    # Its owner at its own front door, held there for the picture.
    r.cmd("data merge entity %s {NoAI:1b}" % lead)
    r.cmd("tp %s %.1f %d %.1f 0 0" % (lead, dx + 0.5, dy, dz + 0.5))      # facing the street
    look("housing-3-built", ax + 4.5, ay + 5, az + 15.5, ax + 0.5, ay + 4, az + 0.5, wait=8)
    look("housing-4-at-the-door", dx + 2.5, dy + 2, dz + 6.5, dx + 0.5, dy + 1.5, dz + 0.5, wait=6)
    r.cmd("data merge entity %s {NoAI:0b}" % lead)
    say("alive after the housing market: %s" % client_alive())


def districts_stage(r, look, cx, cz):
    """The town's quarters and its park (Quarters, Park, ParkGround): the park put up at once on its lot in
    the homes quarter (as the showcase does), its ground made level first (cut and filled to one height,
    the ground round it eased in steps), its trees grown and its paths laid, and everybody off work sent to
    it. Photographed by day from outside its front, a few blocks up, looking in at the fountain with the
    benches, lamps and trees round it; then early in the evening from beyond a front corner, lower down,
    the benches before the fountain; then the books' map of the quarters (the Buildings page's map)."""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("gamemode spectator %s" % USER)
    r.cmd("time set 10000")                            # a bright afternoon: folk on their break go too
    out = r.cmd("execute positioned %d 100 %d run village districts park now" % (cx, cz))
    say("park: " + out[:300])
    m = re.search(r"PARK (-?\d+) (-?\d+) (-?\d+) facing (\w+)", out)
    if not m:
        say("no park went up; nothing to photograph")
        r.cmd("time set 6000")
        return
    px, py, pz = int(m.group(1)), int(m.group(2)), int(m.group(3))
    # "facing" names the lot's back; its front, on the street, is the other way, and the camera stands out there.
    back = {"north": (0, -1), "south": (0, 1), "west": (-1, 0), "east": (1, 0)}.get(m.group(4), (0, 1))
    fx, fz = -back[0], -back[1]
    rx, rz = -fz, fx                                   # across the front
    r.cmd("tp %s %d %d %d" % (USER, px + fx * 14, py + 6, pz + fz * 14))
    time.sleep(30)                                     # the ground arrives; the folk walk over and sit down
    # From fourteen out in front and six up, over the street: the whole lawn, the fountain in the middle.
    look("18-park-1-day", px + fx * 14, py + 6, pz + fz * 14, px, py + 1, pz, wait=8)
    r.cmd("time set 11800")                            # early evening, the light low and warm
    # From beyond a front corner (ten out, four across, three up): the benches before the fountain.
    look("18-park-2-benches", px + fx * 10 + rx * 4, py + 3, pz + fz * 10 + rz * 4, px, py + 0.5, pz, wait=8)
    say("districts: " + r.cmd("execute positioned %d %d %d run village districts" % (cx, py, cz))[:900])
    r.cmd("time set 6000")
    r.cmd("gamemode creative %s" % USER)
    say("map: " + r.cmd("execute as %s at @s run village districts map" % USER))
    time.sleep(4)
    shot("18-park-3-quarters-map")
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    say("alive after the park: %s" % client_alive())
def museum_stage(r, look, cx, cz):
    """The museum and its archive, set out on a stage in clear air beside the village the smoke spawned at
    cx, cz (/village museum stage: one of everything on show, each credited to one of its folk, the chronicle
    so far bound onto the lectern and the shelves, the name over the door and the town's banners either side):
    the whole front from out on its forecourt at noon (the plinth and stair, the portico, the pediment), the
    hall from inside the door, and the Museum page of the town's books."""
    mx, my, mz = cx + 60, 150, cz + 40
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    midday(r)
    r.cmd("gamemode spectator %s" % USER)
    r.cmd("tp %s %d %d %d" % (USER, mx, my + 10, mz - 20))
    time.sleep(10)                                     # the stage's chunks arrive
    out = r.cmd("execute positioned %d %d %d run village museum stage" % (mx, my, mz))
    say("museum: " + out[:900])
    views = dict((v[0], [float(n) for n in v[1:]]) for v in
                 re.findall(r"VIEW (\S+) (-?[\d.]+) (-?[\d.]+) (-?[\d.]+) (-?[\d.]+) (-?[\d.]+) (-?[\d.]+)", out))
    for name in ("m1-front", "m2-hall"):
        if name in views:
            look("18-museum-" + name, *views[name], wait=8)
    say("museum status: " + r.cmd("execute positioned %d %d %d run village museum" % (mx, my, mz))[:600])
    say("stats museum: " + r.cmd("execute as %s at @s run village stats 20" % USER))
    time.sleep(3)
    shot("18-museum-page")
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    say("alive after the museum: %s" % client_alive())


def culture_stage(r, look, cx, cz):
    """The town's culture (entity/Culture) photographed: a theatre set out on a stage in clear air beside the
    village the smoke spawned at cx, cz (/village culture stage: the town's banner hung either side over the
    stage, three players on it in the middle of a play out of the town's own chronicle, an audience on the
    benches); from the street behind the benches over the audience's heads, from the side of the stage along
    the players; and then, back at the village, the Culture page of its books (the banner drawn large, the
    motto, the customs, the theatre, the band and the choir, the pictures, the plaques)."""
    tx, ty, tz = cx - 120, 150, cz - 100
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    midday(r)
    r.cmd("gamemode spectator %s" % USER)
    r.cmd("tp %s %d %d %d" % (USER, tx, ty + 10, tz + 20))
    time.sleep(10)                                     # the stage's chunks arrive
    out = r.cmd("execute positioned %d %d %d run village culture stage" % (tx, ty, tz))
    say("culture stage: " + out[:600])
    views = dict((v[0], [float(n) for n in v[1:]]) for v in
                 re.findall(r"VIEW (\S+) (-?[\d.]+) (-?[\d.]+) (-?[\d.]+) (-?[\d.]+) (-?[\d.]+) (-?[\d.]+)", out))
    for name in ("theatre-house", "theatre-stage"):
        if name in views:
            look("30-culture-" + name, *views[name], wait=6)
    r.cmd("kill @e[tag=folk_lineup,type=!player]")
    say("culture: " + r.cmd("execute positioned %d 0 %d run village culture" % (cx, cz))[:900])
    say("to the village: " + r.cmd("execute positioned %d 0 %d positioned over motion_blocking_no_leaves run tp %s ~2 ~ ~2"
                                   % (cx, cz, USER)))
    time.sleep(4)
    say("stats culture: " + r.cmd("execute as %s at @s run village stats 21" % USER))
    time.sleep(3)
    shot("30-culture-page")
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    say("alive after the culture stage: %s" % client_alive())


# The second towns the stages found, so that a later stage wanting one near the same spot reuses it rather than
# founding a town on top of a town (the job market's, trade's and the wars' towns all lie 200-240 blocks out).
_towns = []


def other_town(r, tx, tz, what="second town"):
    """A town of eight at about (tx, tz): the one a stage already founded within 150 blocks of it, else a new one
    there (the spectator taken over it first so its ground loads, then time for its board and trades)."""
    for x, z in _towns:
        if math.hypot(x - tx, z - tz) < 150:
            say("%s: the one at %d %d" % (what, x, z))
            return x, z
    r.cmd("tp %s %d 140 %d" % (USER, tx, tz))
    time.sleep(15)                                     # the ground arrives
    say("%s: %s" % (what, r.cmd("village spawnat %d %d 8" % (tx, tz))[:200]))
    _towns.append((tx, tz))
    time.sleep(20)                                     # its board goes up, its folk take up their trades
    return tx, tz


def jobs_stage(r, look, cx, cz):
    """The job market between towns (entity/JobMarket, JobSeekers): a second town a little way off,
    the two agreeing to trade, a Wanted notice put up on the second town's board, a folk of the first
    town sent to read its own board (the notice it heard of read out over its head), the Wanted
    notice on the second town's board, and the city books open at the job market."""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("gamemode spectator %s" % USER)
    midday(r)
    tx, tz = cx + 230, cz + 20
    tx, tz = other_town(r, tx, tz, "second town")
    hy = ground_height(r, cx, cz)
    ty = ground_height(r, tx, tz)
    say("pact: " + r.cmd("execute positioned %d %d %d run village jobs pact" % (cx, hy + 1, cz)))
    say("want: " + r.cmd("execute positioned %d %d %d run village jobs want farmer" % (tx, ty + 1, tz)))
    step = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}

    def board_view(x, y, z, far):
        """Where to stand to see a town's board whole, and where to look: (eye, target), or None."""
        out = r.cmd("execute positioned %d %d %d run village jobs" % (x, y, z))
        say("jobs: " + out[:600])
        m = re.search(r"Board: (-?\d+) (-?\d+) (-?\d+) facing (\w+)", out)
        if not m:
            return None
        bx, by, bz, facing = int(m.group(1)), int(m.group(2)), int(m.group(3)), m.group(4)
        right = {"north": "west", "west": "south", "south": "east", "east": "north"}[facing]
        mid_x = bx + 0.5 + step[right][0] * 4.5
        mid_z = bz + 0.5 + step[right][1] * 4.5
        eye = (mid_x + step[facing][0] * far, by + 1.0, mid_z + step[facing][1] * far)
        return eye, (mid_x, by + 2.5, mid_z)

    # A folk of the first town sent to read its own board: the notice it has heard of, over its head.
    first = board_view(cx, hy + 1, cz, 7)
    say("look: " + r.cmd("execute positioned %d %d %d run village jobs look" % (cx, hy + 1, cz))[:300])
    time.sleep(10)                                     # it walks there and starts reading
    if first:
        (ex, ey, ez), (mx, my, mz) = first
        look("19-jobs-1-reading", ex, ey, ez, mx, my - 1.0, mz, wait=4)
    # The Wanted notice on the second town's board.
    second = board_view(tx, ty + 1, tz, 9)
    if second:
        (ex, ey, ez), (mx, my, mz) = second
        look("19-jobs-2-wanted", ex, ey, ez, mx, my, mz, wait=6)
    # The city books at the job market: the notice, and anybody who has applied to it.
    r.cmd("gamemode creative %s" % USER)
    r.cmd("tp %s %d %d %d" % (USER, tx, ty + 1, tz))
    time.sleep(3)
    say("books: " + r.cmd("execute as %s at @s run village jobs books" % USER))
    time.sleep(4)
    shot("19-jobs-3-books")
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("gamemode spectator %s" % USER)
    say("alive after the job market: %s" % client_alive())


def workshop_stage(r, look, cx, cz):
    """The shop's workshop (entity/Workshop): a shop put up beside the town if it has none, its keeper and a
    hand taken on, the hand at the crafting table in the shop's back room making off the order book; the
    shopfront, the bench through the door, and the Shops page's workshop (its makers, what they made today
    and of what, the order book against the stock, and what the next age will let it make)."""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("time set 6000")
    r.cmd("gamemode spectator %s" % USER)
    # Out past the first houses, so a shop put up for the picture stands on open ground.
    out = r.cmd("execute positioned %d 100 %d run village workshop stage" % (cx + 44, cz + 10))
    say("workshop: " + out[:700])
    w = re.search(r"WORKSHOP (-?\d+) (-?\d+) (-?\d+)", out)
    if not w:
        say("no shop to photograph")
        return
    wx, wy, wz = int(w.group(1)), int(w.group(2)), int(w.group(3))
    d = re.search(r"DOOR (-?\d+) (-?\d+) (-?\d+)", out)
    b = re.search(r"BENCH (-?\d+) (-?\d+) (-?\d+)", out)
    r.cmd("tp %s %d %d %d" % (USER, wx + 14, wy + 8, wz + 14))
    time.sleep(8)
    if d:
        dx, dy, dz = int(d.group(1)), int(d.group(2)), int(d.group(3))
        ox, oz = dx - wx, dz - wz                      # from the middle out through the door
        n = max(1.0, math.hypot(ox, oz))
        look("19-workshop-1-shopfront", dx + 9 * ox / n + 3, dy + 4, dz + 9 * oz / n + 3, wx, wy + 2, wz, wait=6)
        if b:
            bx, by, bz = int(b.group(1)), int(b.group(2)), int(b.group(3))
            # In the doorway, looking in at the hand at its bench in the back room.
            look("19-workshop-2-bench", dx + 0.5, dy + 1.6, dz + 0.5, bx + 0.5, by + 0.6, bz + 0.5, wait=6)
    else:
        look("19-workshop-1-shopfront", wx + 12, wy + 6, wz + 12, wx, wy + 2, wz, wait=6)
    say("work: " + r.cmd("execute positioned %d %d %d run village workshop work" % (wx, wy, wz))[:500])
    say("workshop now: " + r.cmd("execute positioned %d %d %d run village workshop" % (wx, wy, wz))[:900])
    say("books: " + r.cmd("execute as %s at @s run village workshop books" % USER))
    time.sleep(4)
    shot("19-workshop-3-books")
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    say("alive after the workshop: %s" % client_alive())


def economy_stage(r, look, cx, cz):
    """The larder, the fuel and the builders' stock (entity/Larder, Fuel, Strays): what the village says of
    them in chat; its smelter set to burn logs into charcoal for the stores (if the village wants it), seen
    at its furnace; then the Stores page of the town's books, the food and coal charts with the larder's
    word on a child under them."""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("gamemode spectator %s" % USER)
    midday(r)
    say("economy: " + r.cmd("execute positioned %d 100 %d run village larder" % (cx, cz))[:900])
    out = r.cmd("execute positioned %d 100 %d run village larder charcoal" % (cx, cz))
    say("charcoal: " + out[:300])
    m = re.search(r"SMELTER (.+?) (-?\d+) (-?\d+) (-?\d+)", out)
    if m:
        sx, sy, sz = int(m.group(2)), int(m.group(3)), int(m.group(4))
        # Never inside the hill: the camera stands over the ground by the furnaces, whatever their height.
        cy = max(sy + 3, ground_height(r, sx + 5, sz + 5) + 2)
        r.cmd("tp %s %d %d %d" % (USER, sx + 6, cy + 2, sz + 6))
        time.sleep(12)                                 # it fills the furnace with logs
        look("20-economy-1-charcoal", sx + 5.5, cy + 0.5, sz + 5.5, sx, sy + 1, sz, wait=4)
    else:
        say("no smelter to photograph at its furnace")
    r.cmd("gamemode creative %s" % USER)
    r.cmd("tp %s %d %d %d" % (USER, cx, ground_height(r, cx, cz) + 1, cz))
    time.sleep(3)
    say("stats stores: " + r.cmd("execute as %s at @s run village stats 11" % USER))
    time.sleep(4)
    shot("20-economy-2-stores")
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("gamemode spectator %s" % USER)
    say("alive after the economy: %s" % client_alive())


def wages_stage(r, look, cx, cz):
    """What every job is worth (entity/JobWorth): the day's pay scale drawn up now; the wages page on the screen
    (the pay level, the lowest wage against the cost of living, the bill against what comes in, every job's worth
    part by part, and who is paid what and why); the cards of the three best paid, each a couple of blocks off
    so the talk screen stays open, at the About page with its Wage line; and the town's books at the Jobs page,
    the pay scale's lines under the trades. Best called after the stats stage, so the books have days in them."""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    midday(r)
    r.cmd("gamemode creative %s" % USER)
    gy = ground_height(r, cx, cz)
    r.cmd("tp %s %d %d %d" % (USER, cx, gy + 1, cz))
    time.sleep(4)
    say("wages reckon: " + r.cmd("execute as %s at @s run village wages reckon" % USER)[:600])
    say("wages: " + r.cmd("execute as %s at @s run village wages" % USER)[:1500])
    say("wages show: " + r.cmd("execute as %s at @s run village wages show" % USER))
    time.sleep(4)
    shot("wages-1-page")
    for n in (1, 2, 3):
        out = r.cmd("execute as %s at @s run village wages card %d" % (USER, n))
        say("wages card %d: %s" % (n, out[:400]))
        m = re.search(r"CARD (.+?) (-?\d+) (-?\d+) (-?\d+) ", out)
        if not m:
            continue
        fx, fy, fz = int(m.group(2)), int(m.group(3)), int(m.group(4))
        # Two and a half blocks east of it, looking at it: the talk screen shuts past ten blocks.
        px, pz = fx + 3.0, fz + 0.5
        yaw = math.degrees(math.atan2(-(fx + 0.5 - px), fz + 0.5 - pz))
        r.cmd("tp %s %.2f %d %.2f %.1f 10" % (USER, px, fy, pz, yaw))
        time.sleep(3)
        say("wages card %d, close to: %s" % (n, r.cmd("execute as %s at @s run village wages card %d" % (USER, n))[:200]))
        time.sleep(3)
        shot("wages-%d-card" % (n + 1))
    r.cmd("tp %s %d %d %d" % (USER, cx, gy + 1, cz))
    time.sleep(2)
    say("wages books: " + r.cmd("execute as %s at @s run village wages books" % USER))
    time.sleep(4)
    shot("wages-5-jobs-page")
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("gamemode spectator %s" % USER)
    say("alive after the wages: %s" % client_alive())


def fields_stage(r, look, cx, cz):
    """The town's tended fields (entity/Fields): what /village larder says of the food in, by where it
    came from, and of the fields' pace; then the first farmer's field from above, its torches round the
    edge and its composter by the work chest, and the same field a game hour later, grown."""
    r.cmd("gamemode spectator %s" % USER)
    midday(r)
    say("economy: " + r.cmd("execute positioned %d 100 %d run village larder" % (cx, cz))[:1200])
    out = r.cmd("execute positioned %d 100 %d run village larder fields" % (cx, cz))
    say("fields: " + out[:900])
    # The field with the most tilled ground, and the middle of its farmland (seldom the middle of the plot:
    # a riverside field is a strip along the bank).
    best = None
    for m in re.finditer(r"FIELD (.+?) (-?\d+) (-?\d+) (-?\d+) r(\d+):.*?tilled (\d+)(?: at (-?\d+) (-?\d+) (-?\d+))?", out):
        if m.group(7) and (best is None or int(m.group(6)) > int(best.group(6))):
            best = m
    if not best:
        say("no farmer's field with tilled ground to photograph")
        r.cmd("gamemode creative %s" % USER)
        return
    fx, fy, fz = int(best.group(7)), int(best.group(8)), int(best.group(9))
    say("photographing %s's field: %s tilled, centred %d %d %d" % (best.group(1), best.group(6), fx, fy, fz))
    cy = max(fy + 7, ground_height(r, fx + 6, fz + 6) + 3)
    r.cmd("tp %s %d %d %d" % (USER, fx + 7, cy + 1, fz + 7))
    time.sleep(10)
    look("21-fields-1-tended", fx + 6.5, cy + 0.5, fz + 6.5, fx, fy, fz, wait=4)
    r.cmd("time add 1000")
    time.sleep(50)                                   # a game hour less the jump: the field grows on
    say("fields an hour on: " + r.cmd("execute positioned %d 100 %d run village larder fields" % (cx, cz))[:600])
    look("21-fields-2-later", fx + 6.5, cy + 0.5, fz + 6.5, fx, fy, fz, wait=4)
    r.cmd("gamemode creative %s" % USER)
    say("alive after the fields: %s" % client_alive())


def prices_stage(r, look, cx, cz):
    """The town's prices (entity/PriceIndex, Purchases): reckoned now and read out in chat (each good today against
    its usual worth, supply against demand, what was too dear, the cost of living against the lowest wage); the
    Prices page of the town's books; and one of the shop's price signs with its live price, the shop put up for the
    picture first if the town has none (as the workshop stage does)."""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("time set 6000")
    say("prices: " + r.cmd("execute positioned %d 100 %d run village prices now" % (cx, cz))[:1500])
    r.cmd("gamemode creative %s" % USER)
    r.cmd("tp %s %d %d %d" % (USER, cx, ground_height(r, cx, cz) + 1, cz))
    time.sleep(3)
    say("prices page: " + r.cmd("execute as %s at @s run village prices page" % USER))
    time.sleep(4)
    shot("25-prices-1-page")
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    out = r.cmd("execute positioned %d 100 %d run village prices shop" % (cx, cz))
    if "SHOP" not in out:
        say("no shop: " + r.cmd("execute positioned %d 100 %d run village workshop stage" % (cx + 44, cz + 10))[:300])
        out = r.cmd("execute positioned %d 100 %d run village prices shop" % (cx, cz))
    say("shop: " + out[:900])
    m = re.search(r"SIGN (-?\d+) (-?\d+) (-?\d+) out (\w+)", out)
    if m:
        x, y, z = int(m.group(1)), int(m.group(2)), int(m.group(3))
        dx, dz = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}.get(m.group(4), (1, 0))
        r.cmd("gamemode spectator %s" % USER)
        # Two blocks out in front of the counter's sign, looking at it.
        look("25-prices-2-sign", x + 0.5 + dx * 2.2, y, z + 0.5 + dz * 2.2, x + 0.5, y + 0.4, z + 0.5, wait=5)
        r.cmd("gamemode creative %s" % USER)
    else:
        say("no price sign at the shop's counters to photograph")
    say("alive after the prices: %s" % client_alive())


def ageing_stage(r, look, cx, cz):
    """Growing old slowly (entity/VillageFolkEntity.ageYears, Lifespans): grown folk age a year every
    three days and the founders come eighteen to forty-five. What /village lifespans says of the town
    (each folk's age, the age it will live to and the day that falls on), then the town's books at the
    Folk page (each one's years) and the Society page (the ages by tens)."""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("gamemode creative %s" % USER)
    r.cmd("tp %s %d %d %d" % (USER, cx, ground_height(r, cx, cz) + 1, cz))
    time.sleep(3)
    say("lifespans: " + r.cmd("execute positioned %d 100 %d run village lifespans" % (cx, cz))[:1500])
    say("stats folk: " + r.cmd("execute as %s at @s run village stats 6" % USER))
    time.sleep(4)
    shot("22-ageing-1-folk")
    say("stats society: " + r.cmd("execute as %s at @s run village stats 7" % USER))
    time.sleep(4)
    shot("22-ageing-2-society")
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("gamemode spectator %s" % USER)
    say("alive after the ageing: %s" % client_alive())


def health_stage(r, look, cx, cz):
    """Health and care (entity/Health, Infirmary, Neighbourly, PoorBox): an infirmary set out on a stage in clear
    air out past the village (/village care stage), its four beds full (two folk with a cold, one hurt, a child) and
    the healer at a bedside with a honey bottle in hand; from the street, then down the aisle from just inside the
    door. Then a cold caught now by the folk nearest the heart (/village care cold), what /village care says of the
    town (who is ill, the infirmary, the poor box, the old visited, newcomers welcomed), and the town's books at the
    Money page, the poor box under the in-and-out."""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("gamemode spectator %s" % USER)
    r.cmd("time set 6000")
    r.cmd("weather clear")
    sx, sy, sz = cx + 90, 150, cz - 90
    r.cmd("tp %s %d %d %d" % (USER, sx + 6, sy + 5, sz + 14))
    time.sleep(10)                                     # the stage's chunks arrive
    out = r.cmd("execute positioned %d %d %d run village care stage" % (sx, sy, sz))
    say("infirmary stage: " + out[:500])
    views = re.findall(r"VIEW (\S+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+)", out)
    for i, (name, x, y, z, ax, ay, az) in enumerate(views):
        x, y, z, ax, ay, az = int(x), int(y), int(z), int(ax), int(ay), int(az)
        look("24-care-%d-%s" % (i + 1, name), x + 0.5, y + 0.2, z + 0.5, ax + 0.5, ay + 0.5, az + 0.5, wait=6)
    if not views:
        say("no infirmary to photograph")
    r.cmd("kill @e[tag=folk_lineup,type=!player]")
    say("a cold: " + r.cmd("execute positioned %d 100 %d run village care cold" % (cx, cz))[:200])
    say("care: " + r.cmd("execute positioned %d 100 %d run village care" % (cx, cz))[:1500])
    r.cmd("tp %s %d %d %d" % (USER, cx, ground_height(r, cx, cz) + 2, cz))
    time.sleep(3)
    say("stats money: " + r.cmd("execute as %s at @s run village stats 2" % USER))
    time.sleep(4)
    shot("24-care-3-money")
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    say("alive after the care: %s" % client_alive())


def sights_stage(r, look, cx, cz):
    """The town's newer sights, found with /village sights (entity/Sights): the welcome sign at the edge of
    town from the road coming in; the gazette on its lectern in the hall; the crier reading the news; the
    children at tag in the park; a family's new cat, taken in with fish a player put in the house chest;
    and a garden in front of a house, with a few flowers and a sapling a player brought to the stores.
    Each is made now (the town's own stores, purses and hands, only not waiting for the hour)."""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("time set 6000")
    r.cmd("weather clear")
    r.cmd("gamemode spectator %s" % USER)
    where = "execute positioned %d 100 %d run " % (cx, cz)
    out = r.cmd(where + "village sights")
    say("sights: " + out[:1500])
    xyz = r"(-?\d+) (-?\d+) (-?\d+)"
    dirs = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}

    # The welcome sign: its face is to the road coming in, so the camera stands out on the road.
    out = r.cmd(where + "village sights sign")
    say("sign: " + out[:600])
    m = re.search(r"SIGN " + xyz + r" out (\w+)", out)
    if m:
        x, y, z = (int(v) for v in m.groups()[:3])
        dx, dz = dirs.get(m.group(4), (1, 0))
        look("23-sights-1-welcome-sign", x + 0.5 + dx * 4, y, z + 0.5 + dz * 4, x + 0.5, y + 1.3, z + 0.5, wait=6)
    else:
        say("no welcome sign up to photograph")

    # The gazette on its lectern.
    out = r.cmd(where + "village sights gazette")
    say("gazette: " + out[:500])
    m = re.search(r"LECTERN " + xyz, out)
    if m:
        x, y, z = (int(v) for v in m.groups())
        look("23-sights-2-gazette", x + 2.5, y + 0.6, z + 2.5, x + 0.5, y + 0.8, z + 0.5, wait=5)

    # The crier sent to read the news: a little while to walk to its spot and begin.
    out = r.cmd(where + "village sights crier")
    say("crier: " + out[:500])
    time.sleep(10)                                   # at its spot and reading (eleven lines take it half a minute)
    out = r.cmd(where + "village sights")
    m = re.search(r"CRIER (.+?) " + xyz + r"(?: stand " + xyz + r")?", out)
    if m and m.group(2):
        x, y, z = int(m.group(2)), int(m.group(3)), int(m.group(4))
        look("23-sights-3-crier", x + 4.5, y + 1, z + 3.5, x + 0.5, y + 1.6, z + 0.5, wait=4)
        say("crier later: " + m.group(0)[:200])
    else:
        say("no crier to be seen")

    # The children's game.
    out = r.cmd(where + "village sights tag")
    say("game: " + out[:500])
    m = re.search(r"GAME .*? ground " + xyz, out)
    if m:
        x, y, z = (int(v) for v in m.groups())
        time.sleep(12)
        look("23-sights-4-tag", x + 9, y + 6, z + 9, x, y + 1, z, wait=4)
        time.sleep(8)
        look("23-sights-5-tag-later", x - 9, y + 6, z + 9, x, y + 1, z, wait=4)

    # A pet: a stray cat by a house with children, and fish in that house's chest, as a player would leave it.
    homes = re.findall(r"HOME " + xyz + r" kids (\d+) grown (\d+) chest (-?\d+ -?\d+ -?\d+|none) garden (\w+) pet (\S+)", out)
    say("households: %d, with children: %d" % (len(homes), sum(1 for h in homes if int(h[3]) > 0)))
    family = next((h for h in homes if int(h[3]) > 0 and h[5] != "none" and h[7] == "none"), None)
    if family:
        hx, hy, hz = int(family[0]), int(family[1]), int(family[2])
        chx, chy, chz = (int(v) for v in family[5].split())
        say("fish: " + r.cmd("item replace block %d %d %d container.26 with minecraft:cod 12" % (chx, chy, chz)))
        r.cmd("summon minecraft:cat %d %d %d" % (hx + 3, hy + 1, hz + 3))
        time.sleep(2)
        out = r.cmd(where + "village sights pet")
        say("pet: " + out[:700])
        pm = re.search(r"HOME %d %d %d .*? pet (cat|wolf) (\S+) " % (hx, hy, hz) + xyz, out)
        if pm:
            x, y, z = int(pm.group(3)), int(pm.group(4)), int(pm.group(5))
            look("23-sights-6-pet", x + 3, y + 1, z + 3, x + 0.5, y + 0.4, z + 0.5, wait=5)
    else:
        say("no household with children and a chest, without a pet")

    # A garden: flowers and a sapling brought to the stores; the household with most put by plants it.
    bell = r.cmd(where + "village bell")
    st = re.search(r"STORES " + xyz, bell)
    if st:
        sx, sy, sz = (int(v) for v in st.groups())
        for slot, item in ((22, "minecraft:poppy 4"), (21, "minecraft:dandelion 4"), (20, "minecraft:oak_sapling 1")):
            say("stores: " + r.cmd("item replace block %d %d %d container.%d with %s" % (sx, sy, sz, slot, item)))
    out = r.cmd(where + "village sights garden")
    say("garden: " + out[:700])
    gm = re.search(r"GARDEN-ERRAND .*? home " + xyz + " done", out)
    if gm:
        x, y, z = (int(v) for v in gm.groups())
        look("23-sights-7-garden", x + 9, y + 5, z + 9, x, y + 1, z, wait=5)
    r.cmd("gamemode creative %s" % USER)
    say("alive after the sights: %s" % client_alive())


def seasons_stage(r, look, cx, cz):
    """[batchB] The town's seasons and festivals (entity/Seasons, Festivals, Fair, Midwinter, Winter), in the village
    spawned at cx, cz: the maypole up on the square and the town dancing round it, from outside the ring once the
    ring has begun to turn; the midsummer bonfire at dusk with the town gathered round it; and the town's books at
    the News page, the season and the year's festivals in the town's calendar. The makings go into the stores
    first (fence posts, wool of four colours, logs, coal and sticks), as a player would bring them, and the
    town's calendar is turned to each festival's day (/village season set)."""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("time set 9000")
    r.cmd("weather clear")
    r.cmd("gamemode spectator %s" % USER)
    here = "execute positioned %d 100 %d run " % (cx, cz)
    xyz = r"(-?\d+) (-?\d+) (-?\d+)"
    say("season: " + r.cmd(here + "village season")[:600])
    st = re.search(r"STORES " + xyz, r.cmd(here + "village bell"))
    if st:
        sx, sy, sz = (int(v) for v in st.groups())
        goods = ((26, "minecraft:oak_fence 5"), (25, "minecraft:red_wool 1"), (24, "minecraft:yellow_wool 1"),
                 (23, "minecraft:light_blue_wool 1"), (22, "minecraft:lime_wool 1"), (21, "minecraft:oak_log 16"),
                 (20, "minecraft:coal 6"), (19, "minecraft:stick 16"))
        for slot, item in goods:
            say("stores: " + r.cmd("item replace block %d %d %d container.%d with %s" % (sx, sy, sz, slot, item)))
    else:
        say("no stores found for the festivals' makings; going on with what the town has")

    # Spring: the maypole, and the May dance round it.
    say("calendar: " + r.cmd(here + "village season set 3"))
    out = r.cmd(here + "village festival maypole now")
    say("maypole: " + out[:700])
    m = re.search(r"AT " + xyz, out)
    if m and "0 blocks put up" not in out:
        x, y, z = (int(v) for v in m.groups())
        time.sleep(50)                                 # they gather, the elder's three lines, and the ring turns
        say("dance: " + r.cmd(here + "village festival maypole")[:300])
        look("24-seasons-1-maypole", x + 11.5, y + 5, z + 11.5, x + 0.5, y + 2.5, z + 0.5, wait=3)
    else:
        say("no maypole to photograph")

    # Midsummer: the bonfire at dusk, the town round it.
    r.cmd("time set 12400")
    say("calendar: " + r.cmd(here + "village season set 11"))
    out = r.cmd(here + "village festival bonfire now")
    say("bonfire: " + out[:700])
    m = re.search(r"AT " + xyz, out)
    if m and "0 blocks put up" not in out:
        x, y, z = (int(v) for v in m.groups())
        time.sleep(40)                                 # gathered round and singing
        look("24-seasons-2-bonfire", x + 9.5, y + 4, z + 9.5, x + 0.5, y + 0.5, z + 0.5, wait=3)
    else:
        say("no bonfire to photograph")

    # The town's books, the News page: the season, the fields in every season, the year's festivals.
    r.cmd("time set 9000")
    say("season now: " + r.cmd(here + "village season")[:600])
    say("stats news: " + r.cmd("execute as %s at @s run village stats 17" % USER))
    time.sleep(3)
    shot("24-seasons-3-news")
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("gamemode creative %s" % USER)
    say("alive after the seasons: %s" % client_alive())


def visitors_stage(r, look, cx, cz):
    """Visitors and the player (entity/Visitors, MapRoom): the travelling bard by the tavern's hearth of an
    evening, the merchant from afar at the market on a morning, and the town's map in its frame on the
    hall's wall. Each is brought about now with /village visitors: the bard and the merchant still come in
    from the edge of the world, and "evening" sets the visitors in town at their places; the map is drawn on
    the town's own paper by a hand at its works (nothing is made for the pictures). After the books, so the
    town has its tavern, its market and its hall."""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("weather clear")
    r.cmd("gamemode spectator %s" % USER)
    where = "execute positioned %d 100 %d run " % (cx, cz)
    xyz = r"(-?\d+) (-?\d+) (-?\d+)"
    dirs = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}
    say("visitors: " + r.cmd(where + "village visitors")[:1200])

    # The bard, of an evening, by the tavern's hearth.
    r.cmd("time set 13500")
    say("bard: " + r.cmd(where + "village visitors bard")[:400])
    out = r.cmd(where + "village visitors evening")
    m = re.search(r"VISITOR bard (\S+) " + xyz, out)
    if m:
        x, y, z = int(m.group(2)), int(m.group(3)), int(m.group(4))
        time.sleep(8)                                    # a phrase or two of its tune, and a line of news
        look("24-visitors-1-bard", x + 3.5, y + 1.2, z + 2.5, x + 0.5, y + 1.4, z + 0.5, wait=5)
    else:
        say("no bard to photograph (no tavern, or no ground at the edge): %s" % out[:300])

    # The merchant from afar, at the market in the morning.
    r.cmd("time set 4000")
    say("merchant: " + r.cmd(where + "village visitors merchant")[:400])
    out = r.cmd(where + "village visitors evening")
    m = re.search(r"VISITOR merchant (\S+) " + xyz, out)
    if m:
        x, y, z = int(m.group(2)), int(m.group(3)), int(m.group(4))
        time.sleep(4)
        look("24-visitors-2-merchant", x + 4.5, y + 1.6, z + 3.5, x + 0.5, y + 1.3, z + 0.5, wait=5)
    else:
        say("no merchant to photograph (no market, or nothing its land lacks): %s" % out[:300])

    # The map room: the town's map on the hall's wall (paper first, as a player would leave it in the stores).
    bell = r.cmd(where + "village bell")
    st = re.search(r"STORES " + xyz, bell)
    if st:
        sx, sy, sz = (int(v) for v in st.groups())
        for slot, item in ((23, "minecraft:paper 36"), (24, "minecraft:item_frame 4")):
            say("stores: " + r.cmd("item replace block %d %d %d container.%d with %s" % (sx, sy, sz, slot, item)))
    out = r.cmd(where + "village visitors map")
    say("map: " + out[:600])
    m = re.search(r"MAPFRAME " + xyz + r" (\w+)", out)
    if m:
        x, y, z = int(m.group(1)), int(m.group(2)), int(m.group(3))
        dx, dz = dirs.get(m.group(4), (0, 1))
        look("24-visitors-3-map-room", x + 0.5 + dx * 3.2, y + 0.4, z + 0.5 + dz * 3.2, x + 0.5, y + 0.4, z + 0.5, wait=6)
    else:
        say("no map on the hall's wall (no hall, or no paper): %s" % out[:300])
    r.cmd("gamemode creative %s" % USER)
    say("alive after the visitors: %s" % client_alive())


def sport_stage(r, look, cx, cz):
    """Sport and play (entity/Sport, Pitch, Football, Archery): the football pitch put up at once on its lot among the
    homes (/village sport pitch now: its goals, benches and lamps, its lines laid in white wool), a match begun on it
    with whoever is free and the stores' slime ball for the ball (a slime ball put in the stores first), photographed
    from beyond a corner of the pitch a few blocks up while the sides are at it; then the watch's archery range put up
    by a corner of the wall with targets on its butts and the guards sent to practise, from behind the line."""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("time set 7000")                            # one o'clock: the midday meal eaten
    r.cmd("weather clear")
    r.cmd("gamemode spectator %s" % USER)
    where = "execute positioned %d 100 %d run " % (cx, cz)
    xyz = r"(-?\d+) (-?\d+) (-?\d+)"
    dirs = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}
    out = r.cmd(where + "village sport pitch now")
    say("pitch: " + out[:300])
    m = re.search(r"PITCH " + xyz + r" facing (\w+)", out)
    if not m:
        say("no pitch went up; nothing to photograph")
    else:
        px, py, pz = (int(v) for v in m.groups()[:3])
        bx, bz = dirs.get(m.group(4), (0, -1))                # the lot's back: one goal; the other toward the street
        rx, rz = -bz, bx                                        # across the field
        bell = r.cmd(where + "village bell")
        st = re.search(r"STORES " + xyz, bell)
        if st:
            sx, sy, sz = (int(v) for v in st.groups())
            say("ball: " + r.cmd("item replace block %d %d %d container.25 with minecraft:slime_ball 1" % (sx, sy, sz)))
        say("match: " + r.cmd(where + "village sport match now")[:300])
        r.cmd("tp %s %d %d %d" % (USER, px + rx * 12 - bx * 10, py + 7, pz + rz * 12 - bz * 10))
        time.sleep(25)                                          # the sides walk out; the kick-off
        # From beyond a front corner of the pitch, twelve across, ten out and seven up: the whole field and both goals.
        look("24-sport-1-football", px + rx * 12 - bx * 10, py + 7, pz + rz * 12 - bz * 10, px, py + 0.5, pz, wait=6)
        # Low behind the back goal, looking down the field at the players.
        look("24-sport-2-goal", px + bx * 12, py + 3, pz + bz * 12, px, py + 1, pz, wait=8)
    out = r.cmd(where + "village sport range now")
    say("range: " + out[:300])
    m = re.search(r"RANGE " + xyz + r" facing (\w+)", out)
    if m:
        x, y, z = (int(v) for v in m.groups()[:3])
        bx, bz = dirs.get(m.group(4), (0, -1))
        time.sleep(15)                                          # the guards walk down to the line
        # From behind the archers' line and a little up: the line, the butts with their targets, the boards behind.
        look("24-sport-3-range", x - bx * 9 + 2, y + 3, z - bz * 9 + 2, x + bx * 4, y + 1, z + bz * 4, wait=6)
    say("sport: " + r.cmd(where + "village sport")[:900])
    r.cmd("gamemode creative %s" % USER)
    r.cmd("time set 6000")
    say("alive after the sport: %s" % client_alive())


def townlife_stage(r, look, cx, cz):
    """Town life and governance (entity/Civics and the rest): beside the town, on ground levelled for them
    (/village civic stage), a post office with its name on the sign by its door and a statue with the names
    of its givers on a sign before it; a petition got up by whoever has a grievance; and the town meeting
    called now, the town gathering before its hall (or its board) to hear the elder's account of the week.
    Photographed: the post office from the street, the statue and its sign, the meeting from behind the
    crowd while the elder speaks; then what /village civic says of the town's affairs."""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("time set 6000")
    r.cmd("weather clear")
    r.cmd("gamemode spectator %s" % USER)
    sx, sz = cx - 100, cz - 100                      # out past the town, where the stage levels its own ground
    gy = ground_height(r, sx, sz)
    r.cmd("tp %s %d %d %d" % (USER, sx, gy + 8, sz + 18))
    time.sleep(8)                                      # the stage's chunks arrive
    out = r.cmd("execute positioned %d %d %d run village civic stage" % (sx, gy, sz))
    say("civic stage: " + out[:600])
    views = re.findall(r"VIEW (\S+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+)", out)
    for name, x, y, z, ax, ay, az in views:
        x, y, z, ax, ay, az = int(x), int(y), int(z), int(ax), int(ay), int(az)
        if name.endswith("meeting"):
            time.sleep(20)                             # the bell rung: the town walks over and finds its places
        look("24-%s" % name, x + 0.5, y, z + 0.5, ax + 0.5, ay + 0.5, az + 0.5, wait=6)
    say("civic: " + r.cmd("execute positioned %d 100 %d run village civic" % (cx, cz))[:900])
    say("alive after the town's affairs: %s" % client_alive())


def store_stage(r, look, cx, cz):
    """The town store (entity/Store, StockKeeper, StoreFloor): a store stood up beside the town if it has none
    (its little shop too), staffed and stocked out of the stores by the stock keeper's count and its deliveries,
    and held as at a busy hour: the assistants and the keeper behind the counters with customers in front, the
    stock keeper in the stockroom's aisle, a crafter at the workshop's bench. The stage says where each camera
    stands (SHOT lines): the whole building from outside, the shop floor from just inside the door, the stockroom
    and the workshop from their doorways, an assistant face to face across its counter. Then the stock book in
    chat (/village stock), and everybody let go."""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("time set 4000")
    r.cmd("weather clear")
    r.cmd("gamemode spectator %s" % USER)
    out = r.cmd("execute positioned %d 100 %d run village stock stage" % (cx + 40, cz - 30))
    say("store: " + out[:2000])
    s = re.search(r"STORE (-?\d+) (-?\d+) (-?\d+)", out)
    if not s:
        say("no store to photograph")
        r.cmd("gamemode creative %s" % USER)
        return
    sx, sy, sz = (int(v) for v in s.groups())
    r.cmd("tp %s %d %d %d" % (USER, sx + 14, sy + 10, sz - 20))
    time.sleep(8)                                   # the ground round it loaded and drawn
    num = r"(-?[\d.]+)"
    shots = re.findall(r"SHOT (\S+) " + " ".join([num] * 6), out)
    for name, ex, ey, ez, ax, ay, az in shots:
        ex, ey, ez, ax, ay, az = (float(v) for v in (ex, ey, ez, ax, ay, az))
        if name.endswith("outside"):
            # Never inside a hill or a tree: over the ground where the camera stands.
            ey = max(ey, ground_height(r, int(ex), int(ez)) + 3)
        look(name, ex, ey, ez, ax, ay, az, wait=6)
    if not shots:
        look("24-store-0-outside", sx + 13, sy + 7, sz + 25, sx, sy + 5, sz, wait=6)
    say("stock book: " + r.cmd("execute positioned %d %d %d run village stock" % (sx, sy, sz))[:1500])
    say("let go: " + r.cmd("execute positioned %d %d %d run village stock stage done" % (sx, sy, sz)))
    r.cmd("gamemode creative %s" % USER)
    say("alive after the store: %s" % client_alive())


def trade_stage(r, look, cx, cz):
    """Trade between towns (entity/TradeBook, TradeTalks, TradeDeals): the town and its nearest neighbour (a second
    town a little way off if it has none), stocked to trade if neither has anything the other is short of (bread
    and wheat here, stone there, as the game tests stock them); the neighbour's envoy before this town's board, the
    town gathered and the envoy and the leader bargaining aloud, round by round, till they shake on it; then the
    deal's caravan on the road to the neighbour, from behind and above as it walks; and the Trade page of the
    town's books (the book, the deal, the rounds of the talks)."""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("gamemode spectator %s" % USER)
    midday(r)
    hy = ground_height(r, cx, cz)
    here = "execute positioned %d %d %d run village trade " % (cx, hy + 1, cz)
    out = r.cmd(here + "stage")
    if "no neighbour" in out:
        other_town(r, cx - 240, cz + 30, "second town")
        out = r.cmd(here + "stage")
    say("trade stage: " + out[:1500])

    def audience_view(text):
        """Where to stand to see the envoy and the leader side on, and where to look: (eye, target), or None."""
        e = re.search(r"ENVOY (-?\d+) (-?\d+) (-?\d+)", text)
        el = re.search(r"ELDER (-?\d+) (-?\d+) (-?\d+)", text)
        if not e:
            return None
        ex, ey, ez = int(e.group(1)), int(e.group(2)), int(e.group(3))
        lx, lz = (int(el.group(1)), int(el.group(3))) if el else (ex + 2, ez)
        mx, mz = (ex + lx) / 2.0, (ez + lz) / 2.0
        dx, dz = lx - ex, lz - ez
        n = max(1.0, math.hypot(dx, dz))
        return (mx - 6 * dz / n, ey + 1.5, mz + 6 * dx / n), (mx, ey + 1.2, mz)

    # The audience: another gathering under way (a town meeting) is let finish first; then the bell, the town
    # gathers, and the envoy and the leader bargain aloud. The picture while the offers are being said.
    taken = False
    last = out
    deal = "DEAL " in out
    for i in range(36):                                # three minutes at most
        if deal:
            break
        time.sleep(5)
        last = r.cmd(here + "audience")
        m = re.search(r"AUDIENCE ENVOY SPEECH .*?line=(\d+)/(\d+)", last)
        if i % 4 == 0 or m:
            say("audience: " + last[:400].replace("\n", " | "))
        if m and not taken and int(m.group(1)) >= 4:
            view = audience_view(last)
            if view:
                (sx, sy, sz), (tx, ty, tz) = view
                look("22-trade-1-audience", sx, sy, sz, tx, ty, tz, wait=4)
                taken = True
        deal = "DEAL " in last
    if not taken:
        view = audience_view(last)
        if view:
            (sx, sy, sz), (tx, ty, tz) = view
            look("22-trade-1-audience", sx, sy, sz, tx, ty, tz, wait=4)
        else:
            say("no envoy to photograph")
    if not deal:
        say("no deal at the audience in time; the two leaders bargain at once: " + r.cmd(here + "now")[:1500])
    say("trade: " + r.cmd(here)[:2500])
    # The caravan on the road, from behind and above: the camera there first, then the caravan set down below it.
    plan = r.cmd(here + "road plan")
    say("road plan: " + plan[:300])
    c = re.search(r"ROAD (-?\d+) (-?\d+) (-?\d+)", plan)
    w = re.search(r"TOWARD (-?\d+) (-?\d+) (-?\d+)", plan)
    if c:
        x, y, z = int(c.group(1)), int(c.group(2)), int(c.group(3))
        ax, az = (int(w.group(1)), int(w.group(3))) if w else (x + 10, z)
        dx, dz = ax - x, az - z
        n = max(1.0, math.hypot(dx, dz))
        ex, ez = x - 10 * dx / n, z - 10 * dz / n
        r.cmd("tp %s %d %d %d" % (USER, ex, y + 20, ez))
        time.sleep(12)                                 # the road's chunks arrive
        cy = max(y, ground_height(r, int(ex), int(ez))) + 6
        r.cmd("tp %s %.1f %d %.1f" % (USER, ex, cy, ez))
        time.sleep(3)
        road = r.cmd(here + "road")
        say("road: " + road[:500])
        if re.search(r"CARAVAN -?\d+", road):
            look("22-trade-2-caravan", ex, cy, ez, x + 6 * dx / n, y + 1, z + 6 * dz / n, wait=3)
        else:
            say("no caravan on the road to photograph")
    else:
        say("no road to photograph")
    # The Trade page of the town's books: the book, the deal and the rounds of the talks.
    r.cmd("gamemode creative %s" % USER)
    r.cmd("tp %s %d %d %d" % (USER, cx, hy + 1, cz))
    time.sleep(3)
    say("trade books: " + r.cmd("execute as %s at @s run village trade books" % USER))
    time.sleep(4)
    shot("22-trade-3-books")
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("gamemode spectator %s" % USER)
    say("alive after the trade: %s" % client_alive())


def war_scouting_stage(r, look, cx, cz):
    """Scouts at war (entity/Spying, Pickets, WarMap): a second town a little way off, the two set at war
    (/village war scout stage), one of the first town's folk sent to watch the second and put down at its
    vantage on the rise outside its streets (/village war scout now): a picture over the scout's shoulder of
    the enemy town it is counting; the first town's pickets out on the road toward it, and one of them at its
    post; then the scout home with its count (/village war scout home) and the town's books open at the War
    map (the enemy where it lies, its guards and the report's age, the reckoning)."""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("gamemode spectator %s" % USER)
    midday(r)
    tx, tz = cx + 200, cz - 40
    tx, tz = other_town(r, tx, tz, "enemy town")
    hy = ground_height(r, cx, cz)
    here = "execute positioned %d %d %d run " % (cx, hy + 1, cz)
    say("war: " + r.cmd(here + "village war scout stage"))
    out = r.cmd(here + "village war scout now")
    say("scout: " + out[:400])
    for name, x, y, z, ax, ay, az in re.findall(r"VIEW (\S+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+)", out):
        r.cmd("tp %s %s %s %s" % (USER, x, y, z))
        time.sleep(6)
        look("24-war-1-" + name, int(x) + 0.5, int(y), int(z) + 0.5, int(ax) + 0.5, int(ay) + 0.5, int(az) + 0.5, wait=10)
    out = r.cmd(here + "village war pickets now")
    say("pickets: " + out[:400])
    m = re.search(r"PICKET (.+?) on (.+?) at (-?\d+) (-?\d+) (-?\d+)", out)
    if m:
        px, py, pz = int(m.group(3)), int(m.group(4)), int(m.group(5))
        look("24-war-2-picket", px + 6.5, py + 3, pz + 6.5, px + 0.5, py + 1, pz + 0.5, wait=8)
    time.sleep(30)                                     # the scout lies watching, and counts
    say("home: " + r.cmd(here + "village war scout home")[:400])
    say("war map: " + r.cmd(here + "village war map")[:1500])
    say("intel: " + r.cmd(here + "village war intel")[:600])
    r.cmd("gamemode creative %s" % USER)
    r.cmd("tp %s %d %d %d" % (USER, cx, hy + 1, cz))
    time.sleep(3)
    say("books: " + r.cmd("execute as %s at @s run village war map books" % USER))
    time.sleep(4)
    shot("24-war-3-map")
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("gamemode spectator %s" % USER)
    say("alive after the war map: %s" % client_alive())


def war_footing_stage(r, look, cx, cz):
    """A town on a war footing (entity/WarFooting, Militia, WarWorks): put on its guard against the nearest
    other town, if the world has one (/village war footing tension); then the armoury and the training yard
    put up side by side on cleared ground beside the village (/village war footing stage), the armoury's
    racks filled, up to six of the town's folk enrolled, called up, armed and set drilling at the dummies, and
    the watch sent to its daily turn at the yard. By day: the yard from beyond its gate, the militia thrusting
    at the dummies with the archery butts either side; the armoury from just inside its door, the racks of
    chests along the back, the anvil and the grindstone; and the town's books open on the News page with its
    "On a war footing" panel. Peace again at the end."""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("gamemode spectator %s" % USER)
    r.cmd("time set 2500")
    r.cmd("weather clear")
    sx, sz = cx + 40, cz - 10
    gy = ground_height(r, sx, sz)
    r.cmd("tp %s %d %d %d" % (USER, sx + 7, gy + 12, sz + 20))
    time.sleep(8)                                     # the stage's chunks arrive
    say("on its guard: " + r.cmd("execute positioned %d %d %d run village war footing tension" % (cx, gy, cz))[:800])
    out = r.cmd("execute positioned %d %d %d run village war footing stage" % (sx, gy, sz))
    say("war stage: " + out[:300])
    m = re.search(r"armoury (-?\d+) (-?\d+) (-?\d+) yard (-?\d+) (-?\d+) (-?\d+) militia (\d+)", out)
    if not m:
        say("no war stage went up; nothing to photograph")
        return
    ax, ay, az, yx, yy, yz, n = (int(v) for v in m.groups())
    say("militia at the dummies: %d" % n)
    time.sleep(6)                                     # the militia walks to its places before the dummies
    # Both face north (their backs, the dummies and the racks, toward -z). The yard's dummies stand two north
    # of its middle, its gate three south; the armoury's racks one north of its anchor, its door three south.
    look("war-1-yard", yx + 0.5, yy + 4, yz + 10.5, yx + 0.5, yy + 1, yz - 1.5, wait=8)
    look("war-2-armoury", ax + 0.5, ay + 0.2, az + 2.5, ax + 0.5, ay + 0.6, az - 1.5, wait=5)
    say("war page: " + r.cmd("execute positioned %d %d %d run village war footing" % (cx, gy, cz))[:1500])
    r.cmd("tp %s %d %d %d" % (USER, cx, gy + 2, cz))
    say("stats news: " + r.cmd("execute as %s at @s run village stats 17" % USER))
    time.sleep(4)
    shot("war-3-news-page")
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    say("peace: " + r.cmd("execute positioned %d %d %d run village war footing peace" % (cx, gy, cz))[:300])
    say("alive after the war footing: %s" % client_alive())


def war_peace_stage(r, look, cx, cz):
    """War and peace between towns (entity/WarAndPeace): a second town a little way off; the first town's
    council called to its hall as a council of war over it (/village war council: the leader's case, each
    councillor's vote and why; with no hall it sits out on the square before the board's face, and is
    photographed from that side, the board behind the ring); war declared (/village war declare: the bell
    in both towns, the war banner out of the stores hung over the gate, else on the front of the hall, else
    on a pole before the board's face), each banner photographed from the side its face looks to (a pole
    by the board together with the board); the town's books at the War page; then peace made
    (/village war peace), the banner taken down again, and the war's memorial put up (/village war memorial)
    and photographed from in front of its sign."""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("gamemode spectator %s" % USER)
    midday(r)
    tx, tz = cx - 240, cz + 40                       # the far side from the job market's town (jobs_stage)
    tx, tz = other_town(r, tx, tz, "the other town")
    hy = ground_height(r, cx, cz)
    # A red banner in the stores, for the war banner (the town makes one of six wool and a stick if it has those).
    say("cloth: " + r.cmd("execute positioned %d %d %d run village war cloth" % (cx, hy + 1, cz)))
    ty = ground_height(r, tx, tz)
    say("cloth there: " + r.cmd("execute positioned %d %d %d run village war cloth" % (tx, ty + 1, tz)))
    out = r.cmd("execute positioned %d %d %d run village war council" % (cx, hy + 1, cz))
    say("council: " + out[:700])
    # Which way a thing's face looks (the board's, the hall's front, a banner's), as a step on the ground.
    step = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}
    # "AT x y z <facing> <indoors|outdoors>": the middle of the council's ring and the side to look at it from.
    m = re.search(r"AT (-?\d+) (-?\d+) (-?\d+)(?: (\w+) (\w+))?", out)
    if m:
        ax, ay, az = int(m.group(1)), int(m.group(2)), int(m.group(3))
        fx, fz = step.get(m.group(4) or "", (0, 1))
        time.sleep(20)                                 # the councillors come in and take their places; the vote
        if m.group(5) == "indoors":
            # Under the hall's roof: from inside its front, a little over their heads, at the middle of the ring.
            look("23-war-1-council", ax + 0.5 + fx * 3, ay + 1, az + 0.5 + fz * 3, ax + 0.5, ay + 0.5, az + 0.5, wait=6)
        else:
            # Out on the square, on the side the board's face looks to, ten back and a little to one side and
            # above: the whole ring (3.8 from its middle) in the frame, their bubbles over them, the board behind.
            sx, sz = -fz, fx
            look("23-war-1-council", ax + 0.5 + fx * 10 + sx * 2, ay + 4, az + 0.5 + fz * 10 + sz * 2,
                 ax + 0.5, ay + 0.5, az + 0.5, wait=6)
    out = r.cmd("execute positioned %d %d %d run village war declare" % (cx, hy + 1, cz))
    say("declare: " + out[:700])
    # "BANNER x y z <facing> <Town>", and for one on its pole by the board " BOARD x y z" (the board's foot).
    found = re.findall(r"BANNER (-?\d+) (-?\d+) (-?\d+) (\w+) (.+?)(?: BOARD (-?\d+) (-?\d+) (-?\d+))?$", out, re.M)
    for i, b in enumerate(found[:2]):
        bx, by, bz, facing = int(b[0]), int(b[1]), int(b[2]), b[3]
        label = "23-war-%d-banner-%s" % (2 + i, re.sub(r"\W+", "-", b[4].strip().lower()))
        fx, fz = step.get(facing, (0.7, 0.7))
        if b[5]:
            # On its pole before the board's face: from out on the square on that side, at the middle between
            # the pole and the board's foot, far enough back for the board, the banner and whoever is about.
            lx, lz = int(b[5]), int(b[7])
            mx, mz = (bx + lx) / 2.0 + 0.5, (bz + lz) / 2.0 + 0.5
            look(label, mx + fx * 11, by + 3, mz + fz * 11, mx, by + 2, mz, wait=8)
        else:
            # On a wall (over the gate, on the hall's front): straight out from its face, a little above it.
            look(label, bx + 0.5 + fx * 8, by + 1, bz + 0.5 + fz * 8, bx + 0.5, by + 0.5, bz + 0.5, wait=8)
    say("war: " + r.cmd("execute positioned %d %d %d run village war" % (cx, hy + 1, cz))[:1500])
    r.cmd("gamemode creative %s" % USER)
    r.cmd("tp %s %d %d %d" % (USER, cx, hy + 1, cz))
    time.sleep(3)
    say("books: " + r.cmd("execute as %s at @s run village war books" % USER))
    time.sleep(4)
    shot("23-war-4-books")
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    say("peace: " + r.cmd("execute positioned %d %d %d run village war peace" % (cx, hy + 1, cz))[:500])
    r.cmd("gamemode spectator %s" % USER)
    # The war's memorial (wanted at the peace, the war itself on it when it cost no lives), put up now out of the
    # stores (/village war memorial: whatever the hour, no walk for the hand): "MEMORIAL x y z <facing> <words>",
    # the post, with the sign on top of it looking <facing>.
    out = r.cmd("execute positioned %d %d %d run village war memorial" % (cx, hy + 1, cz))
    say("memorial: " + out[:300])
    say("after the peace: " + r.cmd("execute positioned %d %d %d run village war" % (cx, hy + 1, cz))[-900:])
    m = re.search(r"MEMORIAL (-?\d+) (-?\d+) (-?\d+) (\w+)", out)
    if m:
        px, py, pz = int(m.group(1)), int(m.group(2)), int(m.group(3))
        fx, fz = step.get(m.group(4), (0.7, 0.7))
        sx, sz = -fz, fx
        # Out in front of the sign's face, a little to one side, at about its height: its words readable.
        look("23-war-5-memorial", px + 0.5 + fx * 4.5 + sx, py + 0.3, pz + 0.5 + fz * 4.5 + sz, px + 0.5, py + 1.5, pz + 0.5, wait=6)
    say("alive after the war: %s" % client_alive())


def guard_kit_stage(r, look, cx, cz):
    """The watch's kit (entity/WatchKit): every guard fitted out of the stores now (/village watch now), and
    /village watch said (each guard's kit, what is on order for the watch and who makes it, and what it has cost
    the town); then three guards stood in a row on open ground beside the town (/village watch stage), in the
    kit the ages bring: leather and a stone blade, iron with a shield, diamond with a shield. By day, from in
    front of the row, the three side by side."""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("gamemode spectator %s" % USER)
    r.cmd("time set 6000")
    r.cmd("weather clear")
    hy = ground_height(r, cx, cz)
    say("the watch fitted: " + r.cmd("execute positioned %d %d %d run village watch now" % (cx, hy + 1, cz))[:300])
    say("the watch: " + r.cmd("execute positioned %d %d %d run village watch" % (cx, hy + 1, cz))[:1500])
    sx, sz = cx + 36, cz - 30                         # out past the first houses, on open ground
    sy = ground_height(r, sx, sz)
    r.cmd("tp %s %d %d %d" % (USER, sx + 2, sy + 6, sz + 10))
    time.sleep(6)                                     # the stage's chunks arrive
    out = r.cmd("execute positioned %d %d %d run village watch stage" % (sx, sy + 1, sz))
    say("watch stage: " + out[:300])
    m = re.search(r"watch (-?\d+) (-?\d+) (-?\d+)", out)
    if not m:
        say("no guards stood up; nothing to photograph")
        return
    wx, wy, wz = int(m.group(1)), int(m.group(2)), int(m.group(3))
    # The three face south (+z), two blocks apart: the camera five blocks in front of the middle one.
    look("24-watch-kit-1-lineup", wx + 2.5, wy + 1.7, wz + 5.5, wx + 2.5, wy + 1.0, wz + 0.5, wait=5)
    say("alive after the watch's kit: %s" % client_alive())


def mine_safety_stage(r, look, cx, cz):
    """The mine made safe (entity/MineSafety, Aboard): a run of mine stairs cut into the ground out past the
    town (/village mine showcase), the open top of them fenced round at the surface, the head left open as
    the way in and the sign "The mine of <town>" on the post beside it; from above the head, and close to
    the sign. Then what /village mine says: who is below ground in the mine, the stair heads fenced, and any
    folk that had to get out of a boat."""
    r.cmd("gamemode spectator %s" % USER)
    midday(r)
    sx, sz = cx + 70, cz - 120                       # out past the town, on ground of its own
    gy = ground_height(r, sx, sz)
    r.cmd("tp %s %d %d %d" % (USER, sx - 6, gy + 8, sz - 8))
    time.sleep(8)                                      # the ground arrives at the server and the client
    out = r.cmd("execute positioned %d 0 %d positioned over motion_blocking_no_leaves run village mine showcase" % (sx, sz))
    say("mine showcase: " + out[:500])
    views = re.findall(r"VIEW (\S+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+)", out)
    for i, (name, x, y, z, ax, ay, az) in enumerate(views):
        look(name, int(x) + 0.5, int(y), int(z) + 0.5, int(ax) + 0.5, int(ay) + 0.5, int(az) + 0.5, wait=8 if i == 0 else 5)
    say("mine: " + r.cmd("execute positioned %d 100 %d run village mine" % (cx, cz))[:900])
    say("alive after the mine: %s" % client_alive())


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

    # The ground of the founded town (the heart cleared and levelled), not wherever the player was set down:
    # from there the first picture was taken from inside an acacia's leaves.
    gy = ground_height(r, cx, cz) or py

    def look_at_the_village(label):
        # From fourteen blocks off and six above the town's ground, at the middle of it.
        r.cmd("tp %s %d %d %d facing %d %d %d" % (USER, cx - 14, gy + 6, cz, cx, gy + 1, cz))
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
    # Its skills: the levels, the knack points, the knacks it chose and why (the Skills page,
    # which asking about them opens).
    say("talk: " + r.cmd("execute as %s at @s run village talk what are your skills" % USER))
    time.sleep(3)
    shot("16-talk-skills")
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
        say("research: " + r.cmd("village research"))        # the city's research, after eight mornings of points
        for page, name in ((0, "overview"), (1, "growth"), (2, "money"), (3, "production"), (4, "shops"), (5, "jobs"),
                           (6, "folk"), (7, "society"), (8, "leader"), (9, "homes"), (10, "buildings"), (11, "stores"),
                           (12, "stock"), (13, "research"), (14, "why"), (15, "trends"), (16, "records"), (17, "news")):
            say("stats %s: %s" % (name, r.cmd("execute as %s at @s run village stats %d" % (USER, page))))
            time.sleep(3)
            shot("16-stats-%d-%s" % (page, name))
        r.cmd("time set 6000")
    except Exception as e:  # noqa: BLE001
        say("stats failed: %s" % e)
    say("alive after the lineup: %s" % client_alive())
    try:
        sweeper_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("sweeper stage failed: %s" % e)
    try:
        workshop_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("workshop stage failed: %s" % e)
    try:
        market_stall_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("stall stage failed: %s" % e)
    try:
        bank_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("bank failed: %s" % e)
    try:
        districts_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("districts stage failed: %s" % e)
    try:
        museum_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("museum stage failed: %s" % e)
    try:
        found_village(r, cx, cz, look)
    except Exception as e:  # noqa: BLE001
        say("founding failed: %s" % e)
    try:
        jungle_stage(r, look)
    except Exception as e:  # noqa: BLE001
        say("jungle stage failed: %s" % e)
    try:
        bell_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("bell stage failed: %s" % e)
    try:
        horses_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("horses stage failed: %s" % e)
    try:
        jobs_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("jobs stage failed: %s" % e)
    try:
        economy_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("economy stage failed: %s" % e)
    try:
        fields_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("fields stage failed: %s" % e)
    try:
        ageing_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("ageing stage failed: %s" % e)
    try:
        sights_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("sights stage failed: %s" % e)
    try:
        seasons_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("seasons stage failed: %s" % e)
    try:
        visitors_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("visitors stage failed: %s" % e)
    try:
        health_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("health stage failed: %s" % e)
    try:
        sport_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("sport stage failed: %s" % e)
    try:
        culture_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("culture stage failed: %s" % e)
    try:
        townlook_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("townlook stage failed: %s" % e)
    try:
        townlife_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("townlife stage failed: %s" % e)
    try:
        prices_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("prices stage failed: %s" % e)
    try:
        store_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("store stage failed: %s" % e)
    try:
        wages_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("wages stage failed: %s" % e)
    try:
        housing_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("housing stage failed: %s" % e)
    try:
        trade_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("trade stage failed: %s" % e)
    try:
        war_peace_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("war peace stage failed: %s" % e)
    try:
        war_scouting_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("war scouting stage failed: %s" % e)
    try:
        war_footing_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("war footing stage failed: %s" % e)
    try:
        guard_kit_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("guard kit stage failed: %s" % e)
    try:
        mine_safety_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("mine safety stage failed: %s" % e)
    r.cmd("gamemode spectator %s" % USER)
    say("alive after the founding: %s" % client_alive())
    try:
        showcase(r, cx, cz, look)
    except Exception as e:  # noqa: BLE001
        say("showcase failed: %s" % e)
    try:
        blueprints_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("blueprints stage failed: %s" % e)
    try:
        school_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("school stage failed: %s" % e)
    try:
        decor_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("decor failed: %s" % e)
    try:
        flats_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("flats failed: %s" % e)
    alive = client_alive()
    say("alive at the end: %s" % alive)
    say("PASS the client drew the village and kept running" if alive else "FAIL the client died while drawing the village")
    try:
        r.cmd("stop")
    except Exception:  # noqa: BLE001
        pass


# ---------------------------------------------------------------------------------------------
# smoke.py mature: the books of a town with real years in it. The first mode photographs the
# books of a hamlet eight days old, so the Homes, Shops and Buildings pages have next to nothing
# on them; this one is run on the world the hundred days saved (village-mature-photos.yml).
# ---------------------------------------------------------------------------------------------

# Every page of the analytics screen (client/CityScreen.TABS), by its number for /village stats.
MATURE_PAGES = ((0, "overview"), (1, "growth"), (2, "money"), (3, "production"), (4, "shops"), (5, "jobs"),
                (6, "folk"), (7, "society"), (8, "leader"), (9, "homes"), (10, "buildings"), (11, "stores"),
                (12, "stock"), (13, "research"), (14, "why"), (15, "trends"), (16, "records"), (17, "news"), (18, "board"))

# One village of /village list: "Village at X, Z (Name) — N folk (M loaded), the Iron Age, built [hall, ...]".
# Read with findall over the whole answer, so it does not matter how RCON joins the lines.
LISTED = re.compile(r"Village at (-?\d+), (-?\d+) \((.*?)\) — (\d+) folk \((\d+) loaded\), (.*?), built \[(.*?)\]")


def wait_for_join(r):
    """The client on the server, or False (and the server stopped) if it never came."""
    deadline = time.time() + 900
    while time.time() < deadline:
        if USER in r.cmd("list"):
            return True
        if not client_alive():
            say("FAIL the client process ended before it joined")
            break
        time.sleep(5)
    else:
        say("FAIL the client never joined the server")
    try:
        r.cmd("stop")
    except Exception:  # noqa: BLE001
        pass
    return False


def listed_villages(r):
    """Every village the server knows of now, biggest first: (folk, buildings, x, z, name, age, loaded).
    A village is only known once one of its folk has loaded (the folk carry it), so this is the
    villages round wherever the player has been."""
    found = []
    for x, z, name, folk, loaded, age, built in LISTED.findall(r.cmd("village list")):
        raised = [b.strip() for b in built.split(",") if b.strip() and b.strip() != "colony"]
        found.append((int(folk), len(raised), int(x), int(z), name, age.strip(), int(loaded)))
    found.sort(key=lambda v: (v[0], v[1]), reverse=True)
    return found


def midday(r):
    """Noon of the day it is. Not /time set: that puts the world's clock back to day nought, and the
    town's books, its folk's ages and its elections all count their days from that clock."""
    m = re.search(r"(\d+)", r.cmd("time query daytime"))
    now = int(m.group(1)) % 24000 if m else 6000
    if not 1000 <= now <= 11000:
        r.cmd("time add %d" % ((6000 - now) % 24000))
    say("the clock: %s (the day), %s (the time of day)" % (r.cmd("time query day"), r.cmd("time query daytime")))


def ground_height(r, cx, cz):
    """How high the town stands: the top of the ground (or a roof) at nine points round the heart,
    the middle one of them. The player is moved to each (a spectator, so nothing falls)."""
    ys = []
    for dx in (-16, 0, 16):
        for dz in (-16, 0, 16):
            out = r.cmd("execute positioned %d 0 %d positioned over motion_blocking_no_leaves run tp %s ~ ~ ~"
                        % (cx + dx, cz + dz, USER))
            if "Teleported" not in out:
                continue
            pos = position(r)
            if pos and pos[1] > -60:                   # an unloaded chunk reads as the bottom of the world
                ys.append(int(pos[1]))
    if ys:
        ys.sort()
        say("the ground round the heart: %s" % ys)
        return ys[len(ys) // 2]
    # The old way, as photo.py does it: stand on whatever is there.
    r.cmd("gamemode creative %s" % USER)
    say("standing at the heart: " + r.cmd("spreadplayers %d %d 0 4 false %s" % (cx, cz, USER)))
    time.sleep(5)
    pos = position(r)
    r.cmd("gamemode spectator %s" % USER)
    return int(pos[1]) if pos else 70


def mature():
    r = Rcon()
    spot = None
    for name in ("hundred-spot.txt", "run/hundred-spot.txt"):
        try:
            with open(name) as fh:
                x, z = (int(v) for v in fh.read().split()[:2])
            spot = (x, z)
            break
        except (OSError, ValueError):
            continue
    say("connected; the hundred days put their village at %s; waiting for the client to join"
        % ("%d, %d" % spot if spot else "an unknown spot"))
    if not wait_for_join(r):
        return
    say("the client joined: %s" % r.cmd("list"))
    for c in ("gamerule doDaylightCycle false", "weather clear 1000000", "gamerule doMobSpawning false",
              "gamemode spectator %s" % USER):
        r.cmd(c)
    midday(r)
    # The folk carry their village: nothing is known of it until the ground it stands on is loaded.
    if spot:
        r.cmd("tp %s %d 150 %d" % (USER, spot[0], spot[1]))
        time.sleep(30)
    towns = []
    for _ in range(12):
        towns = listed_villages(r)
        if towns:
            break
        time.sleep(10)
    for folk, raised, x, z, name, age, loaded in towns:
        say("VILLAGE %s at %d, %d: %d folk (%d loaded), %d buildings, %s" % (name, x, z, folk, loaded, raised, age))
    if towns:
        folk, raised, cx, cz, name, age, loaded = towns[0]
        say("the biggest: %s at %d, %d, %d folk, %d buildings, %s" % (name, cx, cz, folk, raised, age))
    elif spot:
        cx, cz = spot
        name = "the hundred days' village"
        say("no village listed yet; going by the spot the hundred days kept, %d, %d" % (cx, cz))
    else:
        say("FAIL no village in the world, and no spot to look for one at")
        try:
            r.cmd("stop")
        except Exception:  # noqa: BLE001
            pass
        return
    if not spot or math.hypot(cx - spot[0], cz - spot[1]) > 48:
        r.cmd("tp %s %d 150 %d" % (USER, cx, cz))
        time.sleep(30)
    gy = ground_height(r, cx, cz)
    say("the heart of %s is at %d, %d, %d" % (name, cx, gy, cz))
    for line in r.cmd("execute positioned %d %d %d run village status" % (cx, gy, cz)).split("\n"):
        if line.strip():
            say("STATUS " + line.strip())
    # The books' own reading of the town, in words (from the console, with no player, it answers in text).
    say("BOOKS " + r.cmd("execute positioned %d %d %d run village stats" % (cx, gy, cz)).replace("\n", " | "))

    def look(label, x, y, z, tx, ty, tz, wait=12):
        # A camera: yaw 0 looks south (+z), a positive pitch looks down, eyes 1.62 above the feet.
        dx, dz = tx - x, tz - z
        yaw = math.degrees(math.atan2(-dx, dz))
        pitch = -math.degrees(math.atan2(ty - (y + 1.62), math.hypot(dx, dz)))
        r.cmd("tp %s %.2f %.2f %.2f %.1f %.1f" % (USER, x, y, z, yaw, pitch))
        time.sleep(wait)
        shot("mature-" + label)

    # The town from the air (below the clouds, at 192), from two sides and from straight above.
    try:
        look("1-air-southeast", cx + 70, gy + 55, cz + 80, cx, gy, cz, wait=25)
        look("2-air-northwest", cx - 70, gy + 55, cz - 80, cx, gy, cz, wait=18)
        look("3-overhead", cx + 0.5, min(gy + 110, 185), cz + 1.5, cx + 0.5, gy, cz + 0.5, wait=18)
    except Exception as e:  # noqa: BLE001
        say("the air views failed: %s" % e)
    say("alive after the air views: %s" % client_alive())
    # The town's mine, from above and from its edge (where `village mine` says its first face is).
    try:
        mine = r.cmd("execute positioned %d %d %d run village mine" % (cx, gy, cz))
        say("MINE " + mine.replace("\n", " | "))
        m = re.search(r"first face at (-?\d+), (-?\d+)", mine)
        if m:
            mx, mz = int(m.group(1)), int(m.group(2))
            my = ground_height(r, mx, mz)
            look("4-mine-above", mx + 0.5, my + 45, mz + 1.5, mx + 0.5, my - 10, mz + 0.5, wait=20)
            look("5-mine-edge", mx + 22, my + 14, mz + 22, mx, my - 6, mz, wait=12)
    except Exception as e:  # noqa: BLE001
        say("the mine views failed: %s" % e)
    # The newer sights of a town with families in it: the sign, the gazette, the crier, the children's game,
    # a pet and a garden (a young town has no households yet to show them).
    try:
        sights_stage(r, look, cx, cz)
    except Exception as e:  # noqa: BLE001
        say("the sights failed: %s" % e)
    r.cmd("gamemode spectator %s" % USER)
    # The books, every page. /village stats as the player opens the screen on the nearest village's
    # books (as clicking the village board does), so the player hangs over the heart.
    try:
        r.cmd("tp %s %.1f %d %.1f 0 60" % (USER, cx + 0.5, gy + 30, cz + 0.5))
        time.sleep(8)
        for page, title in MATURE_PAGES:
            out = r.cmd("execute as %s at @s run village stats %d" % (USER, page))
            say("stats %d %s: %s" % (page, title, out[:200] or "sent"))
            time.sleep(12 if page == 0 else 5)      # the first is the whole town's books arriving
            shot("mature-stats-%d-%s" % (page, title))
        say("still on the server after the books: %s" % (USER in r.cmd("list")))
    except Exception as e:  # noqa: BLE001
        say("the books failed: %s" % e)
    alive = client_alive()
    say("alive at the end: %s" % alive)
    say("PASS the mature town and its books were photographed" if alive
        else "FAIL the client died while photographing the mature town")
    try:
        r.cmd("stop")
    except Exception:  # noqa: BLE001
        pass


if __name__ == "__main__":
    try:
        if len(sys.argv) > 1 and sys.argv[1] == "mature":
            mature()
        else:
            main()
    except (EOFError, OSError) as e:
        say("DIED: the server went away (%s)" % e)
        sys.exit(3)
