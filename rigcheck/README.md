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
— and every record is tagged `model-knowledge`.

**What harvesting does and does not give you.** `npm run harvest` fetches and
caches every declared source, `src/parse/wikitable.ts` — the rowspan/colspan
expander — turns a specification page into aligned rows, and
`src/parse/spec-mapper.ts` maps those rows onto `GpuRecord` / `CpuRecord` fields
(column identification by header alias, unit normalisation, id slugging, variant
splitting) and reports every row and column it could not use. `npm run parse` runs
both, writing candidates to `agents/out/parsed-*.json` for the reconciler.

The catch is that **the mapper has never been run against a real page**: its tests
run against synthetic fixtures written from memory of what these tables look like,
because the sources are egress-blocked here. The arithmetic, the never-fabricate
rules and the reporting are real; the header vocabulary is a hypothesis. So
harvesting still does not replace the seeded catalogue — the first real run needs
a human to read the report before anything is merged. `agents/log/spec-mapper.md`
lists, in order, exactly what to check.

### Accessibility

`npm run a11y` drives a running preview and measures rather than assuming:
computed contrast against the effective background, form labels, table headers,
accessible names on icon-only controls, heading structure, landmarks, and focus
rings from real Tab presses. Current state is clean on all twelve screens —
0 contrast failures, 10/10 focus rings.

It found one defect worth naming: `--faint`, the colour most of the secondary
text in the app is written in, measured **3.30:1** against the lightest surface
and failed WCAG AA on every screen. It is now `#7c8994` at 4.53:1. The audit
also caught the input focus rules removing the outline outright, which made
tabbing through the app a guessing game.

Two of its own first-run results were false positives and both are documented
in the script: an element inside a `display: none` ancestor still reports a
colour, and calling `.focus()` deliberately does not match `:focus-visible`.

### What has actually been verified

The catalogue is checked against physics and against an external registry on
every build, not just against itself:

| check | result |
|---|---|
| memory bandwidth vs bus width vs memory type | 212/212 physically plausible |
| frame buffer buildable from real chip densities | 212/212, 4 asymmetric (all genuine Nvidia designs) |
| CPU cores/threads/socket/memory invariants | 442/442 |
| GPU identity in the PCI ID Repository (live) | 156/158 confirmed real devices |

Every one of those checks flagged records on its first run and every time the
CHECK was wrong, not the data — the bounds assumed modern parts on a catalogue
reaching back to 2010, and one regex bug reported 57 real cards as fictitious.
The corrected bounds are deliberately wide: the job is to catch the impossible,
not to second-guess the unusual.

**Prices are the weakest data here and are handled separately.** The seed
tables are recalled figures, never sourced. `data/prices-observed/` is the lane
for real market observations — sold prices, not asking prices, with sample size
and date — and anything imported there overrides the seed and is marked
`sourced` in the UI against the `recalled` default. See that directory's README
for how to gather them.

**The validation fixtures are recalled figures, not measurements.** The gate
therefore measures agreement with recollection, not accuracy, and `validate.ts`
detects this from provenance and drops to ADVISORY mode so CI cannot report a
false pass. `COVERAGE.md` states every count, gap and reason.

## Architecture

```
src/core/       types · constants · gates · indices · engine · queries · analysis
                physics (power/thermal/noise/latency) · detect · fit · catalogue
                evidence (source-quality ladder, shared by the gate and the UI)
                presets · advisor (settings for a target) · planner (budget to build)
                health · fixguides · history (before/after, degradation) · peers
                running (PSU efficiency curve, tariff, cost of ownership) · builddiff
src/parse/      rowspan-aware table parser · spec-table → record mapper (fixture-tested)
src/ui/         React app: 12 screens, command palette, mobile drawer, print sheet
scripts/        harvest → parse → reconcile → import-manual → import-prices
                → calibrate → validate → audit → coverage
                a11y-audit.mjs (contrast, labels, focus, landmarks — needs a preview)
harness/        PresentMon benchmark runner, emits straight into data/manual/
data/           catalogue · aliases · fixtures · pricing · prices-observed
                manual · measured · calibration · validation
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
pooling the out-of-fold rows is what made the numbers stable enough to gate on.

| Metric | CV (out-of-fold) | Gate |
|---|---:|---|
| median APE (weighted) | 11.7% | < 15% |
| p90 APE (weighted) | 28.4% | < 45% advisory tier¹ |
| mean APE | 14.5% | < 20% (the spec's original gate) |
| Spearman ρ | 0.951 | ≥ 0.90 |
| delta sign accuracy | 100% (5 decided, 5 abstained²) | ≥ 95% |
| actuals within 1σ band | 71.6% | ~68% target |

**Read that table with one caveat, and it is not a small one.** The fixture
corpus grew from 161 to 234 by adding 22 games and the 3440x1440 resolution
that the model had never been fitted against, and every metric improved. That
is a real generalisation signal — the resolution scaling in particular held to
a 12.6% mean error on twelve ultrawide fixtures with nothing anchoring that
resolution beforehand. But the fixtures and the model's priors were written
from the same source: recollection. Agreement between them is partly
self-consistency, not independent validation, and no amount of it substitutes
for a measurement. That is exactly what the Model Health screen exists to keep
in front of you.

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

**Start** (what are you trying to do) → **Build a PC** (guided) → **Build Analyser** → **Comparison Matrix** (n-way) →
**Upgrade Advisor** (Pareto frontier + knee) → **Inventory Optimiser** →
**Machine Report** → **Trade Desk** → **System Health** → **Identify** →
**Data Explorer** → **Model Health**.

**Build a PC** is the guided path for someone who does not yet know what to
compare: screen, games, budget, build, settings. It recommends the CHEAPEST
build that meets the goal rather than the most expensive one that fits, and
answers "what would the rest of the budget buy" separately — usually the answer
is better-looking frames rather than more of them. "Meets the goal" includes a
quality floor, because without one the cheapest build clearing 120fps at 4K is
a mid-range card running everything at low.

**System Health** answers the question people actually have about a machine they
own. It opens with what the detector reads and what it never reads, in two
columns, before any button — and the type-it-in path reaches the same report
without running anything. It separates certain configuration findings
(single-channel memory, XMP never enabled, a card in a chipset x4 slot) from
measured ones, which inherit the model's uncertainty and are stated against an
explicit band. Recoverable performance is compounded, not summed. The report
ends with what it could NOT check, because a list of findings that stops looks
complete.

It also remembers. Saving a check makes the next one able to answer two things a
snapshot cannot: **did the fix work** — the measured gain against the gain that
was predicted, and when those disagree by more than half the prediction's size
it says so, because a failed prediction is evidence about the model and hiding
it would be the one thing this project exists to avoid — and **is it getting
slower**, attributed only as far as the evidence allows. A configuration change
is blamed before physics is, since someone who pulled a memory stick has a
slower machine for a known reason and sending them to clean a heatsink would be
wrong. Comparisons run on matched measurements only; different settings are
reported as unmatchable rather than silently differenced.

Peer comparison is deliberately blunt about having nothing to compare against:
there is no server and no telemetry, so the only peers are imported
measurements, and the corpus ships empty. It says that rather than substituting
the model's own prediction and calling it a peer.

Every number opens a panel showing each model term, its value, its confidence and
its source. The Upgrade Advisor plots the price/performance Pareto frontier rather
than connecting every point — two cards at the same price with different
performance are not a step along a curve — marks dominated parts hollow, and calls
out saturation, which is usually the real finding: if the top of the curve is flat
within the model's uncertainty, the *other* component is the wall.

**Model Health** is the screen that argues against the rest of them. It shows how
much of the gate's weight is a measurement versus a recollection, how many harness
rows would arm the strict tail gate, accuracy drift across recorded validation
runs with the thresholds drawn on, and where coverage is thinnest — including the
values the tool offers but has never validated at all. Today that is 3440x1440
(zero fixtures) and 29 of the 50 catalogue games. It reads its numbers from
`src/core/evidence.ts`, the same module the gate uses, so it cannot quote a
weighting the gate disagrees with.
