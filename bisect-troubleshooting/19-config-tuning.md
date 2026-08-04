# Config Tuning

Paper-family servers. For modded, most gains come from
[performance mods](16-modpacks.md#performance-mods-worth-adding) and
[view/simulation distance](07-performance.md#1-view-distance-and-simulation-distance)
instead.

## File hierarchy

Paper builds on Spigot, which builds on Bukkit, which builds on vanilla. **All
four config layers apply.**

| File | Layer | Key settings |
|---|---|---|
| `server.properties` | Vanilla | view/simulation distance, max-tick-time, online-mode |
| `bukkit.yml` | Bukkit | spawn limits, tick rates, chunk GC |
| `spigot.yml` | Spigot | entity activation/tracking ranges, mob-spawn-range |
| `paper-global.yml` | Paper | server-wide Paper settings |
| `paper-world-defaults.yml` | Paper | **per-world defaults — where most wins are** |

Realistic expectation: careful tuning recovers roughly **2–5 TPS** on a busy
server without players noticing gameplay changes. It won't rescue a server that's
fundamentally undersized.

## Change one thing at a time

Tuning is where people break servers by pasting a wall of "optimised" settings
from a blog. **Change a few related settings, restart, observe, repeat.**
Otherwise a helpful change and a harmful one cancel out and you learn nothing.

Always profile with [spark](07-performance.md#spark) first. Tuning without
measuring is guessing.

## server.properties

```properties
view-distance=8
simulation-distance=5
max-tick-time=60000
network-compression-threshold=256
sync-chunk-writes=false
```

`view-distance` and `simulation-distance` are **by far the highest-impact
settings available.** Chunk cost scales roughly with the square of the distance —
12 → 8 is closer to a 55% saving than 33%.

`sync-chunk-writes=false` moves chunk saving off the main thread. Standard on
Paper.

## bukkit.yml

```yaml
spawn-limits:
  monsters: 50        # default 70
  animals: 8          # default 10
  water-animals: 3
  water-ambient: 10
  ambient: 1

ticks-per:
  animal-spawns: 400
  monster-spawns: 4
  autosave: 6000      # 5 min; 0 disables (only if you back up another way)

chunk-gc:
  period-in-ticks: 400
```

Spawn limits are **per player**, so they multiply with player count. Lowering
monsters from 70 to 50 on a 20-player server removes up to 400 potential mobs.

## spigot.yml

```yaml
world-settings:
  default:
    entity-activation-range:
      animals: 16       # default 32
      monsters: 24      # default 32
      raiders: 48
      misc: 8
    entity-tracking-range:
      players: 48
      animals: 32
      monsters: 48
      misc: 32
    mob-spawn-range: 4  # default 8
    merge-radius:
      item: 3.5
      exp: 4.0
    ticks-per:
      hopper-transfer: 8
      hopper-check: 8
```

**Entity activation range** is the big one: entities outside it barely tick.
Dropping animals from 32 to 16 cuts their tick cost dramatically with almost no
visible difference.

**`mob-spawn-range: 4`** (from 8) concentrates spawns near players — fewer total
mobs, same gameplay feel.

**`merge-radius`** combines dropped items and XP orbs. Effective against item-lag
from farms; too high and item pickup feels odd.

**Hoppers** are notorious. Raising `hopper-transfer`/`hopper-check` slows them
slightly and helps meaningfully on servers with big sorting systems.

## paper-world-defaults.yml

Where modern Paper tuning lives.

```yaml
entities:
  spawning:
    per-player-mob-spawns: true
    despawn-ranges:
      monster:
        soft: 28        # default 32
        hard: 96        # default 128
  armor-stands:
    tick: false

chunks:
  max-auto-save-chunks-per-tick: 8    # default 24
  prevent-moving-into-unloaded-chunks: true

collisions:
  max-entity-collisions: 2

hopper:
  disable-move-event: true
  ignore-occluding-blocks: true

misc:
  redstone-implementation: ALTERNATE_CURRENT
```

The high-value entries:

| Setting | Why |
|---|---|
| `per-player-mob-spawns: true` | Singleplayer-style spawning instead of Bukkit's random algorithm. **Fairer and cheaper.** |
| `redstone-implementation: ALTERNATE_CURRENT` | Much faster redstone, same behaviour |
| `armor-stands.tick: false` | Armour stands are entities that tick for no benefit. Safe unless a plugin animates them. |
| `max-auto-save-chunks-per-tick: 8` | Spreads save load, reducing periodic spikes |
| `hopper.disable-move-event: true` | Big win — **unless** a plugin listens to hopper events |
| `despawn-ranges` lowered | Fewer lingering mobs |

## paper-global.yml

```yaml
chunk-system:
  io-threads: -1        # -1 = auto
  worker-threads: -1

misc:
  max-joins-per-tick: 3

packet-limiter:
  all-packets:
    max-packet-rate: 500.0
```

The packet limiter is basic protection against packet-spam crash exploits.

## Anti-Xray

`paper-world-defaults.yml`:

```yaml
anticheat:
  anti-xray:
    enabled: true
    engine-mode: 2
    hidden-blocks: [copper_ore, deepslate_copper_ore, gold_ore, deepslate_gold_ore,
                    iron_ore, deepslate_iron_ore, coal_ore, deepslate_coal_ore,
                    lapis_ore, deepslate_lapis_ore, mossy_cobblestone, obsidian,
                    chest, diamond_ore, deepslate_diamond_ore, redstone_ore,
                    deepslate_redstone_ore, clay, emerald_ore,
                    deepslate_emerald_ore, ender_chest]
```

**Engine mode 1** hides ores behind stone — cheap, less effective.
**Engine mode 2** generates fake ores — more effective, costs more CPU and
bandwidth.

Only worth enabling on public servers where X-ray cheating actually matters. It
is not free, and on a whitelisted friends server it's pure overhead.

## Modded

Paper configs don't exist. Your levers:

1. `view-distance` / `simulation-distance` in `server.properties`
2. [Performance mods](16-modpacks.md#performance-mods-worth-adding) — FerriteCore,
   ModernFix, Canary
3. Per-mod configs in `config/` — many have entity limits and tick throttles
4. [Aikar's flags](04-java-memory.md#aikars-flags)
5. Chunk pre-generation with Chunky
6. A world border

Some packs ship their own performance config; check the pack's docs before
hand-tuning.

## Sanity checklist

After tuning, verify:

- [ ] Mobs still spawn at a reasonable rate
- [ ] Farms still function
- [ ] Redstone contraptions still work
- [ ] Item pickup feels normal
- [ ] Hoppers move items acceptably
- [ ] TPS actually improved — measure with `spark tps`

If gameplay broke, revert the last change. **Keep a copy of your working configs
before tuning** — the most common outcome of aggressive tuning is a subtly broken
server nobody can diagnose three weeks later.
