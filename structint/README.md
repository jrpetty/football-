# Structural Integrity — water pressure

Standing water pushes on the walls holding it back, harder the deeper it is. Build a dam too thin
and it bursts at the bottom, where the water is pushing hardest.

`structuralintegrity-1.0.0.jar` is a drop-in replacement for the original jar. Same mod id, same
version, same save data. Everything that was there before behaves identically — this only adds.

## What was added, and what was not touched

Only `Config.class` was rebuilt (to carry the new keys). Every other original class in the jar is
byte-identical; the feature lives in five new classes and three new block tags, and hooks itself in
through its own `@EventBusSubscriber`, so the span solver, the collapse queue and the snow load
system are untouched.

**No new mod dependency.** Water depth is read from vanilla fluid states. Flowing Fluids and
FloodWorld are not required — they just make the water *move*, which is where this gets interesting
(see the bottom of this file).

## The rule

Structural Integrity's existing model is about vertical support: reach from an anchor, horizontal
moves cost span, stacking up is free. That means a 30-tall, 1-thick wall on bedrock is already
perfectly stable no matter what — so water pressure could not be expressed as a span penalty the
way snow load is. It is a **second, independent failure channel** that runs beside the span solver.

For each **player-placed** block touching water:

```
load     = depth × pressurePerBlockOfDepth × bodyFactor
capacity = (sum of resistance back through the wall)  +  bracing  ×  archMultiplier
stress   = load / capacity        →  ≥ 1 bursts,  ≥ 0.75 visibly seeps
```

**Only player-placed blocks are ever examined or broken.** Natural terrain is the abutment: a wall
run that reaches unmanaged ground is *anchored* and never fails. That is why keying a dam into the
valley walls works, and why the ocean floor never dissolves.

### Depth

`depth` is the blocks of water standing at and above the wet face. Real hydrostatic pressure is
p = ρgh — linear in depth, and **independent of how wide the reservoir is**. A 30-deep pond pushes
on your dam exactly as hard, per block, as a 30-deep ocean.

### How much water is behind it

You asked for the size of the body to matter, bounded to the part near the dam. It does, as an
explicitly non-physical gameplay term:

```
bodyFactor = clamp(1 + 0.25 × log2(sampledVolume / 256), 1 .. 2)
```

The body is flood-filled outward **from the wall** and stopped at `sampleCap` (4096 blocks), so a
coastal wall measures the ocean *near it* rather than walking the whole ocean. A pond gives 1.0; a
decent lake ~1.5; anything ocean-sized caps at 2.0. Set `body.maxFactor = 1.0` for pure textbook
depth-only pressure.

Two honest notes. First, this is not physics — it stands in for the stored energy of a big
reservoir and for the fact that a long dam carries more total force. Second, your instinct about
the 100-block-long ocean is right about the *outcome* for a better reason than the factor: a
100-wide dam has a hundred times the chances to contain one weak column, and the first block that
goes starts the cascade.

### Material strength

Blocks of water depth one block of that material holds back with nothing behind it. These reuse the
existing structural tags, so anything already tagged as stone/metal/etc. is covered.

| material | tag | span (existing) | water resistance |
|---|---|---:|---:|
| dirt, clay, mud | `#structint:structural_dirt` | 1 | 2 |
| generic, plain glass | *(fallback)* | 2 | 3 |
| wood | `#structint:structural_wood` | 4 | 4 |
| stone family | `#structint:structural_stone` | 7 | 8 |
| concrete, reinforced beam | `#structint:structural_reinforced` | 12 | 16 |
| metal, heavy girder | `#structint:structural_metal` | 20 | 24 |
| obsidian, the mod's beams | `#structint:pressure_sealing` | — | 32 |
| glass panes, bars, tinted | `#structint:pressure_brittle` | — | 2 |

### Thickness, buttresses, curvature

- **Thickness** is the wall run back from the wet face. It simply adds up. Because load grows with
  depth, a dam naturally wants to be thick at the base and thin at the crest — the real gravity-dam
  profile falls out of the model rather than being scripted.
- **Buttresses** behind the wall lend their *surplus* sideways: `0.5 × surplus / distance`, out to
  3 blocks, capped at 64. A uniform wall shares nothing because there is no surplus. Ribs work;
  widely spaced ribs work much less well.
- **Arch action**: a wall bowed *into* the water gets `× (1 + 0.25 × curve)` up to `× 2`, measured
  from how far the wall sits back on both flanks. This is why a real arch dam gets away with being
  thin, and it is the single biggest lever a player has.

### Water on both sides cancels

Only the *differential* is carried. A flooded chamber is safe; an air-filled one at the same depth
is not. That is what turns an underwater base into a build problem: the outer shell holds back the
sea, and deliberately flooding a room — or an airlock — is a real engineering answer.

## Worked example: your dam

Wooden dam, 1 block thick, 30 blocks of water, ocean-sized body (factor 2.0).

```
base block:  load = 30 × 1.0 × 2.0 = 60      capacity = 4  (one wood)
             stress = 15.0                    →  bursts
```

It fails from the bottom up and only survives where the water is about 2 deep — so essentially all
of it goes, starting at the deepest point, exactly as you described. The first burst lets water
through, the block behind becomes the new wet face, and the breach walks itself open.

Three builds that hold the same 30 blocks (base load 60):

1. **Stone gravity dam, tapered.** 8 thick at the base (8 × 8 = 64), 4 thick at mid-depth
   (32 ≥ load 30), 1 at the crest. Huge — which is what a straight masonry dam against an ocean
   should be.
2. **Concrete arch dam.** 2 thick throughout, bowed 4 blocks into the water:
   `(16 + 16) × 2.0 = 64 ≥ 60`. A quarter of the material. Curving it is the win.
3. **Stone slab on girder buttresses.** 2 stone thick (16) with heavy-girder piers running back
   behind it: a pier column reaches the 12-block cap, and at distance 1 lends
   `0.5 × (112 − 16) / 1 = 48` → total 64 ≥ 60. Buttresses must be every 2 blocks at this depth;
   at distance 2 the bonus halves to 24 and the panel between them fails.

For a lake rather than an ocean (factor ~1.5, load 45) all three get noticeably cheaper — 6 thick
stone, or a 2-thick concrete arch with room to spare.

An air-filled base at ocean depth 20 (load 40) needs 5 thick stone, 3 thick concrete, or 2 of
obsidian. Flood a room and its walls stop caring.

## Seeing whether it will hold, before it doesn't

Three ways, in increasing order of effort.

**It tells you on its own.** At 75% of capacity a block starts visibly seeping water and keeps
doing it. A dam that is about to go looks wet before it goes. Turn this off with `effects = false`.

**`/structint pressure`** — look at any block and get the whole sum:

```
Water pressure at -412, 63, 208 — OVER CAPACITY, this will burst (750.0% of capacity)
  push   60.0  =  30 deep x 2.0 body  (sampled 4096 water blocks behind it)
  hold   8.0   =  thickness 8
  Short by 52.0. Add thickness behind it, put a buttress within 3 blocks, bow the wall
  into the water, or use a stronger material — stone holds 8 per block, concrete 16, metal 24.
```

If nothing bears on it, it says so and tells you which of the three reasons applies — nothing
touching it, water on both sides cancelling, or the run behind it anchored into natural ground.

**`/structint pressure survey`** — paints every player-placed block within 32 blocks by how hard it
is working and keeps repainting for 20 seconds so you can walk the dam and watch:

| particle | meaning |
|---|---|
| green sparkle | comfortable |
| water drip | working |
| orange flame | straining, will seep |
| red lava | over capacity, will burst |

and reports the tally plus the worst block's coordinates. Both are permission level 2, and the
survey radius and duration are config keys. Everything is server-side particles, so nobody needs a
client mod.

The one thing missing is a strength number on the item tooltip — the mod's existing
`TooltipHandler` shows span, and water resistance belongs next to it. Say the word and I'll add it.

## How it is measured, and what it costs

A budgeted sweep near players, cloned from `SnowLoadScanner` — water level changes fire no block
event we can rely on, and a reservoir filling up changes the load without anyone touching anything.

- every `scanIntervalTicks` (40) — examine up to `scanBudget` (2048) player-placed blocks
- within `scanRadiusChunks` (6) of a player, round-robin across chunks so a big base is covered
  over several sweeps rather than in one spike
- the cheap rejection is first: a block with no water on any of its six faces costs six reads and
  stops. Most builds never touch water at all.
- depth probes are memoised per column and body fills per body **for the whole sweep**, so a
  100-column dam face costs 100 column walks and *one* bounded flood fill, not one per block
- `failuresPerSweep` (24) staggers a breach into something you can watch — and run from
- a survey is bounded by the same `scanBudget` and stops painting when it runs out

Fail-safes match the existing style: every walk is bounded (`maxThickness` 12, `maxHead` 64,
`sampleCap` 4096, `arch.scanRange` 6), unloaded chunks stop a fill rather than dragging chunks in,
and `enableBreak = false` keeps the warnings while breaking nothing.

All of it is under `[waterPressure]` in the server config, with `enable = false` to switch the
feature off entirely.

## What it will not do

- **Residual unverified API surface.** Every call the new code makes is diffed against the set of
  descriptors the two original jars (built against real Minecraft) already prove. Twelve are not
  covered by that set: the five `ParticleTypes` fields, `Blocks.AIR`, and six used only by the
  command. All twelve are now wrapped so a wrong one degrades instead of crashing — effects switch
  themselves off and log once, and command registration and execution are caught whole. The
  simulation itself uses only proven descriptors.
- **It has never been run on an actual Minecraft server.** No Minecraft artifacts were reachable
  from the build environment, so this is verified by compiling against signature-exact stubs and
  diffing the emitted method descriptors against the original jar — not by playing it. Test on a
  copy of a world first.
- Arch action is measured from local curvature only. It does not verify the arch actually reaches
  abutments, so a curved wall floating in space still gets the bonus (the span solver will have
  opinions about it separately).
- Buttress sharing is one-directional and distance-weighted, not a real stiffness solve. A slab
  spanning between piers is approximated, not modelled.
- Pressure is evaluated per block against its own wall run. There is no global check that the dam
  as a whole could slide or overturn — the model is rupture, not stability.
- Partial water levels (Flowing Fluids amounts 1–7) count as a full block of depth.
- The body factor is not physics. It is there because you asked for reservoir size to matter, and
  it is capped and configurable for exactly that reason.

## The FloodWorld link

Nothing here requires FloodWorld. But with FloodWorld installed, rain raises the water behind your
dam while you are standing on it — the load climbs, the wall starts seeping at 75%, and if the
storm keeps going it lets go. That is the loop the two mods make together, and it needs no bridge
mod: FloodWorld moves the water, this measures what the water is doing to your build.
