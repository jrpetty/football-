# REKINDLE — Godot 4 port

Open this folder (`zombie/godot`) with **Godot 4.4 or newer** and press Play (F5). The first time, the editor
imports the models, textures and sounds; that takes a minute.

This is the web game rebuilt in Godot, in stages. The world, models, items, characters and sounds are exported
from the web version by `zombie/scripts/godot-export/`, so both versions share the same content.

## What is in so far

- The same world: terrain, roads, the lake, the town and its interiors, street clutter, forest, ore deposits,
  wind-blown grass, day and night.
- First-person movement with the web game's collision (walls, furniture, steps, floors).
- Survival: hunger, thirst, fatigue, stamina, temperature, body parts and wounds, bleeding, infection,
  the grey fever, status icons.
- Inventory with weight, clothing, hotbar, eating and drinking, medicine and wound treatment, skill books.
- Doors (open, close, force a lock), lootable containers with the web game's loot, dropped bags,
  drinking at the lake.
- Melee and firearms: swings, headshots, reloads, aiming, shoving, weapon wear; felling trees and mining rock.
- The dead: the shared character body with its baked animation, six kinds, decay, senses, attacks.
- Synthesised sound effects from the web game, rendered to files.
- Save and load (F5, autosave every three game hours).

## Not ported yet

Building and barricades, machines, belts and power, railways, the settlement and its residents, farming,
crafting and cooking, vehicles, wildlife, weather and seasons' effects, the map, music and ambience.

## Controls

WASD move · Shift sprint · C crouch · Space jump · E interact · Tab inventory · H health · LMB attack ·
RMB aim / shove · R reload · F flashlight · Q bandage · 1–8 hotbar · F5 save · Esc pause

## For developers

- Re-export content from the web game (from `zombie/`):
  `npx vite --config scripts/godot-export/vite.export.mjs &` then `node scripts/godot-export/run.cjs all`
  (parts: `models`, `world`, `data`, `audio`), and `node scripts/godot-export/characters.mjs` for the
  character body.
- Scripted play-through: `godot --headless --path zombie/godot -- --selftest`
- `scripts/world_data.gd` (autoload `WorldData`) holds terrain, collision and the nav grid;
  `scripts/world.gd` builds what you see; `scripts/game.gd` is the game loop.
