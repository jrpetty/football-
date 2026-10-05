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
    views = re.findall(r"VIEW (\S+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+)", out)
    if not views:
        say("no furnished home was set out; nothing to photograph")
        return
    for name, ex, ey, ez, ax, ay, az in views:
        if "dusk" in name:
            continue
        look(name, int(ex) + 0.5, int(ey), int(ez) + 0.5, int(ax) + 0.5, int(ay) + 0.5, int(az) + 0.5, wait=8)
    # Dusk: the household's candles lit (and every window yellow with lamplight).
    r.cmd("time set 12700")
    say("decor at dusk: " + r.cmd("execute positioned %d 100 %d run village decor now" % (x, z))[:600])
    for name, ex, ey, ez, ax, ay, az in views:
        if "dusk" in name:
            look(name, int(ex) + 0.5, int(ey), int(ez) + 0.5, int(ax) + 0.5, int(ay) + 0.5, int(az) + 0.5, wait=8)
    r.cmd("time set 6000")
    say("alive after the furnished home: %s" % client_alive())
def flats_stage(r, look, cx, cz):
    """A block of flats, furnished, on a stage of its own (/village flats stage): from across the street
    (three storeys, the brick bands, the parapet, the railings and the step), the stair hall from just
    inside the front door (the flats' doors and numbers, the stair winding up the back), and a couple's
    flat on the first floor from its door (two beds, the chest, the table, the lantern)."""
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
    # Its back is to the north: the front door at x+3, z+4, the street to the south.
    look("flats-1-front", bx - 8.5, by + 3, bz + 17.5, bx + 0.5, by + 6, bz + 0.5, wait=8)
    look("flats-2-hall", bx + 3.5, by, bz + 3.5, bx + 3.5, by + 2.5, bz - 2.5, wait=6)
    look("flats-3-flat", bx + 1.5, by + 4, bz - 1.5, bx - 3.5, by + 4.2, bz - 2.5, wait=6)
    say("alive after the flats: %s" % client_alive())


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
    look("17-found-4-done-air", fx + 100, level_y + 85, fz + 100, fx, level_y, fz, wait=12)
    look("17-found-5-done-overhead", fx + 4, level_y + 130, fz + 10, fx, level_y, fz, wait=8)
    ex, ez = at(radius + 34, 46)
    look("17-found-6-edge", ex, level_y + 22, ez, hill_x, level_y + 4, hill_z, wait=8)
    look("17-found-7-folk", fx + 10, level_y + 6, fz + 10, fx, level_y + 1, fz, wait=8)
    say("folk founded: " + r.cmd("execute positioned %d %d %d run village list" % (fx, level_y + 1, fz))[:400])


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
    """The village school mid-lesson: a schoolhouse set out on a stage in clear air (/village school
    stage), the blackboard up, the teacher at the lectern and six children at their desks; from the
    street, then from the back of the schoolroom over the children's heads while the teacher says a
    line of the lesson; and the School page of the nearest village's books."""
    sx, sy, sz = cx - 120, 150, cz + 120
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
    """The town's calendar (TownBell, Birthdays, FoundingDay), photographed in the village spawned at cx, cz:
    the town bell rung at dawn by its ringer (a bell set by the board for the picture if the town has none
    yet, as a player brings one), the town gathered before the board for Founding Day hearing its year's
    chronicle read out, and the town's calendar (today's bells, the next Founding Day, the week's
    birthdays) on the News page of its books."""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("gamemode spectator %s" % USER)
    out = r.cmd("execute positioned %d 100 %d run village bell" % (cx, cz))
    say("bell: " + out[:700])
    board = re.search(r"BOARD (-?\d+) (-?\d+) (-?\d+)", out)
    m = re.search(r"BELL-AT (-?\d+) (-?\d+) (-?\d+)", out)
    if not m:
        spot = re.search(r"SPOT (-?\d+) (-?\d+) (-?\d+)", out) or board
        if not spot:
            say("no bell and nowhere to set one; no bell pictures")
            return
        sx, sy, sz = (int(v) for v in spot.groups())
        say("a bell for the town: " + r.cmd("setblock %d %d %d minecraft:bell[attachment=floor,facing=north]" % (sx + 3, sy, sz)))
        out = r.cmd("execute positioned %d 100 %d run village bell" % (cx, cz))
        m = re.search(r"BELL-AT (-?\d+) (-?\d+) (-?\d+)", out)
    if m:
        bx, by, bz = (int(v) for v in m.groups())
        # The dawn bell: the ringer at it, the bell swinging, the call over its head.
        say("ring: " + r.cmd("execute positioned %d 100 %d run village bell ring dawn" % (cx, cz)))
        look("18-bell-1-dawn", bx + 4.5, by + 1, bz + 4.5, bx + 0.5, by + 0.5, bz + 0.5, wait=2)
    # Founding Day before the board: the crowd, and the year's chronicle read out over the leader's head.
    say("founding: " + r.cmd("execute positioned %d 100 %d run village founding now" % (cx, cz)))
    time.sleep(35)                                     # they gather; the first lines are read
    if board:
        lx, ly, lz = (int(v) for v in board.groups())
        look("18-bell-2-founding", lx + 9.5, ly + 6, lz + 9.5, lx + 0.5, ly + 1.5, lz + 0.5, wait=3)
    else:
        look("18-bell-2-founding", cx + 9.5, 100, cz + 9.5, cx + 0.5, 95, cz + 0.5, wait=3)
    say("founding: " + r.cmd("execute positioned %d 100 %d run village founding" % (cx, cz))[:500])
    # The town's books, the News page: the town's calendar above the chronicle.
    say("stats news: " + r.cmd("execute as %s at @s run village stats 17" % USER))
    time.sleep(3)
    shot("18-bell-3-news")
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    say("alive after the bell: %s" % client_alive())
def bank_stage(r, look, cx, cz):
    """The bank (entity/Bank): put up beside the village and opened (/village bank showcase), its
    banker behind the counter; from the street, then from inside by the lectern looking at the vault's
    bars; a word with the banker about an account the player has just opened; and the books' Money page
    with the bank's panel on it. Best called after the stats stage, so the books have days in them."""
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
    back = step.get(facing, (0, -1))
    front = (-back[0], -back[1])
    side = (back[1], -back[0])
    time.sleep(4)
    # From the street: the stone front, its door and windows, the banker inside.
    look("bank-1-front", ax + front[0] * 14 + side[0] * 6 + 0.5, ay + 4, az + front[1] * 14 + side[1] * 6 + 0.5,
         ax + 0.5, ay + 2, az + 0.5, wait=8)
    # Inside, by the lectern: the counter, the banker behind it, and the vault's barred gate and grille.
    look("bank-2-vault", ax + front[0] * 3 + 0.5, ay, az + front[1] * 3 + 0.5,
         ax + back[0] * 1 + 0.5, ay + 1.2, az + back[1] * 1 + 0.5, wait=6)
    # An account: coin in, then a word with the banker across the counter.
    r.cmd("gamemode creative %s" % USER)
    r.cmd("give %s mc_assistant:village_coin 40" % USER)
    say("bank deposit: " + r.cmd("execute as %s at @s run village bank deposit 25" % USER))
    r.cmd("tp %s %.1f %d %.1f" % (USER, ax + front[0] * 2 + 0.5, ay, az + front[1] * 2 + 0.5))
    r.cmd("execute as %s at @s run tp @s ~ ~ ~ facing %d %d %d" % (USER, ax, ay + 1, az))
    time.sleep(3)
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
    r.cmd("gamemode spectator %s" % USER)
    say("alive after the bank: %s" % client_alive())
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
    so far bound onto the lectern and the shelves): the hall from inside the door, a label close up, and the
    Museum page of the town's books."""
    mx, my, mz = cx + 60, 150, cz + 40
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("time set 6000")
    r.cmd("gamemode spectator %s" % USER)
    r.cmd("tp %s %d %d %d" % (USER, mx, my + 10, mz - 20))
    time.sleep(10)                                     # the stage's chunks arrive
    out = r.cmd("execute positioned %d %d %d run village museum stage" % (mx, my, mz))
    say("museum: " + out[:900])
    views = dict((v[0], [float(n) for n in v[1:]]) for v in
                 re.findall(r"VIEW (\S+) (-?[\d.]+) (-?[\d.]+) (-?[\d.]+) (-?[\d.]+) (-?[\d.]+) (-?[\d.]+)", out))
    for name in ("m2-hall", "m4-label"):
        if name in views:
            look("18-museum-" + name, *views[name], wait=8)
    say("museum status: " + r.cmd("execute positioned %d %d %d run village museum" % (mx, my, mz))[:600])
    say("stats museum: " + r.cmd("execute as %s at @s run village stats 20" % USER))
    time.sleep(3)
    shot("18-museum-page")
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    say("alive after the museum: %s" % client_alive())


def jobs_stage(r, look, cx, cz):
    """The job market between towns (entity/JobMarket, JobSeekers): a second town a little way off,
    the two agreeing to trade, a Wanted notice put up on the second town's board, a folk of the first
    town sent to read its own board (the notice it heard of read out over its head), the Wanted
    notice on the second town's board, and the city books open at the job market."""
    say("books shut: " + r.cmd("execute as %s run village stats close" % USER))
    r.cmd("gamemode spectator %s" % USER)
    midday(r)
    tx, tz = cx + 230, cz + 20
    r.cmd("tp %s %d 140 %d" % (USER, tx, tz))
    time.sleep(15)                                     # the ground arrives
    say("second town: " + r.cmd("village spawnat %d %d 8" % (tx, tz))[:200])
    time.sleep(20)                                     # its board goes up, its folk take up their trades
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
    r.cmd("gamemode spectator %s" % USER)
    say("alive after the founding: %s" % client_alive())
    try:
        showcase(r, cx, cz, look)
    except Exception as e:  # noqa: BLE001
        say("showcase failed: %s" % e)
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
