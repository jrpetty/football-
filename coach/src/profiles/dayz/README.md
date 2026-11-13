# DayZ map data

Built by `scripts/build-dayz-data.mjs` from two sources, kept in separate files because their licences differ.

## places.<map>.json — named places

From the **DayZ Wiki** (https://dayz.fandom.com), the location pages and the interactive maps, read through the wiki's API on 2026-11-13. Licence: **CC BY-NC-SA 3.0** (https://creativecommons.org/licenses/by-nc-sa/3.0/): attribution, non-commercial, share alike. This adaptation (names, Cyrillic or Polish names, types, loot tiers, facilities, positions) is shared under the same licence.

Each position was checked against the game's own building list (below): a settlement must have buildings at its grid reference. Where the grid fits only counted from the other edge it was corrected; places with no position, or a position with no buildings, or one copied from another place, are left out and listed in each file's `notes`.

## world.<map>.json — the game's features

From **Bohemia Interactive's DayZ Central Economy** files (https://github.com/BohemiaInteractive/DayZ-Central-Economy): water pumps, fuel stations, medical, police, fire and military buildings, lighthouses, helicopter crash, police car and convoy spawn points, contaminated zones, player spawn points, and the lootable buildings by type near each place. Licence: **Arma and DayZ Public License Share Alike (ADPL-SA)** (https://www.bohemia.net/community/licenses/arma-and-dayz-public-license-share-alike-adpl-sa): attribution, non-commercial, DayZ/Arma only, share alike. © Bohemia Interactive a.s. DAYZ® is a registered trademark of Bohemia Interactive a.s.

## Before any commercial use

Both licences are **non-commercial**. Replace this data, or get permission from the rights holders, before the app is sold or used commercially.
