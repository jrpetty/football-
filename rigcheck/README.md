# RIGCHECK

A build comparison and performance modelling platform. The unit of analysis is a
**build** — CPU + GPU + memory + storage + resolution + refresh target + price.
Builds are first-class objects: shareable by URL and diffable n-way. Bottleneck
analysis is one view computed over a build; comparison is the product.

This is an independent workspace inside a repository it shares with an unrelated
football app. It has its own `package.json`, `tsconfig`, and a CI workflow scoped
by path so the two never interfere.

```bash
cd rigcheck
npm install
npm run dev          # the app
npm test             # unit tests, incl. the rowspan-parser fixtures
npm run gate         # manual import + validation gate
```

## Read this first: what is real and what is seeded

The pipeline is complete. The **specification data is not sourced**, because every
source the design depends on is blocked by this environment's egress policy:

| Source | Status |
|---|---|
| `pci.ids` (PCI ID Repository) | **harvested** — 9,327 graphics-vendor devices |
| Linux `amdgpu` driver tables | **harvested** — 616 ASIC-family mappings |
| NVIDIA open-kernel PCI table | **harvested** — matches by device class, so yields no per-SKU list (recorded as such) |
| Wikipedia MediaWiki API | egress-blocked (403) |
| Vulkan Hardware Database | egress-blocked (403) |
| OpenBenchmarking | egress-blocked (403) |
| UserBenchmark, PassMark, Geekbench, Notebookcheck, TechPowerUp, YouTube | **never fetched** — their terms restrict automated collection |

So the catalogue (265 GPUs, 442 CPUs, 50 games) was seeded from model knowledge
under a strict no-invention rule — null an uncertain field, omit an uncertain SKU
— and every record is tagged `model-knowledge`. Running `npm run harvest && npm run
parse && npm run reconcile` in an unrestricted environment replaces the seed with
sourced, attributed data and requires no code change.

**The validation fixtures are recalled figures, not measurements.** The gate
therefore measures agreement with recollection, not accuracy, and `validate.ts`
detects this from provenance and drops to ADVISORY mode so CI cannot report a
false pass. `COVERAGE.md` states every count, gap and reason.

## Architecture

```
src/core/       types · constants · gates · indices · engine · queries · catalogue
src/parse/      rowspan-aware table parser (unit-tested against fixtures)
src/ui/         React app: 5 screens, dense dark instrument styling
scripts/        harvest → parse → reconcile → import-manual → calibrate → validate → coverage
harness/        PresentMon benchmark runner, emits straight into data/manual/
data/           catalogue · aliases · fixtures · pricing · manual · calibration
agents/         per-agent output and logs from the acquisition fleet
```

### The model

Hard capability gates run **before** estimation and short-circuit it. A mesh-shader
failure returns `WILL_NOT_RUN`, not a low number — the distinction is the point.

CPU-bound and GPU-bound frame rates are estimated independently, then combined
with a power-mean soft minimum, which captures the loss when both components
contend. `Math.min()` misses that entirely.

Design decisions worth knowing about, each of which changed a result materially:

- **The CPU index is a vector, not a scalar** — `{throughput, cacheEndowment,
  latencyScore, threadCapacity}`, weighted per workload archetype. The ratio
  between two CPUs changes sign by game; a scalar fits the average and is wrong at
  both tails, which is where the interesting queries live.
- **X3D cache is derived from the stacked CCD, not the package total.** A 7950X3D
  has 128MB across two chiplets but the stack sits on one. Dividing 128 by 16 would
  rank it *below* a 7800X3D, inverting the answer in exactly the cache-sensitive
  titles the vector model exists for.
- **Architecture efficiency corrects nominal FLOPs.** Ampere and RDNA 3 double-count
  FP32 lanes games cannot saturate. Without the correction an RTX 3080 looks twice
  as fast as a 2080 Ti instead of ~1.3x.
- **Ray tracing is a separate index and an explicit query axis.** RT throughput per
  TFLOP is roughly 0.6–0.7x Ampere's on RDNA 2/3 while raster is competitive, so
  raster alone systematically overrates AMD in ray-traced titles.
- **iGPU bandwidth is a property of the build, not the part.** Integrated graphics
  draw from system RAM, so single-channel memory halves the *GPU's* bandwidth. They
  also get no Infinity Cache multiplier despite sharing an architecture name with
  cards that have one.
- **The VRAM cliff is piecewise, not a regression term,** and degrades 1% lows far
  harder than averages — that asymmetry is its signature.
- **Every estimate carries an uncertainty band, and deltas smaller than the
  combined band are struck through.** Reporting "3% faster" when the model error is
  ±18% is the opaque confidence this tool replaces.

### Two corrections to the specification

1. **`p ≈ 4` does not produce the stated ~10% contention loss.** The power mean at
   equal inputs gives `2^(-1/p)`; at `p=4` that is a 15.9% loss. `p = ln2 / -ln(0.9)
   = 6.58` is the value that yields 10%. 6.58 is the prior, fitted per resolution.
2. **A bare MAPE gate measures the wrong thing.** The product sells ordering, so the
   gate is four metrics — median APE, p90 APE, Spearman ρ, and delta sign accuracy —
   computed on log-FPS and weighted by each fixture's stated confidence, the same
   weighting the import lane applies to measured rows.

## Current validation

Judged by **grouped 5-fold cross-validation**: fixtures are partitioned by silicon
family (the same parts never straddle folds), the per-game reference fitter runs
inside each fold from the pristine seed, and metrics pool the out-of-fold
predictions. A single fixed holdout of ~34 rows swung ±4 points between runs;
pooling 159 out-of-fold rows is what made the numbers stable enough to gate on.

| Metric | CV (out-of-fold) | Gate |
|---|---:|---|
| median APE (weighted) | 12.9% | < 15% |
| p90 APE (weighted) | 34.9% | < 45% advisory tier¹ |
| mean APE | 16.9% | < 20% (the spec's original gate) |
| Spearman ρ | 0.934 | ≥ 0.90 |
| delta sign accuracy | 100% (5 decided, 5 abstained²) | ≥ 95% |
| actuals within 1σ band | 68.6% | ~68% target |

¹ The tail gate is two-tier: 45% while the fixture corpus is recalled, arming to
the strict 30% automatically once measured fixtures outnumber recalled ones. The
p90 of recalled fixtures is dominated by the recollections themselves — the same
rows swung 30–56% between fits that all improved the median — so the strict tier
against recalled data would only reward fitting noise.

² A pair counts toward sign accuracy only when the model makes a confident
directional call (predicted delta outside the pair's combined uncertainty).
Inside it the product reports "within noise" — the matrix greys the delta — which
for a 5% true gap is a correct answer, not a coin flip. Abstention can't be gamed
by inflating bands: the within-band calibration figure would drift visibly above
its ~68% target.

The gate runs in **advisory mode** — every fixture is a recalled figure, not a
measurement, so this measures agreement with recollection. Structural constants
(architecture efficiencies, scaling exponents) are hand-pinned against robust
cross-generation part equivalences (GTX 1080 Ti ≈ RTX 3060, RTX 2080 Ti ≈ RTX
3070, RTX 3080 ≈ 1.75× 3060) rather than fitted: an unconstrained calibrator run
reached 6.7% train median APE by driving GPU scaling to linear and re-inflating
the high end — better metrics, worse physics — and its train/holdout gap fired
the 8% overfit tripwire. The calibrator now fits only bounded per-game reference
scales (shrunk toward 1 for thin games) and **refuses to write global constants**
while the fixtures are recalled; proposals land in `proposed-constants.json`.

Known modelling limitation, on display in the abstention count: per-game vendor
lean (RDR2 favouring Radeon under Vulkan, Forza favouring Arc less than reviews
suggest) is deliberately not fitted from one or two fixtures per game. Measured
data will let a bounded per-game vendor term earn its place.

## Getting real data in

The two lanes that make the model better are deliberately frictionless:

1. **`harness/run-benchmark.ps1`** — a PresentMon runner for Windows test machines.
   Detects hardware, enforces a fixed warm-up/capture protocol, discards the first
   run, reports the median of the rest, warns when run-to-run spread exceeds 5%, and
   writes rows straight into the import schema.
2. **`data/manual/*.csv`** — documented schema in `data/manual/README.md`. Runs on
   every build. Rows missing fingerprint columns are ingested at reduced weight
   rather than dropped; every rejection is reported with a reason.

Then `npm run gate`. Once measured fixtures outnumber recalled ones the gate
promotes itself from advisory to enforcing, and `npm run calibrate` will write.

## Screens

**Build Analyser** → **Comparison Matrix** (n-way) → **Upgrade Advisor** (Pareto
frontier + knee) → **Inventory Optimiser** → **Data Explorer**.

Every number opens a panel showing each model term, its value, its confidence and
its source. The Upgrade Advisor plots the price/performance Pareto frontier rather
than connecting every point — two cards at the same price with different
performance are not a step along a curve — marks dominated parts hollow, and calls
out saturation, which is usually the real finding: if the top of the curve is flat
within the model's uncertainty, the *other* component is the wall.
