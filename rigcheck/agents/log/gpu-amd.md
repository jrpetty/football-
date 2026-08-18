# gpu-amd — catalogue agent log

Agent: `gpu-amd`. Output: `rigcheck/agents/out/gpu-amd.json`.
Remit: all AMD Radeon **desktop discrete** GPUs, GCN 1.0 (HD 7000) through RDNA 4 (RX 9000).
Environment: Wikipedia / TechPowerUp / pci-ids / OpenBenchmarking / vulkan.gpuinfo.org are egress-blocked,
so this is a seed catalogue built from model knowledge, per BRIEF.md. No network fetches were attempted.

## Counts

| | |
|---|---|
| Expected (remit guidance) | ~110–150, midpoint 130 recorded as `expectedCount` |
| **Actual emitted** | **92** |
| Shortfall | ~38 vs midpoint |

By architecture:

| Architecture | Records | Notes |
|---|---:|---|
| GCN 1.0 | 24 | Tahiti / Pitcairn / Curacao / Cape Verde / Oland, spread across HD 7000, R200 **and** R300 series |
| GCN 2.0 | 11 | Bonaire, Hawaii, Grenada (R9 390/390X are rebadged Hawaii) |
| GCN 3.0 | 8 | Tonga/Antigua, Fiji (Fury X / Fury / Nano) |
| GCN 4.0 (Polaris) | 15 | RX 400 + RX 500, incl. Polaris 30 (RX 590, 12nm) |
| GCN 5.0 (Vega) | 4 | Vega 56, Vega 64 air, Vega 64 Liquid, Radeon VII |
| RDNA 1 | 5 | RX 5700 XT / 5700 / 5600 XT / 5500 XT ×2 |
| RDNA 2 | 13 | RX 6400 → RX 6950 XT |
| RDNA 3 | 7 | RX 7600 → RX 7900 XTX incl. 7900 GRE |
| RDNA 4 | 5 | RX 9060 XT ×2, 9070, 9070 GRE, 9070 XT |

42 of the 92 records carry a `variant` (VRAM-config or cooling splits). 25 records are RDNA 2+ and therefore
carry `meshShaders: true` / `rayTracing: true`; the other 67 carry both false.

## Why the shortfall is 92 and not ~130

I did not pad. The ~38-record difference is made up of categories I deliberately declined, all listed in
`gaps` in the JSON:

1. **OEM-only rebadges (~15 SKUs).** The whole RX 500X line (550X/560X/570X/580X), R7 350 / R7 340 /
   R5 340X / R5 330 / R5 240, OEM RX 5300 / RX 5500 / RX 5600, RX 6300, RX 640/630. Real part numbers,
   never retail boxed cards. A rig-checker keyed off what a user can own or buy gains little and risks
   alias-resolver noise.
2. **Dual-GPU boards (3 SKUs).** HD 7990, R9 295X2, Radeon Pro Duo. A single `GpuRecord` cannot express
   "two dies, CrossFire-dependent"; emitting 5632 shaders for a 295X2 would hand the fitted model a
   throughput figure no modern game realises.
3. **China / regional SKUs where I could not recall clocks (~7 SKUs).** RX 6750 GRE 12GB and 10GB,
   RX 7650 GRE, RX 580 2048SP, RX 470D, RX 560D, non-XT RX 9060. Each genuinely exists. In every case I
   knew the shader count but not the boost clock to better than ~8%, and the `fp32TFLOPS` consistency rule
   makes a guessed clock actively corrupting rather than merely absent. Nulling shaders+clock+TFLOPS to
   emit a hollow record was possible but I judged it lower value than an honest gap entry.
4. **Pre-GCN parts wearing HD 7000 badges (~5 SKUs).** HD 7670 / 7570 / 7510 / 7450 etc. are TeraScale
   rebrands and sit outside a GCN-1.0-and-later remit.
5. **Long tail of partner VRAM permutations.** I emitted dual records only where both configurations were
   mainstream retail (RX 480/470/580/570/560/550, RX 5500 XT, RX 6500 XT, RX 9060 XT, R9 290X, R9 285,
   R9 380, R7 370, R9 270X, HD 7850/7770/7750/7790). I did not manufacture records for every 1GB/2GB/4GB
   partner oddity in the HD 7000 and R200 low end.
6. Mobile and FirePro / Radeon Pro / Instinct excluded per remit (that alone would be another 200+ parts).

If the 110–150 estimate counted OEM rebadges and China SKUs, the true residual gap is closer to 10–15
records rather than 38.

## Clock convention used (game clock vs boost clock)

`boostClockMHz` is **AMD's boost ("up to") clock on every record without exception** — never the game clock.
This matters because AMD's marketing since RDNA 1 leads with game clock, which runs 8–20% below boost
(RX 6750 XT: game 2495, boost 2600; RX 9070 XT: game 2400, boost 2970). I cross-checked the convention
against AMD's own published TFLOPS figures, which are computed from the boost clock:

- RX 6950 XT 23.65 TF = 5120 × 2310 × 2 ✔
- RX 7800 XT 37.32 TF = 3840 × 2430 × 2 × 2 (dual-issue) ✔
- RX 9070 XT 48.7 TF = 4096 × 2970 × 2 × 2 ✔
- RX 5700 XT 9.75 TF = 2560 × 1905 × 2 ✔ (RDNA 1, no dual-issue)
- RX Vega 64 12.66 TF = 4096 × 1546 × 2 ✔

Everything reconciles on boost, so the convention is consistent with the vendor's own arithmetic.

**One consequence to flag loudly:** for RDNA 3 and RDNA 4 I store the *single-issue* FP32 figure
(`shaders × boost × 2`), because that is what the validator's rule demands and what predicts raster
throughput. AMD markets double these numbers by counting dual-issue FP32 (61.4 TF for the 7900 XTX vs my
30.72; 48.7 for the 9070 XT vs my 24.33). Anyone comparing this table against AMD slides will see a
factor-of-two gap on RDNA 3/4 only. That is intentional, not an error.

Derived fields were **computed, not recalled**: `fp32TFLOPS = shaders × boost × 2 / 1e6` and
`memBandwidthGBs = bus × memMTs / 8000`, generated programmatically so both 8% consistency rules hold by
construction. Effective memory clock is a working column only — `GpuRecord` has no field for it — so it
survives only implicitly inside `memBandwidthGBs`.

## Infinity Cache (no field on GpuRecord — recorded here as instructed)

Infinity Cache substantially raises *effective* bandwidth over the `memBandwidthGBs` figures stored, most
dramatically on the narrow-bus parts (RX 6500 XT at 144 GB/s raw is not a 144 GB/s-class card in practice,
and conversely its 16MB cache is too small to rescue it at 1440p).

| Part | IC | Part | IC | Part | IC |
|---|---:|---|---:|---|---:|
| RX 6950/6900/6800 XT/6800 | 128 MB | RX 7900 XTX | 96 MB | RX 9070 XT / 9070 | 64 MB |
| RX 6750 XT / 6700 XT | 96 MB | RX 7900 XT | 80 MB | RX 9070 GRE | 48 MB (unverified) |
| RX 6700 10GB | 80 MB | RX 7900 GRE / 7800 XT | 64 MB | RX 9060 XT | 32 MB |
| RX 6650/6600 XT/6600 | 32 MB | RX 7700 XT | 48 MB | | |
| RX 6500 XT / 6400 | 16 MB | RX 7600 XT / 7600 | 32 MB | | |

GCN and RDNA 1 parts have no Infinity Cache, so their stored bandwidth is the whole story.

## Fields most often nulled

| Field | Nulls | Why |
|---|---:|---|
| `lengthMm` | 92 / 92 | Board property, not chip property; varies 100mm+ across AIB designs of the same SKU. Any single number would be wrong for most cards sold. |
| `slotWidth` | 92 / 92 | Same reason — 2/2.5/3-slot variants of one SKU are routine. |
| `driverEolDate` | 92 / 92 | I am confident of `driverStatus` (GCN 1.0–3.0 eol, GCN 4/5 legacy, RDNA 1 maintenance, RDNA 2+ current) but not of AMD's precise legacy-announcement or final-driver dates. |
| `baseClockMHz` | 39 / 92 | AMD did not publish a base clock for much of Hawaii/Tonga/Fiji (spec sheets said only "up to"), and I could not recall it reliably for most RDNA 3/4 parts. Boost is populated on 91 of 92. |
| `recommendedPsuW` | 13 / 92 | Populated only where I'm confident of AMD's published system recommendation. |
| `launchDate` | 6 / 92 | Nulled for partner-introduced VRAM variants (HD 7850 1GB, HD 7790 2GB, R9 290X 8GB, R9 285 4GB, RX 6500 XT 8GB) and the staggered-rollout RX 6700 10GB. |
| `powerConnectors` | 2 / 92 | HD 7870 XT (Tahiti LE, partner-designed only) and R7 250X. |
| `boostClockMHz` / `fp32TFLOPS` | 1 / 92 | RX 9070 GRE only — see below. |
| `pciIds` | omitted on all | pci-ids.ucw.cz and TechPowerUp blocked; a near-miss device ID is worse than an absent one for an alias resolver. |

## What I am least sure about

Ranked, most to least concerning:

1. **RX 9070 GRE.** Shader count (3072), 12GB on 192-bit, 220W TBP I'm confident about. Its clocks I am
   not, so `baseClockMHz`, `boostClockMHz` and `fp32TFLOPS` are all null on that one record. It is the only
   record in the file with no FLOPS figure.
2. **RDNA 4 platform details.** `pcieLanes: 16` for the RX 9060 XT (Navi 44 moving to Gen5 ×16 where the
   RX 7600 it replaces was ×8) is the single spec point I'd most like the harvester to re-check.
3. **RX 7600 boost clock.** I used 2655 MHz; AMD's own 21.5 TFLOPS headline back-solves to 2625 MHz. 1.1%
   either way, internally consistent regardless, but unresolved.
4. **R9 290X / R9 290 TDP** (290W / 275W). AMD never published a TDP for Hawaii; these are the
   universally-cited board figures. HD 7970 GHz Edition is 250W typical against a ~300W board maximum.
5. **HD 7730** — weakest record in the file. Memory config (1GB GDDR5 128-bit 4.5 Gbps) and even the die
   (Cape Verde LE vs Oland XT, both 384 SP) are shaky. Kept because the shader/clock pair I do trust.
6. **R7 240** recorded as the 2GB DDR3 128-bit 780 MHz card; GDDR5 and 1GB versions also shipped and are
   not separately represented.
7. **`caps.vulkanVersion`** is a deliberately conservative per-architecture floor (1.2 for GCN 1.0–3.0,
   1.3 from GCN 4.0 on). The real exposed version is driver-branch dependent; current Adrenalin exposes 1.4
   on RDNA 2+. Understated on purpose so it can only be used as a floor.
8. **`caps.upscaling`** is `[]` for GCN 1.0–3.0 and `['fsr']` from Polaris on, matching AMD's official FSR
   support list. FSR 1 is a spatial post-process that does in fact run on HD 7000/R200/R300 hardware, so
   the empty list understates FSR1-only titles. FSR 4 is RDNA 4-only but the enum has no version axis.
9. **`caps.resizableBar`** true from RDNA 1 on, because AMD backported SAM to the RX 5000 series by driver
   in 2021; false for Polaris/Vega, which never got official support.

## Two things a validator may flag that are real, not errors

- **R9 285 (1792 SP) has fewer shaders than R9 280X (2048 SP)** despite the higher model number. Genuine
  AMD 200-series behaviour: Tonga replaced Tahiti with a narrower, more efficient part. Any family
  monotonicity check that orders by model number will trip here.
- **Feature level splits across a marketing series.** `dxFeatureLevel` follows silicon, not badge:
  R7 370 / R9 270 / 270X / 280 / 280X are Pitcairn/Tahiti (GCN 1.0, FL **11_1**), R9 285 / 380 / 380X are
  Tonga (GCN 3.0, FL **12_0**), R9 390 / 390X are Hawaii (GCN 2.0, FL **12_0**). So the 300 series spans
  two feature levels and the 200 series spans two as well. GCN 1.0 at FL 11_1 is the accurate value —
  BRIEF.md's "12_0 for GCN 1.1+" implies exactly this and 11_1 is what Southern Islands actually reports.

## Capability gates as set

- `meshShaders` / `rayTracing`: **true only for RDNA 2, 3, 4** (25 records). False for RDNA 1, Vega,
  Polaris and every GCN part. No exceptions, no partial credit.
- `dxFeatureLevel`: 11_1 (GCN 1.0) · 12_0 (GCN 2.0/3.0, Polaris) · 12_1 (Vega, RDNA 1) · 12_2 (RDNA 2+).
- `shaderModel`: 5_1 (GCN 1.0) · 6_0 (GCN 2.0/3.0) · 6_5 (Polaris, Vega, RDNA 1) · 6_7 (RDNA 2+).
- `av1Encode`: true for RDNA 3 and RDNA 4 only. RDNA 2 decodes AV1 but does not encode it, so it is false
  there — worth stating because it is a common data error.
- `driverStatus`: eol (GCN 1.0–3.0) · legacy (GCN 4.0, GCN 5.0) · maintenance (RDNA 1) · current (RDNA 2+).

## Method note

Records were emitted from a single generated table so that `fp32TFLOPS` and `memBandwidthGBs` are computed
rather than remembered, then re-validated by an independent script that re-derives both rules from the
written JSON, checks id uniqueness and slug shape, checks that every repeated `brand` has distinct
`variant` values, and asserts the mesh/RT/DX/AV1 gates against architecture. Final state: 0 errors,
0 warnings, file parses.
