# How to run and test Refurb Inc.

## Open it
1. Install **Godot 4.7** (standard build, not .NET). The project was built and verified on 4.7.2.
2. Godot Project Manager → **Import** → choose `refurb-inc/project.godot`.
3. The first import takes a minute (textures and sounds).

## Play it
Press **F5**. You get the title screen with the garage behind it. Click **New game**.
To skip straight into a level, open `scenes/levels/garage/garage.tscn` and press **F6**. That starts a fresh solo game.

**Controls:** WASD move · Shift sprint · Ctrl crouch · Space jump · Mouse look ·
**E / left-click** = interact (tap) or **hold** for timed actions · **Q / right-click** = put down what you're carrying · **Esc** = menu / leave PC.

## What you should see (Milestone 1 loop)
1. **Day 1, 09:00**, cash £2,500. Top-right shows the clock (a day is 15 real minutes), cash, reputation, the next delivery or courier, and a blue **▸ next step** hint.
2. Walk to the **office PC** (back-left desk) and press E. The camera glides onto the monitor and the **PaneOS** desktop takes the mouse.
   - **Mail**: read "How this works".
   - **Purchasing / LotFinder**: lots from fictional sellers, with a per-unit price, seller notes and a market guide. Press **Buy lot**, then **Confirm**. A lorry is booked for 10:00 next working day.
   - **Shop**: order parts (batteries, RAM, SSDs, keycaps…), USB sticks and boxes. They arrive with the next courier visit.
   - Esc or **Step away** returns to first person.
3. Wait for 17:00, or keep working. The **end-of-shift card** appears; press **Start next day**.
4. At **10:00** the **lorry** comes down the street, **reverses onto the drive (beeping)**, lowers its tail lift and leaves a **pallet** of laptops.
5. Look at a laptop. The hover card shows only its **cosmetic** condition. Tap E to pick it up; you can stack up to 3. Look at a shelf or bench and tap E to place it; a green ghost shows where it'll go.
6. **Test bench**: laptop + DiagnostiKit USB (blue, on the bench). The test **starts by itself**. The laptop screen and the bench monitor show live diagnostics, and a label floats above the bench. Walk away; it finishes in ~1 real minute with **PASS** or a **fault list**.
7. **Repair bench**: place a faulty unit and **hold E on the laptop**. A ring fills around the crosshair. You need the part in the parts bins (you start with a few RAM sticks and SSDs).
8. **Imaging**: back on the test bench with the **PaneOS USB** (orange). The screen shows the wipe passes and install, then "Hello."
9. **Grading desk**: hold E. You get a grade **A/B/C/D**, a coloured sticker on the lid and a suggested price.
10. **Shelve it**, then on the PC open **Flogit** → **List**. Cheaper than guide sells faster. Orders arrive on the hour and show on the HUD.
11. **Packing table**: place the sold unit and hold E to box it. Put the box on the **OUTGOING** shelf by the door.
12. The **courier van** comes at **12:00 and 16:30**, collects, and the sale lands in your balance (minus the Flogit fee and postage). Late orders cost reputation.
13. **Fridays** at close: rent and bills come out. Going overdrawn is recoverable but costs fees and reputation.
14. **Kettle** (front-left corner): hold E for a brew, which gives a short speed boost. The **roller door** has a switch by the door.
15. **Esc → Save** at any time. The game also autosaves at the end of every day. Use **Continue** on the title screen to resume.

## Premises and equipment
- **Move equipment:** look at any bench, shelf or desk and press **F**. A green ghost follows your aim and snaps to a 0.25 m grid. **R** or the mouse wheel rotates it, **E** places it and **Q** cancels. The ghost turns red if it would hit a wall, a doorway or another piece of equipment. Whatever is on the equipment moves with it.
- **Office PC → Building app:**
  - **Premises** lists all 7 rungs with photos, rent, utilities, floor area, power, loading bays and move cost.
  - **Move here → Confirm** charges the move fee, fades to black and drops you in the new building. Your equipment is laid out automatically and your stock comes with you. Loose pallets land in goods-in.
  - **Equipment** lets you move, store or place what you own.
  - **Buy equipment** buys extra benches, shelves, desks and so on, and goes straight into placement mode.
- **What to look for in each building:**
  - **Garage:** oil stains and damp, wheelie bins, a semi-detached street with a post box and a telegraph pole.
  - **Lock-up:** a Victorian railway viaduct with businesses in the arches, a puddled court, a skip.
  - **Industrial unit:** a business-park terrace with "Unit 4" on the fascia and the estate totem sign.
  - **Warehouse:** three docks on a sunken apron, a two-storey office, a gatehouse and trailers.
  - **Distribution centre:** eight docks, an amber brand band, a glass office with a rooftop sign, a sprinkler tank and a big car park.
  - **Facility:** a glazed HQ with amber fins, a reflecting-pool plaza, solar car ports, colour-zoned floors, a mezzanine and canteen, and a branded wall.
  - **Mega plant:** a sawtooth super-hall, 16 docks, an HQ tower with a skybridge, a wind turbine, a lake and a roundabout.
- **Quick tour from the editor:** open any `scenes/levels/<id>/<id>.tscn` and press **F6**. It starts a fresh game in that building.

## Co-op (LAN / same PC)
- Title screen → **Host co-op**. On a second instance → **Join co-op…** → host's IP → **Join**.
- Same PC: Godot **Debug → Customize Run Instances… → 2 instances**, then press F5 twice-ish (one hosts, one joins `127.0.0.1`).
- Steam lobbies (GodotSteam) replace this in Milestone 2. The game code doesn't change, only the transport.

## Automated checks
Run everything from a terminal: `tools/run_all_checks.sh /path/to/godot`
Or run individually in the editor:
- `tests/run_tests.tscn` (F6) — 16 headless logic tests: lot pricing/quality, the full buy-to-ship loop, rent, save/load, replication.
- `tools/playtest.tscn` (F6) — drives the real garage at 8× speed: lorry, bench, repair, image, grade, sell, pack, courier, parcel, save/load.
- `tools/premises_test.tscn` (F6) — moves one company through all 7 buildings: layout, stock, loose pallets, lorry to docks, extra equipment.
- `tools/net_test.tscn -- --role host` and `-- --role client` — two-process co-op check.
- `tools/check_scripts.tscn` — compiles every script.
- `tools/screenshot.tscn` — renders QA shots into `tools/out/` (args: `--setup` for a busy scene, `--ui` for HUD/PC shots).

## Regenerating generated content
- `python3 tools/gen_textures.py` — placeholder PBR texture sets
- `python3 tools/gen_audio.py` — placeholder sound effects
- `tools/build_scenes.tscn` (run it; add `-- --only <level>` for one) — rebuilds wrapper scenes, the material library and the 7 premises levels. Don't regenerate a level you've hand-edited.
- `tools/shoot.sh <level> [--poses ext,int,aerial]` — renders review shots of any premises (needs xvfb + Vulkan).
