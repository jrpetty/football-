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

## Before any commercial use

Both licences are **non-commercial**. Replace this data, or get permission from the rights holders, before the app is sold or used commercially.
