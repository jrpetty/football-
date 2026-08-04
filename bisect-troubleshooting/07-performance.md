# Performance and Lag

## Vocabulary

| Term | Meaning | Healthy |
|---|---|---|
| **TPS** | Ticks per second. Minecraft targets one tick every 50ms. | **20** (above 18 is fine) |
| **MSPT** | Milliseconds per tick — how long a tick actually takes. | **under 50** |
| **Server lag** | Low TPS. Everyone affected equally. | — |
| **Client lag** | Low FPS. One player. Not a server problem. | — |
| **Network lag** | High ping, rubber-banding. TPS is fine. | — |

**TPS and MSPT are the same measurement from opposite ends.** MSPT is the more
useful one: TPS clamps at 20 and hides headroom, while MSPT at 45ms tells you
you're nearly out of budget even though TPS still reads a healthy 20.

**Diagnose which lag you have first.** Ask the player: is the *world* frozen
(mobs still, blocks slow to break) or is the *picture* stuttering? Frozen world
with smooth camera = server. Stuttering picture = client. Getting this wrong
sends people mod-hunting over a GPU problem.

## spark

The profiler. Works on Paper, Purpur, Spigot, Fabric, Forge, NeoForge. Install
it as a plugin or mod, matching your platform.

### Commands

| Command | Does |
|---|---|
| `/spark tps` | Instant TPS and MSPT |
| `/spark profiler start` | Begin sampling |
| `/spark profiler stop` | Stop and get a web report link |
| `/spark profiler --timeout 60` | Auto-stop after 60s |
| `/spark profiler start --only-ticks-over 60` | **Only sample ticks over 60ms** — the tool for intermittent spikes |
| `/spark healthreport` | CPU, memory, disk, TPS summary |
| `/spark heapsummary` | Memory usage by class — **the leak hunter** |
| `/spark gc` | GC statistics |

~30 seconds of sampling is enough for a useful picture. **Profile while the
problem is happening** — a profile of a healthy server tells you nothing.

### Reading the report

Three sections do the work:

1. **Platform info** — Java version, memory, CPU. Sanity-check your assumptions.
2. **Sampler** — methods ranked by CPU time, heaviest first. **Start here.** If a
   single mod or plugin sits at the top, you likely have your answer.
3. **Flame graph** — visual call-stack breakdown. Width = time. Wide plateaus are
   the expensive paths.

What you're looking for: a **package name that isn't Minecraft's** high in the
sampler. That's your suspect.

Interpretation traps:

- `--only-ticks-over` reports are about **spikes**, not baseline. Don't read
  percentages from them as overall load.
- High time in GC methods means a **memory** problem, not a CPU one — go to
  [04-java-memory.md](04-java-memory.md).
- Time in `park`/`wait`/`epoll` is **idle**, not load. A server doing nothing
  spends most of its time there.

## Fast wins

Ranked by benefit-to-risk. Apply from the top.

### 1. view-distance and simulation-distance

In `server.properties`. **The highest-impact settings that exist.**

| Setting | Range | Default | Controls |
|---|---|---|---|
| `view-distance` | 1–32 | 10 | Chunks **sent** to clients — mostly bandwidth and memory |
| `simulation-distance` | 3–32 | 10 | Chunks **actively ticked** — mobs, crops, redstone, machines |

**`simulation-distance` is where the CPU goes.** The technique: keep
`view-distance` reasonable so the world still looks big, and drop
`simulation-distance` lower. Players get the view without the tick cost.

Sensible modded starting point:

```
view-distance=8
simulation-distance=5
```

Vanilla or plugin servers tolerate 10/8 comfortably.

Chunk cost scales roughly with the **square** of the distance — going 12 → 8
isn't a 33% saving, it's closer to 55%.

### 2. Entity load

Usually the second-largest cost, and often the actual cause on a "randomly
laggy" server.

- Mob farms and item accumulation from broken hoppers
- Bred animal herds people forget about
- Item entities that never despawn
- On Paper: tune `spawn-limits` and `ticks-per` in `bukkit.yml`, and the entity
  settings in `paper-world-defaults.yml`
- On modded: check for chunk-loading machines running out of sight

`/spark profiler` will show entity ticking clearly if this is it.

### 3. Chunk loading

Chunkloaders, quarries, mining machines, and world-border-less exploration all
force constant chunk generation — expensive and often invisible in-game.

Pre-generating the world with **Chunky** eliminates generation lag for
already-explored area. Set a **world border** to bound the problem permanently.

### 4. JVM flags

Apply Aikar's flags. See [04-java-memory.md](04-java-memory.md#aikars-flags).

### 5. Performance mods

Server-safe options for modded: **FerriteCore** (memory), **ModernFix**
(startup + memory), **Canary**/**Radium** (general optimisation), **AllTheLeaks**
/ **MemoryLeakFix** (leaks), **Alternate Current** (redstone).

Note: **Sodium, Iris, Rubidium, Embeddium and friends are client-only.** Adding
them to a server crashes it. See [06](06-crash-reports.md#client-mods-on-a-server).

## The watchdog

`server.properties`:

```
max-tick-time=60000
```

Milliseconds a single tick may take before the watchdog concludes the server has
hung and kills it. Default 60 seconds. `-1` disables it entirely.

Signature:

```
A single server tick took 60.00 seconds (should be max 0.05)
Considering it to be crashed, server will forcibly shutdown.
```

Result: **exit 143**.

**This is a symptom.** Something made one tick take a minute. Common causes:

- First-boot worldgen on a heavy modpack (legitimate and temporary)
- A huge chunk load or teleport into ungenerated terrain
- A mod deadlock
- Thrashing GC from a nearly-full heap — [04](04-java-memory.md)
- A genuinely overloaded shared node

**Disabling the watchdog is a last resort, not a fix.** It converts "server
restarts" into "server hangs indefinitely", which is strictly worse for
diagnosis. Legitimate use: temporarily, during known-slow first-boot worldgen on
a large modpack. Raise it, get through the slow phase, put it back.

## Shared-hosting reality

On a shared plan, node neighbours affect you. The honest diagnostic:

**If spark shows your server is mostly idle but TPS is still poor, the CPU
you're being scheduled on is contended.** That's a legitimate ticket — attach
the spark report showing low self-load against low TPS. Vague "my server is
laggy" tickets go nowhere; a spark link showing idle-but-slow gets escalated.

Check the status page first for known node incidents.

## Baseline expectations

Rough guidance for what a plan should carry.

| Plan RAM | Realistic |
|---|---|
| 2 GB | Vanilla/Paper, ~5 players, no mods |
| 4 GB | Paper with plugins, ~10–15 players; small modpack (~50 mods), 2–3 players |
| 6 GB | Mid modpack (~150 mods), 3–5 players |
| 8 GB | Large modpack, 5–10 players |
| 12 GB+ | Very large packs (All the Mods-class), 10+ players |

Modded Minecraft is far more **single-thread CPU** bound than RAM bound. Adding
RAM to a CPU-bound server changes nothing — a genuinely common and expensive
misdiagnosis. If spark shows one thread pinned and memory comfortable, more RAM
is not your answer.
