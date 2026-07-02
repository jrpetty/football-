# Maze Runner — NeoForge adventure-map mod (Minecraft 1.21.1)

An authored *Maze Runner* world driven by `maze_config_v2.json` (bundled in the
jar as the single source of truth): a **96×96-chunk** (1536×1536 block) maze of
**unbreakable walls (y61–100)** on a flat floor (y60) under a **barrier ceiling
(y101)**, with the **16×16-chunk Glade** at the centre. Nothing exists outside
the maze but the seven exit pads.

## The loop

- **Custom clock** — 60 real-minute days, 30 real-minute nights (vanilla
  daylight cycle is disabled; the mod drives `dayTime` itself).
- **Dawn (tick 1000):** the four Glade doors grind open.
- **Dusk (tick 12500):** the doors seal shut.
- **Night (tick 18000):** the maze **shifts** — only the ~200 predefined toggle
  points whose state differs between today's and tomorrow's layout animate up
  or down, layer by layer, with grinding stone sounds. The old exit seals, the
  new one opens, and its portal lights up.
- **7 fixed layouts** (all pre-validated solvable) rotate on a schedule that is
  **shuffled once per world from the world seed** — every world plays the same
  7 mazes in a different fixed order, repeating weekly.
- **Escape:** `/maze start` starts a single server-wide timer; the first player
  to run into the **active exit portal** stops it and the time is announced.
  Deaths respawn at the Box in the Glade; the timer never pauses.
- **Supply caches:** ~100 chests in dead-end cells with helpful-but-optional
  loot, rerolled at the start of each 7-day cycle.
- Standard survival everywhere — but the walls are bedrock-grade, so the maze
  cannot be mined through or blown up. Hostile mobs roam the corridors at
  night. (Custom Grievers are a later phase.)

**The Glade is a fresh slate** — flat grass with nothing in it, ready to be
hand-built in-game (the Box, the Deadheads, the lake…). World spawn and death
respawn are pinned to its exact centre (768, 61, 768). Hostile mobs are
automatically purged inside the Glade — it is safe ground; the maze is not.

Quality-of-life on top of the spec:

- A **boss-bar clock**: "Day N — doors seal in 41:23" by day, "Night N — the
  Maze shifts. Dawn in 12:41" by night.
- A red **"doors seal soon"** warning shortly before dusk.
- A persistent **best-time world record**, announced when beaten.
- `/maze tp` — teleport an op to the day's active exit portal for testing.

## Install & play

1. Install [NeoForge](https://neoforged.net/) for **Minecraft 1.21.1** and drop
   `mazerunner-1.0.0.jar` into `mods/`.
2. Create a new world → World Type → **"Maze Runner"**.
3. You spawn at the centre of the empty Glade. Doors open shortly after the
   world starts.

## Commands (op level 2)

| Command | Effect |
| --- | --- |
| `/maze start` / `/maze stop` | Start / manually stop the escape timer |
| `/maze status` | Day, layout, doors, clock, timer, week schedule |
| `/maze validate <1-7>` | BFS-verify a layout is solvable (debug) |
| `/maze section` | Which of the 8 maze sections you're standing in |
| `/maze tp` | Teleport to the day's active exit portal (debug) |
| `/maze skip` | Jump to next dawn — fires dusk/shift/portal swap on the way (debug) |

## Build from source

Java 21. First build needs `maven.neoforged.net`, `piston-meta.mojang.com`,
`piston-data.mojang.com`, `libraries.minecraft.net`.

```bash
cd mazerunner-neoforge
./gradlew build          # → build/libs/mazerunner-1.0.0.jar
./gradlew runClient      # dev client
```

Maze dataset: regenerate with `generate_maze.py` (attached to the project
spec) and replace `src/main/resources/data/mazerunner/maze/maze_config_v2.json`.
