You are going to help me build a commercial Steam game called REFURB INC. Read this entire brief carefully before doing anything. Then save it word for word to CLAUDE.md in the project root so it persists across sessions, and propose a plan for Milestone 1 before writing code.

=====================================================================
1. THE PITCH
=====================================================================
Refurb Inc. is a 1–4 player online co-op, first-person 3D automation / business sim (Satisfactory-style controls and feel, with the job-sim satisfaction of PowerWash Simulator / Supermarket Simulator). Players run an IT refurbishment business: they buy ex-corporate IT hardware (laptops, desktops, servers, switches) in bulk, test it, repair it, wipe and re-image it, grade it, and resell it. They grow from a home garage to a robot-run mega plant.

Tagline: "Buy it. Fix it. Flip it. Scale it."

- Players: 1–4 online co-op (solo fully playable)
- Perspective: first-person 3D
- Engine: Godot 4 (latest stable 4.x), GDScript, GodotSteam for Steam lobbies / P2P networking
- Platform: PC (Steam)
- Price: ~£12.99
- Tone: pressure WITHOUT combat. A happy medium between cosy and stressful. Deadlines, rent, chaos, nobody dies.
- Language: UK English everywhere in game text (colour, tyre, lorry, etc.)
- The creator (me, Jon) works in real IT refurbishment (testing servers, generating reports), so everything should feel grounded and authentic to how the industry actually works.

=====================================================================
2. QUALITY BAR — READ THIS
=====================================================================
This must NOT look like a grey-box programmer prototype. The target is a polished, commercially viable indie game that would look good in a Steam trailer.
- Art style: realistic-leaning but clean ("semi-realistic"), in the vein of Supermarket Simulator / PowerWash Simulator / Gas Station Simulator. Realistic proportions, PBR materials, good lighting, believable spaces. Not low-poly cartoon, not ultra-photoreal.
- Must still run well on modest/mid-range PCs: modest poly counts, LODs, baked or efficient lighting, sensible shadow settings, occlusion where useful.
- Lighting: proper lighting setup (Forward+), tuned exposure/tonemapping, SSAO/SSIL or baked GI as appropriate, believable fluorescent/industrial interior lighting, daylight through the roller door. No blown-out walls.
- Environments must feel real: concrete floors with wear, breeze-block or plastered walls, roller shutter door, strip lights, cables, pallets, cardboard boxes, racking, workbenches with tools, anti-static mats, monitors, posters, a kettle in the corner. Props sell the fantasy.
- Hardware models must look like real (but fictional-brand) laptops, desktops, servers, switches: open/closed lids, screens, keyboards, ports, server ears, drive caddies.
- Use high-quality free/CC0 assets (e.g. Poly Haven textures/HDRIs/models, Kenney where appropriate, Quaternius etc.), and I also have AI 3D/image generation credits (Higgsfield) for custom props/textures. Build a clean asset pipeline: placeholders must be easily swappable for final assets (one scene per prop, consistent scale: 1 unit = 1 metre).
- Juice and feel: smooth first-person movement with head-bob option, satisfying pick-up/put-down (physics-y but controlled), sounds (clunks, fans spinning up, POST beeps, tape gun, printer, lorry reversing beeps), UI animations, particle dust, screen glow on benches.
- UI: clean, modern, diegetic where possible (the office PC is a real in-world monitor you look at, with an in-game OS-style desktop and apps). HUD minimal.
- Keep it fun: NO fiddly mini-games. Players should never be stuck staring at one machine; processes run on timers so you can walk away.

=====================================================================
3. CORE LOOP
=====================================================================
Purchase → Delivery → Haul in → Test → Repair (if faulty) → Wipe/Image → Grade → Stock → Sell → Pack → Ship → Reinvest

Every item in the game is ONE physical object carrying its own data: type, maker, model, specs (CPU, RAM, storage, PSU etc.), external condition, hidden internal faults, test status, grade, value, location/state. Every system reads or changes that object's data.

Rhythm:
- Daily: process stock, fill orders, fire-fight problems.
- Weekly: rent and staff wages due.
- Long term: grow reputation, bigger buildings, staff, full automation.

=====================================================================
4. TIME
=====================================================================
- One in-game working day ≈ 15 real minutes (e.g. 09:00–17:00 shown on a clock).
- Time keeps running everywhere (including when players are in menus/the office PC, and later when players are away on site visits) — that is part of the pressure.
- End of day: shared shift summary/rating (units processed, sales, mistakes, mess, deadlines hit/missed).
- Weekly: rent + wages deducted.

=====================================================================
5. PURCHASING (done on the office PC)
=====================================================================
- Fictional seller companies (banks, schools, councils, insurers, solicitors, startups, NHS-style trusts, logistics firms…) refresh their kit on predictable 1-, 2- or 3-year cycles and list bulk lots of old hardware, e.g. "Northgate Bank — 200 laptops, 3 years old".
- Refresh cycles are predictable so players can plan ahead for big lots.
- Players browse lots on the office PC "Purchasing" app and bid/buy. AI rival refurbishers also bid on lots.
- Reputation unlocks bigger and better sellers.

PRICING / DEALS (important):
- Every hardware model has a hidden market/trade value based on its specs and age.
- Each lot's asking price swings around that value (roughly 60%–130%), so the SAME model can appear at e.g. £100/unit in one lot and £65/unit in another. There must be genuinely good deals and bad deals; the player has to choose the right deal for them.
- Cheaper lots are cheaper for a reason: on average more faults, worse cosmetic grades. Bargains are a gamble. Pricier lots are safer but thinner margins.
- Seller notes give hints ("Sold as seen, untested", "Mostly working, usual office wear", "All working when removed").
- Rule of thumb: roughly 7–8 in 10 units work. Profit comes from volume and speed.

NO HAGGLING:
- There is NO negotiation / haggling / back-and-forth persuasion conversation. None.
- Instead there is a small passive purchasing discount coming from (a) a player's Purchasing skill on their skill tree (earned by XP from buying), and/or (b) a hired AI buyer's purchasing stat.

SITE VISITS (LATER FEATURE — do NOT build in the base version, but architect for it):
- Base version: purchasing is 100% on the office PC.
- Later: players chat to a seller contact on the office PC; the seller says what kit they want rid of; player schedules a visit by pressing "Go"; a car pulls up outside the player's facility (whatever building tier they're on); players click the car, screen fades to black, they appear outside the seller's workplace (which reuses the player building tiers, re-dressed — garage, warehouse etc.) to inspect lots before buying (hovering reveals external condition).
- The party can split: e.g. 2 players go on the visit while 2 stay working at the facility. Both locations must run simultaneously in multiplayer (e.g. seller site exists in the same world far away, or as a separate sub-scene the host simulates). Time keeps ticking.
- Return the same way (car + fade).

=====================================================================
6. DELIVERY & HAULING
=====================================================================
- A lorry arrives (reversing beeps) and drops pallets of servers/desktops/laptops at the loading bay outside.
- Early game: players haul units in by hand (carry one item; maybe stacks of laptops).
- Later: pallet trucks, forklifts, then robot arms unloading pallets onto conveyors.
- Floor space limits how much stock you can hold.

=====================================================================
7. INSPECTION
=====================================================================
- Looking/hovering at a unit shows a simple text tag with its EXTERNAL / cosmetic condition only, e.g. "Cracked screen, missing key", "A grade, pristine", "Light scuffs on lid".
- No physical examining mechanic (it'd be boring).
- Internal faults are hidden until tested.

=====================================================================
8. TESTING
=====================================================================
- Testing is ALWAYS 100% accurate. The limitation is throughput, never accuracy.
- Early game: place a unit on a test bench workstation and plug in a purchasable testing-software USB. One USB = one unit at a time (buy more USBs/benches to scale). The test runs on a timer by itself — players walk away and do other jobs.
- Result is shown above the bench / on its screen: PASS, or a fault list, e.g. "Battery health 41% – needs replacing", "Faulty RAM DIMM in slot B2", "Dead SSD", "No POST – board fault", "PSU 2 failed", "Damaged server ear".
- Later: network-boot test racks that test many units at once (10 → 20 → 40 slots), loaded by hand, then by robot arms fed from conveyors in late game.
- Servers should feel like real server testing (boot, POST, hardware inventory, diagnostics report). The test generates a report.

=====================================================================
9. REPAIR
=====================================================================
- Faulty units go to a repair bench.
- Each fault needs (a) the right spare part and (b) the required repair skill level.
- Interaction: hold interact, a progress bar fills. No fiddly mini-games.
- Repair tiers:
  - Level 1: keys, battery, RAM, drives
  - Level 2: screens, keyboard decks, PSUs, server ears
  - Level 3: motherboards, CPUs, board-level faults
- Parts can be bought (office PC Shop) or stripped from dead "donor" units at a strip-down station into parts bins. Parts are physical objects in bins/shelves.
- Players repair first; later staff and repair robots do it (within unlocked levels).

=====================================================================
10. SOFTWARE: WIPE, IMAGE, GRADE
=====================================================================
- Every unit must be data-wiped and have a fresh OS installed.
- Early: imaging USB per unit (timer). Later: network imaging racks doing many at once.
- Then graded A / B / C / D:
  - A: pristine, full spec
  - B: light wear
  - C: visible wear, fully working
  - D: heavy wear / donor
- Sale price is preset from grade + specs/parts; the player can adjust it.

=====================================================================
11. STOCK
=====================================================================
- Graded units are stored physically on shelving/racking; loose parts in bins.
- Office PC Stock app shows everything live (what, where, grade, value).

=====================================================================
12. SALES — CUSTOMERS COME TO YOU
=====================================================================
- Players don't hunt for customers; orders arrive automatically each in-game hour/day, driven by reputation and how your price compares to market value (cheaper sells faster, pricier slower).
- Channels, unlocked progressively:
  1. FLOGIT — a parody of eBay, available from day one. Low traction at first (a trickle of random buyers).
  2. The Refurb Inc. website — unlocked by reputation; more orders, better margins.
  3. Business buyers — email in with bulk orders and custom server build requests.
- Later: paying for ads or hiring salespeople increases demand.
- Fulfilment is physical: a player (later staff, later robots) picks the item, boxes it at a packing station (box, foam, tape gun, label printer), and puts it out for courier pickup at set times.
- Good service (on time, correct item, accurate grade) grows reputation; late/wrong orders hurt it.

CUSTOM SERVER BUILDS:
- Business customers send a spec with a deadline, e.g. "2x Vortex X9 CPUs, 256GB memory, redundant PSUs, 8x drives".
- Players pull parts from stock, physically build the server at a build bench, test it, image it, and ship it before the deadline.
- Big margins — this is the reason to hoard good parts.

=====================================================================
13. HARDWARE & FICTIONAL BRANDS
=====================================================================
ABSOLUTELY NO REAL BRANDS. Never use Dell, HP, Lenovo, Apple, Cisco, Supermicro, Intel, AMD, Nvidia, eBay, Microsoft, Windows etc. — in names, logos, textures, or text.

Fictional brands (locked):
- System makers: Corvex, Halden, Northstar, Brightline, Kestrel
- CPU makers: Vortex and Arcturo (10+ CPU models across the two, budget → flagship, e.g. Vortex V3/V5/V7/X9 families, Arcturo A5/A7/A9)
- Networking: Linkwell (switches)
- Marketplace: Flogit
- Fictional OS for imaging (invent a name)

Lots of part variety: CPUs, memory (various sizes/speeds), drives (HDD/SSD/NVMe, caddies), PSUs, screens, batteries, keyboards/decks, server ears, motherboards, RAID cards, fans.
ALL hardware and parts are defined as data (JSON/CSV or Godot Resources), never hard-coded, so content can be added easily.

=====================================================================
14. THE OFFICE PC
=====================================================================
An in-world computer at a desk; when used, the camera focuses on the monitor showing a fake in-game desktop OS with apps:
- Purchasing (lots, bids, seller contacts)
- Sales (Flogit listings, website, business orders/emails)
- Stock
- Building (upgrade/move premises, buy equipment, place machines)
- Staff (hire, fire, assign, stat cards)
- Wages & Finance (cash, rent, wages, profit/loss, charts)
- Shop (USBs, parts, tools, benches, racks, conveyors, robots)
Time keeps running while using it. Multiple players can each use it (or there are multiple PCs later).

=====================================================================
15. PROGRESSION
=====================================================================
- What PLAYERS know: per-player skill tree, earned by XP from doing the work (repair levels 1→3, faster testing, faster hauling/carrying more, packing speed, purchasing discount, building speed etc.). Players can specialise but anyone can do any job.
- What the COMPANY owns: equipment, staff, robots, buildings — bought with cash.
- NOTHING is gated by building tier. Everything is purchasable at any time; cost is the only gate (you CAN buy a robot arm while in the garage, it's just unaffordable and impractical).
- Soft limits come from floor space and power capacity.
- Progression flow: manual and slow at the start → hire staff → automation and robots late game. It should be a smooth slope.
- The daily grind must feel rewarding with clear short/medium/long goals and an obvious path to scale the company.

=====================================================================
16. STAFF (AI WORKERS)
=====================================================================
- Hired via the office PC Staff app from a pool of candidates.
- Each candidate has a stat card scoring every skill: Repair, Testing, Packing, Hauling/Lifting, Sales/Communication, Purchasing (plus maybe Speed/Reliability). Right person in the right role = fast; wrong role = slow.
- Staff level up with experience. They're paid weekly wages — over-hiring can bankrupt you.
- Roles include:
  - Floor: hauliers, testers, repair techs, imagers, packers, builders
  - Office: purchasers/buyers (can browse and bid on lots for you, give purchasing discount), listers (list stock online), salespeople (more orders, better prices)
  - Cleaners (see Mess)
- Staff are visible 3D characters walking around and working at stations.

=====================================================================
17. MESS & CLEANING
=====================================================================
- Work creates mess: cardboard, pallet wrap, foam, dead parts, dust build up.
- Mess slows movement/work and lowers the end-of-day shift rating.
- Early: players sweep/tidy themselves (broom, bins, cardboard baler later).
- Later: hire cleaners to handle it automatically.

=====================================================================
18. AUTOMATION
=====================================================================
Late-game factory-building layer (players design the routing themselves):
1. Robot arms unload pallets onto conveyors.
2. Conveyors feed test stations/racks; arms load and unload them.
3. A sorter routes units by test result: Pass → imaging → grading → stock; Fault → repair; Dead → strip-down → parts bins.
4. Repair robots fix faults within unlocked levels.
5. Packing robots box and dispatch orders.
Players place conveyors, splitters, sorters, belt speeds; manage bottlenecks. Machines need floor space and power.

=====================================================================
19. BUILDINGS (7-rung ladder)
=====================================================================
1. Home garage (start)
2. Lock-up unit
3. Small industrial unit
4. Warehouse
5. Distribution centre
6. Refurb facility
7. Mega plant
Bigger = more floor space, more power, more loading bays — and more rent. Nothing is locked behind building size. These same building tiers are reused (re-dressed) as seller sites for the later site-visit feature.

=====================================================================
20. PRESSURE & CHAOS (no combat)
=====================================================================
- Weekly rent and wages
- Order deadlines and custom build deadlines
- Random events: power cuts, conveyor jams, roof leak/floods, a dud batch, courier strikes, surprise mega-lots, rival outbids
- Rival AI refurbishers competing for lots
- End-of-day shared shift rating
- Going into debt has consequences (but should be recoverable, not instant game over, at least on normal difficulty)

=====================================================================
21. CO-OP
=====================================================================
- 1–4 players via Steam lobbies (GodotSteam), host-authoritative P2P.
- Shared company money/stock/reputation; individual skill trees.
- Natural roles emerge (buyer, tester, repairer, packer) but nobody is locked in.
- Co-op chaos should create streamable, clippable moments (e.g. everyone panicking when the lorry turns up while a deadline is due).
- Build networking into the architecture from the START. Don't build a single-player game and bolt on multiplayer later. All game state (economy, items, stations) must be authoritative on the host and synced.

=====================================================================
22. TECHNICAL ARCHITECTURE
=====================================================================
- Godot 4.x, GDScript, typed where sensible. Clean folder structure (scenes/, scripts/, data/, assets/models, assets/textures, assets/audio, ui/).
- Data-driven: hardware models, parts, faults, sellers, staff names, events in data files/Resources.
- Core data types: Unit (id, type, maker, model, specs, hidden faults, external condition, tested, grade, value, state/location), Part, Lot (seller, units, price, hidden quality, notes, expiry), Order, StaffMember, Building.
- Systems/managers: GameClock, Economy/Finance, Purchasing, Stations (generic pattern: take a Unit in, run a timer, change its data, output it), Repair/Parts, Sales/Orders, Staff/AI, Mess, Conveyor/Automation, Events, Reputation, Save/Load, Networking.
- Every station follows one interaction pattern (hover text, interact with empty hands, accept a carried item) so new stations are easy to add.
- Save/load from early on.
- Automated tests for the economy/logic (headless GUT or simple test scenes).
- Performance budget for mid-range PCs.

=====================================================================
23. ROADMAP (I can put in 1–2 hours a day after work; you do the heavy lifting)
=====================================================================
Milestone 1 — Polished vertical slice, solo, garage:
  Great-looking home garage environment (real assets, lighting, props), first-person controller with good feel, office PC with Purchasing + Flogit + Finance apps, lorry delivery, hauling, USB test bench, repair bench (Level 1), imaging USB, grading, stock shelving, Flogit sales, packing station + courier pickup, day clock (15 min), rent, sounds. Laptops only. This should already look good enough for a Steam page screenshot.
Milestone 2 — Co-op: GodotSteam lobbies, 2–4 players, everything synced, host-authoritative.
Milestone 3 — Full loop + content: desktops/servers/switches, repair levels 2–3, parts stripping, custom server builds, business orders, website, reputation tiers, events, shift rating, save/load. Steam page goes live.
Milestone 4 — Staff (all roles incl. buyers, listers, salespeople, cleaners), mess system, building upgrades (lock-up, small industrial unit).
Milestone 5 — Automation: conveyors, sorters, robot arms, network test/imaging racks, bigger buildings up to mega plant.
Milestone 6 — Site visits (car + fade + split party), rival bidders, polish, Next Fest demo, then Early Access.

MVP scope reminder: garage + laptops + USB testing + Level 1 repairs + Flogit + 2-player co-op.

=====================================================================
24. HOW I WANT YOU TO WORK
=====================================================================
- Start by saving this brief to CLAUDE.md, then give me a concrete plan for Milestone 1 (tasks, asset list with sources, folder structure) and ask me any genuinely blocking questions (one at a time, keep it short).
- Prioritise visual quality and game feel alongside systems — I'd rather have a small slice that looks and feels great than lots of ugly systems.
- Source real CC0 assets (models, PBR textures, HDRIs, sounds) and tell me exactly what to download if you can't fetch them yourself. Tell me where AI-generated assets (I have Higgsfield credits) would help most and give me the prompts.
- Commit to git regularly with clear messages.
- After each chunk of work, tell me exactly how to test it in the Godot editor and what I should see.
- Keep CLAUDE.md updated with decisions and progress.
- UK English. Fictional brands only. No haggling. No fiddly mini-games. Never stuck waiting at one machine.

=====================================================================
DECISIONS & PROGRESS LOG (maintained by Claude — everything above this line is Jon's original brief, verbatim)
=====================================================================

## Where things are
- Project root: `refurb-inc/` (Godot project). It sits inside the `jrpetty/football-` git repo only because that is the repo this cloud session is attached to — it is otherwise unrelated and self-contained. Branch: `claude/optimistic-albattani-c0fe64`.
- Docs: `docs/MILESTONE_1_PLAN.md`, `docs/MODEL_CONTRACT.md` (how to drop in final art), `docs/TESTING.md` (how to run/test, what you should see).

## Locked decisions
- Engine: **Godot 4.7.2**, Forward+, Jolt physics, GDScript (typed). Verified headless + rendered (lavapipe) in the cloud container.
- **Networking from day one:** every gameplay action is `Game.request(cmd, args)` → host `Sim.execute()` → mutates `GameState` → dirty entities replicated to clients (`Game._flush_replication`, 20 Hz). Solo = OfflineMultiplayerPeer acting as host (same code path). ENet host/join works now (LAN); Steam (GodotSteam) slots into `Net` in M2. Loose-item physics is host-simulated and streamed (`LevelRoot._rpc_xforms`). Lot hidden data (unit rolls, quality) never leaves the host.
- **State vs views:** `GameState` (plain data: items, slots, stations, lots, orders, company, clock, schedule, players/XP) is the single source of truth, serialised for saves AND network. 3D nodes (`ItemView`, stations, HUD, PaneOS apps) only read it and listen to `state.changed`.
- **Data-driven content:** `data/*.json` (CPUs, laptop models, makers, parts, faults, cosmetic conditions + grade thresholds, sellers + note bands, economy tuning, shop catalogue, names, OS/diag text, mail). `Content` autoload validates cross-references at load.
- **One interaction pattern:** stations extend `StationBase` (hover_info / interact / best_slot_for → SnapSlot / hold_action + hold_complete). Items are `ItemView`s. Players target the HOST (bench/shelf/pallet) and it picks the nearest free SnapSlot.
- **Swappable art:** every visual is a `ModelSlot` (placeholder id OR `model_scene`). Convention: 1 unit = 1 m, origin bottom-centre, **front faces +Z** (glTF default). Laptops: `Lid` pivot node + `Screen` mesh. See `docs/MODEL_CONTRACT.md`.
- Imaging OS = **PaneOS** (Pane Systems). Diagnostics tool = **DiagnostiKit Pro**. Marketplace = **Flogit**. Lorry firm "Pennine Freight", courier "Zippd". Lot site = "LotFinder", parts shop = "PartsHub".
- Time: 09:00–17:00 = 900 real s. Weekends skipped. Rent+utilities every Friday close. Jobs on benches continue overnight. Deadlines count working hours.
- Purchasing: fixed price, NO haggling. Price = nominal trade value × swing (0.6–1.3, triangular); hidden quality correlates with swing + noise + seller bias. ~70–80% of units work. Passive discount = 1%/Purchasing level.
- Repair levels gate faults (L1 keys/battery/RAM/drives/fans, L2 screens/hinges/decks, L3 BIOS lock/board). Unrepairable units can be graded "D – Spares/Repairs" and sold cheap.
- Seller refresh cycles: 1 in-game "year" = 10 working days, visible in LotFinder's Refresh calendar.
- Generated placeholders: `tools/gen_textures.py` (PBR sets), `tools/gen_audio.py` (all SFX), `tools/build_scenes.tscn` (wrapper scenes + garage level — DON'T re-run after hand-editing the garage; use `--only-props`).

## Status
- **Milestone 1 (solo garage vertical slice): functionally complete with placeholder art.** Full loop playable: LotFinder purchase → lorry (reverse beeps, tail lift) → pallet → haul (stack 3) → USB test bench (live on-screen diagnostics) → L1 repair with parts bins → PaneOS imaging → grading + sticker → shelving → Flogit listing/pricing → hourly orders → packing → OUTGOING shelf → courier van 12:00/16:30 → cash. Shop parcels by courier. Rent/bills, overdraft rules, end-of-shift rating card, autosave + manual save/continue, pause/settings (sens, FOV, head-bob, volumes, graphics presets), title screen with live garage backdrop, HUD (clock/cash/rep/next events/orders/toasts/next-step hints), Skills app (per-player XP), Mail onboarding, kettle buff, roller door.
- **Milestone 2 groundwork done early:** ENet co-op works (verified host+client in two processes: snapshot join, command routing, replication). Remaining for M2: GodotSteam lobbies + `SteamMultiplayerPeer`, lobby UI, polish of remote-player avatars/animation, host migration/disconnect UX.
- **Automated checks (all passing):** `tools/run_all_checks.sh` → compile all scripts, 77 unit tests, in-engine playtest of the whole loop, premises test (move through all 7 buildings), 20-day economy bot, 2-process co-op test (incl. Insights report to a client).

## Premises & equipment (added after Milestone 1)
- **Seven playable premises** (`data/buildings.json`, scenes in `scenes/levels/<id>/`), each generated by `tools/buildings/<id>.gd` with the BuildKit; quality rises with tier (grimy garage → railway-arch lock-up → business-park unit → warehouse with docks → distribution centre → glazed flagship HQ → sawtooth mega campus with wind turbine). Interiors are left mostly empty for the player's equipment.
- **Equipment = fixtures in GameState** (`fixtures` dict: type/label/placed/p/r). LevelRoot spawns them from `data/fixtures.json` scenes, auto-lays out unplaced ones from each level's `Layout` markers (falls back to a free-space search), validates footprints against `floor_rects`/`keepout_rects`. Player placement mode: F move, R rotate, E place, Q cancel. Station ids = fixture ids, so slot contents travel with equipment.
- **Moving:** `move_building` command (fee from data) → equipment unplaced, loose items flagged `relocate` → GameRoot fades and swaps the level → host auto-lays out and drops loose stock in goods-in. Rent/utilities come from the current building. Old saves are migrated (`Sim.migrate_save`).
- **Vehicles** follow per-level routes; lorries reverse into tail-lift spots or level docks (dock door opens, pallet lands inside).
- **Art pipeline:** BuildKit batches meshes per material (`assets/generated/<id>/*.res`), materials live in `assets/materials/*.tres` (MatLib), textures in `assets/textures/{surfaces,decals}`; Building-app thumbnails in `assets/textures/ui/buildings/`.
- Decision: building tiers gate nothing — only money and floor space (as briefed). Seller site visits (M6) will reuse these scenes re-dressed.
- Move fees (2026-10-21, Jon): geometric £10k → £1M (~2.5x per tier): lock-up £10k, industrial £25k, warehouse £63k, distribution £160k, facility £400k, mega plant £1M. Moving up is a long-term goal, not an early hop.

## Business Insights (office PC app)
- **Insights app** (`ui/office_os/apps/insights_app.gd`, widgets in `ui/office_os/insights_ui.gd`): 17 sidebar sections.
  - The sections are Highlights, Overview, Sales, Pricing & demand, Customers, Purchasing, Products & quality, Stock, Operations, Automation*, Energy*, Orders & service, Finance, People & premises, Staff*, Lifetime stats* and Timeline (* = live, `BusinessReport.compute_live`, `Game.request_report(true)`).
  - Automation counts come from `BusinessStats.on_auto(state, kind, fid)` (called by machines); energy from `Sim.power_breakdown` + the day records (`kwh`, `power_cost`).
  - Widgets: KPI tiles, striped tables, meters, insight cards, hover charts and a demand heatmap.
- **Recording:** `BusinessStats` (`scripts/systems/business_stats.gd`) writes history to `GameState.stats` (saved, never replicated) through `on_*` hooks in Sim/GameState. It records:
  - every sale with full unit economics, buyer, specs and per-stage pipeline hours;
  - lots, with a public price index and their outcomes;
  - per-model reliability, cosmetic/battery distributions, faults, repairs and parts;
  - a weekday×hour demand grid, test-bench busy time and opening minutes;
  - per-player hours and actions, day summaries, on-time streaks, all-time money by ledger category;
  - a company timeline (milestones, records, level-ups, reputation marks, seller unlocks, moves, equipment).
- **UnitData** gained `received`, `tested_at`, `repaired_at`, `imaged_at`, `graded_at`, `listed_at`, `sold_at` and `parts_cost`.
- **Analysis:** `BusinessReport.compute(state)` (`scripts/systems/business_report.gd`) builds one plain-Dictionary report on the host. It includes:
  - pricing bands, specs value, customers, deal-quality bands and lot ROI;
  - funnel, bottleneck, utilisation/capacity and repair ROI;
  - forecasts, weekly table, goals (next premises, milestones, next seller unlock), records and awards;
  - auto **insights**: rule-based plain-English findings with a tone.
- **Clients:** they call `Game.request_report()` → RPC → `report_ready`.
- **Hours:** counted per connected player while the day runs (`Sim.active_peers`). Staff/wage fields read 0 until the Staff app (M4) writes a `wages` ledger category.
- **`BusinessBot`** (`tools/business_bot.gd`) plays the business through real commands. It's used by `tools/economy_sim.tscn` (in `run_all_checks.sh`, which prints insights), the unit test that checks the report reconciles, and `screenshot.tscn -- --insights`.
- **Balance notes from the bot:**
  - Perfect play makes about £3–4.5k net in 20 working days, with a 60–75% pass rate.
  - Reputation hits 100 by about day 20, since every on-time order is +1; consider diminishing returns when reputation tiers land in M3.
  - Bargain lots usually pass less often than premium ones but still return a higher ROI.
  - The early bottleneck is waiting to be tested (one bench, about 6.4 units a day of capacity).
- The PaneOS taskbar is icon-only (tooltips) so every app fits.

## Hardware models (Jon's Meshy models, 2026-10-20)
- 13 AI models (5 laptops, 3 desktops, 5 servers) went through `tools/model_pipeline/` (see `docs/MODEL_CONTRACT.md` → AI-generated models).
  - Output is `assets/models/{laptops,desktops,servers}/<name>/` at 40–75k tris LOD0 with Godot LODs and 2K JPEG PBR textures, 41 MB in total. The raw files were 700 MB.
  - Raw sources are NOT in git. They live in Jon's `Desktop/Refurb INC/assets` folder.
- Laptop tiers → data: rugged = Kestrel Swift 14; office = Prospect 5420/7390, Folio 840, Meridian T14; ultralight = Meridian X13, Aura 13; mainstream = Vantage 3510, Folio 650, Edge E15.
  - Gaming = Northstar Vanguard G17 (2022, 17.3in, Vortex V7-12700H / Arcturo A9, 16–32GB, 512GB–1TB NVMe, Arcturo Prism P70M GPU).
    - A-grade £640; sold by the new seller Pixelmoor Studios (games developer, min rep 10).
    - It brought new parts: a 90Wh battery, a 1TB NVMe drive and a 17.3in 144Hz panel.
    - Models may set an optional `gpu`, which is shown in the spec line.
- LaptopView reads the scene metadata, so one mesh serves several models:
  - `footprint` scales the mesh to the JSON width and sets the real depth/height for collision and stacking;
  - `lid_open_deg` is the model's own open angle (110–115°);
  - `sticker_pos` places the grade sticker.
- Desktops/servers have wrapper scenes only. M3 adds the item types that use them.
- Decision: hardware budget raised from <8k to ~40–75k tris (Jon: models must stay close to what he sent). Godot LODs keep piles cheap.
- `tools/model_showcase.tscn` = visual QA renders for all models.

## Jon's request tracker (2026-10-20/21) — keep every item until it is built AND verified
- [x] Use the 13 Meshy models (laptops/desktops/servers): optimised, scaled, tiered, in game.
- [x] Gaming laptop in the data (Northstar Vanguard G17).
- [x] Desktops + servers as stock; heavy-duty racking for big kit; bigger OUTGOING shelf with floor bays.
- [x] Moving premises much dearer, scaled geometrically: £10k, £25k, £63k, £160k, £400k, £1M.
- [x] Things must actually sell (auto-list on grade, higher early demand, first sale within the hour, ETA in Flogit).
- [x] Never fall through the map: site fence + guaranteed ground + respawn safety net (premises test: 0 holes on all 7 sites).
- [~] Use ALL 45 further models (done: processed; wired: ESD test bench, repair bench, desk + 27in PaneOS monitor, shelving,
      heavy racking, pallet, boxes, USB sticks, box lorry, pallet truck. To wire: arm, conveyors, forklift, car, parts…).
      (the "Unconfirmed *.crdownload" files are complete GLBs): stations, shelving, racking,
      pallet, lorry, car, forklift, pallet truck, robot arm, conveyors (straight + 90° curve — fix the curve's looks),
      boxes, USB stick, label printers, barcode scanner, bins, parts, monitors, switch, Wi-Fi AP, dock, phone, tablet…
- [x] ACCEPTANCE: a fully automated factory tested start to finish — unit test (`test_lights_out_factory_ships_a_lot`)
      AND filmed in the mega plant (`tools/factory_film.tscn`): 6 laptops + 2 rack servers + a tower server go
      pallet → arm → goods-in conveyor → scanner → arm → test rack → repair robot / imaging rack → conveyor → vision
      grader → arm → stock → (sold) packing robot → OUTGOING → courier; 9/9 shipped with nobody touching anything.
- [x] Automation: conveyors (straight, curve L/R), goods-in scanner (auto stock booking), sorter (rules per exit, E to
      change), vision grader (grades + lists), pick-and-place robot arms (Jon's arm cut into 5 joints, IK, vacuum plate
      that sizes to the item), network test/imaging racks (6 bays, LEDs, PXE screens), repair + packing robot cells.
      Arms wait for a busy next station instead of sending kit down the line.
- [x] Conveyors fit big kit (Jon, 2026-10-21): rollers lengthened (frames keep their thickness) → 0.6 m between the
      rails, so 19-inch rack servers fit; items ride on the roller tops, stop wholly on the belt at open ends, load
      without overhanging. Loading point at the start of every straight. Test: `test_big_kit_fits_and_rides`.
- [x] Automation easy to set up + neat (Jon): conveyors snap onto the nearest open end and turn to match; green arrows on
      open ends and cyan reach rings for arms while placing; ghost tells you what an arm reaches / which arm covers a
      machine; hover warns about open ends with no arm. Shop grouped Workstations / Storage / Automation.
- [x] Electricity cost for everything powered (Jon): metered per working minute — lighting by floor area + each fixture
      at its rating while working (standby fraction otherwise) + kettle boils; billed at day close (pennies carry),
      shown on the day card and in the shop (⚡ kW, £/day). Weekly bills now = water/broadband/standing charge.
- [x] First day: anything bought on day one arrives the same day (lorry ~30 min, shop parcel ~20 min).
- [x] Scale models freely where needed, but keep them looking right (Jon) — e.g. conveyors widened by stretching only the
      rollers, never the frames.
- [~] Better delivery: lorry arrives loaded, shutter rolls up, a hi-vis driver pulls each pallet with the pallet truck
      onto the tail lift and wheels it in (done); player pallet truck + forklift and arms → conveyors (to do).
- [x] Empty pallets (Shop) adopt the first unit's stacking grid; hand pallet truck (start kit + Shop): take, forks under,
      jack up, push (walls block it, Pallet pro perk), lower, let go. Host-authoritative, synced.
- [x] Skill system that matters: skills level 1–10 from XP with perks; job time scales with level. AI staff feel it
      even more (×0.41 at level 1 → ×2.3 at level 10, `Sim._skill_speed`). Test: `test_staff.gd`.
- [~] Co-op designed in: shared goals (campaign milestones), any number of players at the office PC, staff work
      beside players. Still to do: a sales & purchasing office with several desks/PCs in the bigger buildings.
- [x] New stock types: monitors (24in, 27in, 34in ultrawide), Linkwell LS-24P switch + Air AX3000 Wi-Fi AP, Corvex
      WD-22 dock, Brightline Slate 11 tablet, Kestrel Pulse 6 + Northstar Nova 13 phones. Own diagnostics stages,
      inventory screens, faults, parts, scrap values and sellers. Monitors/docks hold no data (test → grade);
      phones/tablets get a secure wipe + factory reset, switches/APs a config wipe + firmware flash; mobile kit has
      battery health (caps grade A) and activation locks (unfixable → D Spares/Repairs). Test: `test_new_stock.gd`.
- [ ] Immersion + interaction polish; a grind long enough to commit to (balance the premises ladder vs earnings).
- [x] END GOAL: mega plant AND £20,000,000 lifetime revenue; run timer in working days (`data/goals.json`,
      `Campaign`, Goals app). 20 milestones on the way, each timed.
- [x] Challenge + leaderboard: local records per milestone (`user://records.json`, Goals app → Leaderboards).
      Steam leaderboards when GodotSteam lands (M2).
- [x] Every new office-PC app follows the PaneOS/Insights style: Staff, Goals, Achievements apps (sidebar/tabs, cards,
      coloured tiles, taskbar icons).
- [~] Test-bench screens per kind of kit: diagnostics stages + live hardware inventory for laptops, desktops, servers
      (BMC/PSU lines), monitors, switches, APs, docks, tablets, phones; wipe screens for mobile/network kit; ready
      screens per kind. Still to do: dedicated BMC/RAID management screens for servers.
- [x] AI staff (M4) built AND tested in every level (`tools/staff_test.tscn`: 0 route failures in all 7): hire in the
      Staff app, roles, real commands at real stations, AStar navigation, Friday wages + pay rises, value estimates.
- [x] Player avatars + AI staff look like people (baked skinned humans with clothes, hair styles, faces, LODs);
      character menu (shirt, trousers, skin, hair, style, build, height, hi-vis, cap, big head) with a photo-face
      upload, synced in co-op.
- [x] Tea break: pour + first-person mug (Refurb Inc. branded, soft steam) lifted to the lips, head tips back, sip, "ahh";
      co-op players see your avatar drink.
- [x] Tea ROUND (Jon, 2026-10-21): kitchen station in every premises — cupboard (mugs out, up to 6, random colours and
      daft slogans), kettle (fill & boil, walk away, it clicks off; uses electricity), pour the round (kettle lifts and
      fills each mug in turn, the brew rises and steeps from water to tea, teabag tag), fridge (milk into each),
      carry mugs side by side, [E] on a colleague hands them one, [E] drinks it (three sips, level drops, a brew a
      colleague made lasts 1.5×, cold tea half), empties into the sink to wash up. Test: `test_tea.gd`; film:
      `tools/tea_film.tscn`.
- [x] Insights app performance: a page only rebuilds when its own numbers change (signature per page); live pages
      pull a small `compute_live` report (machines, power, staff, counters) every 1.5 s instead of the full report.
- [x] All possible stats live on the computer (Jon, 2026-10-21): Insights now has 17 sections incl. live Automation
      (moves per hour, every machine's state, queues/bottleneck), Energy (kW now by equipment, kWh/£ by day, per
      unit), Staff (status, speed, wage vs worth, work done, skills), Lifetime stats (every counter + the tea room),
      and sales/stock by kind of kit. Test: `test_stats.gd::test_live_sections_track_machines_power_staff_and_counters`.
- [x] Achievements (Jon, 2026-10-21): 70 in 10 groups (7 hidden) in `data/achievements.json`, `Achievements`
      (`scripts/systems/achievements.gd`) works them out from replicated state + lifetime counters
      (`totals.lifetime`, via `bump_stat`), so host and guests unlock them. Per-PC profile `user://achievements.json`;
      off in tools/tests (`Game.records_enabled`, headless). Gold unlock card + fanfare, Achievements app, main-menu
      panel. Steam API names `ACH_<ID>` ready for M2. Test: `test_achievements.gd`.
- [x] More automation kit (Jon: "don't hold back", 2026-10-21) — all in the Shop under Automation:
      **splitter/balancer** (alternate or overflow-left-first, E to switch, flap + "NEXT" signs), **belt speed tiers**
      Standard/Fast/Express (×1.0/1.7/2.5 speed, ×1.0/1.4/1.9 power; hold E on a belt to upgrade its whole run; data in
      `economy.json` `belt_speed_tiers`, stored as `tier` on the fixture), **line monitor** (55in screen on a stand:
      moves/hour, belts, every machine BUSY/WAITING/STARVED/IDLE, bottleneck outlined with a plain-English fix, stack
      light; `Automation.line_status`), **people's hatch** (belt stops at a pick face for a person; "send on" half
      rides on), **stop gate** (E: open/hold). Machines report to `BusinessStats.on_auto`. Decision: sorter/merger/
      splitter exits are named from the kit's point of view (as the curves are). Placement ghosts now use the
      fixture's model scale. Tests in `test_automation.gd`; renders `tools/automation_shot.tscn -- --shots`.
- [x] Flexible conveyor bend (Jon): any angle 15–180° in 15° steps, radius 0.75–2.5 m, max 4 m of belt; snaps to
      straights; chevrons show the direction of travel. Merger (side input), automated stock tower (24 trays) and
      recycling cage also added. Conveyors chain end to end automatically.
- [~] Smoothness: no FPS spikes/dips. Benchmark every premises full of stock; optimise (screens, shadows, LOD/visibility
      ranges, Insights redraws, physics) without hurting looks. [x] Always-on FPS overlay (F3) with 1% low.
      [x] `tools/benchmark.tscn` (shelves + pallets full, camera pans, v-sync off). Garage: stored stock no longer casts
      shadows + LOD threshold per preset → 10.8M→4.8M tris, 2000→1000 draws, 72→81 fps inside (1% low 54→69).
      Bench/PC screens throttled, conveyors swap to a light built copy past 16 m. To do: run it on the other 6 premises.
- [x] Merge the cloud session's fixes (look-to-pick, E-press, shelf pick, build stamp) — B5; one branch, pushed.
- [x] Written breakdown: `docs/ROADMAP_BREAKDOWN.md` (what would make it amazing and smooth + the asset list for Jon).
- [ ] Add own features; test everything (unit tests, playtests, renders of every process from arrival to dispatch).

## Next up
1. Lighting pass with real assets: bake LightmapGI in the editor, HDRI sky, decals (oil stains, scuffs), more clutter.
2. Juice: item outline highlight, pick-up/put-down animation curves, particles (dust in the door light, cardboard bits), screen glow light on benches, footstep surfaces, music.
3. M2: GodotSteam.
4. M3 content per roadmap (desktops/servers/switches, L2–3 repair, strip-down station + donor parts, custom server builds, business orders, website channel, reputation tiers, random events).

## Known limitations / notes
- Laptops (and the ready desktop/server scenes) use Jon's models; stations, props and vehicles are still procedural boxes with real PBR textures.
- The office PC and bench screens use SubViewports (cheap at current counts; revisit when there are 40-slot racks).
- Staff and automation are in; mess/cleaners are not yet (M4). Staff issue the same commands as players (virtual peers
  `-(1000+id)`), so anything a player can do at a station, staff can be taught to do.
