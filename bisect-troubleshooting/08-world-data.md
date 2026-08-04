# Worlds, Corruption, and Backups

## World layout

| Server type | Layout |
|---|---|
| **Vanilla / Forge / NeoForge / Fabric** | One `world/` folder containing `region/`, `DIM-1/` (Nether), `DIM1/` (End), plus modded dimensions |
| **Paper / Spigot / Purpur** | **Three separate folders:** `world/`, `world_nether/`, `world_the_end/` |

This difference bites people during backups and migrations — on a plugin server,
backing up only `world/` silently loses the Nether and End.

Key files:

| Path | Contains |
|---|---|
| `world/region/r.X.Z.mca` | Terrain. Each file is a 32×32 chunk region. |
| `world/entities/` | Entity data (1.17+, separated from terrain) |
| `world/poi/` | Points of interest — villager workstations, portals |
| `world/level.dat` | World metadata, seed, spawn, gamerules. **Corruption here breaks everything.** |
| `world/level.dat_old` | Automatic previous copy — **your first recovery option** |
| `world/playerdata/*.dat` | Per-player inventory and position, by UUID |
| `world/data/` | Maps, raids, structure references |

## Corruption

### Causes

Nearly always an **unclean write**:

- A crash or kill (exit 137 / 139) mid-save
- Running out of disk space while saving
- OOM during a save
- Forcibly stopping instead of using `stop`
- Two processes touching the same world — e.g. a zombie process from a failed
  restart

### Symptoms

| Symptom | Likely |
|---|---|
| Crash when a player enters a specific area | Corrupt chunk |
| Holes of void in the terrain | Missing/deleted chunks |
| `Failed to save chunk` in the log | Active corruption or disk problem |
| `Chunk file at X,Z is missing` | Missing region data |
| Server won't start, `level.dat` in the trace | Corrupt world metadata |
| One player crashes on join, others fine | Corrupt `playerdata` for that UUID |
| Entities vanished from an area | Corrupt `entities/` region |

### Correlating a crash to a location

Crash reports include:

```
-- Affected level --
  All players: 1 total; [ServerPlayer['Name'/123, l='ServerLevel[world]', x=1234.5, y=64.0, z=-5678.9]]
```

Convert player coordinates to a region file:

```
chunk_x  = floor(x / 16)        region_x = floor(chunk_x / 32)
chunk_z  = floor(z / 16)        region_z = floor(chunk_z / 32)
→ world/region/r.<region_x>.<region_z>.mca
```

Worked example: `x=1234.5, z=-5678.9` → chunk (77, −355) → region (2, −12) →
`r.2.-12.mca`.

## Repair

**Back up before every one of these. Download it — a copy on the same server
isn't a backup.**

### Level 1 — restore from backup

Fastest, safest, always try first. **Backups** tab → restore the newest
pre-corruption snapshot.

Remember a panel backup restores the **whole server directory**, so re-apply any
config fixes made since.

### Level 2 — `level.dat_old`

If `level.dat` is the problem: delete or rename `level.dat`, copy
`level.dat_old` to `level.dat`, start. Costs at most a few minutes of world
metadata.

### Level 3 — delete corrupt chunks

The affected chunks regenerate as fresh vanilla terrain. **Player builds in
those chunks are gone** — this is a real cost, not a free fix.

**MCA Selector** (GUI) — download the world, open `region/`, locate the chunk by
coordinate, delete, re-upload. Good when you know roughly where the problem is.

**Minecraft Region Fixer** (CLI) — scans and repairs in bulk:

```
python regionfixer.py -p 4 --delete-corrupted /path/to/world
```

Also does `--replace-corrupted` against a backup copy, which preserves builds
where the backup has good data.

### Level 4 — singleplayer round-trip

For chunks that are damaged rather than unreadable:

1. Download `world/region/`.
2. Drop the region files into a local singleplayer world's `region/`.
3. Open it in creative, fly over every affected chunk so the client rewrites
   them.
4. Quit, copy the region files back to the server.

Slower, but it can save builds that deletion would destroy.

### Level 5 — player data

If exactly one player crashes on join, delete their `world/playerdata/<uuid>.dat`.
They respawn at world spawn and **lose their inventory and position**. Also check
`world/advancements/<uuid>.json` and `world/stats/<uuid>.json`.

Get the UUID from the crash report or `usercache.json`.

## Backup strategy

Panel backups are free. Use them properly.

**Automate:** **Schedules / Automation** → daily backup, plus a restart. Nightly
is the minimum defensible cadence.

**Manual snapshot before:** modpack installs and updates, loader version
changes, **Reinstall**, bulk mod changes, chunk deletion, and any config change
you're unsure about.

**Keep an off-panel copy.** Download a full backup periodically. Panel backups
live on the same infrastructure as the server — a node-level incident can take
both. This applies to anything you'd be upset to lose.

**Retention:** panel backup slots are limited by plan. Rotate — keep one recent,
one a week old, one a month old. Corruption is often not noticed for days, and
three same-day backups of an already-corrupt world are worth nothing.

## Safe stops

**Always stop with the `stop` command or the panel's Stop button.** Both trigger
a full world save and clean shutdown.

**Never** use Kill unless the server is genuinely wedged. Kill sends SIGKILL —
no save, no flush, and a real chance of corrupting whatever was mid-write. Kill
during a save is the single most reliable way to corrupt a world.

If a stop hangs, wait for the panel's timeout before killing. It's usually
mid-save, which is exactly when killing does damage.

## Migrating a world

1. Stop both servers.
2. Download `world/` (plus `world_nether/`, `world_the_end/` on plugin servers).
3. Upload to the destination — **SFTP, not the web File Manager**, for anything
   large.
4. Match `level-name` in `server.properties` to the folder name.
5. Start.

Cross-type moves have caveats. Vanilla/modded → Paper needs the nested
dimensions split into `world_nether/` and `world_the_end/`. Modded → vanilla
drops every modded block and item in the world, usually leaving holes.
