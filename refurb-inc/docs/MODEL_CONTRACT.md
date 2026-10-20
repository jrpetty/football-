# Model Contract — dropping in final art

Every visual in the game is a **ModelSlot**. Empty `model_scene` = procedural placeholder.
Set `model_scene` to your imported model and it replaces the placeholder. Nothing else changes:
collisions, snap points, scripts and lights live on the parent scene, not on the model.

## Universal rules (all models)

| Rule | Value |
|---|---|
| Scale | **1 unit = 1 metre** (a laptop is ~0.32 m wide, a workbench ~0.9 m tall) |
| Origin | **Bottom-centre** (where it touches the floor/desk) |
| Facing | **Front faces +Z.** For a workbench, that's the side you stand at; for a vehicle, the cab end. This is glTF's default "forward", so most exports are already right. |
| Format | `.glb` (glTF binary) preferred. One prop per file. |
| Folder | `assets/models/<category>/<prop_name>/<prop_name>.glb` plus its textures |
| Poly budget | Small props < 5k tris · laptops < 8k · benches/shelves < 15k · vehicles < 40k |
| Materials | PBR (albedo / normal / roughness, metallic where needed). 1–2 materials per prop is ideal. |
| Branding | **Fictional brands only** (Corvex, Halden, Northstar, Brightline, Kestrel, Vortex, Arcturo, Linkwell, Flogit). No real logos. |

**Import settings** (select the .glb in Godot → Import tab):
- *Meshes → Generate LODs*: **on**
- *Meshes → Create Shadow Meshes*: **on**
- *Meshes → Light Baking*: **Static Lightmaps** (for static props), **Dynamic** for carried items and vehicles
- Leave physics off. Colliders come from the wrapper scene.

You can line a model up without re-exporting it. Set `model_offset`, `model_rotation_deg` and `model_scale` on the ModelSlot.

## How to swap one

1. Copy the `.glb` into `assets/models/...` and let Godot import it.
2. Open the wrapper scene from the table below, select its **Model** node (a ModelSlot), and drag the `.glb` into **Model Scene**.
3. Press F6 on `scenes/levels/garage/garage.tscn` to see it in place.

Or send me the files and I'll wire them up, check scale/orientation in a render and commit.

## What the game expects

### Hardware (data-driven)
Laptops are set per model in `data/hardware_models.json` → `visual.model` = path to a `.glb`/`.tscn`.
Leave it `""` to use the procedural laptop.

| Requirement | Detail |
|---|---|
| Closed by default | Lid shut, lying flat |
| Node named **`Lid`** | Pivot exactly on the hinge axis, at the back edge. The game rotates it about local X (negative = opens, about −108°). |
| MeshInstance3D named **`Screen`** | Inside the lid. The game replaces its material with the live screen (POST / diagnostics / wipe / PaneOS), so give it simple UVs covering 0–1. |
| Footprint | Match the model's `w` / `d` / `h` in the JSON (or update them). They size the collision and the stacking. |
| Optional | Separate keycap meshes are not needed. Missing keys/cracks are overlays. |

Ten models are defined: Corvex Prospect 5420 / 7390 / Vantage 3510, Halden Folio 840 G7 / 650 G5, Northstar Meridian T14 / X13 / Edge E15, Brightline Aura 13, Kestrel Swift 14.
A few good base meshes plus re-coloured materials will cover them.

### Stations and props (wrapper scenes)

| Wrapper scene | Placeholder id | Target size (W×D×H m) | Named nodes the code looks for |
|---|---|---|---|
| `scenes/stations/test_bench.tscn` | `test_bench` | 1.4 × 0.75 × 0.9 (+ riser, status monitor) | `Screen` (status monitor surface) |
| `scenes/stations/repair_bench.tscn` | `repair_bench` | 1.6 × 0.75 × 0.92 (+ pegboard 0.9 high) | — |
| `scenes/stations/grading_desk.tscn` | `grading_desk` | 1.2 × 0.7 × 0.9 | `LabelPrinter` (optional) |
| `scenes/stations/packing_table.tscn` | `packing_table` | 1.6 × 0.8 × 0.92 | `TapeGun`, `LabelPrinter` (optional) |
| `scenes/stations/metal_shelf.tscn` | `metal_shelf` | 1.2 × 0.45 × 1.9, 5 levels | — (snap points are generated per level) |
| `scenes/stations/courier_shelf.tscn` | `courier_shelf` | 1.0 × 0.5 × 1.0, 3 levels | — |
| `scenes/stations/parts_shelf.tscn` | `parts_shelf` | 1.0 × 0.4 × 1.6 | — (bins are generated) |
| `scenes/stations/office_desk.tscn` | `office_desk` | 1.4 × 0.7 × 0.74 (+ chair, tower) | — |
| OfficePC (inside `office_desk`) | `monitor` | 0.62 wide 16:9 panel | **`Screen`** = a **QuadMesh** facing +Z (UI is projected onto it) |
| `scenes/stations/kettle_corner.tscn` | `kettle_corner` | 0.9 cabinet + 0.48 fridge | — |
| `scenes/props/pallet.tscn` | `pallet` | 1.2 × 0.8 × 0.144 (EUR) | — |
| `scenes/props/*.tscn` | bin, ladder, paint_tins, cable_reel, cardboard_box, strip_light, workbench | real-world sizes | — |
| `scenes/vehicles/lorry.tscn` | `lorry` | ~2.45 × 7.2 × 3.2, rear at −Z | **`TailLift`** (lowers to 0.05), **`RearDoor`** (slides up) |
| `scenes/vehicles/courier_van.tscn` | `van` | ~2.0 × 5.0 × 2.3, rear at −Z | — |
| Items (code, `ItemView.KIND_VISUAL`) | usb_stick, cardboard_box | USB ~0.018 × 0.055 · parcel 0.42 × 0.32 × 0.26 · order box 0.44 × 0.34 × 0.12 | — |
| Remote player avatar | `person` | 1.75 m human, faces +Z | — (rigged characters come with staff in Milestone 4) |

### Garage shell
The walls, floor, roof, drive and street are simple boxes under `Garage/Shell` and `Garage/Exterior` in `garage.tscn`.
To use a modelled garage, hide or delete those nodes and drop your model in at the same place. The interior is 6.0 × 7.0 m, the ceiling is 2.7 m and the door opening is 4.2 × 2.25 m at z = +3.5.
**Note:** `tools/build_scenes.tscn` regenerates `garage.tscn`, so stop re-running it once you start hand-editing the level (use `--only-props`).

### Textures
Swap any file in `assets/textures/surfaces/<set>/` (`albedo.png`, `normal.png` in OpenGL/Y+ format, `roughness.png`) with a Poly Haven / ambientCG / AI texture of the same name.
Everything using that set updates. Mapping is world-space triplanar, so tile size is set in code (`Mats.surface(set, tile_m)`).

### Sounds
`assets/audio/{sfx,ui,ambience}/<name>.wav` (or `.ogg`). Variants are `name_1.wav`, `name_2.wav`… and are picked at random.
The current files are synthesised placeholders from `tools/gen_audio.py`. Replace them freely with the same names.
Names in use: footstep_1-4, pickup, place, impact_light, impact_heavy, pallet_drop, box_open, post_beep, test_pass, test_fail, fan_loop, label_print, repair_loop, repair_done, tape_gun, roller_door, kettle, reverse_beep, diesel_idle, van_engine, air_brake, hydraulic, van_door, ui_click, error, notify_good/warn/bad, pc_open, pc_close, day_end, cash, street_ambience, fluorescent_hum.
