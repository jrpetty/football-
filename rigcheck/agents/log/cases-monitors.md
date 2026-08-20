# cases + monitors — catalogue agent log

## What I produced

- `rigcheck/data/catalogue/cases.json` — **48 records**, target was 35–50.
- `rigcheck/data/catalogue/monitors.json` — **37 records**, target was 30–45.

Both parse (`node -e JSON.parse`), both have `count` equal to `records.length`, both carry
the single `model-knowledge` provenance entry, and every record carries
`"_prov": {"*": ["model-knowledge"]}`. No other file was written.

Wikipedia and vendor spec pages are egress-blocked in this environment, so **nothing here
was verified against a source**. Every figure is recalled, not read.

### cases.json coverage

48 cases across 16 brands, release years 2010–2023.

| formFactor | count |     | airflowTier | count |     | noiseTier | count |
|---|---|---|---|---|---|---|---|
| mid-tower | 38 |  | excellent | 14 |  | silent | 3 |
| sff | 6 |  | good | 17 |  | quiet | 10 |
| micro-atx | 2 |  | moderate | 12 |  | moderate | 35 |
| mini-itx | 1 |  | restricted | 5 |  | loud | 0 |
| full-tower | 1 |  |  |  |  |  |  |

Brands: Fractal Design (11), Lian Li (6), NZXT (5), Corsair (5), Cooler Master (5),
Phanteks (3), be quiet! (3), Hyte (2), plus Ncase, SSUPD, Montech, DeepCool, Antec,
Thermaltake, Dell, HP. Includes the requested SFF group (NR200P, Ncase M1, Fractal Terra,
SSUPD Meshlicious) and second-hand/OEM territory (HAF 912, NZXT S340, Define R5, Meshify C,
Focus G, Carbide 275R, Versa H18, Dell OptiPlex 7010 SFF, HP EliteDesk 800 G4 SFF).

### monitors.json coverage

37 monitors across 11 brands, release years 2013–2024.

| resolution | count |    | panelType | count |    | adaptiveSync | count |
|---|---|---|---|---|---|---|---|
| 1080p | 13 |  | IPS | 21 |  | gsync-compatible | 18 |
| 1440p | 12 |  | QD-OLED | 5 |  | freesync | 10 |
| 2160p | 7 |  | VA | 5 |  | none | 5 |
| 3440x1440 | 5 |  | OLED | 3 |  | gsync (module) | 4 |
|  |  |  | TN | 3 |  |  |  |

Refresh spread: 60, 75, 144, 160, 165, 170, 175, 180, 240, 280, 360 Hz — from 1080p60
office panels (Dell P2419H) through the esports tiers (VG248QE, XL2411P, XL2546K, AW2521H
360Hz) to 4K 240Hz QD-OLED (PG32UCDM, MPG 321URX) and 4K/240 + 1080p/480 dual-mode
(LG 32GS95UE).

## Fields I most often had to null

### cases.json — the clearance millimetres, overwhelmingly

| field | nulls / 48 | why |
|---|---|---|
| `maxPsuLengthMm` | 45 (94%) | Almost nobody memorises this. I emitted it only for the three Corsair chassis where I am confident (4000D/4000X 180mm, 5000D 225mm). |
| `maxGpuLengthMm` | 35 (73%) | See below — nulled wherever two plausible numbers competed in memory. |
| `maxCoolerHeightMm` | 35 (73%) | Same. |
| `radiatorSupport` | 31 (65%) | Nulled unless I could recall the actual mount sizes rather than infer them from case size. |
| `includedFans` | 8 (17%) | Nulled where the count differs between SKUs of the same case (P500A plain vs D-RGB, Lancool II Mesh plain/RGB/Performance, NR200 vs NR200P, Focus G colour variants, Meshify 2 Compact). |

**34 of 48 cases carry no clearance millimetres at all.** That is the headline gap in this
file and it is deliberate: per the brief, a null is a reported gap and a wrong millimetre
silently breaks a fit check. The cases that *do* carry numbers are:

- GPU length: North 355, Meshify 2 315, Meshify C 315, Define 7 315, Define R5 440,
  O11 Dynamic 420, H510 381, H210 325, 4000D 360, 4000X 360, 5000D 400,
  Pure Base 500DX 369, NR200P 330.
- Cooler height: North 170, Meshify 2 185, Define 7 185, Define R5 180, O11 Dynamic 155,
  H510 165, H210 165, 4000D 170, 4000X 170, 5000D 170, Pure Base 500DX 190, NR200P 155,
  Ncase M1 130.
- PSU length: 4000D 180, 4000X 180, 5000D 225.

`airflowTier` and `noiseTier` are populated on all 48, as required.

### monitors.json

| field | nulls / 37 | why |
|---|---|---|
| `typicalPriceGBP` | 13 (35%) | Nulled for discontinued models where the second-hand spread is too wide to be informative (27GL850, 27GN950, 34GN850, AW2521H, AW3423DW, U2720Q, P2419H, G7 32, VG248QE, PG279Q, XB271HU, XL2411P, C24G1). |
| `hdr` | 12 (32%) | **Mostly not a gap.** For the TN esports panels, the office IPS panels and the AOC/Acer budget models, `null` means the display genuinely has no HDR support, which is the correct value. |
| `responseMs` | 6 (16%) | Nulled where the manufacturer's headline is MPRT/backlight-strobing rather than GtG (AOC 24G2, C24G1, CU34G2X, Acer VG240Y, Gigabyte M27Q, Samsung G5). Passing a 1ms MPRT figure off as a response time would be a lie with a number attached. |

`refreshHz` cannot be nulled — the type requires a number — so where I was unsure I still
had to commit. That is called out per-panel below.

## What I am least confident about

### Cases — clearance figures (read this before trusting a fit check)

1. **Define R5 `maxGpuLengthMm: 440`** — this is the published maximum *with the middle
   drive cage removed*. With the cage in place the real clearance is far shorter (I recall
   ~310mm but did not emit it). A fit check using 440 will pass cards that do not fit a
   stock R5.
2. **Meshify 2 / Define 7 `maxGpuLengthMm: 315`** — the opposite problem: 315 is the figure
   *with a front fan installed*, and the open-layout maximum is much larger (somewhere in
   the 460s, which I would not commit to). This under-promises rather than over-promises,
   which is the safer direction, but it is not "the maximum".
3. **NR200P `maxCoolerHeightMm: 155`** — only true with the vented steel side panel. With
   the tempered glass panel fitted it drops to roughly 76mm. Both facts are in the record's
   notes, but the single number cannot express the conditional.
4. **O11 Dynamic 420 / 155** — the least-verified pair I chose to emit. Moderate confidence
   only; if anything in this file is a misremembering, this is a good candidate.
5. **Pure Base 500DX 369 / 190** — 369mm is an oddly specific figure that I believe is
   right, and 190mm cooler clearance is unusually generous for the size, which is exactly
   the shape of a number that gets misremembered. Flagging rather than nulling because my
   recall here is genuinely firm, but it deserves early re-verification.
6. **North 355 / 170** — reasonably confident, unverified.
7. **`includedFans` I did emit from marketing recall** rather than a spec table: Montech
   AIR 903 MAX (4), Antec DF700 Flux (5), Lancool III (4), Torrent (5), Lancool 215 (3).
   Torrent's 5 (2×180 front + 3×140 floor) I am confident about; the Montech is the shakiest.
8. **Release years for the older cases** are ±1: HAF 912 (2010), S340 (2014), Define R5
   (2014), Meshify C (2016), Pure Base 600 (2016), Focus G (2017).

### Cases — two structural caveats

- **`airflowTier` and `noiseTier` are my judgement, not vendor data.** They cannot be
  harvested later from a spec page the way a millimetre can, so they will stay
  judgement-based unless someone replaces them with review measurements. Two calls worth
  arguing with: the **O11 Dynamic family is tiered `good`** even though it ships with *zero*
  fans — that is its potential with intake populated, and run bare it is poor; and
  **Silent Base 802 / Define 7** are tiered for the damped configuration they ship in, not
  for the mesh panels the 802 also includes in the box.
- **No case is tiered `loud`.** I did not want to award that tier to a case I could not
  honestly defend — the obvious candidates (Versa H18, HAF 912) are cheap and unrefined but
  not actually loud at stock. If the thermal model needs the tier exercised, it needs an
  Antec Nine Hundred-class case added, not a relabelling of one of these.
- **`radiatorSupport: null` conflates "unknown" with "none".** For the Dell/HP OEM SFFs,
  Terra and the OptiPlex the honest value is "no radiator support"; for NR200P and Lancool
  III it means "I could not recall the mounts". The schema has no way to distinguish these.

### Monitors

1. **`typicalPriceGBP` is the weakest field in either file.** My knowledge ends in May 2026,
   these are UK street-price ballparks I never checked, and monitor pricing moves fast.
   Treat all 24 populated values as ±30% and the OLED figures (32GS95UE 1000, PG32UCDM 1000,
   MPG 321URX 850, 27GR95QE 600) as the least reliable of those.
2. **Gigabyte M27Q `refreshHz: 170`** — 165 and 170 are both plausible to me and the field
   cannot be nulled. Flagged in the record's own `notes` as well as here. This is the single
   most likely wrong number in monitors.json.
3. **ASUS VG259QM `refreshHz: 280`** — correct as the headline, but I have described it as
   240Hz native overclocked to 280; I am confident about 280, less so about the 240 native
   claim in the note.
4. **Refresh convention.** `refreshHz` is the headline maximum *including* factory
   overclock, because that is what the owner actually runs. Affected: 27GP850-B (180, 165
   native), 27GN950-B (160, 144), 34GN850-B (160, 144), PG279Q (165, 144), XB271HU (165,
   144), VG259QM (280, 240). If a downstream consumer wants guaranteed-native rates, these
   six need re-reading.
5. **Samsung model-code parentheticals** (`G40B`, `G55T`, `G75T`) are moderate confidence;
   I dropped the codes entirely on the Neo G7 and OLED G8 rather than risk inventing one.
6. **`hdr` strings on the mid-tier panels.** DisplayHDR 600 on the 27GN950-B and the
   Odyssey G7 32in is moderate confidence; "HDR10" on the M27Q, 24GN650-B and Odyssey G4
   means "accepts an HDR10 signal", not a certification, and should not be read as one.
   The Neo G7's `"HDR10+ (Samsung Quantum HDR 2000)"` is Samsung's own branding — deliberately
   not written as a VESA tier, because it is not one.
7. **The emitted `responseMs` values are still manufacturer claims**, not measurements.
   Nulling the MPRT ones removes the worst offenders but does not make 1ms GtG true.
8. **Dell P2419H `responseMs: 5`** is the fast-mode figure; the normal-mode claim is 8ms.

## Coverage gaps I could not close

- The `resolution` enum has no 16:10 or 2560x1080 member, so I could not include the
  1920x1200 office panels (Dell U2412M/U2415 and similar) or the cheap 21:9 ultrawides,
  despite both being very common "monitor I already own" hardware for second-hand buyers.
  Samsung's Odyssey G9 line (5120x1440) is excluded for the same reason.
- No 500Hz+ 1080p esports panels: I could not pin down model names and specs confidently
  enough to emit them.
- Cases: no Silverstone, InWin, BitFenix or Antec Nine Hundred-era towers, and only two OEM
  chassis. The pre-2014 second-hand market is thinner here than it should be, because that
  is where my clearance recall degrades fastest and I would rather be short than wrong.
- I did not pad either file to hit the top of its range.
