# DayZ map data

Places and features are built by `scripts/build-dayz-data.mjs`, guns by `scripts/build-dayz-weapons.mjs`, from two sources kept in separate files because their licences differ.

## places.<map>.json — named places

From the **DayZ Wiki** (https://dayz.fandom.com), the location pages and the interactive maps, read through the wiki's API on 2026-11-13. Licence: **CC BY-NC-SA 3.0** (https://creativecommons.org/licenses/by-nc-sa/3.0/): attribution, non-commercial, share alike. This adaptation (names, Cyrillic or Polish names, types, loot tiers, facilities, positions) is shared under the same licence.

Each position was checked against the game's own building list (below): a settlement must have buildings at its grid reference. Where the grid fits only counted from the other edge it was corrected; places with no position, or a position with no buildings, or one copied from another place, are left out and listed in each file's `notes`.

## world.<map>.json — the game's features

From **Bohemia Interactive's DayZ Central Economy** files (https://github.com/BohemiaInteractive/DayZ-Central-Economy): water pumps, fuel stations, medical, police, fire and military buildings, lighthouses, helicopter crash, police car and convoy spawn points, contaminated zones, player spawn points, and the lootable buildings by type near each place. Licence: **Arma and DayZ Public License Share Alike (ADPL-SA)** (https://www.bohemia.net/community/licenses/arma-and-dayz-public-license-share-alike-adpl-sa): attribution, non-commercial, DayZ/Arma only, share alike. © Bohemia Interactive a.s. DAYZ® is a registered trademark of Bohemia Interactive a.s.

## weapons.json — guns

Built by `scripts/build-dayz-weapons.mjs`. From the **DayZ Wiki** weapon pages and its "Weapons, damage and armor" ammunition table (CC BY-NC-SA 3.0, read 2026-11-13; the table gives DayZ 1.12 values): descriptions, fire modes, rate of fire, recoil, noise, sights and zeroing, damage, shock, muzzle speed and air friction. From the **Central Economy** files (ADPL-SA): where each gun spawns, its loot tiers and count, and the magazines and attachments it spawns with. The bullet drop is worked out from the speed and air friction. The look of each gun is written for this pack; no images are bundled.

## Map images (src/ui/assets/dayz/*.jpg)

Built by `scripts/build-dayz-maps.py` from the top-down map images in Bohemia Interactive's Central Economy tool (`CETool/<Map>/map.png` in the same repository, ADPL-SA). Resized to 2048 (Chernarus, Sakhal) or 3072 pixels (Livonia, whose dark image is brightened); otherwise unchanged. North up; each covers the whole terrain.

## terrain.<map>.json — the ground, for routes

Built by `scripts/build-dayz-terrain.py` from the same map images: a 1024 × 1024 grid per map, each cell road, open ground, forest or water, read from the picture's colours and texture (no road network is published). An estimate: small paths and bridges can be missed; Sakhal's roads barely show. ADPL-SA like the images.

## items.chernarus.json — where things spawn on Chernarus

Built by `scripts/build-dayz-items.mjs` from the Central Economy's `db/types.xml` (every item with a nominal count: its building types, loot tiers and count, colour variants grouped) and `areaflags.map` (the loot tier of every spot, as a 256 × 256 grid). Names are the class names made readable, with the in-game name where it differs. ADPL-SA.

## Before any commercial use

Both licences are **non-commercial**. Replace this data, or get permission from the rights holders, before the app is sold or used commercially.

## Travel times

`src/core/dayz/travel.ts` gives rough trip times. They are estimates, not the game's numbers: Bohemia's scripts (`DayZ-Script-Diff`, `playerconstants.c`) give food, water and stamina use per second but no movement speeds, and the DayZ Wiki lists car top speeds only (for example the Sarka 120 at about 100 km/h on flat roads). Jogging is taken as about 16 km/h (it costs no stamina in 1.x); "with sprints" generalises sprinting whenever stamina lets you and jogging between, about 19 km/h on average (how long you can sprint depends on stamina and what you carry, so heavy loads do worse); swimming about 5 km/h, a car about 65 km/h on roads and 30 km/h off them. Straight-line answers add a detour (×1.15 on foot, ×1.4 by car); the map uses the length over the ground.

## Map marks

`src/core/dayz/marks.ts` keeps the player's own marks: what they saw (players, shots, danger) and what is theirs (base, stash, vehicle, loot, meeting point, notes), plus their last spot when a life ends in death. Every mark comes from what the player said or clicked; nothing is read off the screen for it and no other player's position is inferred. Sightings carry the time they were marked, fade after 30 minutes and are dropped after six hours (danger and shots after a day). Marks are stored on the player's PC as the `marks` document (kept whenever made, deleted by "Forget my data", included in the export) and can be shared as an `SLMARKS1:` code.

## Heights

`scripts/build-dayz-heights.mjs` builds `heights.<map>.json` from the heights of every building in `mapgrouppos.xml` and every object in `mapgroupcluster*.xml` of the Central Economy (ADPL-SA): about 230,000 points on Chernarus and Livonia, 75,000 on Sakhal, averaged into 128 × 128 squares, empty squares filled from the nearest, the open sea at 0 m. Checked on one point in twenty left out of the build: median error 3.2 m (Chernarus), 2.2 m (Livonia), 3.4 m (Sakhal); far from any building or tree it is rougher.

## Crafting and vehicles

`scripts/build-dayz-crafting.mjs` builds `crafting.json` from Bohemia's game scripts (DayZ-Script-Diff, DayZ Public License: attribution, non-commercial, DayZ only): the 143 recipes `pluginrecipesmanagerbase.c` registers (ingredients, quantities, what is kept or worn, conditions) and what each vehicle needs to start. Fuel tank sizes and the radiator's 5.85 L are from the DayZ Wiki (CC BY-NC-SA, read 2026-11-15); the Gunter 2 and motorbike tanks are unknown. Recipe names are written for this pack (the scripts' names are string-table keys).

## Base building

`building.json` (written 2026-11-15 for game version 1.30): kit recipes, lock rules, dismantling and flag-pole timings from the scripts; lifetimes from the Central Economy; per-stage material counts from the DayZ Wiki. The wiki's watchtower page contradicts itself on panel and roof counts; answers say to check the in-game prompt. Explosive damage to walls is in the game config, so no numbers are given for it.
