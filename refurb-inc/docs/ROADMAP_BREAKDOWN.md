# REFURB INC — what would make it amazing (and smooth)

Written after the automation, staff, tea, goals and character work (October 2026). Each item says **why** it matters,
**what** to build, and roughly **how big** it is (S = an evening, M = a few sessions, L = a milestone).

## 1. Deliveries & logistics — the "better way to deliver"

The lorry + hi-vis driver + pallet truck already looks good. What would make deliveries feel great:

| Idea | Why | Size |
|---|---|---|
| **Delivery windows you book** on LotFinder (morning / afternoon / next-day saver at −5%) | Turns waiting into planning: book the lorry for when a bench frees up | S |
| **Dock leveller + roller doors that open themselves** when a lorry reverses in (warehouse tier and up) | Big-building fantasy; no running to the switch | S |
| **Forklift** (Jon's model is already processed) — drive it, lift two pallets, put pallets on racking | The natural step up from the pallet truck; huge for warehouses | M |
| **Pallet racking for whole pallets** (store a pallet of 30 laptops high up, pull it down when the line needs it) | Stock that doesn't eat floor space; forklift has a purpose | M |
| **Depalletiser cell**: arm + scanner that empties a pallet straight onto the goods-in conveyor | Late-game "lorry to line with no hands" — the automation payoff | M |
| **Courier van you can see** collecting at 12:00/16:30 (driver walks to OUTGOING with a sack truck) | The outbound half of the delivery story; satisfying to watch the shelf empty | S |
| **Dispatch conveyor** that feeds a cage at the door the courier empties | Closes the automated loop visually | S |
| **Delivery problems** (late lorry, wrong pallet, a damaged unit in transit you can claim for) | Light chaos, very on-brief | S |

## 2. Automation — from "it works" to "I can't stop building"

Built: conveyors (straight, 90° curves, flexible bends 15–180°), merger, scanner, sorter (rules per exit),
vision grader, arms (with vacuum plate), test/imaging racks, repair + packing robots, stock tower, recycling cage,
splitter/balancer, belt speed tiers (Standard/Fast/Express), line monitor screen, people's hatch, stop gate,
snapping, port arrows, reach rings, people-in-the-loop fallbacks, live Automation + Energy pages in Insights. Next:

| Idea | Why | Size |
|---|---|---|
| **Inclines / spiral lifts** and a **mezzanine** in the bigger buildings | Lines that go over walkways look incredible and save space | L |
| **Power capacity**: each building's kW limit with a breaker trip if you overload (already metered) | The brief's soft limit; a reason to upgrade premises | S |
| **Maintenance**: arms/belts slowly wear, a technician (or maintenance robot) services them | Gives staff a late-game job; light pressure | M |
| **Blueprints**: save a cell (arm + racks + belts) and stamp it down again (paid per item) | Huge quality-of-life in the mega plant | M |
| **Strip-down station + robot** for dead units → parts bins | It's in the brief; closes the "donor" loop | M |
| **Custom server build bench + business orders** | Big-margin content from the brief (M3) | L |

## 3. Staff — make them characters, not just helpers

Built: hiring from weekly candidates, stat cards, roles, pathfinding in every building, real commands, skill
levels driving speed (×0.4 at level 1 → ×2.3 at level 10), Friday wages with pay rises, value estimates.

| Idea | Why | Size |
|---|---|---|
| **Shifts & breaks** (they use the kettle — and make you a brew) | Charm; ties the tea round into staff | S |
| **Morale** (overwork → slower; tea, pay rises, a tidy floor → faster) | A reason to care about them | M |
| **Training** (send someone on a course: £, a few days away, +1 level) | Spend money to make people better | S |
| **Mess & cleaners** (cardboard, foam, dust from unboxing) | In the brief; adds a visible, light chore | M |
| **Staff photos** using the character menu's photo-face feature for comedy hires | Very streamable | S |

## 4. Smoothness (performance) — what's done and what's next

Done: conveyors swap to a 1k-triangle built copy beyond 16 m (the detailed model is 77k), characters have LODs
(≈30k close, far less at range), arms fade past 45 m, bench/PC screens only redraw when near and changing,
belt logic indexes items once per tick, staff navigation uses Godot's C++ AStarGrid2D.

Benchmark (`tools/benchmark.tscn`, v-sync off, GTX 1080 Ti, 1600×900): every shelf and rack bay full plus 12 full
pallets on the floor. First finding: **stock casting shadows was most of the frame** — 276 detailed laptops in every
light's shadow pass. Stored stock (shelves, racking, pallets, stock towers) now skips shadows and the presets use a
mesh-LOD threshold (low 4 px … ultra 1 px):

| Garage | before | after |
|---|---|---|
| Inside: avg / 1% low | 72 / 54 fps | 81 / 69 fps |
| Outside: avg / 1% low | 101 / 70 fps | 120 / 101 fps |
| Triangles per frame | 10.8 M | 4.8 M |
| Draw calls | 2,000 | 1,000 |

**All seven premises (21 October 2026)**, Ultra preset, every shelf full plus 12 pallets (276–696 units),
average fps / 1% low:

| Premises | Inside before | Inside after | Outside before | Outside after | High preset, inside after |
|---|---|---|---|---|---|
| Garage | 82 / 37 | 84 / 71 | 119 / 94 | 127 / 105 | 127 / 109 |
| Lock-up | 107 / 87 | 108 / 90 | 126 / 99 | 135 / 103 | 153 / 135 |
| Industrial unit | 109 / 64 | 116 / 96 | 116 / 94 | 134 / 108 | 152 / 120 |
| Warehouse | 72 / 60 | 76 / 49 | 71 / 56 | 101 / 75 | 100 / 84 |
| Distribution centre | 38 / 27 | 73 / 60 | 32 / 20 | 92 / 69 | 91 / 70 |
| Refurb facility | 32 / 21 | 64 / 55 | 32 / 20 | 86 / 56 | 82 / 66 |
| Mega plant | 43 / 30 | 77 / 66 | 33 / 20 | 87 / 66 | 95 / 80 |

What the probe (`-- --probe`) found and what was fixed:
- **Every stored unit reset its own transform every physics tick** (700 items × 60 Hz). Hiding the stock took the
  distribution centre from 36 to 85 fps; pausing just their scripts gave 70. Items now go idle once settled in a slot
  and wake when their state changes or their slot moves (`SnapSlot.moved`: a shelf moved, a pallet wheeled away).
- **Grade stickers and crack overlays** (two extra draw calls per unit) fade out beyond 8–10 m: draws 2,700 → 1,550.
- **No LODs on the heaviest models.** The AI meshes have a UV seam at almost every vertex, so Godot's simplifier can't
  reduce them: the 176k-triangle racking (also the network racks), conveyors, shelving, benches, desk, servers and
  the lorry were drawn in full in every shadow cascade. `tools/shadow_proxies.gd` gives each a ~1/12 shadow-only
  stand-in (looks identical: `tools/shadow_compare.tscn`). Simplified *visible* LODs were tried and rejected — the
  textures smeared. Not yet measured on the GPU (it dropped out again); it only removes shadow-pass work.
- Post-processing, lamps and MSAA each cost under ~10% on Ultra; SSIL + SSR + 4× MSAA + TAA are why Ultra trails High.

Next, in order of payoff:
1. **Re-run the benchmark** after a reboot to measure the shadow stand-ins; look at the warehouse inside view's
   occasional 28 ms frame (likely a first-view shader compile — try a shader warm-up on level load).
2. **MultiMesh for shelf stock** far from the camera (hundreds of laptops on racking as one draw call).
3. **Bake lighting** (LightmapGI) for each level in the editor; keep dynamic shadows to the sun + a few lamps.
4. **Occlusion culling** in the warehouse and up (Godot OccluderInstance3D for walls/racking).
5. **Physics sleep audit**: loose items settle and sleep; frozen in slots already.
6. **Insights**: compute the report on a worker thread, send guests diffs only.

## 5. Immersion & feel

- Item outline highlight on the thing you'll pick up; pick-up/put-down curves with a little weight.
- Dust motes in the roller-door light; screen glow lights on benches; footstep surfaces (concrete/metal/rubber mat).
- Music: a lo-fi radio on the workbench you can switch on (and off).
- Weather through the door (rain on the roof, puddles in the yard), day/night on the long days.
- Random events from the brief: power cut (machines stop, emergency lights), roof leak, courier strike, surprise mega-lot.

## 6. The long game

- Built: campaign milestones timed in working days, the end goal (mega plant + £20M), local leaderboards,
  achievements (70ish), skills to level 10 with perks, premises ladder £10k → £1M.
- Next: **reputation tiers** that unlock the website channel and business buyers; **rival refurbishers** bidding on
  lots; **seller site visits** (M6); **Steam leaderboards and achievements** when GodotSteam goes in (M2).

## 7. Assets that would lift it most (for Jon to generate)

Highest impact first. Same pipeline as before: drop the GLBs in `Desktop/Refurb INC/assets` and they're processed.

1. **Rigged, animated people** (Meshy image-to-3D with *rigging + animation on*, A-pose, 30k tris, PBR):
   - Warehouse technician — navy polo, grey work trousers, safety boots (male and female versions)
   - Lorry driver in hi-vis vest and cap
   - Office worker — shirt and chinos (male and female)
   - Animations to request: idle, walk, carry (box), push (cart).
   The procedural humans now in the game are a solid stand-in; real rigged models would be the final art.
2. **Forklift** (have) → plus a **sack truck** and a **roll cage** (courier).
3. **Strip-down station** (bench with a parts tray), **server build bench**, **cardboard baler**.
4. **Wall screens / factory dashboard monitor**, **safety barriers**, **floor-mounted bollards**, **pedestrian gate**.
5. **Kitchen props**: proper kettle, mug tree, small fridge, biscuit tin (the tea round deserves them).
6. **Courier van** (Zippd livery-free white van), **car for site visits** (have hatchback).
7. **Monitors on arms / desk props** for the sales & purchasing office in the bigger buildings.
