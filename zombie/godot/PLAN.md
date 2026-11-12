# REKINDLE — the plan to take it up a level

The aim: a game that feels finished the moment you start it — it looks good, it sounds good, nothing clips or
slides or floats, and there is always something worth doing next. "AAA" budgets are not on the table, so the
plan spends effort where a player notices it first.

## Pillars

1. **Solid.** Nothing walks through anything. Zombies, the player and (later) residents never pass through
   walls, doors, furniture, trees, vehicles or each other; nothing sinks into or floats over the ground; no one
   gets stuck. This is enforced by automated audits that run hundreds of bodies through the town and fail on a
   single violation.
2. **Looks right.** Believable light at every hour, weather, real materials, living vegetation, water you want
   to walk up to, and hands on your weapon.
3. **Feels right.** Every action answers back: weight in a swing, a kick from a gun, a flinch when you are hit.
4. **Sounds right.** A score that follows the danger, a world you can hear (wind, birds, night, rain, rooms),
   and the dead placed in space around you.
5. **Deep enough.** The loop the web version already has — scavenge, fortify, craft, settle, defend — all of it,
   in this engine.
6. **Finished edges.** Menu, loading, settings, map, compass, tooltips, controller, saves that never break.
7. **Fast.** 60 fps on a mid-range GPU with a horde on screen.

## Stage A — now

| Area | Work |
|---|---|
| Solid | No-clip audit for zombies and the player (wall crossing, penetration, floor height, overlap); fixes until it reports zero; zombie–zombie and zombie–player separation; corpses lie on the slope; stuck recovery; doors and windows as the only ways through a wall. |
| Light | Global illumination and volumetric fog on Forward+; contact shadows, SSAO/SSIL; reflections on water; anti-aliasing; exposure that adapts indoors. |
| Sky & weather | Clouds, stars and a moon in the sky shader; clear, overcast, rain, storm and fog; wet ground; wind that drives grass, trees and rain together. |
| Materials | Photographic ground, wall and roof textures (generated with Higgsfield, made seamless, with derived normal and roughness maps) replacing the procedural 512 px set where it shows most. |
| Vegetation | Wind sway on trees and bushes, light through leaves, distance fade without popping. |
| Water | Depth colour, shoreline foam, ripples, reflections. |
| Character | First-person arms on every weapon; blood decals and screen feedback when hurt. |
| Sound | Two-layer score (calm / threat) that cross-fades with danger; ambience beds for day, night, rain and indoors; room reverb. |
| Front end | Main menu over key art, loading screen with progress and tips, settings (graphics presets, field of view, sensitivity, volumes), pause. |
| HUD | Compass, map, item tooltips, damage direction, stealth meter, cleaner inventory. |
| Systems | Sleeping, crafting, building and barricading, campfire and cooking. |

## Stage B — next

The settlement and its residents (jobs, schedules, skills, bonds, the bite), farming, vehicles, wildlife,
machines and power, railways, seasons with snow, corpse looting, controller support, performance passes
(LOD, occlusion, shader warm-up).

## How it is kept honest

- `godot --headless --path zombie/godot -- --selftest` — a scripted play-through.
- `scripts/zombie_test.gd` — zombie behaviour.
- `scripts/test_noclip.gd` — the no-clip audit (Stage A adds it).
- Every stage ends with these green on the exported build, not only in the editor.
