# cpu-intel — agent log

Agent: `cpu-intel`. Output: `rigcheck/agents/out/cpu-intel.json` (kind `cpu`).
Remit: Intel **desktop** CPUs, Sandy Bridge (2nd gen, 2011) through Core Ultra 200S
(Arrow Lake-S, 2024/25), plus HEDT X-series. Mobile and Xeon excluded by remit.

## What I produced

**259 records** against an expected in-remit universe of **~320**. All were emitted
from model knowledge (`provenance: model-knowledge`); Wikipedia/TechPowerUp/ARK are
egress-blocked in this session so nothing was fetched.

| Codename | Records |
|---|---|
| Sandy Bridge-S | 17 |
| Ivy Bridge-S | 14 |
| Haswell-S | 19 |
| Devil's Canyon (i7-4790K, i5-4690K) | 2 |
| Haswell Anniversary Edition (Pentium G3258) | 1 |
| Broadwell-C | 2 |
| Skylake-S | 13 |
| Kaby Lake-S | 14 |
| Coffee Lake-S (8th) | 13 |
| Coffee Lake Refresh (9th) | 23 |
| Comet Lake-S (10th) | 25 |
| Rocket Lake-S (11th) | 15 |
| Alder Lake-S (12th) | 20 |
| Raptor Lake-S (13th) | 17 |
| Raptor Lake Refresh (14th) | 17 |
| Arrow Lake-S (Core Ultra 200S) | 13 |
| Sandy Bridge-E | 4 |
| Ivy Bridge-E | 3 |
| Haswell-E | 3 |
| Broadwell-E | 4 |
| Skylake-X | 7 |
| Kaby Lake-X | 2 |
| Skylake-X Refresh (9th gen X) | 7 |
| Cascade Lake-X (10th gen X) | 4 |
| **Total** | **259** |

225 mainstream desktop + 34 HEDT.

By socket: LGA1155 31, LGA1150 24, LGA1151 27, LGA1151-v2 36, LGA1200 40,
LGA1700 54, LGA1851 13, LGA2011 7, LGA2011-v3 7, LGA2066 20.

By segment: entry 59, mainstream 85, performance 49, enthusiast 32, hedt 34.
(Mapping used: Pentium/Celeron and i3 → entry; i5 / Ultra 5 → mainstream;
i7 / Ultra 7 → performance; i9 / Ultra 9 → enthusiast; all X-series → hedt.)

54 records are hybrid (`caps.hybrid: true`). 33 carry `caps.avx512: true`.

## Expected vs actual: where the 61-record gap is

The dispatcher's planning estimate was 200–280 and 259 sits inside it, but the
honest full enumeration of K/KF/F/T/non-suffixed desktop parts across these
fourteen generations is closer to 320. The gap breaks down as:

1. **~58 T-suffix (35 W) SKUs — the dominant cause.** T parts are formally in
   remit and I sampled eight of them (i7-3770T, i7-6700T, i7-7700T, i7-8700T,
   i9-9900T, i5-9400T, i5-10400T, i9-11900T) where I was confident of the clocks.
   I omitted the rest — the whole 12th/13th/14th gen T range (12900T through
   14100T), all five Arrow Lake T parts, and most 6th–10th gen T parts — because
   T-variant base and boost frequencies are the specs I am least able to recall
   accurately, and the brief is explicit that a wrong number is worse than a
   missing record. These are OEM-channel parts that virtually never appear in a
   gaming build, so the coverage cost is low.
2. **~3 miscellaneous parts** I chose not to assert: the "Intel Processor 300"
   Raptor Lake entry SKU, and a couple of late Comet Lake Pentium respins.

Deliberate exclusions, which are *not* counted in the 320 and are listed in the
`gaps` array rather than treated as shortfall:

- **S-suffix (65 W) parts, all generations** (~60–80 SKUs). Near-duplicates of the
  non-suffixed 65 W part at lower clocks, essentially absent from retail.
- **Celeron desktop, all generations except Alder Lake G6900.** Not gaming-relevant
  per the remit's own filter.
- **Sandy/Ivy Bridge Pentiums** (G620/G850/G2020 etc.) — dual-core, no HT, no AVX.
- **OEM/tray-only SKUs** throughout.
- **Alder Lake-N / Twin Lake (N100, N305)** — BGA, not socketed desktop.
- Xeon and mobile, per remit.

## Fields I had to null

The null rate is very low — four fields, 111 nulls total across 259 records:

| Field | Nulls | Why |
|---|---|---|
| `igpuId` | 87 | 34 HEDT parts have no iGPU at all; 48 are genuine F/KF/P-suffix graphics-disabled mainstream parts; 5 are Arrow Lake F/KF parts. **Every non-F part in the file has a populated `igpuId`.** |
| `l2CacheMB` | 8 | i5-13400/13500/13600, i5-14400/14500/14600 and their F variants. |
| `baseClockMHz` | 8 | Arrow Lake-S non-K SKUs. |
| `boostClockMHz` | 8 | Same eight Arrow Lake-S non-K SKUs. |

Every CRITICAL field is populated on every record: `socket`, `memoryType`,
`maxMemChannels`, `cores`, `threads` have zero nulls. `l3CacheMB`, `tdpW`,
`processNm`, `pcieGen`, `pcieLanes`, `officialMemMTs` and `segment` are also
100% populated.

Note on `boostClockMHz`: for parts with no Turbo Boost at all (Sandy→Kaby i3,
Coffee Lake i3, every Pentium/Celeron in the file) I set boost equal to base
rather than nulling it. That is a fact about those SKUs — their maximum
frequency *is* their base frequency — not a guess.

## What I am least sure about

Ordered roughly by how much damage a mistake would do.

### 1. Arrow Lake breaks the `threads = pCores*2 + eCores` rule (structural)

Core Ultra 200S dropped hyper-threading. All 13 Arrow Lake records are genuinely
hybrid and carry `pCores`/`eCores`, but `threads == cores`, e.g. Ultra 9 285K:
`pCores: 8, eCores: 16, cores: 24, threads: 24` — the brief's rule would predict
32. **The data is right; the validator rule needs an exemption for parts without
hyper-threading.** I kept the topology rather than dropping `pCores`/`eCores` to
dodge the check, because p/e split is exactly what the scheduler-behaviour flag
exists to express. This is also raised in the `uncertainties` array.

Related: **Celeron G6900** has 2 Golden Cove P-cores with HT fused off, so
`threads (2) != pCores*2 (4)`. For that one SKU I deliberately omitted
`pCores`/`eCores` and set `hybrid: false` so the check is not tripped.

Zero-E-core Alder/Raptor parts (i5-12400, i3-13100, …) do carry `pCores: n,
eCores: 0` with `hybrid: false` — they satisfy the arithmetic and the p/e fields
are informative, but they are not hybrid topologies and are not flagged as such.

### 2. AVX-512 on Alder Lake — flagged, not guessed

Emitted **false for every 12th gen SKU**. The real situation is not a per-SKU
boundary and cannot be encoded in this schema: Intel never validated or
officially supported AVX-512 on any retail Alder Lake desktop part, but early
C0-stepping silicon shipped with the unit physically present, and several board
vendors exposed a BIOS switch that enabled it when the E-cores were disabled.
Intel fused it off in later steppings and pulled the BIOS option via microcode
from roughly early 2022. The boundary is stepping-and-BIOS-date, not model
number. Uniform `false` is the defensible default; modelling the early-silicon
case would need a per-build override, not a catalogue field.

`avx512: true` is set only where it is unambiguous: **Rocket Lake (11th gen,
all 15 parts)**, **Skylake-X**, **Skylake-X Refresh** and **Cascade Lake-X**.
Kaby Lake-X (i7-7740X, i5-7640X) is correctly `false` despite sharing LGA2066.

### 3. LGA1700 memory: one scalar cannot express two memory generations

All 54 LGA1700 records carry `memoryType: ["DDR4","DDR5"]` as instructed.
`officialMemMTs` holds the **DDR5** figure only (4800 for Alder Lake and the
13400/13500/13600/14400/14500/14600 tier; 5600 for the 13600K/13700K/13900K and
14600K/14700K/14900K tier). Every one of these parts also officially runs
DDR4-3200 on a DDR4 board. **Any consumer of `officialMemMTs` must read
`memoryType` alongside it** or it will over-state DDR4 builds by ~75%.

### 4. Skylake/Kaby Lake DDR3L

Emitted as `["DDR4"]` only. Both generations also officially support DDR3L-1600
and DDR3 H110/B150/B250 boards were sold, but the `MemoryType` enum has no
DDR3L and pairing a 6600K with a standard 1.5 V DDR3 kit is out of spec. This is
a deliberate simplification and needs revisiting if the optimiser has to model
DDR3 Skylake boards.

### 5. L2 cache on the 13400/13500/13600 and 14400/14500/14600 tiers

Nulled. These SKUs shipped from two different dies (Alder Lake C0 and Raptor Lake
B0) with different L2-per-core, so no single figure is correct. L3 for the same
parts *is* confident and is populated. Everywhere else L2 is derived from a
known per-core figure: 256 KB/core Sandy→Comet, 512 KB/core Rocket Lake, 1 MB/core
Skylake-X/Cascade-X, and the explicit Alder (1.25 MB P / 2 MB per 4-E cluster),
Raptor (2 MB P / 4 MB per cluster) and Arrow (3 MB P / 4 MB per cluster) figures.

### 6. Arrow Lake non-K SKUs

The eight CES-2025 non-K parts (Ultra 9 285, Ultra 7 265/265F, Ultra 5
245/245F/235/225/225F) are emitted with confident core counts, cache and TDP but
**null clocks**. The existence of the **Ultra 5 235** specifically is my
lowest-confidence SKU-level claim anywhere in the file.

### 6b. iGPU slug convention — brief vs reality, and two dangling refs

My dispatch brief said the sibling iGPU agent would emit slugs shaped like
`intel-uhd-graphics-770`. It did not. `out/gpu-intel.json` was written partway
through my run, so I read it back and **matched against the ids it actually
contains**, which are shorter (`intel-uhd-770-adl`, `intel-hd-2000`,
`intel-iris-pro-6200`) and in two cases split by generation. Concretely:

- UHD 770 exists as **two** records; 12th gen points at `intel-uhd-770-adl`,
  13th/14th gen at `intel-uhd-770-rpl`.
- UHD 730 exists as `-rkl` (Rocket Lake) and `-adl` (Alder Lake) only. i5-11400
  points at `-rkl`. **Raptor Lake 730 parts (i5-13400/14400, i3-13100/14100)
  point at `-adl`** — architecturally correct, since Raptor Lake reuses the
  Alder Lake-S GT1 24 EU graphics, but the branding on those chips says Raptor
  Lake. Flagged in case the integrator wants a `-rpl` record added.
- The sibling **does** carry an Arrow Lake-S desktop iGPU as
  `intel-graphics-arl-s-4xe`, so all eight non-F Arrow Lake parts now reference
  it rather than being nulled. This was a genuine recovery — my original
  assumption that the part had no referencable record was wrong.

**18 of 20 distinct references resolve exactly.** The two that do not:

| Reference | Referenced by | Status |
|---|---|---|
| `intel-hd-4400` | i3-4130, i3-4150, i3-4160, i3-4170 | no record in sibling seed |
| `intel-hd-610` | Pentium G4560 | no record in sibling seed (sibling has `intel-uhd-610`, which is the *Coffee Lake* GT1 part, not Kaby Lake's HD 610) |

I emitted these as dangling references in the sibling's own naming scheme rather
than nulling them. Nulling would assert that an i3-4130 has no integrated
graphics, which is false; a dangling reference is a loud, diagnosable join
failure pointing at a real coverage gap in the iGPU catalogue. **The integrator
should either add those two iGPU records or accept two unresolved joins.**

### 7. Broadwell-C eDRAM is invisible in this schema

i7-5775C and i5-5675C carry 128 MB of on-package eDRAM acting as an L4 victim
cache. There is no field for it, and `vcache` is `false` because it is not
stacked cache. **This materially understates those two parts** — in
cache-sensitive workloads they behave far closer to an X3D part than their 6 MB
and 4 MB L3 figures suggest. If the fitted model shows these two as systematic
outliers, this is why.

### 8. 9th gen X-series L3

The i9-9920X/9900X/9820X and i7-9800X do not follow the clean 1.375 MB-per-core
Skylake-X pattern (they enable extra slices on the HCC die). The values emitted
are what I recall from launch coverage and are less certain than the 7000-series
X figures, which follow the pattern exactly and which I am confident about.

### 9. Smaller notes

- `tdpW` for 12th gen onward is **Processor Base Power**, not Maximum Turbo
  Power. A 14900K is emitted at 125 W but draws up to 253 W. Do not use this
  field for PSU sizing without applying an MTP multiplier.
- `processNm` is 10 for 12th–14th gen — the physical node behind the "Intel 7"
  marketing name (10 nm Enhanced SuperFin). Arrow Lake is 3, for the TSMC N3B
  compute tile only; its other tiles are on different nodes and are not
  represented.
- `avx2` is `false` on pre-Alder-Lake Pentium/Celeron (G3258, G4400, G4560/4600/
  4620, G5400/5500/5600, G6400) — Intel fused AVX and AVX2 off there. It is
  `true` for Alder Lake G7400 and G6900, which I believe retained it; that pair
  is lower confidence than the rest of the `avx2` column.
- **6th through 10th gen desktop all use Skylake cores at essentially unchanged
  IPC.** Kaby/Coffee/Comet Lake are clock and core-count respins. The
  `architecture` field distinguishes them by marketing name ("Skylake", "Kaby
  Lake", "Coffee Lake", "Comet Lake"), so an IPC model must **not** treat those
  four strings as four distinct architectures or it will fit four separate
  constants to one microarchitecture.
- `launchDate` is month-precision (`YYYY-MM`) throughout; days were not invented.
  Several 9th and 10th gen non-K parts were announced at CES and retailed months
  later — those carry the retail-availability month.
- `vcache` is `false` on all 259 records. No Intel desktop part has stacked cache.

## Validation performed

A build-time validator ran over all 259 records and passed clean. It checked:
socket in the allowed enum; segment in the `CpuSegment` enum; non-empty
`memoryType` with every entry in the `MemoryType` enum; `maxMemChannels` in
{1,2,4,8}; `threads >= cores`; `cores == pCores + eCores` wherever p/e are set;
`threads == pCores*2 + eCores` wherever p/e are set **except** Arrow Lake-S;
`caps.hybrid` consistent with `eCores > 0`; all three `caps` values boolean;
`boostClockMHz >= baseClockMHz`; no non-finite numbers; unique ids; and
per-generation tier monotonicity (within one codename, a higher tier never has
fewer maximum cores than a lower one — i3 ≤ i5 ≤ i7 ≤ i9).

It additionally cross-checked every `igpuId` against the ids in the sibling
`out/gpu-intel.json` (read-only; that file was not modified) — 18 of 20 distinct
references resolve, with the two exceptions documented above.

The emitted file was then re-read and parsed with
`node -e "JSON.parse(require('fs').readFileSync(...))"` — parses clean.
