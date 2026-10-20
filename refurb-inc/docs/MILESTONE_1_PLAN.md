# Milestone 1 Plan: Polished Solo Garage Vertical Slice

**Goal:** One home garage that looks good enough for a Steam screenshot, with the full laptop loop playable solo:
buy a lot → lorry delivers → haul in → USB test → Level 1 repair → USB image → grade → shelve → list on Flogit → order arrives → pack → courier collects → cash → rent.

**Status:** Draft, awaiting Jon's go-ahead.

---

## 1. Key technical decisions (proposed)

| Area | Decision | Why |
|---|---|---|
| Engine | Godot 4.x latest stable, **Forward+** renderer, GDScript (typed) | As briefed. Forward+ needed for SSAO/SSIL/volumetric fog. |
| Networking from day one | All state mutations go through a host-authoritative **command layer** (`request_*` RPC → host validates → host mutates → state replicated). In M1 the peer is `OfflineMultiplayerPeer`, so solo play runs the *same* code path as a host. M2 swaps in `SteamMultiplayerPeer` (GodotSteam) plus an ENet peer for local two-window testing. | M2 becomes wiring, not a rewrite. |
| State vs visuals | Game state lives in plain typed classes (`UnitState`, `LotState`, `OrderState`…) held in host registries, serialisable to `Dictionary`. 3D nodes are *views* that read state by id. | One format for save files, network sync and tests. Visual swaps never touch logic. |
| Content data | **JSON files in `data/`** (hardware models, CPUs, parts, faults, cosmetic conditions, sellers, Flogit buyer names, economy tuning) loaded into a `ContentDB` autoload with validation. | Spreadsheet/diff-friendly, easy for you to add content, moddable later. |
| Stations | One `StationBase` (accept carried item → slot → timer → mutate unit data → output/display). Test bench, imaging, repair, grading and packing are thin subclasses plus data. | Matches §22. New station = one scene plus a small script. |
| Interaction | One `Interactable` contract: `get_hover_text()`, `can_accept(item)`, `interact(player)`, `interact_hold(player, delta)`. Used by everything. | Consistent feel, easy to extend. |
| Physics feel | Held items follow a hold point with damped spring and sway, and snap to valid slots with a short tween and a clunk. Dropped items become `RigidBody3D` (host-simulated later). | Physics-y but controlled. No juggling frustration. |
| Time | `GameClock` autoload. Day = 09:00–17:00 over 900 real seconds (1 game hour = 112.5 s). Never pauses for menus or the office PC. Pause only in solo, and only from the Esc menu. | §4. |
| Save/load | Basic save in M1 (state is serialisable anyway). Full polish in M3. | Cheap now, painful later. |
| Tests | GUT addon. Headless tests for lot generation, pricing, fault rolls, the economy ledger, the clock and Flogit order rates. | §22. |
| Binary assets | Git LFS for `.glb .png .jpg .exr .hdr .wav .ogg` (see question in chat). | Keeps the repo usable. |

**Imaging OS name (proposed):** **PaneOS**, with the desktop shell also called PaneOS (a fictional OS with a window-pane logo). Alternatives: *Strata*, *Hearth OS*.

---

## 2. Folder structure

```
refurb-inc/
  project.godot
  CLAUDE.md
  docs/                      plans, asset sources, AI prompts, credits/licences
  addons/                    gut/ (M1), godotsteam/ (M2)
  assets/
    models/
      environment/           garage shell pieces, roller door, strip lights
      props/<prop_name>/     one folder per prop: source .glb + textures
      hardware/<model>/      laptops (later desktops/servers/switches)
      vehicles/              lorry, courier van
    textures/
      surfaces/              PBR sets (albedo/normal/roughness/AO)
      decals/                stains, cracks, posters, brand logos, labels
      ui/                    icons, wallpapers
    hdri/
    audio/{sfx,ambience,ui,music}/
    fonts/
  data/                      *.json content definitions
  scenes/
    main.tscn
    levels/garage/garage.tscn
    player/player.tscn
    props/<prop>.tscn        WRAPPER scenes: swap the mesh inside, nothing else changes
    hardware/laptop.tscn
    stations/                test_bench, repair_bench, imaging_bench, grading_desk,
                             packing_station, shelf_unit, parts_shelf, courier_point
    vehicles/                lorry.tscn, courier_van.tscn
    office_pc/office_pc.tscn
  scripts/
    autoload/                ContentDB, GameClock, Net, Company (cash/rep), Registry,
                             SaveManager, AudioBus
    state/                   unit_state.gd, part_state.gd, lot_state.gd, order_state.gd
    systems/                 purchasing.gd, delivery.gd, sales_flogit.gd, finance.gd, lot_generator.gd
    interaction/             interactable.gd, carryable.gd, snap_slot.gd
    stations/                station_base.gd + subclasses
    player/                  player_controller.gd, interactor.gd, carrier.gd, head_bob.gd
  ui/
    theme/                   one Theme resource, fonts, colours
    hud/                     crosshair, hover tag, clock, hold-progress ring, toasts
    office_os/               PaneOS desktop + apps/{purchasing,shop,stock,flogit,finance}
    menus/                   main menu, pause, settings, end-of-day summary
  tests/unit/
```

Scale rule: 1 unit = 1 metre. Every prop wrapper scene has its origin at the floor contact point, faces −Z, and has a collision shape plus LODs (auto-generated on import).

---

## 3. Gameplay spec for M1 (laptops only)

- **Starting position:** £2,500 cash, rent £120/week (Fridays at 17:00), one test bench, one repair bench, one imaging spot, a packing table, one metal shelving unit, a parts shelf, the office PC, 1× test USB and 1× imaging USB.
- **Lots:** 3–6 lots visible in Purchasing at once, refreshing daily. Each lot has 4–20 laptops, a seller (e.g. "Ashby Parish Council"), an age, seller notes, a price per unit swung 60–130% of hidden value, and a hidden quality tied to that swing (cheap = more faults and worse cosmetics). Fixed price, buy-it-now only in M1. Rival bids arrive in M6.
- **Delivery:** bought before 15:00 → lorry arrives at a scheduled slot the next morning, beeps while reversing onto the drive, and drops a pallet. Laptops are picked individually. You can carry a stack of up to 3 laptops; that grows with the Hauling skill later.
- **Inspect:** hover shows a cosmetic tag only ("Light scuffs on lid", "Missing key (Q)", "Cracked screen").
- **Test (USB):** place the laptop on the test bench and plug in the USB. It runs for 30 game minutes (about 56 s real). The laptop screen shows a POST then diagnostics UI. The result appears on the screen and as a floating tag (PASS / fault list), and a test report is stored on the unit.
- **Faults in M1:** Level 1 can be repaired (battery, RAM DIMM, SSD/HDD, keys). Level 2–3 faults (cracked screen, no POST/board fault) can roll, but you can't repair them in M1. Those units become **"Spares/Repairs"** listings on Flogit, which is still an honest outcome.
- **Repair:** parts come from the Shop app and arrive with the next courier visit as a parcel. You unbox the parcel into the parts shelf. At the bench you hold interact to fill a progress bar (3–6 s depending on the part). This uses the part and clears the fault.
- **Image (USB):** wipe plus a PaneOS install over 45 game minutes. The screen shows wipe passes, then the install progress bar.
- **Grade:** a short hold at the grading desk (about 1.5 s) assigns A/B/C/D from cosmetics and spec. The label printer spits out a grade sticker. Sale price is preset and adjustable in the Stock/Flogit app.
- **Stock:** put the unit on the shelving, which has snap slots. The Stock app shows it live.
- **Sell on Flogit:** list stock (one click per unit, or "list all graded"). Orders roll each game hour with chance = f(reputation, price ÷ market value). Each order has a dispatch-by deadline.
- **Pack:** put the sold unit on the packing station and hold to box, foam and tape it. The label prints and the parcel is bound to the order. Place it at the courier point. The courier van collects at 12:00 and 16:30, then cash lands. Late or missed dispatch costs reputation.
- **End of day:** summary card with units processed, sales, profit, late orders and a star rating. Then the next morning begins.
- **Debt:** cash can go negative. Below −£500 you get a warning; after a week overdrawn, a bailiff letter costs reputation and adds a fee. Recoverable, never a game over in M1.

---

## 4. Task breakdown (in build order)

Each phase ends with a **"how to test it in the editor"** note from me.

**P0: Skeleton (≈1 session)**
project.godot (Forward+, input map, physics layers), folder tree, autoload stubs, GUT installed, `.gitignore` for `.godot/`, Git LFS attributes, a `docs/ASSETS.md` licence/credits register.

**P1: Feel first (≈2–3 sessions)**
First-person controller (accel/decel curves, sprint, crouch, head-bob toggle, FOV slider, footsteps by surface), interaction raycast plus hover tag, the carry/place/snap system, a hold-to-interact progress ring, and a greybox garage at real dimensions (≈6.5 × 7 m interior, 2.6 m ceiling, drive and pavement outside).

**P2: Core sim, headless-testable (≈2–3 sessions, runs in parallel with art)**
ContentDB plus JSON schemas, UnitState, LotGenerator (value curve, swing ↔ quality correlation, fault rolls, cosmetics), GameClock, Company ledger (cash, transactions, rent), Flogit demand model, and the GUT tests for all of it.

**P3: Garage art pass 1 (≈3 sessions), giving the first screenshot moment**
Real PBR surfaces, HDRI exterior, roller shutter, strip lights (emissive plus lights), LightmapGI bake, SSAO/SSIL, subtle glow, volumetric dust in the door light, reflection probe, tonemap and exposure tuning, decals (stains, tyre marks, cracks), and first prop dressing (shelving, pallets, boxes, kettle corner).

**P4: Laptop and station framework (≈3 sessions)**
Laptop scene: separate base and hinged lid, screen as a dynamic material (off / POST / diagnostics / wipe / PaneOS), variants driven by data (size, colour, bezel, logo decal). Also StationBase, the test bench, the USB stick prop, result display.

**P5: Office PC and PaneOS (≈3–4 sessions)**
Camera glides to the monitor. The UI renders in a SubViewport on the monitor mesh with mouse passthrough, and the clock keeps running. Apps: **Purchasing, Shop, Stock, Flogit, Finance** (Shop and Stock are added because M1 needs USBs/parts and live stock).

**P6: Vehicles and delivery (≈2 sessions)**
Lorry on a path with reversing beeps, tail-lift pallet drop, then drive-off. The courier van uses the same system. Tinted windows, so there are no drivers to model.

**P7: Repair, imaging, grading, shelving (≈3 sessions)**

**P8: Sales, packing, courier, reputation (≈2–3 sessions)**

**P9: Day/week cycle (≈2 sessions)**
HUD clock, end-of-day summary, rent, debt rules, save/load.

**P10: Juice, audio, menus, performance (≈3 sessions)**
Main menu (a garage backdrop), settings (graphics presets, head-bob, FOV, sensitivity, volume), audio mix, particles, UI animations, and a performance pass.

Performance target: 60 fps at 1080p on GTX 1060 / RX 580 class GPUs, medium preset. The garage should stay under ≈600k triangles visible and under ≈400 draw calls, with shadows on 1–2 key lights only.

---

## 5. Asset list and sources

The cloud container I work in **can't reach asset sites** (Poly Haven, ambientCG, Freesound, Kenney all blocked), so I'll give you exact search terms and you drop the files into the listed folders. Every asset is logged in `docs/ASSETS.md` with its licence.

### Environment textures: Poly Haven (polyhaven.com/textures) and ambientCG (ambientcg.com), CC0, 2K, download the PNG/JPG maps
| Use | Search for | Folder |
|---|---|---|
| Garage floor | Poly Haven "concrete floor worn", ambientCG "Concrete" (pick a stained, slightly polished one) | assets/textures/surfaces/concrete_floor/ |
| Walls | ambientCG "Bricks"/"Concrete blocks" (painted breeze block) **or** Poly Haven "painted plaster" | …/walls/ |
| Roller shutter | ambientCG "Corrugated steel"/"Metal" | …/shutter/ |
| Driveway / pavement | Poly Haven "asphalt", "paving slabs" | …/exterior/ |
| Exterior brick | Poly Haven "red brick" (UK-style) | …/brick/ |
| Workbench tops / shelving boards | ambientCG "Plywood", "OSB", "Wood" | …/wood/ |
| Cardboard | ambientCG "Cardboard" | …/cardboard/ |
| Painted metal (racking, bench legs) | ambientCG "Painted metal" | …/metal_painted/ |

**HDRI** (Poly Haven, 2K .hdr or .exr): an overcast or partly cloudy suburban/outdoor one (e.g. search "partly cloudy", "overcast"). UK skies are grey, and that suits the look. Folder: assets/hdri/.

### Props
| Prop | Best source |
|---|---|
| Pallet, cardboard boxes, plastic crates, fire extinguisher, broom, bucket, mug, bins | Poly Haven Models first (CC0, realistic, PBR), then Sketchfab filtered to **CC0** |
| Metal shelving/racking, workbenches, parts shelf with bins | **I'll build these in-engine** (simple modular geometry plus real PBR textures looks great and is perfectly sized) |
| Laptops (3 variants) | **I'll build procedurally** (hinged lid, separate screen surface and keyboard decal). AI 3D meshes come out fused into one piece, so the lid can't open and the screen can't show the POST/test UI. |
| Kettle, tape gun, label printer, desk monitor, office tower, USB stick, anti-static mat, extension leads | Higgsfield image → 3D (small, static props are where AI 3D shines), with Poly Haven/Sketchfab CC0 as fallback |
| Lorry (7.5t box lorry with tail lift), courier van | Sketchfab CC0 search "box truck"/"delivery van" (low-poly realistic). AI 3D is usually poor for vehicles. I'll re-skin with fictional liveries |
| Posters, notices, brand logos, packaging art, PaneOS wallpaper, Flogit logo | **Higgsfield image generation**, the highest-value use of your credits |

### Audio: Freesound.org (filter licence = **Creative Commons 0**), Kenney audio packs (CC0), Sonniss GDC bundles (royalty-free, commercial OK)
Lorry reversing beeps + diesel idle, roller shutter open/close, footsteps on concrete, cardboard handling, tape gun, thermal label printer, laptop fan spin-up, plastic clunks/impacts (Kenney "Impact Sounds"), UI clicks (Kenney "Interface Sounds"), kettle boil, fluorescent hum, distant street/birds ambience, van door slam.
**I'll synthesise** POST beeps, error beeps, PaneOS UI chimes and the test-complete jingle myself (Python here), so they're unique to the game.

### Fonts (Google Fonts, SIL OFL, fine for commercial use)
UI: **Inter** or **Manrope**. Reports/terminal: **JetBrains Mono** or **IBM Plex Mono**. Labels/stickers: **Barlow Condensed**.

---

## 6. Where Higgsfield credits help most (I can run these for you via the Higgsfield connector, with your OK)

1. **2D brand and decal art (top priority, cheap, big visual payoff):** Corvex/Halden/Northstar/Brightline/Kestrel lid logos, the Vortex/Arcturo CPU stickers, the Flogit logo, the PaneOS logo and wallpaper, garage posters ("Data Wipe Certified", health & safety, a fake band gig poster), grade stickers, the Refurb Inc. logo.
   *Example prompt:* "Flat vector logo for a fictional laptop manufacturer called 'Kestrel', minimalist bird-of-prey mark with wordmark, silver on transparent background, corporate, clean, no text other than KESTREL"
2. **Small static props via image → 3D:** kettle, tape gun, label printer, desk monitor, desktop tower (for the office PC), USB stick, anti-static mat, plastic parts bins.
   *Example prompt (image stage):* "Product photo of a generic white plastic electric kettle, UK style, slightly worn, plain background, three-quarter view, no logos, realistic PBR materials"
3. **Tileable texture variety:** grimy anti-static mat, bubble wrap, foam inserts, pallet wrap.
   *Prompt suffix:* "seamless tileable texture, top-down, even lighting, no shadows, photorealistic"
4. **Steam capsule and key art** later, once the garage looks right (M3).

Full prompt list goes in `docs/ART_PROMPTS.md` once you approve the approach.

---

## 7. Limitations of my environment you should know about
- There's no Godot binary in this cloud container and I can't download one, so **you run the game in the editor** after each chunk. I'll write GUT tests and tell you exactly what to click and what you should see.
- Asset sites are blocked, so you download the listed assets (≈15 minutes, once). I can generate textures and sounds procedurally here, and Higgsfield art through the connector.
