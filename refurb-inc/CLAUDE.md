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
- 2026-10-20: Brief saved. Project lives in `refurb-inc/` inside the `jrpetty/football-` repo for now (that repo's root is an unrelated football predictor app). Move to a dedicated repo if/when Jon confirms.
- 2026-10-20: Milestone 1 plan drafted in `docs/MILESTONE_1_PLAN.md`. Awaiting Jon's go-ahead before writing code.
- Proposed (pending confirmation): fictional imaging OS = "Pane OS" (wordmark: PaneOS). Rival refurbishers TBD.
