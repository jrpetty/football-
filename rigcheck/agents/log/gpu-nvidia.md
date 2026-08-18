# gpu-nvidia — catalogue agent log

Agent: `gpu-nvidia`. Output: `rigcheck/agents/out/gpu-nvidia.json` (132 KB, valid JSON,
verified with `JSON.parse`).

Remit: all NVIDIA GeForce **desktop discrete** GPUs, Fermi (GTX 400) through Blackwell
(RTX 50). Mobile, Quadro/Tesla/workstation and pre-Fermi parts excluded by remit.

## What I produced

**112 records**, against an expected ~130–170. Every record carries
`formFactor: "desktop"`, `vendor: "nvidia"` and `_prov: {"*": ["model-knowledge"]}`.

By marketing series:

| Series | Records | Notes |
|---|---|---|
| GTX 400 (Fermi) | 9 | GTX 480/470/465/460 1GB/460 768MB/460 SE, GTS 450, GT 440/430 |
| GTX 500 (Fermi) | 7 | incl. GTX 560 Ti 448 Cores (GF110) as its own record, GT 520 |
| GTX 600 (Kepler) | 13 | incl. 680/670/660 Ti/660 VRAM splits, GT 640, GT 610 (GF119 rebrand, recorded as Fermi) |
| GTX 700 (Kepler + Maxwell 1) | 15 | incl. GTX Titan / Titan Black, GT 730 ×2 |
| GTX 900 (Maxwell 2) | 7 | incl. GTX 960 2GB/4GB, GTX Titan X |
| GTX 10 (Pascal) | 13 | incl. GTX 1060 3GB/6GB, GTX 1050 2GB/3GB, GT 1030 GDDR5/DDR4, Titan X Pascal, Titan Xp |
| Titan V (Volta) | 1 | |
| GTX 16 (Turing) | 7 | incl. GTX 1650 GDDR5/GDDR6 |
| RTX 20 (Turing) | 9 | incl. RTX 2060 6GB/12GB, Titan RTX |
| RTX 30 (Ampere) | 13 | incl. RTX 3080 10/12GB, 3060 Ti GDDR6/GDDR6X, 3060 8/12GB, 3050 6/8GB |
| RTX 40 (Ada) | 10 | incl. 4060 Ti 8/16GB, 4080 vs 4080 Super as separate parts |
| RTX 50 (Blackwell) | 8 | 5090/5080/5070 Ti/5070/5060 Ti ×2/5060/5050 |

43 records carry a `variant` (dual-configuration names split as the brief requires).
7 Titan records are included; the remit made them optional.

All the dual-configuration cases the brief and the task named are present and split
with distinct ids and correct differing specs:

- GTX 1060 3GB (1152 shaders) vs 6GB (1280 shaders)
- RTX 3060 8GB (128-bit, 240 GB/s) vs 12GB (192-bit, 360 GB/s)
- RTX 4080 (9728 sh, 22.4 Gbps) vs 4080 Super (10240 sh, 23 Gbps)
- GTX 460 768MB (192-bit) vs 1GB (256-bit)
- GTX 1050 2GB (640 sh, 128-bit) vs 3GB (768 sh, 96-bit)
- GTX 1650 GDDR5 (1665 boost, 128 GB/s) vs GDDR6 (1590 boost, 192 GB/s)
- RTX 2060 6GB (1920 sh) vs 12GB (2176 sh)
- RTX 3060 Ti GDDR6 (448 GB/s) vs GDDR6X (608 GB/s)
- GT 1030 GDDR5 (48.1 GB/s) vs DDR4 (16.8 GB/s)
- GT 730 GDDR5 64-bit vs DDR3 64-bit
- RTX 4060 Ti / RTX 5060 Ti 8GB vs 16GB, GTX 960 2GB vs 4GB, plus Nvidia-listed
  Kepler VRAM splits (680, 670, 660 Ti, 660, 770, 780)

## Consistency

`fp32TFLOPS` and `memBandwidthGBs` were **not typed by hand**. Every record was emitted
by a generator that computes `fp32 = shaders * boostClockMHz * 2 / 1e6` and
`bandwidth = memBusBits * memClockMTs / 8000` from the shader/clock/bus/memory-rate
figures I entered, so both validator rules hold exactly (0% error, not 8%). The
generator also asserts: unique ids, no `rayTracing: true` without `meshShaders: true`,
and non-null `dxFeatureLevel`/`shaderModel` on every record.

Family monotonicity was checked by eye across the printed table; no higher-tier part
has fewer shaders than a lower-tier one in the same family.

## Capability gates

`meshShaders: true` on 47 records — exactly the Turing and later parts (TU1xx, GA1xx,
AD1xx, GB2xx), including the GTX 16 series, which has mesh shaders and VRS but no RT
cores. False on Pascal, Volta, Maxwell, Kepler, Fermi. This is the gate that decides
"GTX 1660 runs Alan Wake 2, GTX 1060 does not", and it is the field I checked hardest.

`rayTracing: true` on 40 records — RTX Turing and later only. GTX 16 series is
`meshShaders: true, rayTracing: false`, which is the load-bearing distinction.

Other gate mappings (all uniform per architecture):

| Arch | dxFeatureLevel | shaderModel | driverStatus |
|---|---|---|---|
| Fermi | 11_0 | 5_1 | eol (`driverEolDate` 2019-04) |
| Kepler | 11_0 | 6_0 | eol (`driverEolDate` 2024-09) |
| Maxwell 1 (GM107) | 11_0 | 6_0 | legacy |
| Maxwell 2 / Pascal / Volta | 12_1 | 6_5 | legacy |
| Turing (incl. GTX 16) | 12_2 | 6_7 | maintenance |
| Ampere / Ada / Blackwell | 12_2 | 6_7 | current |

`resizableBar` true from Ampere onward; `av1Encode` true only on Ada and Blackwell
(Ampere is AV1 decode only). `upscaling` lists `dlss` only where Tensor cores exist
(RTX 20+); `fsr`/`xess`/`tsr` are listed as software-runnable from Maxwell 2 onward,
`fsr` only on Kepler/Maxwell 1, empty on Fermi.

## Expected vs actual: where the ~40-record gap is

I count roughly 116 retail desktop GeForce SKUs across these generations, so 112 is
close to full **retail** coverage. The gap against the 130–170 estimate is almost
entirely categories I chose not to emit, and I would rather report the shortfall than
fill it with guesses:

1. **Dual-GPU flagships (3 SKUs: GTX 590, GTX 690, GTX Titan Z)** — deliberately
   omitted. `GpuRecord` has one `shaders`, one `memBusBits`, one `vramGB`. Emitting the
   combined shader count would make any fitted performance model overestimate these
   cards enormously (AFR scaling was 0–70% then, and modern titles do not support SLI
   at all), and emitting the marketed VRAM overstates the usable framebuffer by 2x
   because AFR mirrors it. Both options silently corrupt the model, which the brief
   ranks as worse than a gap.
2. **OEM-only SKUs (~15)** — GTX 555, GTX 545, GT 545, GT 530, GT 420, GT 405,
   GTX 460 v2, GTX 645, GTX 660 OEM (1152-core), GTX 760 Ti (OEM), GTX 745, GT 605,
   GT 635, GT 705, GT 1010. Real parts, but I do not have confident clock/TDP figures
   for them and they were never sold at retail.
3. **Low-end names covering multiple different GPUs (~6)** — GT 630 (GF108 / GK107 /
   GK208 revisions), GT 620, GT 640 GDDR5 and GK208 revisions, GT 730 GF108 128-bit
   DDR3 revision. Splitting these correctly needs the PCI-ID tables, which are exactly
   what the egress policy blocks. I emitted only the revisions I could pin down.
4. **Regional / respin variants (~5)** — GTX 1060 5GB (China), RTX 4090 D, RTX 5090 D,
   RTX 4070 GDDR6 (2024 respin), RTX 3050 8GB GA107 115W respin.
5. **AIB-only double-VRAM variants below Kepler (~5)** — GTX 460 2GB, GTX 560 Ti 2GB,
   GTX 650 Ti 2GB, GTX 750 Ti 1GB and similar. I did split the Kepler-era ones Nvidia
   listed on its own spec pages (680, 670, 660 Ti, 660, 770, 780) but drew the line
   there rather than manufacturing a long tail of near-identical records.
6. **Any RTX 50 "Super" refresh** — if Nvidia shipped Blackwell refresh parts after my
   knowledge cutoff, they are missing. I have no reliable shader/clock/VRAM figures for
   them and the brief forbids guessing, so I emitted nothing.

If the 130–170 estimate assumed OEM parts and every AIB VRAM permutation counted
separately, items 2, 3 and 5 (~26 SKUs) explain most of the difference.

## Fields I had to null

Out of 112 records:

| Field | Nulls | Why |
|---|---|---|
| `lengthMm` | 68 | Board length is an AIB decision with 40–80mm spread. Populated only for Founders Edition / reference designs I am confident of. |
| `driverEolDate` | 70 | Populated only for Fermi (2019-04) and Kepler (2024-09). Maxwell/Pascal/Volta moved to legacy after my cutoff and I do not know the branch end date. |
| `fp32TFLOPS` | 17 | All Fermi — see below. |
| `caps.vulkanVersion` | 17 | All Fermi. I could not confirm whether the 390 branch ever exposed Vulkan on GF1xx. |
| `baseClockMHz` | 1 | RTX 5050 only. |
| `powerConnectors` | 1 | GTX 1630 only (75W part, board vendors differ on whether a 6-pin is fitted). |
| `pciIds` | 112 | Omitted entirely — PCI device IDs come from driver INF tables, which are egress-blocked. The alias resolver gets nothing from this seed. |

Everything else — `shaders`, `boostClockMHz`, `vramGB`, `vramType`, `memBusBits`,
`memBandwidthGBs`, `tdpW`, `architecture`, `chip`, `processNm`, `pcieGen`, `pcieLanes`,
`slotWidth`, `recommendedPsuW`, all of `caps`, `driverStatus` — is populated on all
112 records.

## What I am least sure about

Ordered by how much a mistake would cost:

1. **Fermi FP32 is null on purpose.** Fermi clocks its shader array at 2x the core clock
   (GTX 580: 772 MHz core, 1544 MHz shader). `GpuRecord` has no shader-clock field, and
   the validator computes FP32 from `shaders * boostClockMHz * 2`, which for a Fermi
   part gives exactly half the real figure. I set `baseClockMHz = boostClockMHz = core
   clock` (Fermi has no boost algorithm, so the core clock is the max clock) and left
   `fp32TFLOPS` null rather than emit a 2x-low number or misuse `boostClockMHz` to hold
   a shader clock. **Correct values for the harvest to restore:** GTX 480 1.345,
   GTX 580 1.581, GTX 570 1.405, GTX 560 Ti 1.263, GTX 560 Ti 448 1.312, GTX 560 1.089,
   GTX 470 1.089, GTX 465 0.855, GTX 460 1GB / 768MB 0.907, GTX 460 SE 0.749,
   GTS 450 0.601, GTX 550 Ti 0.691 TFLOPS. If anything downstream derives FP32 from
   `shaders * boost * 2` for these records, it will be 2x low.
2. **GTX 16 series feature level.** I set `dxFeatureLevel: 12_2` / `shaderModel: 6_7`
   per the brief's "12_2 for Turing+". Strictly, Nvidia does not certify TU116/TU117 as
   DirectX 12 Ultimate (no RT cores) and the driver reports feature level 12_1. I
   followed the brief because it also produces the better product behaviour: a mesh-
   shader title correctly runs on a GTX 1660, and an RT-required title is still blocked
   by `rayTracing: false`. Flagging it so the harvest does not "fix" it in the direction
   that starts wrongly blocking Alan Wake 2 on 16-series cards.
3. **RTX 5050.** Boost 2572 MHz is moderate confidence and base clock is null. Shaders
   (2560), GB207, 8GB GDDR6 128-bit, 130W are higher confidence. The most likely record
   in the RTX 50 block to need correction.
4. **GK107/GK208 low-end (GT 640, GT 710, GT 720, GT 730 ×2, GT 740).** These have no
   published boost clock, so `boostClockMHz` repeats the core clock and `fp32TFLOPS` is
   a ceiling rather than a sustained figure. TDPs (19W/25W/38W/64W/65W) are moderate
   confidence. GT 710/720/730 are set PCIe 2.0 x8.
5. **GTX 970's 4GB.** `vramGB: 4` is the marketed and physically-present configuration,
   but the last 512MB sits on a crippled partition at ~28 GB/s. A VRAM-ceiling gate
   treating it as flat 4GB will be optimistic by roughly 0.5GB.
6. **Asymmetric memory parts.** GTX 660, GTX 660 Ti, GTX 550 Ti and GTX 1050 3GB do not
   have uniform bandwidth across the whole framebuffer; `memBandwidthGBs` is the peak.
7. **`recommendedPsuW`** is Nvidia's published minimum system power, not a measured
   transient figure. High-spike parts (3080 Ti, 3090 Ti, 4090) can trip protection on a
   nominally compliant unit.
8. **`powerConnectors`** describes the Founders Edition / reference design. AIB cards
   routinely differ (RTX 3090 FE is 12-pin, AIB cards 3x8-pin; RTX 5070 AIB cards often
   ship 1x8-pin).
9. **`caps.vulkanVersion`** is a driver-branch property, not hardware. Kepler is pinned
   at 1.2 because its branch froze before Vulkan 1.3; Ada/Blackwell are 1.4; everything
   between is 1.3. Low stakes but not a hardware fact.
10. **`shaderModel` bucketing.** Kepler/Maxwell 1 at 6_0 and Maxwell 2/Pascal/Volta at
    6_5 follows the brief's coarse mapping. Nvidia's current driver reports a higher SM
    level on these parts than their actual feature support justifies, so a strict
    SM-based gate built on this seed will be conservative for them.
11. **Launch dates** for AIB double-VRAM variants (GTX 680 4GB, 670 4GB, 660 3GB,
    660 Ti 3GB, 770 4GB, 780 6GB, GTX 960 4GB, RTX 3060 Ti GDDR6X, RTX 3060 8GB) are
    month-granularity approximations of retail availability, not Nvidia announcements.
12. **`processNm` for Ada/Blackwell is 5** — TSMC 4N and 4NP are 5nm-class nodes, not a
    separate 4nm node. Pascal is 16 except GP107/GP108 which are 14 (Samsung).

## Things a reviewer should not mistake for errors

- `boostClockMHz == baseClockMHz` on Fermi and on non-boost Kepler parts (GTX 650,
  GTX 650 Ti, GT 6xx/7xx) is intentional: those parts have no GPU Boost.
- The four NVIDIA-branded Titans use `nvidia-titan-*` ids (Titan X Pascal, Titan Xp,
  Titan V, Titan RTX) because they are not GeForce-branded; GTX Titan, Titan Black and
  Titan X (Maxwell) use `nvidia-geforce-*`.
- Titan V is the only Volta record and is `meshShaders: false` — Volta predates the
  mesh-shader pipeline despite being newer than Pascal.
