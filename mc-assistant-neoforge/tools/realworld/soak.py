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


def village(r, biome, count=12, compact=False):
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
    for upto in (300, 1500, 4500, 12000, 24000, 48000, 72000):
        sprint(r, upto - done)
        done = upto
        report(r, x, z, "%s day %.1f" % (biome, done / 24000.0), compact)
    say("PASS the server ran three game days on %s" % biome)


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


def main():
    scenario = sys.argv[1] if len(sys.argv) > 1 else "plains"
    r = Rcon()
    say("connected; scenario %s" % scenario)
    try:
        if scenario == "takeover":
            takeover(r)
        elif scenario == "crowd":
            # A hundred settlers, the cap: is the server still a server?
            village(r, "plains", count=100, compact=True)
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
