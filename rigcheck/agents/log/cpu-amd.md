# cpu-amd — catalogue agent log

Agent: `cpu-amd`
Output: `rigcheck/agents/out/cpu-amd.json` (183 records, `kind: "cpu"`)
Provenance: every field `model-knowledge` via the compact `{"*": ["model-knowledge"]}` form.
Date: 2026-08-16.

## What I produced

183 AMD **desktop** CPU records, FX (Bulldozer, 2011) through Zen 5 (Granite Ridge /
Shimada Peak). Expected band from the remit was 130–180; I landed at 183, slightly
over, entirely because I carried the full Threadripper PRO WX lines rather than only
the non-PRO HEDT parts. No mobile parts, no EPYC.

### Breakdown by architecture

| Architecture | Count |
|---|---|
| Bulldozer (Zambezi) | 8 |
| Piledriver (Vishera / Trinity / Richland) | 20 |
| Steamroller (Kaveri / Godavari) | 9 |
| Excavator (Carrizo / Bristol Ridge) | 6 |
| Zen (Summit Ridge / Raven Ridge / Whitehaven) | 19 |
| Zen+ (Pinnacle Ridge / Picasso / Colfax) | 19 |
| Zen 2 (Matisse / Renoir / Castle Peak) | 29 |
| Zen 3 (Vermeer / Cezanne / Chagall) | 30 |
| Zen 4 (Raphael / Phoenix / Storm Peak) | 26 |
| Zen 5 (Granite Ridge / Shimada Peak) | 17 |

### Breakdown by socket

`AM4` 83 · `AM5` 25 · `AM3+` 22 · `sTR5` 18 · `FM2+` 10 · `sWRX8` 9 · `sTR4` 7 · `FM2` 6 · `sTRX4` 3

### Breakdown by segment

`entry` 50 · `performance` 40 · `mainstream` 39 · `hedt` 37 · `enthusiast` 17

Segment is assigned **purely by marketing tier** so the fitted model sees a
consistent variable: FX-4xxx / A-series / Athlon / Ryzen 3 = `entry`,
FX-6xxx / Ryzen 5 = `mainstream`, FX-8xxx & 9xxx / Ryzen 7 = `performance`,
Ryzen 9 = `enthusiast`, all Threadripper = `hedt`. Note the consequence: the
7800X3D and 9800X3D are `performance`, not `enthusiast`, despite being halo
gaming parts. If the model wants "halo gaming" as a class it should key off
`vcache` plus tier, not off `segment`.

## The `vcache` flag — 10 records, audited individually

`vcache: true` on exactly these and nothing else. The generator asserts both
directions (any id ending `x3d` must have `vcache: true`, and any `vcache: true`
must be on an explicit audited allowlist and carry L3 of 96 or 128 MB), so a
silent drift here fails the build rather than corrupting the fit.

| id | cores/threads | L3 total | topology |
|---|---|---|---|
| `amd-ryzen-5-5600x3d` | 6/12 | 96 MB | single CCD, 32 base + 64 stacked |
| `amd-ryzen-7-5700x3d` | 8/16 | 96 MB | single CCD, 32 + 64 |
| `amd-ryzen-7-5800x3d` | 8/16 | 96 MB | single CCD, 32 + 64 |
| `amd-ryzen-5-7600x3d` | 6/12 | 96 MB | single CCD, 32 + 64 |
| `amd-ryzen-7-7800x3d` | 8/16 | 96 MB | single CCD, 32 + 64 |
| `amd-ryzen-9-7900x3d` | 12/24 | 128 MB | **asymmetric**: CCD0 32+64=96, CCD1 32, no stack |
| `amd-ryzen-9-7950x3d` | 16/32 | 128 MB | **asymmetric**: CCD0 32+64=96, CCD1 32, no stack |
| `amd-ryzen-9-9900x3d` | 12/24 | 128 MB | **asymmetric**, same split as 7900X3D |
| `amd-ryzen-9-9950x3d` | 16/32 | 128 MB | **asymmetric**, same split as 7950X3D |
| `amd-ryzen-7-9800x3d` | 8/16 | 96 MB | single CCD, 32 + 64 |

**On the asymmetric dual-CCD parts** — the remit asked me to verify rather than
guess, so stating my understanding explicitly: the split is **96 + 32**, not
64 + 32. Each Zen 4/Zen 5 CCD carries 32 MB of base L3. On the X3D dual-CCD
parts only *one* CCD gets the 64 MB stacked die, giving 32+64 = 96 MB on the
cache CCD and a plain 32 MB on the other; 96 + 32 = 128 MB total, which is the
figure AMD markets. So the 64 MB is the *stack*, not the CCD's total.

This matters for the model in a way a single `l3CacheMB` number cannot express:
a game pinned to the cache CCD of a 7950X3D sees 96 MB, effectively a 7800X3D.
A game scheduled onto the frequency CCD sees 32 MB and behaves like a 7700X.
The 128 MB figure is never available to a single thread. **`l3CacheMB: 128` on
these four records is a whole-package figure and will over-predict
`cacheSensitivity` if consumed naively.** Recommend the index model add a
per-CCD "L3 visible to one thread" derived field (96 for every part in the table
above, uniformly) and drive `cacheSensitivity` from that instead. Flagged in
`uncertainties` too.

Deliberately **not** flagged `vcache`: nothing else. In particular the large-L3
non-X3D parts (5950X/7950X/9950X at 64 MB, Threadripper at 128–384 MB) are
plain multi-CCD L3 and are correctly `false`.

## FX module topology — the marketed core count is a lie

Every FX part is emitted with the **marketed** `cores`/`threads` as instructed:
FX-8350 is `cores: 8, threads: 8`. The schema has no field for module topology,
so recording it here:

| Marketed | Bulldozer/Piledriver modules | Shared per module |
|---|---|---|
| FX-4xxx (4 "cores") | 2 modules | 1× 128-bit FMAC FPU, fetch/decode, L2 |
| FX-6xxx (6 "cores") | 3 modules | same |
| FX-8xxx / 9xxx (8 "cores") | 4 modules | same |

Each module has two integer clusters but **one shared FPU, one shared
fetch/decode front end and one shared 2 MB L2 slice**. The practical effect in
games: an FX-8350 behaves much closer to a 4-core part than to an 8-core one,
and its per-thread throughput collapses when both integer clusters in a module
are loaded. These parts underperform their core count badly — a
`threadScaling` term fitted off `cores` alone will systematically over-predict
every FX SKU. **Recommend the model treat FX `cores` as `cores/2` for the
throughput/threadScaling terms**, or add an explicit `moduleCount` field at
harvest. `l2CacheMB` is recorded as the marketed aggregate (8 MB on an
FX-8350 = 4 modules × 2 MB), which is the figure AMD publishes, but no single
thread sees more than 2 MB of it.

Related: FX is `avx2: false` across the board. Bulldozer has AVX 1.0 + FMA4 +
XOP; Piledriver adds FMA3; **neither has AVX2**. Steamroller (Kaveri/Godavari)
also lacks it. Only Excavator (Athlon X4 845, the AM4 Bristol Ridge A12/A10/A8/A6
and Athlon X4 950) is set `avx2: true`. Modern titles that hard-require AVX2 will
gate-fail every FX and every Kaveri part, which is correct behaviour.

## `caps.hybrid` is false everywhere — including the Zen 4c parts

The Ryzen 5 8500G and 8400F (Phoenix 2 die) mix 2 Zen 4 cores with 4 Zen 4c
cores. I set `hybrid: false` deliberately rather than true:

- Zen 4c is **ISA-identical** to Zen 4 — no AVX-512 difference, no feature split.
- Every core is SMT-2, so `threads == cores * 2` holds and the schema's
  `cores = pCores + eCores`, `threads = pCores*2 + eCores` decomposition (which
  is written for Intel P/E) does not apply and would produce a wrong thread count.
- `pCores`/`eCores` are left unset rather than filled with a misleading split.

What *is* true is that the Zen 4c cores clock materially lower (the 8500G's
5.0 GHz boost is reachable only on its Zen 4 cores). That is a clock-asymmetry
issue, not a scheduler-topology issue, and it is recorded in `uncertainties`
rather than smuggled into the `hybrid` flag.

## Fields I most often had to null, and why

Only four fields carry any nulls at all. Every `CpuRecord` has non-null
`socket`, `memoryType`, `maxMemChannels`, `cores`, `threads`.

| Field | Nulls | Why |
|---|---|---|
| `igpuId` | 118 | Genuinely absent — every FX, every non-APU Ryzen through 5000, all Threadripper, and the F-suffix parts have no integrated graphics. Not a gap. |
| `pcieLanes` | 33 | 22 FX parts (no on-die PCIe at all — lanes come from the 990FX/970 northbridge), 5 Phoenix 8000G, 6 Threadripper 7000/9000 non-PRO on TRX50. |
| `baseClockMHz` | 6 | 3 Athlon GE parts (locked, no separate base/boost) + 6 Threadripper PRO 9000WX where I could not confirm the base clock. |
| `boostClockMHz` | 4 | Athlon 200GE/220GE/240GE/3000G are boost-locked; base clock is the only clock. |

`l3CacheMB: 0` appears on 21 records and is **a real value, not a gap** — Trinity,
Richland, Kaveri, Godavari, Carrizo and Bristol Ridge APUs and their Athlon X4
derivatives have no L3 cache whatsoever. I deliberately did not null these,
because a null would read to the loader as "unknown" and to a
cache-sensitivity model as missing data, when the truth is that the number is
zero and these parts are catastrophically memory-latency bound because of it.

## Expected vs actual, and what is missing

183 emitted against a 130–180 expectation. The count is *above* band, so there is
no aggregate shortfall — but there are specific, named holes, and they are
concentrated in exactly the place that matters most:

**Zen 5 X3D parts I believe exist but omitted (3):** `Ryzen 5 9600X3D`,
`Ryzen 7 9850X3D`, `Ryzen 9 9950X3D2`. My recollection of all three is real but
not solid enough to meet the brief's bar. The 9600X3D is the one I am most
confident about (6C/12T, 96 MB, Micro Center-first, late 2025); the 9850X3D and
the dual-stacked 9950X3D2 (V-Cache on *both* CCDs, ~192 MB) I associate with a
CES 2026 announcement but cannot confirm shipped. Given that the brief calls a
wrong X3D record the single worst corruption available to me, and that inventing
a dual-stacked part would poison the asymmetric-cache logic above, I omitted all
three. **These are the highest-priority additions for the harvest pipeline.**

**Other omissions:** Zen 5 F-suffix desktop (9500F, 9700F) — recalled, not
verified. Any Zen 5 AM5 desktop APU line (a "9000G") — no confident knowledge one
shipped. Ryzen 3 8300G (OEM, L3 config unknown). Ryzen 3 5100 (OEM, uncertain it
shipped). FX-8140/FX-6120/FX-4150 (OEM-only Bulldozer). Athlon Gold/Silver PRO
3150G/3125GE (OEM). Sub-A6 FM2/FM2+ dual-core APUs and A4-series (not
gaming-relevant). AM1 Sempron/Kabini (socket out of remit). Athlon II / Phenom II
(pre-FX, out of remit).

## What I am least sure about

Ordered by how much damage a wrong value does:

1. **The four 128 MB dual-CCD X3D records.** The *number* is right; the risk is
   the model reading it as uniform 128 MB. See the section above.
2. **Threadripper 9000 clocks.** PRO 9000WX base clocks are nulled outright. The
   non-PRO 9980X/9970X/9960X base clocks I did emit mirror the 7000-series values
   exactly, which is a pattern-match rather than a recollection. Boost 5.4 GHz
   across the Shimada Peak line is the part I am confident about.
3. **`igpuId` cross-agent joins — checked, partly resolved, see below.** No longer
   speculative: I reconciled against the iGPU records other agents actually emitted.
   12 of 24 distinct references resolve; 12 do not, and 38 records still carry a
   dangling reference. Details in the next section.
4. **F-suffix Phoenix iGPU status.** `igpuId: null` on the 8700F and 8400F. I am
   not confident whether AMD fully disabled the 780M/740M on these or shipped a
   cut-down iGPU. If they retain graphics, two records are wrong.
5. **Excavator AVX2.** I set `avx2: true` for Carrizo/Bristol Ridge on the basis
   that Excavator added AVX2/BMI2. Moderate confidence. If wrong, six entry-level
   records will pass an AVX2 gate they should fail.
6. **FX-4320 and FX-4130 L3 at 4 MB** (vs 8 MB on FX-4170/FX-4350). FX-4300 at
   4 MB I am confident about; the other two are moderate.
7. **`officialMemMTs` semantics.** These are 1 DIMM-per-channel single-rank JEDEC
   ratings. AM4 in particular derates hard with four DIMMs or dual-rank modules
   (a 3200-rated Vermeer is officially 2133 at 4×dual-rank). The build model must
   not read these as achievable speeds.
8. **`pcieLanes` convention.** I recorded *total CPU-provided lanes including the
   chipset uplink*: 24 for Summit/Pinnacle/Matisse/Vermeer/Renoir/Cezanne, 16 for
   Raven Ridge/Picasso, 12 for Bristol Ridge, 28 for Raphael/Granite Ridge, 64/72/128
   for Threadripper. The schema cannot express the thing that actually matters for
   builds: **Raven Ridge and Picasso give the GPU only x8**, and Renoir/Cezanne and
   all 5000G parts give x16 but at **PCIe 3.0** on an otherwise PCIe 4.0 platform.
   A build optimiser that ignores this will over-rate a 5600G + high-end GPU pairing.

## Cross-agent check: `igpuId` reconciliation

After the other catalogue agents' outputs landed I checked my 65 APU records
against the iGPU namespace they actually produced, rather than leaving the join
untested.

**Finding: `gpu-amd.json` contains 0 `formFactor: "igpu"` records** — that agent
emitted discrete desktop cards only. All AMD integrated graphics currently live in
`gpu-intel.json` (53 iGPU records, 18 of them AMD), which appears to own the iGPU
namespace for both vendors. Worth confirming that ownership is intentional.

I repointed 29 references onto records that exist, using the codename-specific ids
that agent chose (which are better than my originals — they distinguish Vega 8 on
Raven Ridge from Picasso from Renoir from Cezanne, which really do differ in clocks):

`amd-vega-{3,8,11}-raven` · `amd-vega-{8,11}-picasso` · `amd-vega-8-renoir` ·
`amd-vega-{6,7,8}-cezanne` · `amd-radeon-780m-8700g` · `amd-radeon-760m` · `amd-radeon-740m`

**Result: 12 of 24 distinct references resolve, covering 27 of 65 APU records.**

The remaining **12 dangling references (38 records)** are enumerated precisely in
the JSON `gaps` so the owning agent can create them. Highest impact by far:

- **`amd-radeon-graphics-raphael-2cu` — 20 records.** Every non-F Raphael and
  Granite Ridge desktop CPU has the 2 CU RDNA 2 iGPU. There is no record for it.
- `amd-vega-7-renoir`, `amd-vega-6-renoir` — 4600G/4650G, 4300G/4350G.
- Six pre-Zen APU graphics ids (Trinity HD 7660D/7560D, Richland HD 8670D,
  Kaveri/Godavari R7 and R5, Bristol Ridge R7 and R5).

**I deliberately did not null these dangling references.** A null on `igpuId` means
"this CPU has no integrated graphics", which is *false* for all 38 parts. A dangling
reference is a visible, reported gap; a null would be a silent falsehood that a
build optimiser would act on (e.g. concluding a 5600G build needs a discrete GPU to
display). Where no codename-correct record existed I kept a well-formed forward
reference rather than mispointing at a near-miss — the Renoir Vega 7/6 parts point
at `amd-vega-*-renoir`, not at the Cezanne records, even though those exist.

## Deviations from the brief worth flagging

- **`sWRX8` socket used** for the Threadripper PRO 3000WX and 5000WX lines (9
  records). It is not in the brief's socket list, but it is the factually correct
  socket and `socket` may never be null. Forcing them onto `sTRX4` would have been
  a compatibility lie that the inventory optimiser would act on.
- **183 records vs a 130–180 expectation.** Over, not under. Drop the 15
  Threadripper PRO WX records if the catalogue is meant to be gaming-desktop only.
- Included two `variant` records for the Zen+ "AF" rebadges (`Ryzen 5 1600 AF`,
  `Ryzen 3 1200 AF`) as distinct SKUs, since they are Zen+ silicon at 12 nm sold
  under a Zen 1000-series name and perform measurably differently from the
  original 1600/1200.

## Validation performed

The generator asserts, and the build fails on any violation:

- unique ids; `threads >= cores`; `boost >= base` where both present
- `socket`, `memoryType`, `maxMemChannels`, `cores`, `threads` all non-null
- `memoryType` ∈ {DDR3, DDR4, DDR5} and coherent with `socket`
  (AM3+/FM2/FM2+ → DDR3, AM4/sTR4/sTRX4/sWRX8 → DDR4, AM5/sTR5 → DDR5)
- `maxMemChannels` ∈ {1,2,4,8}; `segment` ∈ the five `CpuSegment` values
- `vcache` true **iff** the id ends in `x3d`, **and** the id is on a hand-audited
  allowlist, **and** `l3CacheMB` ∈ {96, 128}

All 183 records pass. JSON verified to parse with
`node -e "JSON.parse(require('fs').readFileSync('rigcheck/agents/out/cpu-amd.json','utf8'))"`.
