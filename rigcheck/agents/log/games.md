# games — catalogue agent log

## What I produced

`rigcheck/agents/out/games.json`: 50 `GameRecord` entries, the exact fixed list from my
remit, no substitutions and no additions.

Expected 50, actual 50, **no shortfall**. The list was specified rather than discovered,
so there is no coverage gap in the usual sense — the gaps are per-field nulls, not
missing records (see below).

Archetype distribution, verified programmatically:

| archetype | count |
|---|---|
| esports | 10 |
| sim-cpu | 10 |
| aaa-raster | 12 |
| aaa-rt | 8 |
| ue5 | 5 |
| vram-heavy | 5 |

Every record carries `"_prov": {"*": ["model-knowledge"]}`. JSON parses (`node -e
JSON.parse`). Checks run and passing: 50 records, no duplicate ids, `minThreads >=
minCores` wherever both are set, `vramDemandGB` strictly monotonic in resolution for all
50, exactly 12 `coreLoop` records matching the specified 12, exactly one `meshShaders`,
exactly one `rayTracingRequired`.

## Fixed constraints from the remit — all satisfied

- `coreLoop: true` on exactly: Counter-Strike 2, Dota 2, Rainbow Six Siege, Fortnite,
  Factorio, Total War Warhammer III, Cyberpunk 2077, Forza Horizon 5, Shadow of the Tomb
  Raider, Black Myth Wukong, Hogwarts Legacy, Alan Wake 2. Count = 12.
- Alan Wake 2: `meshShaders: true` — the only record with it.
- Metro Exodus Enhanced Edition: `rayTracingRequired: true` — the only record with it.
- Elden Ring: `fpsCap: 60`.
- Counter-Strike 2 peaks at 3.4GB (2160p) — under 4GB at every resolution.
- The Last of Us Part I 1440p = 8.8GB, Hogwarts Legacy 1440p = 8.5GB — both genuinely
  over the 8GB cliff, as required.

## Field-by-field notes

### builtInBenchmark (20 of 50 true)

True only where a deterministic benchmark ships with the game or as a standalone tool:
Rainbow Six Siege, Factorio (`--benchmark`), Total War Warhammer III, Anno 1800, RDR2,
Horizon Forbidden West, Forza Horizon 5, Shadow of the Tomb Raider, Hitman WoA, Ghost of
Tsushima, AC Shadows, Cyberpunk 2077, F1 24, Metro Exodus EE, Far Cry 6, Watch Dogs
Legion, Spider-Man 2, Black Myth Wukong, The Last of Us Part I, Ratchet & Clank.

Deliberate falses worth flagging:

- **Counter-Strike 2** — no official benchmark ships; reviewers use community workshop
  maps or demo playback, which is not deterministic across game builds.
- **Dota 2** — replay playback, not a benchmark mode.
- **Alan Wake 2, Hogwarts Legacy** — both in the core loop, neither has a benchmark, so
  the harness needs a scripted manual route for them.
- **Civilization VII** — the one I would most expect to be wrong. Civ V and VI both
  shipped graphics and AI benchmarks and VII plausibly does too, but I could not confirm
  it. I resolved it to `false` because a false negative merely excludes it from the
  harness suite, whereas a false positive puts a nonexistent fixture in the fitting set.

Three benchmarks ship multiple scenes with materially different CPU/GPU weighting (Total
War Warhammer III battle vs campaign, Hitman Dubai vs Dartmoor, RDR2's multi-scene run).
Fixtures from these are not comparable to each other unless the scene is recorded in the
`SettingsFingerprint`.

### fpsCap

Set on 4 records only: Elden Ring 60 (specified, hard engine cap), Factorio 60 (render
rate is bounded by the fixed 60 UPS update rate), Overwatch 2 600 (limiter ceiling), Apex
Legends 300 (`fps_max` is clamped there even when set to unlimited). Null everywhere
else. The Overwatch 2 and Apex figures are the two I am least sure of; both are high
enough that they will rarely bind, so the downside is small.

### requirements

Policy I applied, because these drive WILL_NOT_RUN and a false gate is much worse than a
missing one: **`minCores`/`minThreads` take the lowest core/thread count among the
published minimum CPUs** (permissive rather than over-gating), and **anything I could not
confirm is null rather than estimated**. Nulls: `minVramGB` 4, `minCores` 7, `minThreads`
7, `minSystemRamGB` 1, `minShaderModel` 2.

Two deliberate `minShaderModel: null` decisions worth calling out:

- **Starfield** really requires Shader Model 6.6, but the catalogue's shader-model
  vocabulary (`6_7` / `6_5` / `6_0` / `5_1` per the brief) cannot express 6.6 without
  falsely gating Pascal — and Pascal (GTX 1070 Ti) is Starfield's own published minimum
  GPU. Null is the honest encoding.
- **Alan Wake 2** — mesh shaders imply SM 6.5, but I left the shader model null so
  `meshShaders: true` is the single authority for that gate. I set
  `minDxFeatureLevel: "12_2"` alongside it, which is consistent (12_2 is exactly
  Turing+/RDNA2+/Arc, the same hardware set) rather than an independent second gate.

Where a game exposes both DX11 and DX12 I set `minDxFeatureLevel: "11_0"` /
`minShaderModel: "5_1"`, i.e. the floor of the lower path. DX12-only games get `12_0` /
`6_0`. Since the GPU catalogue's lowest feature level is `11_0`, the DX11 games are
effectively ungated on that axis, which is correct.

**Known over-gate: The Witcher 3 Next-Gen.** Its requirements are the published v4.x
minimums (6 cores, 6GB VRAM), but the retained DX11 path runs far below them. Builds that
would play it fine will be gated. I kept the published figures because the record is
explicitly the next-gen release, but this is the one entry where I expect user-visible
false negatives.

### vramDemandGB — the field most likely to be wrong

These are estimates of **working-set demand at a high preset**, not measured allocation.
That distinction matters most for adaptive streamers (Black Ops 6 above all, also Diablo
IV and Far Cry 6), which will allocate most of whatever VRAM exists; a review's measured
allocation figure for those titles is not the same quantity as what I recorded and should
not be used to "correct" it naively.

Least confident, in descending order:

1. **Star Citizen** (7.0 / 8.5 / 11.0) — perpetual alpha, demand changes patch to patch,
   and client performance is dominated by server tick and streaming. Widest error bars in
   the file by a distance.
2. **Microsoft Flight Simulator 2024** (6.0 / 8.0 / 11.0) — streaming-first architecture
   means demand varies enormously with scenery density and whether assets have arrived.
   A single triple is a poor summary of this game.
3. **Escape from Tarkov** (5.0 / 6.5 / 8.5) — targeted at Streets of Tarkov, the worst
   case; other maps are 1.5-2GB lighter, so the map choice dominates the figure.
4. **Cities Skylines II** (5.0 / 6.5 / 8.5) — unusually GPU-heavy for a city builder
   because of HDRP and launch-state LOD behaviour, and patches have moved it.
5. **Assassin's Creed Shadows** (6.5 / 8.0 / 10.5) — recent, and the RTGI-on vs raster
   fallback split changes it substantially.
6. **Call of Duty Black Ops 6** (6.5 / 8.0 / 10.5) — the allocation-vs-demand problem
   above, which also makes it hard to validate against published review data.
7. **Civilization VII** (3.5 / 4.5 / 6.0) — recent, and I had little to anchor on.

Also flagged, for a different reason: **Factorio** (2.0 / 2.2 / 2.6). Its VRAM is sprite
atlas dominated, so it barely scales with resolution. That deliberately breaks the
resolution-scaling assumption the other 49 records share, and it is correct — but any
fitting routine that assumes monotonic pixel-count scaling will find Factorio anomalous.

Moderate confidence and load-bearing for the cliff: The Last of Us Part I and Hogwarts
Legacy. Both are set just over 8GB at 1440p per the remit and match my recollection of
review behaviour, but TLOU in particular was patched down substantially from its
release-day demand — I recorded the patched state, which is a judgement call about which
version the catalogue describes.

### upscalingSupport

`["none"]` (rather than an empty array) encodes "native only" for 9 records: Valorant,
Dota 2, League of Legends, Apex Legends, Rocket League, Factorio, Stellaris, Elden Ring,
ARMA Reforger. I used a one-element `["none"]` so that a downstream lookup for the
`NATIVE` setting still finds a match; an empty array would have read as "no rendering
mode supported".

Uncertain entries, all asserted from patch-history recollection I could not verify:
PUBG (FSR), Overwatch 2 (FSR), Total War Warhammer III (FSR, DLSS assumed absent), Anno
1800 (FSR), Star Citizen (FSR), Cities Skylines II (DLSS + FSR), Metro Exodus EE (DLSS
only — FSR may have been added later), and **ARMA Reforger**, where I asserted none and
have a weak contrary recollection of an upsampling option in Enfusion's video settings.
This field cannot be nulled (it is a required array), so unlike the numeric fields every
entry here is an assertion rather than an honest gap. That makes it structurally the
second-riskiest field in the file after `vramDemandGB`.

### Archetype fit — where the assigned archetype misdescribes the game

The archetypes were fixed by my remit and I did not deviate, but four assignments will
produce systematic residuals when the CPU weights are fitted, and the fitter should know:

- **Baldur's Gate 3** (aaa-raster) — Act 3 Lower City is heavily CPU bound.
- **The Finals** (ue5) — server-authoritative destruction physics is a large CPU load the
  ue5 archetype assumes away.
- **Stalker 2** (ue5) — A-Life simulation, same problem in milder form.
- **Starfield** (aaa-raster) — cities are CPU bound well below the raster assumption.

Additionally **Hellblade II** renders at a fixed cinematic aspect with letterboxing, so
its effective pixel count is below nominal; the resolution-scaling term will overstate
its GPU load unless corrected.

## Other omissions, stated deliberately

- No `cpuWeightOverride` on any record. Per-game weights need fixture data to fit; seeding
  them from model knowledge would be inventing the exact parameters the model is supposed
  to learn.
- `vramDemandGB` covers `1080p` / `1440p` / `2160p` only. `3440x1440` is absent so the
  estimator interpolates from pixel count rather than reading a fabricated number — and
  ultrawide is the one resolution where I have essentially no recollection of measured
  data for any of these titles.
- Years for live-service and early-access titles are initial release, not current build:
  Escape from Tarkov 2017 (closed beta), Star Citizen 2015 (persistent universe alpha, no
  1.0), Factorio 2020 (1.0), Fortnite 2017 (now on UE5), Hitman World of Assassination
  2023 (engine lineage is Hitman 3, 2021). PC-port years are used for the PlayStation
  ports (Horizon Forbidden West 2024, God of War Ragnarok 2024, Ghost of Tsushima 2024,
  Spider-Man 2 2025, Ratchet & Clank 2023, The Last of Us Part I 2023).
