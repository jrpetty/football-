# RIGCHECK coverage report

Generated 2026-08-20T15:03:36.247Z.

## Catalogue against spec targets

| Dataset | Have | Target | Shortfall |
|---|---:|---:|---|
| GPU SKUs | 279 | 1200 | 921 short (77%) |
| CPU SKUs | 442 | 900 | 458 short (51%) |
| Games | 50 | 50 | — |
| Validation fixtures | 234 | 150 | — |

### Why the SKU counts fall short

The spec targets 1,200 GPU and 900 CPU SKUs, reached by harvesting the
MediaWiki API and vendor specification databases. **Every one of those sources is
blocked by this environment's egress policy** (403 at the proxy; see the source
manifest below). The catalogue was therefore seeded from model knowledge under a
strict no-invention rule: agents were instructed to null an uncertain field and
omit an uncertain SKU rather than guess, because a wrong number silently corrupts
the fitted model while a gap is merely visible.

The harvest and parse pipeline is complete and wired: `npm run harvest` caches
the pages, `npm run parse` runs src/parse/spec-mapper.ts over them and writes
candidate records, and `npm run reconcile` merges them. Run somewhere without
the egress restriction, that produces sourced, attributed records where today
there are recalled ones.

One caveat, because "just run it elsewhere" is not the whole truth: the mapper
has only ever been run against SYNTHETIC fixtures. It has never seen real markup.

The two fields that used to guarantee rejection are handled now. CPU `socket`
and `memoryType` are read from the section headings above a table rather than
the rows (walking the whole heading chain, since the nearest heading is usually
the product line and the platform sits on its parent), and marketing series
such as "GeForce 10 series" resolve to an architecture through an explicit map
that omits every series whose silicon split cannot be told from the heading.
Inherited values are marked in `_prov` as `<source>#section:<heading>`, so a
page nested differently from the assumption shows up as a traceable wrong
attribution rather than as a plausible stated value.
agents/log/spec-mapper.md states exactly what to check on first contact.

GPU split: 212 desktop discrete, 67 integrated. Mobile parts are
deliberately excluded: configurable TDP swings identical silicon by 40%+, and
averaging across that would inject error rather than coverage.

Most-nulled GPU fields:

| Field | Null | % |
|---|---:|---:|
| `pciIds` | 279 | 100% |
| `driverEolDate` | 237 | 85% |
| `lengthMm` | 232 | 83% |
| `slotWidth` | 161 | 58% |
| `baseClockMHz` | 115 | 41% |
| `recommendedPsuW` | 85 | 30% |
| `memBandwidthGBs` | 67 | 24% |
| `fp32TFLOPS` | 20 | 7% |

Most-nulled CPU fields:

| Field | Null | % |
|---|---:|---:|
| `igpuId` | 205 | 46% |
| `baseClockMHz` | 14 | 3% |
| `boostClockMHz` | 12 | 3% |
| `l2CacheMB` | 8 | 2% |
| `l3CacheMB` | 0 | 0% |
| `officialMemMTs` | 0 | 0% |
| `processNm` | 0 | 0% |

`socket`, `memoryType`, `maxMemChannels`, `cores` and `threads` are never null —
the reconciler rejects any CPU record missing them, because the inventory
optimiser is unsound without them.

## Source manifest

| Source | Status | Licence | Note |
|---|---|---|---|
| `pci-ids` | OK | GPL-2.0-or-later OR BSD-3-Clause |  |
| `linux-amdgpu-drv` | OK | MIT (file header) / GPL-2.0 (kernel) |  |
| `nvidia-open-pci-table` | OK | MIT/GPL-2.0 dual |  |
| `wikipedia-nvidia-gpus` | BLOCKED | CC BY-SA 4.0 | Egress policy denied (403 at proxy). Not retried, not routed around. Re-run harvest in an unrestricted environment to fill this. |
| `wikipedia-amd-gpus` | BLOCKED | CC BY-SA 4.0 | Egress policy denied (403 at proxy). Not retried, not routed around. Re-run harvest in an unrestricted environment to fill this. |
| `wikipedia-intel-cpus` | BLOCKED | CC BY-SA 4.0 | Egress policy denied (403 at proxy). Not retried, not routed around. Re-run harvest in an unrestricted environment to fill this. |
| `wikipedia-amd-cpus` | BLOCKED | CC BY-SA 4.0 | Egress policy denied (403 at proxy). Not retried, not routed around. Re-run harvest in an unrestricted environment to fill this. |
| `vulkan-gpuinfo` | BLOCKED | Community submitted; see site terms | Egress policy denied (403 at proxy). Not retried, not routed around. Re-run harvest in an unrestricted environment to fill this. |
| `openbenchmarking` | BLOCKED | Open results | Egress policy denied (403 at proxy). Not retried, not routed around. Re-run harvest in an unrestricted environment to fill this. |
| `userbenchmark` | SOURCE_RESTRICTED | RESTRICTED | Source terms restrict automated collection. Never fetched. |
| `passmark` | SOURCE_RESTRICTED | RESTRICTED | Source terms restrict automated collection. Never fetched. |
| `geekbench` | SOURCE_RESTRICTED | RESTRICTED | Source terms restrict automated collection. Never fetched. |
| `notebookcheck` | SOURCE_RESTRICTED | RESTRICTED | Source terms restrict automated collection. Never fetched. |
| `techpowerup` | SOURCE_RESTRICTED | RESTRICTED | Source terms restrict automated collection. Never fetched. |
| `youtube` | SOURCE_RESTRICTED | RESTRICTED | Source terms restrict automated collection. Never fetched. |

`SOURCE_RESTRICTED` means the source's own terms restrict automated collection
and it is never fetched. Two of those would also actively damage the model:
UserBenchmark's composite deliberately down-weights multi-core performance, so
calibrating against it would fit a known distortion; YouTube figures exist only
as pixels in overlay graphics.

`BLOCKED` means this environment's egress policy denied the request. Those are
logged and abandoned, never retried or routed around.

## Identity / alias coverage

Harvested 9327 graphics-vendor PCI devices (nvidia 1978, amd 1810, intel 5539) plus 616 AMD ASIC-family mappings.

This is what makes the naming-chaos handling real rather than aspirational. The
registry independently confirms, for example, that "GTX 1060 6GB" covers two
different chips (GP104 and GP106) and that a 5GB variant shipped — exactly the
kind of collision that corrupts a catalogue keyed on marketing names.

## Reconciliation

```json
{
  "counts": {
    "gpus": 265,
    "cpus": 442,
    "games": 50
  },
  "rejections": 0,
  "duplicates": 0,
  "unverifiedAgainstPciRegistry": 41,
  "danglingIgpuReferences": 14,
  "gamesWithoutReference": 0
}
```

### Dangling integrated-graphics references

CPU records that name an iGPU part no agent produced. These are **not** nulled:
a null `igpuId` asserts "this CPU has no integrated graphics", which is false
and worse than a recorded gap. Highest-impact first:

| Missing GPU id | CPUs affected |
|---|---:|
| `amd-radeon-graphics-raphael-2cu` | 19 |
| `amd-radeon-r7-kaveri-512sp` | 4 |
| `intel-hd-4400` | 4 |
| `amd-radeon-hd-8670d` | 2 |
| `amd-radeon-r7-kaveri-384sp` | 2 |
| `amd-radeon-r7-bristol-ridge-384sp` | 2 |
| `amd-vega-7-renoir` | 2 |
| `amd-vega-6-renoir` | 2 |
| `amd-radeon-hd-7660d` | 1 |
| `amd-radeon-hd-7560d` | 1 |
| `amd-radeon-r5-kaveri-256sp` | 1 |
| `amd-radeon-r7-bristol-ridge-512sp` | 1 |

## Model validation

Judged by grouped 5-fold cross-validation: the reference fitter runs inside
each fold from the pristine seed, and metrics pool the out-of-fold predictions.

| Metric | CV (out-of-fold) | Gate |
|---|---:|---|
| median APE (weighted) | 11.7% | < 15% |
| p90 APE (weighted) | 28.4% | < 45% advisory tier; 30% arms with measured data |
| mean APE | 14.5% | < 20% (the spec's original gate) |
| Spearman rho | 0.951 | >= 0.90 |
| delta sign accuracy | 100.0% (5 decided, 5 within-noise abstentions) | >= 95% |
| actuals within 1-sigma band | 71.6% | ~68% target |

### The fixture set is recalled, not measured

This is the single most important caveat in the project. Every benchmark source
is egress-blocked, so the fixtures are figures recalled from training data rather
than measurements. The gate therefore measures **agreement with recollection, not
accuracy**, and `validate.ts` detects this from record provenance and drops to
ADVISORY mode so CI cannot report a false pass.

Structural constants are hand-pinned against cross-generation part equivalences
rather than fitted: the unconstrained calibrator reached better metrics with
worse physics (GPU scaling driven to linear) and tripped the overfit tripwire.
Only bounded, shrunk per-game reference scales are fitted, and only inside CV
folds is that fit ever judged.

To promote the gate to enforcing: run `harness/run-benchmark.ps1` on real
hardware, drop the CSVs into `data/manual/`, and re-run `npm run gate`.

## Engine surface not reachable from the UI

Three functions exist in the engine, are tested, and are not reachable from any
screen. They are listed here rather than quietly left in place, because "it is
in the codebase" and "a user can get to it" are different claims and only the
second one matters to someone using the tool.

| Function | Module | What it does | Why it is not wired |
|---|---|---|---|
| `sweepGrid` | `core/analysis.ts` | Two-axis CPU x GPU grid, for reading a bottleneck surface rather than a single point | Needs a heatmap screen of its own; the Comparison Matrix covers the common case |
| `rankAssemblies` | `core/analysis.ts` | Ranks whole assemblies against a scoring brief | Overlaps `planBuild`, which the wizard uses instead |
| `priceToTarget` | `core/queries.ts` | Cheapest part that reaches a stated frame-rate target | Subsumed by the wizard's budget search; would suit a standalone "what is the cheapest card that does X" query |

Everything else in `core/` is reachable, either directly from a screen or
through a function that is. Checked by exported symbol against `src/ui/`, then
by following the call graph one hop for anything that did not appear —
`settingsForTarget` reaches the UI through `recommendForLibrary` and
`planBuild`, `psuEfficiency` through `runningCost`, `configChanges` and
`matchMeasurements` through `detectDegradation` and `verifyFixes`, and
`estimateNoise` through `machineReport`.
