# Release checklist: Steam Early Access

Target: **Steam Early Access**. The brief's roadmap ends "Next Fest demo, then Early Access". Early Access suits a sim that keeps growing after launch (site visits and more content come later). Everything below must be done, or decided, before pressing Release.

Status key:
- ✅ done and verified
- 🔧 in progress
- ⬜ to do
- 👤 needs Jon (only you can do it: accounts, money, art direction, real hardware)

## 1. Ship blockers

| | Item | Owner | Notes |
|---|---|---|---|
| ✅ | Saves can't be lost or corrupted (temp+bak writes, newest-save Continue, archive on new game, save on close, version check) | Claude | `tests/unit/test_release_fixes.gd` |
| ✅ | Paid deliveries can't vanish on save/quit/move | Claude | `GameState.in_transit` |
| ✅ | No soft-locks: every repair part buyable, debt can't block shipping, office PC can't be stored | Claude | |
| ✅ | Host-only company decisions in public lobbies | Claude | `Sim.HOST_ONLY` |
| ✅ | Saves and join snapshots stay bounded | Claude | order and "gone" item pruning |
| ✅ | Export preset includes data/*.json, excludes tools/tests | Claude | `export_presets.cfg` |
| 🔧 | A real exported build boots and plays (test export with the Godot 4.7.2 templates) | Claude | |
| 🔧 | Steam app id in one place, easy to switch from 480 to the real id | Claude | |
| 👤 | Steamworks: create the app ($100 fee), get the app id, register the 91 achievements (`ACH_<ID>`, list in `docs/STEAM.md`) and leaderboards | Jon | |
| 👤 | Two-account Steam co-op test (you plus a friend, or two PCs) | Jon | steps in `docs/STEAM.md` |
| 🔧 | No dev text in release builds ("early development build", build stamp, debug cheats) | Claude | debug builds keep them |
| 🔧 | Product names clearly fictional (model and CPU numbers mirror real products) | Claude | trademark caution |
| 🔧 | Credits and licences screen (Godot MIT, GodotSteam, Steamworks, asset credits) | Claude | required by the licences |

## 2. What players expect on Steam

| | Item | Owner | Notes |
|---|---|---|---|
| 🔧 | Key rebinding screen | Claude | Settings → Controls |
| 🔧 | Gamepad support (Steam Deck) | Claude | move/look/interact/menus |
| ⬜ | Music | 👤 Jon / Claude | Jon picks the style; the music slider currently does nothing |
| ✅ | Settings saved; graphics presets; FPS overlay (F3) | upstream | |
| ✅ | F1 hide HUD, F12 screenshot | Claude | |
| 👤 | Performance check on real GPUs (min-spec and mid-range PC) | Jon | `tools/benchmark.tscn` |

## 3. Gameplay the brief promises (Early Access scope)

| | Feature (brief §) | Owner | Notes |
|---|---|---|---|
| ✅ | Core loop, laptops, desktops, servers, monitors, phones, network kit (§3, §13) | upstream | |
| ✅ | Staff (§16), automation (§18), 7 premises (§19), forklift and pallet truck (§6) | upstream | |
| ✅ | Business buyers, custom server builds (§12) | upstream | |
| 🔧 | Random events: power cuts, courier strikes, dud batches, surprise mega-lots, roof leaks (§20) | Claude | |
| 🔧 | Rival refurbishers buying lots off the board (§5, §20) | Claude | |
| 🔧 | Mess and cleaning: cardboard, foam, dust; broom; cleaner staff (§17) | Claude | |
| ⬜ | Refurb Inc. website sales channel, unlocked by reputation (§12) | Claude | |
| ⬜ | Strip-down station: dead units → donor parts (§9) | Claude | |
| ⬜ | Spares units can be wiped (data-wipe realism, §10) | Claude | |
| ⬜ | Power capacity enforced as a soft limit (§15) | Claude | |
| — | Site visits (§5) | later | brief says not in the base version |

## 4. Store page and launch (Jon)

| | Item | Owner |
|---|---|---|
| 👤 | Capsule art, screenshots, trailer (Higgsfield credits help here) | Jon |
| 👤 | Store description, tags, price (~£12.99), Early Access FAQ | Jon (Claude can draft) |
| 👤 | Final art for props that are still procedural boxes (stations, vehicles) | Jon |
| 👤 | Age rating questionnaire, tax/bank details in Steamworks | Jon |
