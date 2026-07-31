# Equivalence test

`src/` contains a deterministic stand-in for the small slice of the Minecraft / NeoForge /
Flowing Fluids API that `FloodEngine` touches, plus a fake world (terrain, a lake, leaf canopy,
`#floodworld:rain_blocked` blocks, a no-rain biome band, sheltered columns and an unloaded-chunk
boundary) and a harness that drives 1600 ticks across four config scenarios:

| scenario | exercises |
|---|---|
| `A-defaults` | stock config |
| `B-no-threshold` | `saturationThreshold=0`, `downhillSearchRadius=0`, `maxFloodHeight=1` |
| `C-cell-cap` | `maxActiveCells=40` — the cap warning path |
| `D-wide-scan` | `downhillSearchRadius=12`, the widest downhill scan |

Each run logs every `modifyFluidAmountAtPos` call, the full contents of the three persisted maps,
the per-tick "was the save marked dirty" flag, the logged cap warning, and the result of
`/floodworld clear`. `run.sh` diffs the original jar's output against the optimised build's.

This is not a Minecraft test — the fakes are not Minecraft. It pins down that the *rewrite* is
behaviour-preserving; the API bindings are covered separately by matching bytecode descriptors
against the original jar.
