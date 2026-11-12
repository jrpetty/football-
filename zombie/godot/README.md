# REKINDLE — Godot 4 port

Open this folder (`zombie/godot`) with **Godot 4.4 or newer** and press Play (F5). The first time, the editor
imports the models, textures and sounds; that takes a minute.

This is the web game rebuilt in Godot, in stages. The world, models, items, characters and sounds are exported
from the web version by `zombie/scripts/godot-export/`, so both versions share the same content.

## What is in so far

- The same world: terrain, roads, the lake, the town and its interiors, street clutter, forest, ore deposits,
  wind-blown grass and trees, day and night, weather (clear, cloud, rain, storms, fog).
- Photographic ground, wall and roof materials; a sky with drifting cloud, moon and stars; a lake with clear
  shallows and reflections.
- First-person movement with the web game's collision (walls, furniture, steps, floors).
- Survival: hunger, thirst, fatigue, stamina, temperature, body parts and wounds, bleeding, infection,
  the grey fever, status icons.
- Inventory with weight, clothing, hotbar, eating and drinking, medicine and wound treatment, skill books.
- Doors (open, close, force a lock), lootable containers with the web game's loot, dropped bags,
  drinking at the lake.
- Melee and firearms: swings, headshots, reloads, aiming, shoving, weapon wear; felling trees and mining rock.
- Building and barricading: foundations, walls, doors, windows, stairs, roofs, gates, a watchtower,
  crates and stations; boarding up town doors and windows; the dead break what you build.
- Crafting by hand and at a workbench; campfires and wood stoves that warm, light and cook; sleeping.
- The dead: the shared character body with its baked animation, six kinds, decay, senses, hordes.
- A score that follows the danger, ambience for wind, rain, night and rooms, and the web game's effects.
- Main menu, character creation, settings, loading, pause, map and minimap, compass.
- Save and load (F5, autosave every three game hours).

## Not ported yet

Machines, belts and power, railways, the settlement and its residents, farming, vehicles, wildlife, snow,
corpse looting, key rebinding and controller support. See PLAN.md.

## Controls

WASD move · Shift sprint · C crouch · Space jump · E interact · Tab inventory · H health · M map ·
LMB attack · RMB aim / shove · R reload · F flashlight · Q bandage · 1–8 hotbar · F5 save · Esc pause

B build menu (LMB build · R rotate · X dismantle · RMB / Esc leave) · N crafting ·
hammer in hand + E boards up a door or window, or mends what you built · E / Space gets you out of bed

## For developers

- Re-export content from the web game (from `zombie/`):
  `npx vite --config scripts/godot-export/vite.export.mjs &` then `node scripts/godot-export/run.cjs all`
  (parts: `models`, `world`, `data`, `audio`), and `node scripts/godot-export/characters.mjs` for the
  character body.
- Tests (headless): `godot --headless --path zombie/godot -- --selftest` (play-through),
  `-s res://scripts/test_systems.gd` (building, crafting, fire, sleep), `-s res://scripts/zombie_test.gd`,
  `-s res://scripts/test_noclip.gd` (nothing walks through anything), `-s res://scripts/test_ui.gd -- --check`
- Photographic textures: `python3 scripts/godot-export/textures.py <dir of source .png>`
- `scripts/world_data.gd` (autoload `WorldData`) holds terrain, collision and the nav grid;
  `scripts/world.gd` builds what you see; `scripts/game.gd` is the game loop.
