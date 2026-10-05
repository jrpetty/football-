#!/usr/bin/env python3
"""
Drive a REAL dedicated server over RCON.

The game tests run a village on ground the tests themselves made: flat, tidy,
with ponds and woods put exactly where a village wants them. That proves the
logic and hides everything about real terrain. This runs the same village on
whatever the game's own world generator put at a chosen biome, lets three game
days pass at full speed (/tick sprint), and reports what the folk did.

    soak.py <scenario>      scenario is a biome name (plains, forest, taiga,
                            savanna, desert, ...) or "takeover"

"takeover" instead finds a vanilla village, forces its chunks to generate, and
checks the game's own villagers were turned into folk WITHOUT freezing the
server (the join event fires inside chunk generation, where touching the world
can deadlock it).

Every line of output starts [REAL]. Only a dead or hung server is a hard
failure; everything else is measurement, read from the published report.
"""
import json
import re
import socket
import struct
import sys
import time

HOST, PORT, PASSWORD = "127.0.0.1", 25575, "soak"


def say(msg):
    print("[REAL] " + msg, flush=True)


class Rcon:
    def __init__(self):
        deadline = time.time() + 900          # first start: download, build, generate
        last = None
        while time.time() < deadline:
            try:
                self.sock = socket.create_connection((HOST, PORT), timeout=30)
                break
            except OSError as e:
                last = e
                time.sleep(5)
        else:
            raise SystemExit("[REAL] FATAL server never opened RCON: %s" % last)
        self.sock.settimeout(1800)
        self.n = 0
        want = self._send(3, PASSWORD)
        rid, _, _ = self._recv()
        if rid != want:
            raise SystemExit("[REAL] FATAL rcon login refused")

    def _send(self, ptype, body):
        self.n += 1
        payload = struct.pack("<ii", self.n, ptype) + body.encode("utf-8") + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(payload)) + payload)
        return self.n

    def _recvn(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise EOFError("server closed the connection")
            buf += chunk
        return buf

    def _recv(self):
        (length,) = struct.unpack("<i", self._recvn(4))
        data = self._recvn(length)
        rid, rtype = struct.unpack("<ii", data[:8])
        return rid, rtype, data[8:-2].decode("utf-8", "replace")

    def cmd(self, text):
        """Run a command and return everything it printed.

        Vanilla's RCON server reads with a single recv and gives up on the
        connection if a second packet has arrived in the same read, so two
        requests must never be in flight at once — and a slow command (a village
        founding takes seconds) makes that overlap certain. The reply to a long
        answer comes in 4096-byte pieces with no "last one" flag, so a second,
        deliberately unknown request is sent once the FIRST piece has arrived;
        its answer marks the end."""
        want = self._send(2, text)
        out = []
        end = None
        while True:
            rid, _, body = self._recv()
            if rid == want:
                out.append(body)
                if end is None:
                    end = self._send(100, "")
            elif end is not None and rid == end:
                break
        return "".join(out).strip()


def gametime(r):
    m = re.search(r"(\d+)", r.cmd("time query gametime"))
    return int(m.group(1)) if m else -1


LAST_MSPT = [None]


def sprint(r, ticks):
    """Run this many ticks as fast as the machine will go, and wait for them.
    Says how fast that was: a village that is fine at twelve folk and grinds at
    a hundred is a finding, and this is where it shows."""
    start = gametime(r)
    began = time.time()
    r.cmd("tick sprint %dt" % ticks)
    stalled = 0
    last = start
    while True:
        time.sleep(4)
        now = gametime(r)
        if now >= start + ticks:
            took = max(0.001, time.time() - began)
            say("sprint: %d ticks in %.0f s = %.1f ms a tick (%.0f tps)"
                % (ticks, took, 1000.0 * took / ticks, ticks / took))
            LAST_MSPT[0] = round(1000.0 * took / ticks, 2)
            return now
        if now == last:
            stalled += 1
            if stalled > 90:                      # six minutes of no progress
                say("HUNG: game time stuck at %d (wanted %d)" % (now, start + ticks))
                raise SystemExit(2)
        else:
            stalled = 0
        last = now


def where(r, what, dim="minecraft:overworld"):
    """/locate → (x, z), or None."""
    out = r.cmd("locate %s" % what)
    m = re.search(r"\[(-?\d+), (~|-?\d+), (-?\d+)\]", out)
    if not m:
        say("locate %s said: %s" % (what, out[:200]))
        return None
    return int(m.group(1)), int(m.group(3))


def report(r, x, z, label, compact=False):
    at = "execute positioned %d 64 %d run " % (x, z)
    say("== %s @tick %d" % (label, gametime(r)))
    say("STATUS " + r.cmd(at + "village status").replace("\n", " | "))
    people = [l for l in r.cmd(at + "village people").split("\n") if l.strip()]
    if people:
        say("PEOPLE " + people[0])
        for line in people[1:7]:
            say("  person: " + line)
    lines = [l for l in r.cmd(at + "village folk").split("\n") if l.strip()]
    if not compact:
        for line in lines:
            say("  " + line)
        return
    # A hundred lines a checkpoint is more than anybody reads: tally instead.
    trades, idle, gated, frozen = {}, 0, 0, 0
    for line in lines[1:]:
        m = re.search(r" L\d+ (\w[\w ]*?) hp=", line)
        if m:
            trades[m.group(1)] = trades.get(m.group(1), 0) + 1
        if " job=- " in line:
            idle += 1
        if "missing=[" in line:
            gated += 1
        m = re.search(r"sinceWork=(\d+)", line)
        if m and int(m.group(1)) > 6000:
            frozen += 1
    say("TALLY %d folk; trades %s; %d with no job, %d missing something, %d not worked in 5 min"
        % (max(0, len(lines) - 1), trades, idle, gated, frozen))
    for line in lines[1:9]:
        say("  " + line)


def setup(r):
    for c in ("gamerule doDaylightCycle true", "gamerule doWeatherCycle false",
              "gamerule randomTickSpeed 15", "gamerule doMobSpawning true",
              "weather clear 1000000", "time set 1000", "difficulty normal"):
        r.cmd(c)


def night(r, x, z, label):
    """Midnight: who has a bed, and who is in it. Every folk should be asleep but the
    guards keeping the watch."""
    status = r.cmd("execute positioned %d 64 %d run village status" % (x, z)).replace("\n", " ")
    m = re.search(r"Beds: (\d+) of (\d+) have one, (\d+) asleep, homes for (\d+)(?: made up of \d+)?, camp (\d+)", status)
    if m:
        up = re.search(r"; up: (.*?)\. Room for|; up: (.*?)\. Growing", status)
        say("NIGHT %s: %s of %s folk have a bed, %s asleep (homes hold %s, %s still at the camp)%s"
            % (label, m.group(1), m.group(2), m.group(3), m.group(4), m.group(5),
               ("; up: " + (up.group(1) or up.group(2))) if up else ""))
    else:
        say("NIGHT %s: no beds line in the status" % label)


def raid(r, x, z):
    """Dusk on the first day: the things that come out at night, put among the
    settlers. Nothing spawns on a server nobody is playing on, so without this
    the village never meets a monster and every defensive rule in the mod goes
    untested. spreadplayers stands them on the surface round the heart."""
    heart = "execute positioned %d 100 %d run " % (x, z)
    for kind, n in (("zombie", 6), ("skeleton", 3), ("creeper", 2), ("spider", 2)):
        for _ in range(n):
            r.cmd(heart + 'summon minecraft:%s ~ ~ ~ {Tags:["raid"],PersistenceRequired:1b}' % kind)
    r.cmd("spreadplayers %d %d 3 14 false @e[tag=raid]" % (x, z))
    say("RAID at dusk: " + r.cmd("execute if entity @e[tag=raid]"))


def village(r, biome, count=12, compact=False, days=3, label=None, raided=True):
    setup(r)
    spot = where(r, "biome minecraft:" + biome)
    if spot is None:
        say("SKIP no %s within reach of this seed" % biome)
        return
    x, z = spot
    say("village of %d goes at %d, %d (%s)" % (count, x, z, biome))
    began = time.time()
    say("spawn: " + r.cmd("village spawnat %d %d %d" % (x, z, count)))
    say("spawning took %.1f s of wall clock" % (time.time() - began))
    done = 0
    marks = sorted(set([300, 1500, 4500, 12000, 13500, 15000, 17000, 41000]
                       + [24000 * d for d in range(1, days + 1)]))
    for upto in marks:
        sprint(r, upto - done)
        done = upto
        if upto in (17000, 41000):
            night(r, x, z, "%s night %d" % (label or biome, 1 + upto // 24000))
            continue
        if upto == 13500:
            if raided:
                raid(r, x, z)
                continue
        report(r, x, z, "%s day %.2f" % (label or biome, done / 24000.0), compact)
        if upto == 15000 and raided:
            say("after the raid: " + r.cmd("execute if entity @e[tag=raid]"))
        if upto >= 24000 * 2:
            say("village list: " + r.cmd("village list").replace("\n", " | "))
    say("PASS the server ran %d game days on %s" % (days, label or biome))


def twin(r, days=3):
    """Two players, two villages: two settlements a thousand blocks apart run side by side,
    and the ground round each is loaded and dropped again in turn, as players coming and
    going would do it. Both should go on growing, and the server should neither choke nor
    lose either village's work."""
    setup(r)
    a = where(r, "biome minecraft:plains") or where(r, "biome minecraft:forest")
    if a is None:
        say("SKIP no plains or forest within reach of this seed")
        return
    # The second village on dry plains (or forest) found from a thousand blocks out: placed
    # blindly at an offset, the first run's village B stood in a lake and every lot was wet.
    b = None
    for biome in ("plains", "forest", "savanna"):
        out = r.cmd("execute positioned %d 64 %d run locate biome minecraft:%s" % (a[0] + 1000, a[1] + 200, biome))
        m = re.search(r"\[(-?\d+), (~|-?\d+), (-?\d+)\]", out)
        if m and abs(int(m.group(1)) - a[0]) + abs(int(m.group(3)) - a[1]) > 400:
            b = (int(m.group(1)), int(m.group(3)))
            break
    if b is None:
        b = (a[0] + 1000, a[1] + 200)
    say("TWIN village A at %d, %d; village B at %d, %d" % (a[0], a[1], b[0], b[1]))
    for x, z in (a, b):
        say("forceload %d,%d: %s" % (x, z, r.cmd("forceload add %d %d %d %d" % (x - 48, z - 48, x + 48, z + 48))))
        say("spawn: " + r.cmd("village spawnat %d %d 10" % (x, z)))
    for day in range(1, days + 1):
        for half in range(2):
            # A player near one village, then the other: the wide ground round one loaded,
            # the other's dropped.
            here, there = (a, b) if half == 0 else (b, a)
            r.cmd("forceload add %d %d %d %d" % (here[0] - 160, here[1] - 160, here[0] + 160, here[1] + 160))
            r.cmd("forceload remove %d %d %d %d" % (there[0] - 160, there[1] - 160, there[0] + 160, there[1] + 160))
            r.cmd("forceload add %d %d %d %d" % (there[0] - 48, there[1] - 48, there[0] + 48, there[1] + 48))
            sprint(r, 12000)
        r.cmd("save-all")
        for name, (x, z) in (("A", a), ("B", b)):
            status = r.cmd("execute positioned %d 64 %d run village status" % (x, z)).replace("\n", " ")
            m = re.search(r"— (\d+) folk.*?, (the \w+ Age)\.", status)
            built = re.search(r"Built: \[([^\]]*)\]", status)
            say("TWIN day %d village %s: %s folk, %s, built [%s]" % (day, name, m.group(1) if m else "?",
                m.group(2) if m else "?", built.group(1) if built else ""))
    say("village list: " + r.cmd("village list").replace("\n", " | "))
    say("PASS two villages ran %d days side by side" % days)


def epic_day(r, x, z, day, began, last_age, metrics_file="epic-metrics.jsonl"):
    """One game day of the long game: the dusk (every fifth with a raid), the night's beds,
    the morning, then the village in numbers. Returns the age it is in now."""
    if day % 5 == 1:
        # Dusk, with the things that come out at night among them (nothing spawns on
        # a server with nobody on it, so the watch would otherwise never be tested).
        sprint(r, 13500)
        raid(r, x, z)
        sprint(r, 3500)
    else:
        sprint(r, 17000)
    night(r, x, z, "the long game, night %d" % day)
    sprint(r, 7000)
    status = r.cmd("execute positioned %d 64 %d run village status" % (x, z)).replace("\n", " ")
    listing = r.cmd("village list")
    villages = [l for l in listing.split("\n") if l.strip().startswith("Village at")]
    world = sum(int(m) for m in re.findall(r"— (\d+) folk", listing))
    m = re.search(r"— (\d+) folk.*?, (the \w+ Age)\.", status)
    folk, age = (m.group(1), m.group(2)) if m else ("?", "?")
    built = re.search(r"Built: \[([^\]]*)\]", status)
    names = [b.strip() for b in built.group(1).split(",") if b.strip()] if built else []
    renown = re.search(r"Renown (\d+)", status)
    colonies = names.count("colony")
    community = re.search(r"Community: \d+ folk: ([^;]*)", status)
    beds = re.search(r"Beds: (\d+) of (\d+) have one, (\d+) asleep", status)
    say("DAY %d: %s folk, %s, %d buildings, renown %s, %d villages in the world (%d colonies from this one), %d folk in all, %s, %s, %.0f min"
        % (day, folk, age, len(names) - colonies, renown.group(1) if renown else "0",
           len(villages), colonies, world, community.group(1) if community else "no community line",
           ("beds %s/%s" % (beds.group(1), beds.group(2))) if beds else "no beds line",
           (time.time() - began) / 60.0))
    if age != last_age:
        say("AGE on day %d: %s" % (day, age))
        last_age = age
    # The day in numbers, one JSON line a day (epic-metrics.jsonl, and the history across
    # builds the workflow keeps): what a regression looks like is a curve that bends.
    stores = re.search(r"Stores: food (\d+) logs (\d+) stone (\d+) coal (\d+) iron (\d+) diamond (\d+) obsidian (\d+)", status)
    trades = re.search(r"Trades: ([^.]*)\.", status)
    tally = {}
    if trades:
        for n, t in re.findall(r"(\d+) ([a-z]+)", trades.group(1)):
            tally[t] = int(n)
    watch = re.search(r"(\d+) gates (?:open|shut), (\d+) posts", status)
    coins = re.search(r"Treasury: (\d+) coins", status)
    purses = re.search(r"Treasury: \d+ coins, (\d+) in purses", status)
    content = re.search(r"Contentment: (\d+)", status)
    made = re.search(r"homes for (\d+)(?: made up of (\d+))?", status)
    rank = re.search(r"Rank: a (\w+)", status)
    metrics = {
        "day": day, "folk": int(folk) if folk.isdigit() else None, "age": age,
        "buildings": len(names) - colonies, "renown": int(renown.group(1)) if renown else 0,
        "villages": len(villages), "colonies": colonies, "world": world,
        "bedded": int(beds.group(1)) if beds else None, "beds_made": int(made.group(1)) if made else None,
        "beds_planned": int(made.group(2)) if made and made.group(2) else None,
        "trades": tally, "guards": tally.get("guard", 0),
        "gates": int(watch.group(1)) if watch else None, "posts": int(watch.group(2)) if watch else None,
        "coins": int(coins.group(1)) if coins else None, "purses": int(purses.group(1)) if purses else None,
        "contentment": int(content.group(1)) if content else None,
        "minutes": round((time.time() - began) / 60.0, 1),
        "ms_per_tick": LAST_MSPT[0],
        "rank": rank.group(1) if rank else None,
    }
    if stores:
        for i, k in enumerate(["food", "logs", "stone", "coal", "iron", "diamond", "obsidian"]):
            metrics[k] = int(stores.group(i + 1))
    # The newer systems: the scouts' atlas, the elders' dealings, who is at the town's works.
    atlas = re.search(r"Scouts: atlas (\d+), explored (\d+)%", status)
    if atlas:
        metrics["atlas"], metrics["explored"] = int(atlas.group(1)), int(atlas.group(2))
    diplo = re.search(r"Diplomacy: ([^.]*)\.", status)
    if diplo:
        temper = re.search(r"temper (\w+)", diplo.group(1))
        metrics["temper"] = temper.group(1) if temper else None
        metrics["pacts"] = diplo.group(1).count(" pact")
        metrics["allies"] = diplo.group(1).count(" allied")
    works = re.search(r"Town works: ([^.]*)\.", status)
    if works:
        metrics["town_hands"] = 0 if works.group(1).startswith("nobody") else works.group(1).count(",") + 1
    wealth = re.search(r"Wealth: \{([^}]*)\}", status)
    if wealth:
        metrics["wealth"] = {k.strip(): int(v) for k, v in re.findall(r"(\w+)=(\d+)", wealth.group(1))}
    say("METRICS " + json.dumps(metrics, separators=(",", ":")))
    try:
        with open(metrics_file, "a") as out:
            out.write(json.dumps(metrics, separators=(",", ":")) + "\n")
    except OSError:
        pass
    say("STATUS " + status)
    if day % 5 == 0 or day == 1:
        report(r, x, z, "the long game, day %d" % day, compact=True)
        for line in villages:
            say("  " + line)
    return last_age


def epic(r, days, minutes, biome="plains"):
    """The long game. One village, founded the way a spawner founds one, left alone
    for as many game days as the machine will run in the time it has: does it keep
    evolving — ages, buildings, people, great works, colonies of its own — or does it
    stall somewhere along the way? One DAY line a game day says where it has got to."""
    setup(r)
    spot = where(r, "biome minecraft:" + biome) or where(r, "biome minecraft:forest")
    if spot is None:
        say("SKIP no %s within reach of this seed" % biome)
        return
    x, z = spot
    say("the long game: a village of 8 at %d, %d (%s), up to %d days or %d minutes" % (x, z, biome, days, minutes))
    say("spawn: " + r.cmd("village spawnat %d %d 8" % (x, z)))
    began = time.time()
    last_age = None
    for day in range(1, days + 1):
        last_age = epic_day(r, x, z, day, began, last_age)
        if time.time() - began > minutes * 60:
            say("STOP the time this run had is spent, after %d game days" % day)
            break
    say("village list at the end: " + r.cmd("village list").replace("\n", " | "))
    say("PASS the long game ran")


def hundred(r, first, last, minutes, biome="plains"):
    """A hundred days, in legs. A runner has six hours, and a growing town runs slower by
    the day, so the hundred days are run a leg at a time: each leg plays as many days as fit
    in the time it has, the server saves the world, and the next leg loads it and goes on
    from the day after. Where the village is and the day reached are kept beside the world."""
    spotfile, reached = "hundred-spot.txt", "hundred-reached.txt"
    if first <= 1:
        setup(r)
        spot = where(r, "biome minecraft:" + biome) or where(r, "biome minecraft:forest")
        if spot is None:
            say("SKIP no %s within reach of this seed" % biome)
            return
        x, z = spot
        say("the hundred days: a village of 8 at %d, %d (%s)" % (x, z, biome))
        say("spawn: " + r.cmd("village spawnat %d %d 8" % (x, z)))
        with open(spotfile, "w") as fh:
            fh.write("%d %d" % (x, z))
    else:
        for c in ("gamerule doDaylightCycle true", "gamerule doWeatherCycle false", "gamerule randomTickSpeed 15",
                  "gamerule doMobSpawning true", "difficulty normal"):
            r.cmd(c)
        with open(spotfile) as fh:
            x, z = (int(v) for v in fh.read().split())
        say("the hundred days, on from day %d: the village at %d, %d (game time %s)" % (first, x, z, r.cmd("time query gametime").strip()))
    began = time.time()
    last_age, done = None, first - 1
    for day in range(first, last + 1):
        last_age = epic_day(r, x, z, day, began, last_age, "hundred-metrics.jsonl")
        done = day
        with open(reached, "w") as fh:
            fh.write(str(done))
        if time.time() - began > minutes * 60:
            say("LEG the time this leg had is spent at day %d" % day)
            break
    say("village list: " + r.cmd("village list").replace("\n", " | "))
    say("relations: " + r.cmd("village relations").replace("\n", " | "))
    if done >= last:
        say("PASS the hundred days ran: day %d reached" % done)


def takeover(r):
    setup(r)
    spot = where(r, "structure minecraft:village_plains") or where(r, "structure minecraft:village_taiga")
    if spot is None:
        say("SKIP no vanilla village within reach of this seed")
        return
    x, z = spot
    say("vanilla village near %d, %d" % (x, z))
    # Generating a village's chunks is what fires the join event for its
    # villagers from inside the chunk's own loading — the case that could freeze.
    say("forceload: " + r.cmd("forceload add %d %d %d %d" % (x - 96, z - 96, x + 96, z + 96)))
    sprint(r, 400)
    left = r.cmd("execute if entity @e[type=minecraft:villager]")
    say("villagers left after 400 ticks: " + left)
    report(r, x, z, "takeover")
    sprint(r, 4000)
    left = r.cmd("execute if entity @e[type=minecraft:villager]")
    say("villagers left after 4400 ticks: " + left)
    report(r, x, z, "takeover +4000")
    say("PASS the server generated a vanilla village and kept running")


def natural(r):
    """Nobody founds anything: the world is left to its own config, which lets
    villages appear as ground is generated — what a player who explores will
    meet. The world says where it will look (/village anchors); the ground
    round the first few of those spots is loaded a chunk at a time, the way
    walking up to them would, and whatever the game founds is reported."""
    setup(r)
    sites = r.cmd("village anchors")
    say("the world's village sites: " + sites.replace("\n", " | "))
    spots = [(int(a), int(b)) for a, b in re.findall(r"site at (-?\d+), (-?\d+)", sites)][:4]
    if not spots:
        say("SKIP this seed has no village sites within reach")
        return
    for x, z in spots:
        say("forceload %d,%d: %s" % (x, z, r.cmd("forceload add %d %d %d %d" % (x - 48, z - 48, x + 48, z + 48))))
        sprint(r, 300)
    listing = ""
    for upto in (600, 3000, 12000, 24000):
        sprint(r, upto)
        say("== natural villages after %d more ticks (tick %d)" % (upto, gametime(r)))
        listing = r.cmd("village list")
        for line in listing.split("\n"):
            say("  " + line)
    # One of them, close up.
    m = re.search(r"Village at (-?\d+), (-?\d+)", listing)
    if m:
        x, z = int(m.group(1)), int(m.group(2))
        report(r, x, z, "the first natural village")
        say("PASS the world founded a village of its own and it kept running")
    else:
        say("FAIL no village was founded at any of the world's own sites")


def restart1(r):
    """The first half of "did it survive a restart": found a village, let a day
    pass, save the world and let the server be stopped."""
    setup(r)
    spot = where(r, "biome minecraft:plains")
    if spot is None:
        say("SKIP no plains within reach of this seed")
        return
    x, z = spot
    say("village goes at %d, %d" % (x, z))
    say("spawn: " + r.cmd("village spawnat %d %d 12" % (x, z)))
    done = 0
    for upto in (300, 4500, 24000):
        sprint(r, upto - done)
        done = upto
        report(r, x, z, "before the restart, tick %d" % done)
    say("village list before: " + r.cmd("village list").replace("\n", " | "))
    say("saved: " + r.cmd("save-all flush"))
    say("STOPPING: the world is on disk")


def restart2(r):
    """The second half: the same world, a new server. Did the village come back
    — the same people, the same age, the same buildings — and does it carry on?"""
    for c in ("gamerule doDaylightCycle true", "gamerule doWeatherCycle false",
              "gamerule randomTickSpeed 15", "weather clear 1000000"):
        r.cmd(c)
    say("village list straight after the restart: " + r.cmd("village list").replace("\n", " | "))
    sprint(r, 300)
    listing = r.cmd("village list")
    say("village list 300 ticks on: " + listing.replace("\n", " | "))
    m = re.search(r"Village at (-?\d+), (-?\d+)", listing)
    if not m:
        say("FAIL the village did not come back")
        return
    x, z = int(m.group(1)), int(m.group(2))
    report(r, x, z, "after the restart")
    done = 0
    for upto in (4500, 24000):
        sprint(r, upto - done)
        done = upto
        report(r, x, z, "a day after the restart" if upto == 24000 else "after the restart +%d" % upto)
    say("village list at the end: " + r.cmd("village list").replace("\n", " | "))
    say("PASS the village came back from a restart and carried on")


def main():
    scenario = sys.argv[1] if len(sys.argv) > 1 else "plains"
    r = Rcon()
    say("connected; scenario %s" % scenario)
    try:
        if scenario == "takeover":
            takeover(r)
        elif scenario == "restart1":
            restart1(r)
        elif scenario == "restart2":
            restart2(r)
        elif scenario == "natural":
            natural(r)
        elif scenario == "crowd":
            # A hundred settlers, the cap: is the server still a server?
            village(r, "plains", count=100, compact=True)
        elif scenario == "pair":
            # What a survival player actually starts with: one or two spawner items, not
            # twelve folk. Can two settlers found a village that grows?
            village(r, "forest", count=2, days=5, label="pair forest", raided=False)
        elif scenario == "twin":
            twin(r)
        elif scenario == "modpack":
            # The mod among others (the workflow puts JEI and Jade in the mods folder).
            village(r, "plains", days=2, label="plains with JEI and Jade")
        elif scenario == "hundred":
            first = int(sys.argv[2]) if len(sys.argv) > 2 else 1
            last = int(sys.argv[3]) if len(sys.argv) > 3 else 100
            minutes = int(sys.argv[4]) if len(sys.argv) > 4 else 300
            hundred(r, first, last, minutes)
        elif scenario == "epic":
            days = int(sys.argv[2]) if len(sys.argv) > 2 else 60
            minutes = int(sys.argv[3]) if len(sys.argv) > 3 else 300
            epic(r, days, minutes)
        elif scenario == "long":
            # Ten game days in a forest: does the village grow up — ages, houses,
            # births — or only get through three days?
            village(r, "forest", count=12, days=10, label="long forest")
        else:
            village(r, scenario)
    except (EOFError, OSError) as e:
        say("DIED: the server went away (%s)" % e)
        raise SystemExit(3)
    try:
        r.cmd("stop")
    except Exception:
        pass


if __name__ == "__main__":
    main()
