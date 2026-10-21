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

## Business Insights (office PC → Insights, the yellow ▲)
Everything about the business, live, in 13 sections down the left. Hover the charts for each day's figures. **Refresh** pulls the latest; it also refreshes itself every few seconds.
- **Highlights:**
  - Plain-English findings spotted from your data: bottleneck, pricing sweet spot, best earner, lemon models, best and worst suppliers, whether bargain lots really pay, the value of repairs, what extra RAM is worth, the busiest order times, low supplies, overdue orders and cash runway.
  - Goals with progress bars and ETAs: the next premises, sales milestones and the next seller unlock.
  - Records: biggest and most profitable sale, fastest flip, best day, best lot, streaks and peak cash.
- **Overview:** headline KPIs, where the money goes on an average sale, the daily revenue/profit chart, and today so far.
- **Sales:** average and median sale price, average purchase price of what sold, markup, price vs guide, days in stock, and time to sell once listed. Broken down by grade, model, maker and channel, plus the last 30 sales.
- **Pricing & demand:**
  - Price bands against the market guide, showing how fast each band sells, its profit, and "£ per shelf-day".
  - A weekday × hour heatmap of when orders arrive.
  - What memory, storage and processor are worth.
- **Customers:** unique and repeat buyers, share of orders from repeat buyers, average spend, and top customers with their orders, spend, profit, first/last order and late deliveries.
- **Purchasing:**
  - **Deal quality**: bargain, typical and premium lots by price index, showing pass rate, faults, grade, profit and ROI.
  - **Lot returns**: per-lot money recovered, ROI so far and projected ROI.
  - Sellers, sectors, recent lots and deliveries due.
- **Products & quality:** model reliability (pass rate, faults per unit, most common fault, battery, average grade, profit), how kit arrives (cosmetic conditions) and battery health on arrival.
- **Stock:** pipeline with average age, days of cover, stock turns, graded stock by grade, listed units (bought vs listed vs guide), slow movers, parts and packing supplies.
- **Operations:**
  - Where the time goes: hours per step from delivery to shipped, with the bottleneck flagged and units waiting now.
  - The funnel from bought to shipped.
  - Test-bench utilisation and capacity, throughput, grades, "Repairs pay?", faults and parts used.
- **Orders & service:** on-time %, streaks, lateness, cancellations, dispatch times, reputation chart and shift-rating distribution.
- **Finance:** full P&L, averages, 5- and 20-day cash forecasts, break-even, runway, week-by-week table, closing-cash chart, best and worst day, and ledger categories.
- **People & premises:**
  - Awards, staff and wages (£0 until hiring arrives).
  - Hours and jobs per hour per player, and work done.
  - Rent per m², floor and power use, and equipment.
- **Timeline:** the company diary, covering lots bought, milestones, record days, skill level-ups, reputation marks, new sellers, moves and equipment.
- **To see it full of data:** run `xvfb-run godot --path . res://tools/screenshot.tscn -- --insights`. It simulates 20 working days with the bot and saves every section to `tools/out/insights_*.png`.

## Your business name
- Title screen → **New game** (or **Host co-op**): a "Name your business" box appears, filled with the last name you used (first time: Refurb Inc.). Type a name, press Enter or **Start trading**. The sign on the building shows it.
- Office PC → **Finance** → **Rename** at the top. Type a new name, press Enter or **Save**. A toast confirms it and the sign outside changes straight away. In co-op, everyone sees the new name.

## Staff, characters, stock, achievements, automation (October 2026)
- **Character:** title screen → **Character**. Change shirt, trousers, skin, hair and style, build and height; tick hi-vis, cap or big head; **Upload photo** puts your face on your avatar. The preview turns as you change things. In co-op the others see your look.
- **Staff:** office PC → **Staff** (smiley icon) → **Candidates** → **Hire** a tester. They walk to the test bench, swap USBs and test whatever is waiting. **Your team** shows what they're doing, their skills and their wage. A level-1 hire is slow; a level-8 one is quick and costs much more. Wages go out on Friday with the rent.
- **New stock:** LotFinder lots now include monitors, Linkwell switches and Wi-Fi APs, docks, tablets and phones. On the test bench each kind gets its own diagnostics steps and hardware list.
  - Monitors and docks show "TESTED — ready to grade": they have no data to wipe.
  - Phones and tablets need the PaneOS USB for a secure wipe and factory reset. Switches and APs get a config wipe and firmware update.
  - An activation-locked phone can't be fixed and grades as D (Spares/Repairs).
- **Goals:** office PC → **Goals** (crown icon). Milestones are timed in working days and fastest times are kept under **Leaderboards**.
- **Achievements:** office PC → **Achievements**, or the button on the title screen. Brew a cup of tea to see the gold unlock card.
- **Automation:** Building → Shop → **Automation**.
  - Lay out conveyor → stop gate → conveyor → splitter with a conveyor off each side exit. Each piece snaps onto the green arrows.
  - Kit alternates left and right at the splitter. Press **E** on the splitter for Overflow, and **E** on the gate to hold or open.
  - **Hold E** on any belt to upgrade the whole run to Fast (blue), then Express (orange).
  - A **people's hatch** stops kit at a pick face for a person; put it on the far half to send it on.
  - A **line monitor** shows the line live with the bottleneck outlined in red.
  - **Flexible bend:** while placing it, scroll changes the angle, Shift+scroll the radius and **T** flips the side.
- **Insights:** the new **Automation, Energy, Staff and Lifetime stats** pages update live while you watch them.
- **Bench screens for every kind of kit:** run `tools/bench_screen_shot.tscn` (F6). It saves `tools/out/bench_<model>_<1-4>.png`.
- **Benchmark:** run `tools/benchmark.tscn` (F6, or `-- --levels garage,warehouse --preset high`). It fills every shelf, drops pallets, pans the camera with v-sync off and writes avg FPS, 1% low and draw calls to `tools/out/benchmark_<preset>.txt`. Add `--probe` to see where the frame goes (stock, sun shadows, post-processing, lamps, building). It runs the GPU flat out: run one premises per launch.
- **Shadow stand-ins:** `tools/shadow_compare.tscn` renders the heavy props with their own shadows vs the stand-ins side by side (`tools/out/shadow_*.png`). After new models come through the pipeline, run `godot --headless --path . -s tools/shadow_proxies.gd` to give any new heavy mesh a stand-in. `tools/lod_report.gd` lists every mesh's triangles and LODs.

## Forklift, pallet racking, unload bay, fit-out (October 2026)
- **Forklift:** office PC → **Shop** → Equipment → **Electric forklift** (£7,800). It's dropped off in goods-in (no box).
  - Look at it and press **E** to get in. The view glides into the seat.
  - **W/S** drive, **A/D** steer. The back swings out like a real counterbalance truck.
  - **R/F** (or the mouse wheel) raise and lower the forks. They stop the moment you let go.
  - **H** sounds the horn and **E** gets you out. Reversing beeps, and the motor whines with speed.
  - Walls, equipment, racking and stock stop the truck. Bare forks stop at walls too.
- **Lift a pallet:** lower the forks to the floor and drive them into the pallet until the truck stops at the backrest. The card says "Forks under the pallet", so press **R** and it comes up. Lower it anywhere on the floor with **F**.
- **Pallet racking:** Building → Buy equipment → **Logistics**.
  - **Pallet racking** has 3 bays × 3 levels (4 m tall), for warehouse-height buildings.
  - **Low pallet racking** has 2 × 2 and fits under a garage roof.
  - Carry the pallet long side along the forks and raise it above the beam you want. Drive in until the truck stops at the racking. The card says "Lined up with Racking R1 · level 2 · bay 2"; press **F** and it settles on the beam.
  - Off-line over a beam, it stops and says "Line it up with a bay".
  - Units on a racked pallet show in the **Stock** app as e.g. "Racking R1 · level 3 · bay 2". You can't hand-pick from the upper levels.
  - The hand **pallet truck** reaches the floor level only: wheel a pallet into a floor bay and press E.
- **Pallet → line:** buy a **Pallet unload bay** (yellow painted box) and put a **robot arm** beside it, with the start of a conveyor inside the arm's cyan ring.
  - Bring a delivered pallet over with the forklift or pallet truck and set it down in the bay.
  - The arm unloads it unit by unit onto the belt (or straight onto a test rack if one is in reach).
  - Pallets stored in racking are left alone.
- **Fit-out app** (office PC, pink ✎ icon) has five tabs:
  - **Walls & colours:** pick a paint and the inside walls change straight away. "As found" strips it back. The accent colour goes on the signs, rug and motto poster.
  - **Floor markings:** walkway lines 0.9 m in from the walls and/or painted boxes round every piece of equipment, plus a hatched pallet zone at goods-in. Choose the line colour. The boxes follow equipment when you move it.
  - **Signage:** rename the company, pick a logo (initials badge, ring, chevrons or name only) and put the door signs up or down. A sign goes above every roller door, and the logo goes on the shutter.
  - **Decor:** plants, a rug, the site radio (E switches it on and plays Refurb FM), a wall clock that keeps game time, and three posters. Wall pieces hang on the nearest wall.
  - **Employee of the Month:** this week's winner and last week's leaderboard.
- **Employee of the Month:** the gold frame on the wall (every premises has one).
  - Every Monday, whoever got the most done the week before goes up: tests, repairs ×1.5, imaging, grading, packing, hauling ÷4 and lots bought ×2. Staff count too.
  - If the winner uploaded a photo face (Character menu), it's their face in the frame. Otherwise it's a name card with their initials in the accent colour.
- **Moving premises:** all of the above comes with you: paint, lines, signs, decor, racking (with its pallets), the forklift and the frame.
- **Co-op:** the guest sees the driver sat in the forklift, the forks moving and the pallet riding them. Paint and decor changes show for everyone.
- **Renders:** `tools/logistics_shot.tscn -- --shot rack` (forklift at the racking, unload bay with an arm) or `-- --shot garage` (a customised garage). Use the software renderer if the GPU is busy: `godot --rendering-driver d3d12 --gpu-index 1 --path . res://tools/logistics_shot.tscn -- --shot garage`. Output goes to `tools/out/`.

## Co-op (LAN / same PC)
- Title screen → **Host co-op**. On a second instance → **Join co-op…** → host's IP → **Join**.
- Same PC: Godot **Debug → Customize Run Instances… → 2 instances**, then press F5 twice-ish (one hosts, one joins `127.0.0.1`).
- Steam lobbies (GodotSteam) replace this in Milestone 2. The game code doesn't change, only the transport.

## Automated checks
Run everything from a terminal: `tools/run_all_checks.sh /path/to/godot`
Or run individually in the editor:
- `tests/run_tests.tscn` (F6) — 102 headless logic tests: lot pricing/quality, the full buy-to-ship loop, rent, save/load, replication, premises, business stats, staff, new stock, tea, goals, achievements, automation, Steam, logistics (forklift, racking, lorry → unload bay → arm → belt) and the premises fit-out (paint, lines, signs, decor, Employee of the Month).
- `tools/playtest.tscn` (F6) — drives the real garage at 8× speed: lorry, bench, repair, image, grade, sell, pack, courier, parcel, save/load.
- `tools/premises_test.tscn` (F6) — moves one company through all 7 buildings: layout, stock, loose pallets, lorry to docks, extra equipment, and in every building the paint, floor lines, door logos, the Employee of the Month frame, the forklift's parking spot and a racked pallet that travels with its racking.
- `tools/forklift_test.tscn` (F6) — the real player drives the real forklift with simulated keys: forks under a pallet, lift, into the racking at level 2, set down, reverse out, walls stop it.
- `tools/net_test.tscn -- --role host` and `-- --role client` — two-process co-op check.
- `tools/check_scripts.tscn` — compiles every script.
- `tools/economy_sim.tscn -- --days 20 --seed 3` — a bot runs the business through the real commands for N days and prints the headline numbers and the auto-insights (good for balancing).
- `tools/screenshot.tscn` — renders QA shots into `tools/out/` (args: `--setup` for a busy scene, `--ui` for HUD/PC shots).

## Regenerating generated content
- `python3 tools/gen_textures.py` — placeholder PBR texture sets
- `python3 tools/gen_audio.py` — placeholder sound effects
- `tools/build_scenes.tscn` (run it; add `-- --only <level>` for one) — rebuilds wrapper scenes, the material library and the 7 premises levels. Don't regenerate a level you've hand-edited.
- `tools/shoot.sh <level> [--poses ext,int,aerial]` — renders review shots of any premises (needs xvfb + Vulkan).
