# RIGCHECK coverage report

Generated 2026-08-18T16:45:17.682Z.

## Catalogue against spec targets

| Dataset | Have | Target | Shortfall |
|---|---:|---:|---|
| GPU SKUs | 265 | 1200 | 935 short (78%) |
| CPU SKUs | 442 | 900 | 458 short (51%) |
| Games | 50 | 50 | — |
| Validation fixtures | 143 | 150 | 7 short (5%) |

### Why the SKU counts fall short

The spec targets 1,200 GPU and 900 CPU SKUs, reached by harvesting the
MediaWiki API and vendor specification databases. **Every one of those sources is
blocked by this environment's egress policy** (403 at the proxy; see the source
manifest below). The catalogue was therefore seeded from model knowledge under a
strict no-invention rule: agents were instructed to null an uncertain field and
omit an uncertain SKU rather than guess, because a wrong number silently corrupts
the fitted model while a gap is merely visible.

The harvest and parse pipeline is complete and wired. Running `npm run harvest`
followed by `npm run parse && npm run reconcile` in an environment without the
egress restriction fills the gap with sourced, attributed data and requires no
code change. The shortfall is an environment limitation, not a design one.

GPU split: 212 desktop discrete, 53 integrated. Mobile parts are
deliberately excluded: configurable TDP swings identical silicon by 40%+, and
averaging across that would inject error rather than coverage.

Most-nulled GPU fields:

| Field | Null | % |
|---|---:|---:|
| `pciIds` | 265 | 100% |
| `driverEolDate` | 223 | 84% |
| `lengthMm` | 218 | 82% |
| `slotWidth` | 147 | 55% |
| `baseClockMHz` | 101 | 38% |
| `recommendedPsuW` | 71 | 27% |
| `memBandwidthGBs` | 53 | 20% |
| `fp32TFLOPS` | 20 | 8% |

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

| Metric | Holdout | Gate |
|---|---:|---|
| median APE | 19.4% | < 15% |
| p90 APE | 44.6% | < 30% |
| Spearman rho | 0.912 | >= 0.90 |
| delta sign accuracy | 0.0% | >= 95% |

Unmet:

- only 143 fixtures; 150 needed for the metrics to be statistically meaningful
- median APE 19.4% exceeds 15.0%
- p90 APE 44.6% exceeds 30.0%

### The fixture set is recalled, not measured

This is the single most important caveat in the project. Every benchmark source
is egress-blocked, so the fixtures are figures recalled from training data rather
than measurements. The gate therefore measures **agreement with recollection, not
accuracy**, and `validate.ts` detects this from record provenance and drops to
ADVISORY mode so CI cannot report a false pass.

Tuning was stopped deliberately once the train/holdout gap closed. Pushing the
numbers further against recalled fixtures would be fitting recollection — the
exact over-fitting failure the spec warns about, dressed up as progress.

To promote the gate to enforcing: run `harness/run-benchmark.ps1` on real
hardware, drop the CSVs into `data/manual/`, and re-run `npm run gate`.
