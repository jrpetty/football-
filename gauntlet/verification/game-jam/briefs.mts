/**
 * The Game Jam (creative.game-jam): the five game design briefs.
 *
 * `node verification/game-jam/briefs.mts` writes tests/creative/game-jam.json from these briefs. Every round
 * shares the same creative brief, technical rules, test description and delivery rules, word for word, so all
 * five genres are judged on the same terms. The "How your game will be tested" paragraph quotes the playtest
 * script's own description (src/scoring/playtest.ts), so the prompt can never drift from what is really run.
 *
 * Changing any text here changes what models see: bump the test version.
 */
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { GENRES } from '../../src/scoring/playtest.ts';
import { GAME_JAM_PROTOCOL } from '../../src/scoring/game-jam-shared.ts';
import type { JamGenre } from '../../src/scoring/game-jam-shared.ts';

const ROOT = join(import.meta.dirname, '..', '..');

// ───────────────────────────── Shared sections ─────────────────────────────

const INTRO = (n: number, title: string) =>
  `# THE GAME JAM · Round ${n} of 5: ${title}

You are one of several AI developers entering a game jam: five rounds, five genres, one prompt each, and no second attempt. You write the complete game in a single reply. Everyone gets exactly this brief.`;

const CREATIVE = `## Your creative brief (this is where you win)
The art direction, theme, setting, story, twist and extra mechanics are yours: surprise us. The numbered requirements below are the skeleton every entry shares; your identity is what makes your game the one people remember. Visual quality and creativity are the two largest parts of the judges' score, so a faithful but plain-looking clone will lose to an equally complete game that looks stunning and has a memorable idea. A twist must never cost a requirement: deliver both.`;

const VISUAL_BAR = `## The visual bar (the largest part of the score, with creativity)
The target is a game that could pass for a screenshot of a polished commercial indie game on Steam. Flat rectangles on a plain background score at the bottom of the scale, however well they play. Deliver all of these:
V1. Art direction first: open the file with a short comment stating your art direction, mood and colour palette (5-8 named colours), then follow it everywhere, so backgrounds, characters, UI and effects belong to one visual world.
V2. Crisp at any size: render at devicePixelRatio (backing store = CSS size × DPR, capped at 2), fill the whole window at 1920×1080 and resize gracefully, with no blurry upscaling.
V3. Depth: several parallax layers (or real 3D), atmospheric perspective and a sense of place.
V4. Light: dynamic lighting and shadows, such as light maps, glow and bloom (offscreen canvases with blend modes like "lighter" or "screen", or shaders), and colour grading that changes with time of day or mood.
V5. Particles: real particle systems with hundreds of particles (sparks, smoke, dust, rain, debris, and blood or ichor where the genre fits), pooled so they cost nothing.
V6. Living characters: every character and creature is drawn procedurally with several animation frames or procedural animation (walk cycles, wing beats, idle breathing) and squash and stretch; never a static rectangle or circle.
V7. Camera: smooth follow with look-ahead, shake on impact, and zoom where it helps.
V8. Post effects: at least a vignette and a colour grade; CRT, scanlines, chromatic aberration or film grain where they suit the style.
V9. UI: a polished HUD and menus with well-styled type, panels and icons drawn in code, animated transitions, and a title screen that looks like a real game's title screen (logo treatment, animated background, clear call to action).
V10. Juice everywhere: hit-stop, flashes, screen shake, easing on every movement and UI change, satisfying feedback for every action.
V11. Performance: a steady 60 fps at 1920×1080 with all of the above.
WebGL and WebGL2 are allowed and encouraged: hand-written shaders, a real 3D scene, lit 2.5D, post-processing passes. Everything must still live in the one file, with no libraries or downloads; you may write and inline your own engine code.`;

const TECH = `## Technical rules (all mandatory)
T1. One self-contained HTML file with all HTML, CSS and JavaScript inline. No external resources of any kind: no libraries, CDNs, web fonts, image or audio files, and no network requests. Draw every graphic in code (Canvas 2D, WebGL/WebGL2 with your own shaders, or inline SVG) and synthesise every sound with the Web Audio API.
T2. There is no size limit and no artificial length limit: use as much of your reply as the game needs (the file is only checked against a 20 MB safety net for runaway output).
T3. Animate with requestAnimationFrame and a frame-rate-independent update (delta time, clamped so a stalled tab cannot teleport anything), holding 60 fps.
T4. Render at devicePixelRatio and fill the browser window; the game is judged at 1920×1080 and must stay fully playable down to 1280×720, handling resizes, with nothing important cut off. Phones get the touch controls described above.
T5. The game opens on a title screen. Pressing Enter there must start a new game with default settings. A click, a tap or Space should start it too; if the title screen offers choices (difficulty, seed, options), they may be picked first by mouse or keys, but Enter always starts immediately with the defaults.
T6. Create or resume the AudioContext on the first key press, click or tap (browsers block sound before that). M toggles mute, with an on-screen indicator.
T7. The game may run in a sandboxed frame where localStorage is missing or throws: wrap every storage access in try/catch and keep the game fully working (just without saving) when it fails.
T8. No alert, confirm or prompt dialogs, no pointer lock, no fullscreen requests, and no console errors.
T9. Organise the code in clear sections with named constants for every tuning value. Comments are welcome, but a feature that exists only in a comment or a menu label earns nothing.`;

const TESTED = (inputs: string) => `## How your game will be tested
1. It is opened in a desktop browser at 1920×1080 and played automatically for 30 seconds: ${inputs}. Eight full-HD screenshots (the title screen, then moments in the middle of the action) and a motion strip of six frames 0.1 s apart are taken, and compared with an untouched copy. The scripted player is not skilled: it only proves that the game starts, draws, keeps running, reacts to its controls and throws no errors.
2. A panel of AI judges from other companies then reads your entire file, studies the screenshots, marks every numbered requirement PASS, PARTIAL or FAIL, and scores: visual quality and art direction (the largest share), creativity and originality (the next largest), does it actually play, game feel and juice (including audio), and ambition and depth.
3. People will also play it, on camera, in full screen.`;

const DELIVERY = `## Delivery
Respond with a single \`\`\`html code block containing the complete file, and nothing else: no explanation before or after it. There is no artificial length limit: you may use your model's entire output allowance. Only your model's own maximum applies, and a file that is cut off cannot run, so plan a scope you can finish: a complete, gorgeous game beats an unfinished bigger one.`;

const VISUAL_DONE = 'It meets the whole visual bar (V1-V11): one clear art direction, crisp full-HD rendering, depth, light, particles, animated characters, camera, post effects, polished UI and juice. A screenshot could pass for a commercial indie game.';

interface Brief {
  id: string;
  genre: JamGenre;
  title: string;
  /** Everything between the intro and the creative brief: the pitch. */
  pitch: string;
  /** This round's art direction targets (on top of the shared visual bar). */
  art: string;
  controls: string;
  screens: string;
  /** Numbered requirements, in order, each with the short label shown in the UI and to the judges. */
  requirements: Array<[label: string, text: string]>;
  feel: string;
  done: string[];
  /** Honest per-case output estimate (tokens, including reasoning), and the reasoning behind it. */
  estimate: number;
  notes: string;
}

// ───────────────────────────── Round 1: Flappy ─────────────────────────────

const FLAPPY: Brief = {
  id: 'j1-flappy',
  genre: 'flappy',
  title: 'The Flappy Remake',
  pitch: `## The pitch
Rebuild **Flappy Bird**, the one-button classic, as a faithful, juicy remake. A small hero flaps through an endless course of gaps: one input, instant restarts, a score you want to beat. The original's genius was feel: a precise arc, fair gaps and a death that makes you tap "retry" at once. Match that feel exactly, then add the juice and personality the original never had.`,
  art: `## Art direction for this round
Make it look like a premium mobile hit remastered for Steam. The hero is a character, not a sprite: several wing-beat frames, blinking, a squash on every flap, a trail. The world is a painted scene in several parallax layers (distant silhouettes, mid-ground detail, a textured foreground), with light that changes through the day/night cycle: warm rim light by day, glowing windows, lanterns or stars at night, bloom on every light source. Obstacles have material, texture and shading, not flat pipes. Weather or ambient particles drift through the scene, and the death is a proper cinematic moment. The medal panel and title screen should look designed, with a logo treatment and animated background.`,
  controls: `## Controls
- Flap: Space, Arrow Up, W, a left mouse click, or a tap anywhere.
- Start and restart: Enter, Space, a click or a tap (restart only once the game-over panel has appeared).
- Pause: P or Escape. Mute: M.`,
  screens: `## Screens and flow
1. Title: the game's name or logo, the hero idling (bobbing and animated), the best score, the controls and "Press Enter, Space or tap to play".
2. Get Ready: the hero hovers in place with a flap hint until the first flap; obstacles do not move yet.
3. Playing: the score, large, at the top centre.
4. Game over: a panel slides in with the score, the best score, the medal, a "NEW" badge and the restart hint.
5. Pause: an overlay that freezes everything.`,
  requirements: [
    ['Flight physics: gravity, fixed flap impulse, tilt', 'Flight physics: constant gravity; each flap SETS the vertical velocity to a fixed upward impulse (it does not add to it); a terminal fall speed; the hero tilts with its velocity (nose up after a flap, diving when falling fast). All tuning values are named constants.'],
    ['Obstacles with random, always-reachable gaps', 'Obstacles: pairs of pipes (or your themed equivalent) with an opening, spawned off the right edge at an even spacing and scrolling left at a constant speed. The opening height is random within safe bounds, and two consecutive openings never differ by more than the hero can climb or fall in the time between them.'],
    ['Scoring +1 per gap with sound and pop', 'Scoring: +1 each time the hero passes the middle of an obstacle pair, with a sound and a pop or scale animation on the score.'],
    ['Best score kept (storage when available)', 'Best score: kept across restarts and, when storage is available, across reloads (see T7).'],
    ['Medals at 10/20/30/40 + NEW badge', 'Medals: shown on the game-over panel at 10 (bronze), 20 (silver), 30 (gold) and 40 (platinum) points, each drawn in code, plus a "NEW" badge when the best score is beaten.'],
    ['Fair collisions: obstacles, ground, no escaping over the top', 'Collisions: touching an obstacle or the ground ends the run, and the hero cannot escape over the obstacles off the top of the screen. Hitboxes are slightly forgiving (smaller than the drawn sprite).'],
    ['Death sequence: flash, shake, tumble, delayed panel', 'Death sequence: a white flash, screen shake, the hero tumbles to the ground, and the game-over panel slides in about half a second later. Input is ignored for about 0.4 s so a frantic tap cannot skip it.'],
    ['3+ parallax layers', 'Parallax: at least three background layers scrolling at different speeds (for example a distant skyline or mountains, near hills or trees, and the ground strip), plus drifting clouds or an equivalent.'],
    ['Day/night cycle', 'Day/night cycle: the palette changes smoothly over time or with the score (sky gradient, layer tints, a sun or moon, stars at night); at least two distinct times of day appear in a normal run.'],
    ['Particles: flap, death, ambient', 'Particles: a burst of feathers (or your equivalent) on every flap, a bigger burst on death, and at least one ambient particle effect (dust, leaves, fireflies, rain, embers…).'],
    ['Fair difficulty ramp with clamped limits', 'Difficulty ramp: speed and/or opening size tighten gradually with the score, clamped to fair limits defined as constants (for example, the opening never gets smaller than three times the hero\'s height).'],
    ['All five screens with transitions', 'Screens: the title, Get Ready, playing HUD, game-over panel and pause overlay described under "Screens and flow", with smooth transitions between them.'],
    ['Synthesised sounds + mute', 'Sound: synthesised effects for the flap, scoring, a hit, the fall and a medal or new best, plus the mute toggle (M) with an on-screen indicator.'],
    ['Three extra juice touches', 'Extra juice: at least three more "feel" touches of your choice, such as squash and stretch, slow motion on death, a motion trail, the score counting up on the panel, or milestone celebrations.'],
  ],
  feel: `## Feel targets
- One flap lifts the hero about one and a half to two of its own heights, and the arc peaks after roughly a third of a second.
- A new player survives a few gaps on their first try and immediately wants another go.
- A run restarts within a second of pressing the key on the game-over panel.
- A steady 60 fps; nothing jumps when the window is resized.`,
  done: [
    'Title → Get Ready → play → game over → restart works with the keyboard, the mouse and touch.',
    'The physics feel crisp and fair, and every gap is passable.',
    'Score, best score, medals and the NEW badge all work.',
    'Parallax, day/night, particles, the flash and the shake are visible in normal play.',
    'Difficulty ramps within the stated limits.',
    'Every sound plays after the first input, and M mutes.',
    'It works without storage, logs no console errors and holds 60 fps at 1920×1080.',
    'It has an identity of its own: a theme, an art direction and at least one twist.',
  ],
  estimate: 20000,
  notes:
    '[extreme] Round 1 of The Game Jam: Flappy Bird remake. The smallest round: expect 8-20k tokens of code plus reasoning (estimate 20k). Checks: the numbered requirements R1-R14 (judge checklist), the technical rules T1-T9, and the genre playtest (flap presses every 0.38 s). Scored 25% by automatic browser checks and 75% by the cross-vendor judge panel (visual quality and creativity weigh most).',
};

// ───────────────────────────── Round 2: RTS ─────────────────────────────

const RTS: Brief = {
  id: 'j2-rts',
  genre: 'rts',
  title: 'Command & Construct',
  pitch: `## The pitch
Build a small but complete **real-time strategy game in the spirit of Command & Conquer**. Harvest a resource to fund a base, grow that base on a grid, raise an army whose units counter one another, and destroy an AI opponent that plays by exactly the same rules. Everything a classic RTS needs, the economy, the build sidebar, fog of war, the minimap, pathfinding and a real enemy commander, in one HTML file.`,
  art: `## Art direction for this round
Aim for the look of a modern indie RTS. Terrain is lit and textured, with blended tile edges, height shading, water that moves and ore fields that sparkle. Buildings are detailed, cast shadows, animate (spinning radar, smoking stacks, blinking lights) and play a construction animation when placed. Units have readable silhouettes and animate: infantry walk cycles, tank turrets that rotate to aim, tracks that leave marks, harvesters that visibly fill and unload. Combat is spectacular: muzzle flashes that light the ground, tracers, explosions with shockwaves, smoke plumes and scorch decals that stay. The fog of war has soft edges, and the sidebar and minimap look like a real command interface with bevelled panels and animated build progress.`,
  controls: `## Controls
- Left click: select a unit or building. Drag: box-select with a visible rectangle. Shift + click or drag: add to the selection. Double-click: select every visible unit of that type.
- Right click: the context order: move on open ground, attack on an enemy, harvest on ore (harvesters), or set the rally point when a production building is selected.
- A, then left click: attack-move. S: stop. Ctrl+1…9: set a control group; 1…9: recall it.
- Camera: the arrow keys, the mouse at the screen edge, and clicking or dragging on the minimap.
- Sidebar: left click starts or queues a build, right click cancels it with a refund; a finished structure is placed with a left click (right click or Escape cancels placement).
- Pause: P or Escape. Mute: M.
- Touch: tap to select, tap and drag for a box, and a sidebar button for the context order.`,
  screens: `## Screens and flow
1. Title: the name, your faction or setting, a difficulty choice (Easy, Normal, Hard; Enter starts on Normal) and a controls summary.
2. Match: the map view, a sidebar on the right (credits, power, build tabs, the minimap on top) and notifications.
3. Pause menu: resume, restart and controls.
4. End screen: victory or defeat with the match statistics and "play again".`,
  requirements: [
    ['Scrolling map, terrain, 4+ ore fields, camera', 'Map and camera: a scrolling tile map at least three screens wide and three screens high, with impassable terrain (cliffs, water, rocks or forest) that creates chokepoints, and at least four ore fields (one near each base, the rest contested). The camera scrolls with the arrow keys, the screen edges and the minimap.'],
    ['Harvester economy: ore → refinery → credits', 'Harvester economy: harvesters drive to ore, fill up over a few seconds, return to the nearest refinery and unload into credits, with the cargo and the unloading visible. Ore fields deplete (and may slowly regrow). Credits are shown in the sidebar and tick up and down as they change.'],
    ['6+ structures, grid placement with green/red ghost', 'Base building on a grid: at least six structure types, namely a construction yard, a power plant, a refinery (which comes with a free harvester), barracks, a war factory and a defensive turret, each with its own cost, build time, power value, HP and footprint. Placement shows a ghost footprint that is green where valid and red where not (clear ground, near your existing buildings).'],
    ['Power supply vs demand affects production', 'Power: power plants supply it and every other building consumes it, and a power bar shows supply against demand. In low power, production slows (for example to half speed) and turrets fire slower or stop, with a warning.'],
    ['Build sidebar: queues, progress, cancel, tech tree', 'Build sidebar: structure and unit tabs with icons drawn in code, costs and hotkey hints; a queue per production building (at least five queued units) with progress shown as a bar or clock-wipe; right click cancels with a refund; items stay greyed out until their prerequisites exist (a small tech tree, e.g. the war factory needs a refinery).'],
    ['3+ combat units with rock-paper-scissors counters', 'Units: a harvester plus at least three combat unit types with rock-paper-scissors counters, implemented through a weapon-versus-armour damage table (for example rifle infantry beats rocket infantry, rocket infantry beats tanks, tanks beat rifle infantry). Each unit\'s counter is shown in a tooltip or help panel.'],
    ['Selection: click, box, shift, groups; move/attack orders', 'Selection and orders: click, box and shift selection, control groups, and the move, attack, attack-move and harvest orders listed under Controls. Selected units show selection markers and health bars, and every order shows a marker and gets an acknowledgement sound.'],
    ['A*/flow-field pathfinding, formations, steering', 'Pathfinding: A* (or a flow field) on the tile grid around terrain and buildings. Groups spread into a loose formation instead of stacking on one tile, and units steer around each other and re-plan when blocked.'],
    ['Combat: targeting, projectiles, explosions, damage states', 'Combat: units and turrets acquire targets in range automatically, fire visible projectiles or tracers with muzzle flashes, and explode or fall when destroyed. Damaged buildings smoke and burn; destroyed ones leave rubble.'],
    ['Fog of war: shroud + fog', 'Fog of war: a black shroud over unexplored ground and a dimmed fog over ground explored but not currently seen. Enemy units are hidden in the fog and enemy buildings show their last-known state; every unit and building has a sight radius.'],
    ['Minimap with fog, units, camera box, click to jump', 'Minimap: terrain, ore, fog, your units and visible enemies as coloured dots, and the camera rectangle. Clicking it moves the camera. (Optionally, it goes dark in low power, as radar did in the classics.)'],
    ['Enemy AI builds, harvests and attacks in waves', 'Enemy AI: a real opponent bound by the same rules and costs. It builds its base in a sensible order, harvests with its own harvesters (and replaces lost ones), trains a mixed army that respects the counters, defends its base, and attacks in escalating waves, the first after roughly three to four minutes on Normal. Difficulty changes its economy and aggression, never the rules.'],
    ['Win/lose + end-screen statistics', 'Winning and losing: destroy every enemy structure to win; you lose when you have no structures left. The end screen shows victory or defeat, the match time, units built and lost, and credits harvested, with "play again".'],
    ['Notifications and sound feedback', 'Feedback: notifications for key events ("Construction complete", "Unit ready", "Insufficient funds", "Low power", and "Base under attack" with a ping on the minimap), plus synthesised sounds for orders, gunfire and explosions.'],
    ['Pause menu and clean restart', 'A pause menu, and a restart that fully resets the match.'],
  ],
  feel: `## Feel targets
- A new player understands the loop within a minute: build power, then a refinery, and watch the credits roll in.
- Orders feel immediate: units respond the moment you right-click.
- Battles read at a glance: who is winning, what is dying, and where.
- A steady 60 fps with 60 or more units on the map.`,
  done: [
    'Title → match → victory or defeat → play again works.',
    'Every structure and unit type can be built, and the economy pays for it.',
    'A power shortage visibly slows production.',
    'Counters matter: the right unit wins the fight.',
    'Units path around terrain and around each other.',
    'The fog of war and the minimap agree.',
    'The AI builds, harvests and attacks on its own, and beats a passive player.',
    'It logs no console errors, works without storage and holds 60 fps at 1920×1080.',
    'It has an identity of its own: a setting, factions, an art style or a twist.',
  ],
  estimate: 50000,
  notes:
    '[extreme] Round 2 of The Game Jam: Command & Conquer-style RTS. One of the two largest rounds: a credible entry is 30-50k tokens of code plus reasoning, (estimate 50k; each model may use its full output allowance). Checks: R1-R15 (judge checklist), T1-T9, and the genre playtest (box-select, right-click orders, sidebar clicks, arrow/edge scrolling).',
};

// ───────────────────────────── Round 3: RPG ─────────────────────────────

const RPG: Brief = {
  id: 'j3-rpg',
  genre: 'rpg',
  title: 'The Little Legend',
  pitch: `## The pitch
Build a small **top-down action RPG** with the feel of classic Zelda and early Pokémon: a village to start in, a wilderness to explore, a dungeon to conquer and a boss at the bottom of it. Talk to people, take on a quest, fight with a sword, grow stronger, gear up, save your progress, and see it through to an ending.`,
  art: `## Art direction for this round
Aim for a lovingly crafted pixel-art or painterly RPG. Tiles are detailed and autotiled (grass meets path, water meets shore), with animated water, swaying grass, flickering torches and drifting leaves. Characters have four-direction walk cycles, idle animations and an attack animation with a motion smear. The dungeon is dark and lit by torches and spells through a light map, with shadows. Dialogue boxes have portraits drawn in code, typewriter text with a subtle bounce, and a designed frame. The boss is big, animated, and telegraphs its attacks with clear visual effects. Area transitions, level-ups and item pickups each get their own visual moment.`,
  controls: `## Controls
- Move: the arrow keys or WASD (four or eight directions, your choice).
- Attack: Space, J or Z. A secondary item or skill, if you add one: K or X.
- Talk, interact, pick up, advance dialogue: E or Enter (Space also advances dialogue).
- Inventory and equipment: I or Tab. Pause menu: Escape or P. Mute: M.
- Mouse: click menu items and inventory slots. Touch: an on-screen d-pad plus A (attack) and B (interact) buttons.`,
  screens: `## Screens and flow
1. Title: the name, "New game", "Continue" (only when a save exists) and the controls.
2. A short intro that sets up the quest (skippable with Enter or Space).
3. Playing: the world view with the HUD.
4. The dialogue box, the inventory and equipment screen, the pause menu with the quest log, and the shop.
5. A game-over screen (retry from the last save or the start of the area) and an ending screen once the quest is done.`,
  requirements: [
    ['3+ areas (village, forest, dungeon) with transitions', 'World: a tile-based map drawn in code with at least three areas, a village (houses, NPCs, a shop), a wilderness or forest with enemies, and a dungeon of at least two rooms plus a boss room, joined by transitions (Zelda-style screen scrolls, doors or paths) with a fade or slide. Walls, water, trees and furniture are solid.'],
    ['Smooth camera + area names', 'Camera: it follows the player smoothly and stays inside each area\'s bounds (or flips room by room), and each area shows its name when you enter it.'],
    ['4+ NPCs with typewriter dialogue that tracks the quest', 'NPCs: at least four characters with idle animations and multi-line dialogue in a dialogue box with the speaker\'s name and typewriter text. What they say changes as the quest progresses.'],
    ['Multi-stage quest with a quest log', 'Quest: a main quest with at least three stages (for example: talk to the quest-giver, fetch something from the forest, open the dungeon, defeat the boss, return for the reward and the ending), with a quest log showing the current objective.'],
    ['Inventory (6+ item kinds) and equipment slots', 'Inventory and equipment: an inventory screen with at least six kinds of item (consumables, key items, equipment); weapon and armour slots whose items change your stats; potions heal; picking something up shows a message.'],
    ['HP/XP/levels with stat growth and damage numbers', 'Stats and levelling: HP, attack, defence, XP and level. Defeating enemies gives XP; levelling up raises stats with a visible and audible celebration; damage uses attack against defence, and damage numbers pop out of whatever is hit.'],
    ['Melee: swing arc, knockback, hit-stop, i-frames', 'Melee combat: a directional attack with a visible swing arc and a short-lived hitbox, knockback on every hit (for enemies and the player), a brief hit-stop, and invincibility frames (with flashing) after the player is hit.'],
    ['3+ enemy types with distinct AI', 'Enemies: at least three types with clearly different AI, for example a wanderer that charges when it sees you, a ranged shooter that keeps its distance and fires projectiles, and an ambusher or patroller. Each has its own look, HP, damage and drops (hearts, coins, XP).'],
    ['Boss: telegraphed patterns + second phase', 'Boss: a large boss in its own arena with a health bar, at least three distinct attacks with clear wind-up telegraphs, a second phase below 50% HP (faster or new attacks), and a victory sequence.'],
    ['Coins and a working shop', 'Economy: coins from enemies, pots or chests, and a village shop selling at least two useful items or upgrades.'],
    ['Save/Continue via localStorage (safe without it)', 'Saving: save the position, area, stats, inventory and quest state to localStorage (at a save point or from the pause menu), with "Continue" on the title screen. Every storage access is wrapped in try/catch, and when storage is unavailable the game says so and keeps working.'],
    ['Pause menu', 'Pause menu: resume, inventory and equipment, the quest log, save, controls, and quit to the title.'],
    ['HUD: HP, XP, level, coins, weapon, objective', 'HUD: hearts or an HP bar, the XP bar and level, coins, the equipped weapon and the current objective.'],
    ['Game over with retry + ending screen', 'Death and ending: a game-over screen with retry, and an ending screen after the quest is complete.'],
    ['Synthesised SFX + area music + mute', 'Audio: synthesised effects (swing, hit, hurt, pickup, level up, door, dialogue blips) and a simple sequenced melody for each area, with mute.'],
  ],
  feel: `## Feel targets
- Movement is snappy, with no sliding, and combat reads: you always know when you hit, when you were hit and when you are invincible.
- The world feels hand-made and alive: animated water, swaying grass, NPCs with personality.
- A full playthrough of the quest takes five to fifteen minutes.`,
  done: [
    'New game → quest → boss → ending works from start to finish, and death → retry works.',
    'All three areas, their transitions and their collisions work.',
    'Dialogue, the quest log, inventory, equipment, the shop and levelling all work together.',
    'The three enemy AIs and the boss\'s patterns are distinct and fair.',
    'Save and Continue work where storage exists, and the game still runs where it does not.',
    'It logs no console errors and holds 60 fps at 1920×1080.',
    'It is an original world: a setting, characters, a story and at least one mechanic of your own.',
  ],
  estimate: 48000,
  notes:
    '[extreme] Round 3 of The Game Jam: top-down action RPG. A credible entry is 30-50k tokens of code plus reasoning (estimate 48k). Checks: R1-R15 (judge checklist), T1-T9, and the genre playtest (arrows/WASD walking in all directions with Space/J/Z attacks and E/Enter). Storage in the playtest page throws, as in the sandboxed "Play it" frame, which R11 and T7 require models to handle.',
};

// ───────────────────────────── Round 4: Zombies ─────────────────────────────

const ZOMBIE: Brief = {
  id: 'j4-zombie',
  genre: 'zombie',
  title: 'The Long Night (the hardest round)',
  pitch: `## The pitch
This is the ambitious one. Build a **top-down (or isometric) zombie survival game** in a procedurally generated town overrun by the dead. Scavenge by day, craft what you need, fortify a safehouse, rescue survivors, and live through nights that keep getting worse, until the evacuation helicopter comes. The systems must feed each other: noise draws the hordes, light keeps you alive but gives you away, and the base buys you time but never wins on its own.`,
  art: `## Art direction for this round
This round is about atmosphere. Night must be genuinely dark, lit by a volumetric flashlight cone, muzzle flashes, fires and floodlights, with real shadow casting from walls and buildings. Add rain or fog, puddles, flickering street lights and a colour grade that shifts from dusty daylight to cold blue night and a red emergency tint at low health. Zombies shamble with distinct silhouettes and animations per type; hits spray ichor that stays as decals; fire spreads light and smoke. Barricades show cracks and splinters as they take damage. The HUD should look like a survival game's: worn panels, clear icons, a readable materials count and a clock that feels threatening at dusk.`,
  controls: `## Controls
- Move: WASD (or the arrow keys). Sprint: Shift. Aim: the mouse (the character faces the cursor).
- Attack or shoot: left click. Reload: R. Throw (a molotov, a decoy…): Q or right click.
- Interact, search, pick up, and (hold) repair: E. Quick slots: 1-5. Inventory: Tab or I. Crafting: C.
- Build mode: B (left click places, R rotates while building, right click or Escape cancels).
- Flashlight on or off: F. Survivors follow or hold position: G.
- Pause: Escape or P. Mute: M.
- Touch: the left thumb moves and the right thumb aims and fires (twin-stick), with on-screen buttons for the rest.`,
  screens: `## Screens and flow
1. Title: the name, the town seed (shown, and editable or re-rollable; Enter starts with a random seed), a difficulty choice and the controls.
2. Playing: the world with a HUD (health, hunger, thirst, stamina, ammo, quick slots, materials, the clock and day count, and the objective).
3. Overlays: inventory, crafting, build mode and pause.
4. Death or victory: the summary screen (requirement 18) with restart on the same seed or a new one.`,
  requirements: [
    ['Procedural, seeded town with 12+ enterable buildings', 'Procedural town: every seed generates a different town, with roads, blocks and at least 12 enterable buildings of several types (houses, a pharmacy, a hardware store, a gas station, a police station, a supermarket…) with walls, doors and windows, plus props (cars, fences, trees, debris). Roofs hide interiors until you step inside. The same seed always makes the same town.'],
    ['Day/night with flashlight cone and wall-blocked light', 'Day and night: a clock in the HUD; a day lasts a few real minutes, with dusk and dawn transitions. At night the world goes dark except for a mouse-aimed flashlight cone and other light sources (fires, floodlights), drawn as a lighting and visibility mask in which walls block both light and sight (ray casting or shadow casting).'],
    ['Night is deadlier: dusk horde, stronger zombies', 'Night is deadlier: at dusk a horde arrives from the edges of the map, and at night zombies are more numerous, faster and more aggressive.'],
    ['4 zombie types: walker, runner, brute, spitter', 'Zombie types: at least four, namely a walker (slow and common), a runner (fast and fragile), a brute (slow, very tough, smashes barricades and knocks you back) and a spitter (ranged acid that leaves a damaging pool), each with a distinct look and sound.'],
    ['Zombie AI: sound-chasing pathfinding + flocking', 'Zombie AI: a state machine (wander → investigate a sound → chase on sight → attack) with pathfinding (A* or a flow field) around buildings to the position of the sound they heard, and flocking with separation so hordes flow around obstacles and surround you instead of stacking. Zombies attack structures that block their path.'],
    ['Noise system: loudness drives hordes', 'Noise: every action has a loudness (gunshots very loud; breaking things loud; sprinting and generators moderate; melee and walking quiet) that creates a noise event with a radius. Noise attracts zombies, and can be shown as expanding rings.'],
    ['Hunger, thirst, stamina, health', 'Survival needs: health, hunger, thirst and stamina meters. Hunger and thirst fall over time and hurt when empty; sprinting and swinging use stamina; food, water and medicine restore them.'],
    ['Timed scavenging with building-specific loot', 'Scavenging: loot containers (cupboards, fridges, lockers, car boots…) take time to search, shown as a progress ring that can be interrupted, and their loot tables depend on the building type (a pharmacy has medicine, a hardware store has nails and scrap…).'],
    ['Limited inventory, quick slots, materials count', 'Inventory: limited slots or weight, stacking, a quick-slot bar (1-5), use and drop, and a readable count of materials (wood, scrap, nails, fuel, cloth…).'],
    ['Melee + 2 firearms with ammo, reload, noise', 'Weapons: at least one melee weapon and two firearms (for example a pistol and a shotgun or rifle) with magazines, reloading, scarce ammo, spread or recoil and a muzzle flash. Firearms are loud (requirement 6) and melee is quiet: that trade-off is the heart of the game.'],
    ['Crafting: 6+ recipes, workbench-gated', 'Crafting: a recipe screen with at least six recipes (for example a bandage, a medkit, a molotov, a barricade kit, a spike trap, a noise decoy, ammunition), each showing its ingredients, greyed out when you lack them, and taking time to craft; some recipes need a workbench (requirement 12).'],
    ['Base building: 5+ structures, preview, costs, HP, repair', 'Base building: choose a building as your safehouse; build mode (B) then shows a grid around it with a placement preview that is green where valid and red where not, plus rotation. At least five structure types, for example a wooden barricade or wall; a reinforced door (you pass, zombies must break it); a spike trap; a watchtower or lookout (extends your vision or light radius); a workbench (unlocks advanced recipes); a rain collector or garden (slowly produces water or food); a generator with a floodlight (burns fuel, lights the area, makes noise). Every structure costs scavenged materials (wood, scrap, nails, fuel) and takes build time during which you are exposed. Structures have HP, show visible damage (cracks, sparks) as zombies attack them (brutes hit hardest), can be destroyed, and can be repaired for materials (hold E).'],
    ['Base is balanced, not a win button', 'Base balance, so the base is never a win button (define these rules as named constants): materials are scarce, so you cannot wall off everything; structure HP and trap damage are limited, and traps have limited uses or must be re-armed; horde size and pressure grow with the size and noise of your base (generators and floodlights attract more); turrets, if you add them, need scarce ammo and cannot hold alone; night waves can and do breach, so you must still fight, repair and ration; and there is a build limit or an upkeep cost (fuel, decay).'],
    ['2+ rescuable survivors who follow and fight', 'Survivors: at least two rescuable survivor NPCs placed in the town (trapped, besieged…). Once rescued they follow you, fight with their own weapon and AI, can be told to follow or hold position (G) or guard the base, can be hurt and die, and each has a name and a trait.'],
    ['Clear objective with progress (nights or radio + evac)', 'Objective: a clear goal shown in the HUD with visible progress: survive a set number of nights (for example five), or find radio parts in marked buildings, repair the radio at your base and hold out until the evacuation helicopter lands (with a final siege).'],
    ['Escalating nights', 'Escalation: every night is harder than the last: more zombies, more runners, brutes and spitters, and new directions of attack.'],
    ['Atmospheric positional audio + mute', 'Atmosphere and audio: a synthesised ambient drone, zombie groans that grow louder and pan with their position, a heartbeat at low health, gunshots, breaking wood and a relief sting at dawn, with mute.'],
    ['Death/victory summary + restart', 'Death, victory and summary: on death or evacuation, a summary screen with the cause of death (or "Evacuated"), days survived, zombies killed by type, survivors rescued, structures built, items crafted and a final score, and a restart on the same seed or a new one.'],
    ['60 fps with 100+ zombies', 'Performance: 60 fps with 100 or more zombies on the map (spatial hashing, pooling, off-screen culling).'],
  ],
  feel: `## Feel targets
- The first day teaches the loop: scavenge, craft, choose a safehouse, build.
- Every gunshot feels like a decision.
- Night is tense: limited light, sounds in the dark, barricades groaning.
- Dawn is a relief, and each day is a scramble to repair and restock.
- A skilled player survives night 1 comfortably and has to fight for night 3.`,
  done: [
    'Title → day → night → dawn → … → evacuation or death → summary → restart works.',
    'The town is different for every seed and identical for the same seed.',
    'Lighting: the flashlight cone and the walls really limit what you can see at night.',
    'All four zombie types behave differently and come for your noise.',
    'Hunger, thirst, stamina, scavenging, inventory, crafting and weapons all connect.',
    'Base building: at least five buildable structure types, a build mode with a placement preview and red/green validity, structures that visibly take damage and can be destroyed, and a readable materials HUD.',
    'The base helps but never wins alone: scarce materials, limited traps, noise-driven hordes, and breaches happen.',
    'Survivors can be rescued, follow you, fight and die.',
    '60 fps at 1920×1080 with 100+ zombies; no console errors; it works without storage.',
    'A distinct identity: a setting, a tone, an art direction and a twist of your own.',
  ],
  estimate: 58000,
  notes:
    '[extreme] Round 4 of The Game Jam: zombie survival, the deliberately hardest round (19 requirements, including base building and an explicit base-balance requirement, R13, that judges must only PASS when the code enforces it). Expect the longest replies (estimate 58k typical; each model may use its full output allowance); a reply cut off at the model limit scores the automatic checks only. Playtest: WASD movement, mouse aim sweeps, left-click bursts, R and E.',
};

// ───────────────────────────── Round 5: Racing ─────────────────────────────

const RACING: Brief = {
  id: 'j5-racing',
  genre: 'racing',
  title: 'Grand Prix',
  pitch: `## The pitch
Build an **arcade racing game** with a real sense of speed. Choose one of two classic approaches and commit to it:
- A. Pseudo-3D road projection in the style of OutRun and the Mode-7 racers: road segments projected onto the screen, curves, hills, and roadside sprites scaled by distance.
- B. Top-down, with real physics: a vehicle model with grip and slip, weight transfer, drifting and skid marks.
Either way: six cars, one circuit, three laps, and a finish you remember.`,
  art: `## Art direction for this round
Consider a real 3D racer in WebGL: a lit 3D track, car models and a chase camera. If you choose pseudo-3D instead, it must still look premium: a painted sky with a setting sun, layered mountains or city skylines, detailed roadside objects scaled smoothly, and cars drawn with shading, reflections and brake lights. Either way sell the speed: motion blur or speed lines, camera shake and a widening field of view on nitro, tyre smoke on drifts, sparks on contact, lens flare or bloom on the sun and headlights. The HUD should look like a real racing game's: an animated speedometer dial, a slick position and lap display, and a designed results screen.`,
  controls: `## Controls
- Accelerate: Arrow Up or W. Brake and reverse: Arrow Down or S. Steer: Arrow Left and Right, or A and D.
- Drift or handbrake: Space. Nitro: Shift (or N).
- Pause: Escape or P. Mute: M.
- Touch: on-screen left and right steering zones, accelerate and brake pedals, and a nitro button.`,
  screens: `## Screens and flow
1. Title: the name, a preview of the track, options (1, 3 or 5 laps; difficulty) and the controls. Enter starts a 3-lap race on Normal.
2. Grid and countdown: the cars lined up on a staggered grid, then 3-2-1-GO with lights and beeps.
3. Racing: the HUD (requirement 11).
4. Pause menu: resume, restart and quit to the title.
5. Results: the finishing order and times, then race again or return to the title.`,
  requirements: [
    ['Committed style: pseudo-3D road or physics top-down', 'One committed style, rendered convincingly. For A: projected road segments with lane markings and alternating rumble strips, curves that bend the road and push the car outward, hills and crests you can see over, and a parallax sky and background that shift in corners. For B: a car model with grip against slip, weight transfer, drifting when grip is exceeded, and skid marks.'],
    ['Closed circuit: 6+ varied corners (+ hills)', 'Track: at least one complete closed circuit with a name, a start/finish line, at least six corners of varied tightness (a hairpin, fast sweepers, a chicane) and, for A, at least two hills or crests, defined as data so it is easy to extend.'],
    ['5+ kinds of procedural roadside scenery', 'Roadside scenery drawn in code: at least five kinds of object (for example trees, signs, billboards, rocks, grandstands, lamps, tyre walls) placed along the whole track and scaled with distance or perspective.'],
    ['Handling: speed, braking, off-road, racing line', 'Handling: an acceleration curve and a top speed, braking, steering that scales with speed, off-road slowdown and loss of grip, and cornering that rewards a good racing line.'],
    ['Drift and/or nitro with feedback', 'Drift and nitro (ideally both, at least one): drifting builds a boost meter, or nitro has limited charges refilled by clean driving, with strong visual feedback (flames, speed lines, a camera stretch) and sound.'],
    ['5+ AI rivals with racing lines and personalities', 'Opponents: at least five AI cars, each with its own colour, name and skill profile, that follow racing lines (braking for tight corners, taking the inside), overtake, avoid collisions and occasionally make mistakes.'],
    ['Fair, bounded rubber-banding', 'Fair rubber-banding: AI pace adapts gently to the player\'s position within a bounded range defined as a constant (for example ±8%), with no teleporting, and a player who drives cleanly can win from any grid slot.'],
    ['Car and scenery collisions', 'Collisions: car-to-car contact (a bump, slowing or spinning, sparks) and car-to-scenery contact (solid objects stop or bounce you), with sound and camera shake.'],
    ['Grid, 3-2-1-GO countdown, lap options', 'Race structure: a staggered grid, a 3-2-1-GO countdown with lights and beeps, a false-start penalty or lockout, and races of 3 laps by default (1, 3 or 5 selectable).'],
    ['Lap counter, lap times, best lap, total time', 'Timing: the lap counter, the current lap time, the last lap and the best lap; the best lap on the track is kept as a track record (with storage, per T7), plus the total race time.'],
    ['HUD: speedometer, position, boost, minimap', 'HUD: a speedometer (a dial, or digital with a bar), your position (for example 3/6) computed live from laps and distance, laps and times, the boost or nitro meter, and a minimap of the circuit with every car as a coloured dot.'],
    ['Callouts: overtakes, final lap, wrong way', 'Race drama: callouts for overtakes and position changes, "Final lap!", and a wrong-way warning if the player turns around.'],
    ['Results screen for all cars', 'Finish and results: after the last lap, the player\'s car carries on under AI control while a results screen shows every car\'s finishing position, name, total time and best lap, with the player\'s best lap highlighted, then race again or return to the title.'],
    ['Engine pitch, screech, thuds, beeps, fanfare', 'Audio: a synthesised engine whose pitch follows speed or RPM (with gear changes if you model gears), tyre screech when drifting, collision thuds, countdown beeps and a finish fanfare, with mute.'],
    ['Sense of speed effects', 'Sense of speed: camera sway and shake at high speed, speed lines or a motion effect, and road markings and rumble strips that make speed readable.'],
  ],
  feel: `## Feel targets
- The first corner teaches braking; the third lap feels like a real race.
- Top speed feels fast: scenery streams past and the engine screams.
- The pack stays close enough for overtakes all race long, without the AI ever feeling like it cheats.
- A steady 60 fps with all six cars and the scenery on screen.`,
  done: [
    'Title → countdown → 3 laps → results → race again works.',
    'One full circuit with varied corners (and hills, for pseudo-3D) and scenery.',
    'Five AI rivals race, overtake and can be beaten fairly.',
    'Laps, lap times, best lap, position, speedometer and minimap all update live.',
    'Drift or nitro (ideally both) with clear feedback.',
    'Collisions with cars and with scenery.',
    'It logs no console errors, works without storage and holds 60 fps at 1920×1080.',
    'A world and a style of your own: a setting, vehicles, a time of day, a twist.',
  ],
  estimate: 38000,
  notes:
    '[extreme] Round 5 of The Game Jam: racing (pseudo-3D road projection or top-down physics). A credible entry is 20-40k tokens of code plus reasoning (estimate 38k). Checks: R1-R15 (judge checklist), T1-T9, and the genre playtest (hold accelerate from 1.9 s, alternate left/right steering, Shift taps). A 3-2-1 countdown is expected, so the 3 s screenshot may still show the grid.',
};

export const BRIEFS: Brief[] = [FLAPPY, RTS, RPG, ZOMBIE, RACING];

export function renderBrief(b: Brief, round: number): string {
  return [
    INTRO(round, b.title),
    b.pitch,
    CREATIVE,
    VISUAL_BAR,
    b.art,
    b.controls,
    b.screens,
    `## Numbered requirements (the judges check every one)\n${b.requirements.map(([, text], i) => `${i + 1}. ${text}`).join('\n')}`,
    b.feel,
    TECH,
    TESTED(GENRES[b.genre].inputs),
    `## Definition of done\n${[...b.done, VISUAL_DONE].map((d) => `- [ ] ${d}`).join('\n')}`,
    DELIVERY,
  ].join('\n\n');
}

// ───────────────────────────── The judges' rubric ─────────────────────────────

export const RUBRIC = `Grade six things. Strong but different interpretations of the brief must score as well as literal ones: reward what the game actually does and shows, not how closely it matches the picture in your head. Use the full-HD screenshots and the motion strip for what the game really looks like, and the code for what it does. Be honest and use the whole scale: most games are not a 10.

REQUIREMENT CHECKLIST: one line per numbered requirement.
- PASS: implemented and working as described; a different but reasonable interpretation counts.
- PARTIAL: present but clearly incomplete, buggy, or much simpler than asked.
- FAIL: missing, only in comments or menu text, or broken so it cannot work.
When a requirement asks for balance or fairness (fair gaps, bounded rubber-banding, a zombie base that is not a win button), only PASS it when the code's numbers and rules actually enforce it, not merely when the text claims it.

VISUALS (0-10): visual quality and art direction, the largest part of the score.
10 = could pass for a screenshot of a polished commercial indie game: one cohesive art direction, crisp full-HD rendering, depth, dynamic light, rich particles, animated characters, post effects and a designed UI. 7 = clearly art-directed, lit, animated and cohesive, with a few rough edges. 5 = clean but simple: flat shapes, little light or animation. 3 = basic shapes with little animation. 1 = placeholder rectangles. 0 = nothing visible. Judge what the screenshots show; code that would draw something never seen on screen earns nothing.

CREATIVITY (0-10): creativity and originality.
10 = a memorable identity: a fresh theme or art direction and a twist that changes how the genre plays, with extra mechanics that fit together; you would remember it tomorrow. 7 = a clear theme of its own plus at least one meaningful mechanic nobody asked for. 4 = a competent but generic clone, or a cosmetic reskin. 1 = the bare minimum. 0 = none. Never mark a creative twist down for departing from the classic game as long as the numbered requirements are still met. Creativity cannot rescue a game that does not run: if PLAYS is 2 or less, CREATIVITY is at most 4.

PLAYS (0-10): does it actually play?
10 = starts from the title screen and the whole loop works (title → play → lose or win → restart) with every system connected. 7 = plays through with minor bugs. 4 = plays, but a major system is broken or the loop cannot be completed. 1 = barely interactive. 0 = does not start. If the playtest shows the page froze, crashed or stayed blank, PLAYS is at most 2 and VISUALS at most 2.

FEEL (0-10): game feel and juice, including audio.
10 = tight, responsive and satisfying like a polished indie game: tuned physics and timing, fair difficulty, hit-stop, shake, flashes and easing on every action, and rich Web Audio sound design. 7 = responsive with good feedback and sound, a little uneven. 5 = functional but floaty, unfair or quiet. 3 = sluggish with little feedback. 0 = unplayable.

AMBITION (0-10): ambition and depth.
10 = clearly more than the brief asked for, and all of it works: content, variety and systems that interact. 5 = what was asked. 0 = a small fraction of it. Weigh this against the size of the brief: the zombie round asks for more than the Flappy round.

Hard rules: never award anything for features that exist only in comments, menu labels or text. If the file loads any external resource, VISUALS and AMBITION are at most 3.`;

// ───────────────────────────── Write the test ─────────────────────────────

function approxTokens(text: string): number {
  return Math.ceil(text.length / 3.8);
}

if (import.meta.main) {
  const cases = BRIEFS.map((b, i) => ({
    id: b.id,
    prompt: renderBrief(b, i + 1),
    expected: { genre: b.genre, requirements: b.requirements.map(([label]) => label) },
    notes: b.notes,
  }));
  const inputAvg = Math.round(cases.reduce((s, c) => s + approxTokens(c.prompt), 0) / cases.length / 50) * 50;
  const outputAvg = Math.round(BRIEFS.reduce((s, b) => s + b.estimate, 0) / BRIEFS.length / 500) * 500;
  const test = {
    kind: 'prompt',
    id: 'creative.game-jam',
    version: '2.0.0',
    name: 'The Game Jam',
    category: 'creative',
    description:
      'Five full game design briefs, one per genre, each built from a single prompt as one self-contained HTML file with a commercial-indie visual bar: a Flappy Bird remake, a Command & Conquer-style real-time strategy game, a top-down action RPG, a deliberately hardest zombie survival game with base building, and a racer (WebGL welcome). Each model may use its full output. Each game is played by a scripted player for its genre in a headless browser at 1920×1080 (eight screenshots and a motion strip, freeze and error checks), then graded by a cross-vendor judge panel that sees the code and the screenshots, ticks every numbered requirement and weights visual quality and creativity most, so it separates models that can design, draw and engineer a whole game from those that sketch one.',
    difficulty: 'extreme',
    tags: ['creative', 'html', 'canvas', 'game', 'artifact', 'one-shot', 'game-jam', 'playtest'],
    hook: 'Five genres. One prompt each. Build the whole game.',
    // No artificial cap: each model may write up to its own maximum output (Contestant.maxOutputTokens).
    maxOutputTokens: 'model-max',
    // Long generations stream for an hour or more; one retry at most, since a retry restarts a paid reply.
    timeLimitSec: 10800,
    maxRetries: 1,
    estimate: {
      inputTokens: inputAvg,
      outputTokens: outputAvg,
      // Each judge reads the brief, the rubric and the whole game (≈ the visible output) plus nine full-HD pictures.
      judgeInputTokens: 56000,
      judgeOutputTokens: 3000,
    },
    author: 'Gauntlet Core',
    createdAt: '2026-09-28',
    scorer: {
      type: 'artifact',
      format: 'html',
      checks: [{ check: 'parses' }, { check: 'no_external_requests' }, { check: 'max_bytes', bytes: 20000000 }, { check: 'runs_without_errors' }, { check: 'has_canvas_or_svg' }, { check: 'responds_to_input' }],
      rubric: RUBRIC,
      judgeWeight: 0.75,
      playtest: { protocol: GAME_JAM_PROTOCOL },
    },
    cases,
  };
  const file = join(ROOT, 'tests', 'creative', 'game-jam.json');
  writeFileSync(file, `${JSON.stringify(test, null, 2)}\n`);
  console.log(`wrote ${file}: ${cases.length} cases, ~${inputAvg} input tokens and ~${outputAvg} output tokens per case`);
  for (const c of cases) console.log(`  ${c.id}: ${approxTokens(c.prompt)} prompt tokens, ${c.expected.requirements.length} requirements`);
}
