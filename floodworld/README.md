# FloodWorld 1.0.0 — performance pass

Rain-driven flooding for NeoForge 1.21.1, riding on the Flowing Fluids partial-water API.
This directory holds the **optimised** build of the mod plus the source it was built from.

`floodworld-1.0.0.jar` is a drop-in replacement for the original jar. Same mod id, same version,
same config file, same save format — an existing world keeps its floods.

## What changed

Only `FloodEngine` and `FloodWorldFluids` were touched. `FloodWorld`, `Config`, `FloodSavedData`
and `FloodWorldCommand` are byte-identical to the original build (their `.class` files were reused
verbatim when assembling the jar; the `.java` here is a faithful reconstruction for future edits).

The simulation itself is unchanged. Same sampling, same thresholds, same random stream, same
water levels, same persistence. What changed is how many times the engine asks the chunk source
for the same chunk.

### 1. One chunk lookup per chunk instead of one per column

The downhill scan in `puddle()` walked `(2r+1)²` columns and called
`chunkSource.hasChunk()` **and** `Level.getHeight()` (itself another `hasChunk` + `getChunk`)
for every single one — 81 columns at the default radius, up to 625 at the maximum. Those columns
span at most a handful of chunks. The engine now resolves each chunk once through a small cache
with a last-hit fast path and reads the heightmap directly off it.

`sampleColumn()` got the same treatment: it used to cost ~9 chunk lookups (heightmap, `isLoaded`,
`canSeeSky`, biome, block state, and up to five fluid states for the neighbour check). Those now
come off one resolved chunk.

The scan compares raw `ChunkAccess` heightmap values instead of going through `Level.getHeight`
per column. Those two differ only by a constant, so the winning column is identical; only the
winner's absolute Y is read back through the level — one lookup per puddle rather than 81.

### 2. Cheapest-first rejection

The per-sample guards were reordered: block-tag check (a section read) now runs before
`canSeeSky` (a light-engine query) and `Biome.getPrecipitationAt` (biome sampling plus a
temperature lookup). Every guard is a side-effect-free read, so the set of columns that survive
is unchanged — the expensive ones just run less often.

### 3. No allocation on the read paths

`new BlockPos(...)`, `.below()`, `.north()`/`.south()`/`.east()`/`.west()` allocated on every
sample and every neighbour probe. Reads now go through two reused `MutableBlockPos` instances.
Positions handed to the external Flowing Fluids API are still immutable copies.

### 4. Smaller things

- Config values are read once per pass instead of once per sample / per deposit.
- `containsKey` + `get` pairs collapsed to a single lookup (the maps' default return values
  already serve as absence sentinels).
- The recession snapshot and its rebuild tick live in one map entry — one probe, no `Long`
  boxing. This also fixes a small leak where the tick map was never cleared alongside the
  snapshot.
- `setDirty()` is called once per recession pass instead of once per drained cell.
- `decayPass` uses fastutil's `fastIterator()`, which stops allocating an `Entry` per column.
- A raining dimension with no players no longer loads its save data or runs a pass.

## Measured effect

From the differential test below, over 1600 ticks with four players (stock config):

| | original | optimised |
|---|---:|---:|
| chunk-source lookups¹ | ~2,080,000 | ~75,000 |
| `BlockPos` allocations | 61,162 | 8,019 |
| config value reads | 24,020 | 6,613 |

With `downhillSearchRadius=12` the gap widens to roughly **15.6M → 136k** chunk-source lookups,
because that is where the original's per-column cost bites hardest.

¹ Counting what each call costs in real Minecraft: `Level.getHeight` is `hasChunk` + `getChunk`,
`getBlockState`/`getFluidState` are one `getChunk` each. The harness counts the mod's calls; the
table applies those multipliers.

## Verifying it behaves identically

`equivalence-test/` runs the original jar and the optimised build against the same deterministic
fake world and diffs **every** observable effect — each water write, the full contents of the
three persisted maps, the per-tick dirty flag, the cap warning, and `/floodworld clear`.

    ./equivalence-test/run.sh <original.jar> <optimised.jar> <fastutil.jar>

Across four config scenarios (36,300 water writes) the two builds are byte-for-byte identical.

Note what this does and does not cover. The fakes are not Minecraft, so the test pins down that
the *rewrite* is behaviour-preserving, not that the API bindings are right. Those were checked
separately by diffing the compiled method descriptors against the original jar: everything the
optimised classes call either appears in the original's constant pool or is long-stable public
API (`MutableBlockPos.set`, `BlockPos.getX/getY/getZ(long)`, `ChunkPos.asLong`,
`Level.getChunk(int,int)`, `LevelChunk.getBlockState`, `ChunkAccess.getHeight`,
`BlockState.getFluidState`). The class file version is unchanged (Java 21).

**This has not been run on an actual Minecraft server** — no Minecraft artifacts were reachable
from the build environment. Test it on a copy of your world before putting it on anything you
care about.

## Building

`build.gradle` uses ModDevGradle. Set `flowing_fluids_version` in `gradle.properties` to the
Modrinth version id of the Flowing Fluids build you target, then:

    ./gradlew build

The pinned `neoforge_version` and the ModDevGradle plugin version could not be verified against
`maven.neoforged.net` from here — adjust them to whatever your setup already uses.

## Pre-existing issues, not addressed

These are original behaviour and were deliberately left alone, since the brief was performance
with no functional change:

- `/floodworld clear` clears `baseY` wholesale while leaving flood entries for unloaded chunks in
  the map, so those columns lose their reference elevation and the height cap can ratchet upward.
- `assets/floodworld/lang/en_us.json` is dead — the command builds literal strings, and the
  `status` entry has one fewer format argument than the message it is meant to describe.
- `decayPass` decays every saturation column on a global timer, despite the config comment
  promising "ticks without a rain hit".
- `baseY` has no size cap, unlike `flood` (`maxActiveCells`) and `saturation` (decay).
- Rain over open ocean raises sea level up to `maxFloodHeight` near players, since the
  `MOTION_BLOCKING` heightmap counts water.
