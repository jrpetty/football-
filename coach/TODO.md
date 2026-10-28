# TODO: outcome-based delivery items

Legend:
- ☑ done and verified (automated test, or a recorded browser/Electron run);
- ◐ implemented, but not verifiable in the build environment (reason given);
- ☐ not started.

## v1 foundation

- ☑ The player picks a window. Capture needs a single-use consent token, and Stop capture is always visible and works. *Electron smoke run: capture without consent is denied; consent is single-use; the title shows ●; Stop restores it.*
- ☑ A bounded, drop-oldest frame queue. Frames are never persisted.
- ☑ Pixel recognisers that report "can't read" instead of guessing.
- ☑ Episode evidence grading, with no causal language (guardrail test).
- ☑ Local TTS: local voices only, interruptible, expiring.
- ☑ The renderer has no network access and no Node. The camera is refused; the microphone is allowed only with voice input on. *Electron smoke run.*

## v2 delivery

### Safety and policy
- ☑ A typed `PolicyStatus` registry with sources, review dates, staleness downgrade and a tournament override. *Outcome:* an unknown, stale or tournament scope never yields live output (`test/policy.test.ts`).
- ☑ A governor keyed on category × delivery window × status × user mode. *Outcome:* competitive-safe and post-match-only scopes block every live message (exhaustive 1,000-case matrix).
- ☑ VALORANT registered `post_match_only`, citing Riot's third-party application policy. *Outcome:* no live output is possible for VALORANT in any mode, including offline practice.
- ☑ Questions asked during restricted play are held, then delivered at match end as "Earlier you asked: …". This is shown as **Held** in the conversation.
- ◐ Registry entries reviewed by a person. *The Riot and EA sources are search excerpts (the pages could not be fetched from the build environment). The FC and AoE offline entries, and the default offline entry, are internal judgements needing product/legal sign-off. See POLICY_REGISTRY.md.*

### Seeing more games
- ☑ Domain types: `Observation`, `VisibleState`, `CoachingMessage`, `PatternRecord`, `PolicyStatus`.
- ☑ The visible-state fuser handles TTL expiry, ignores late arrivals, flags conflicts, and marks state stale.
- ☑ Frame-health monitor: black, frozen and resolution change. Track mute (a minimised or protected window) is reported as a capture problem.
- ☑ Pack schema v2: vision schema, zones, heatmaps, mistake rules, score rules, match stats, player context, knowledge, modes, window-title hints, fail-closed conditions.
- ☑ Packs: generic, EA SPORTS FC, Age of Empires II DE. The synthetic arena is migrated.
- ◐ Cloud vision:
  - opt-in;
  - the key in `safeStorage`;
  - structured output and a validator;
  - a sampler: interval, change detection, one request in flight, backoff;
  - a cost estimate and a session cap.

  *Verified with a fake client and through Electron IPC refusals: no key, cloud off, bad key. Real API calls are untested: no key in the build environment.*
- ☑ Pack suggestion from the window title in the picker, confirmed by the player.
- ◐ Real-game recognition accuracy for FC and AoE II. *It depends on the vision model reading real screens. The synthetic replays prove the pipeline, not the recogniser.*

### Analyses players asked for
- ☑ FC:
  - goals from scoreboard changes (a misread is not a goal);
  - conceded and scored zones and build-up type, from replays;
  - turnovers by zone (sampled);
  - stats-screen match records;
  - two quick goals by the same side are both placed.
- ☑ AoE2: housed time, idle villagers, floating resources, and Feudal/Castle timing against the player's targets. Nothing is inferred when the numbers are unreadable.
- ◐ Generic: deaths by minimap area, results over time. *In the generic pack schema; there is no replay fixture or test yet.*
- ☑ Heatmaps (FC pitch and goal area), mistake panels, stat trends and cross-match history (opt-in). *Recorded browser run with the FC and AoE replays.*
- ☐ AoE timing in game time. *Mistake durations are measured in real capture time; AoE II game time runs about 1.7× faster.*

### Coaching and conversation
- ☑ The planner produces `CoachingMessage`s with category, evidence summary, delivery and suppression reasons, and expiry.
- ☑ Live training tips from mistake rules (offline vs AI only) and from pattern cues.
- ☑ Ask the coach:
  - without an API: local intents (concede/death zones, turnovers, mistakes, summary, patterns, stats), with correct small-sample wording;
  - with an API: Claude answers open questions from a structured digest.
- ◐ Push-to-talk with local STT (sherpa-onnx + Moonshine tiny). *Recognition is verified on a real recording (shell test with `SIGHTLINE_STT_ARCHIVE`). The microphone permission gate is verified in Electron. The in-app download and the real-microphone round trip are untested here: there is no microphone.*
- ☑ Player notes and goals, saved when opted in and included in the Claude digest.
- ◐ Game research brief (Claude with web search; cited, dated, cached, deletable). *Fake client only.*
- ☑ Speech priority with pre-emption, a Quiet control, and a caption for every message.

### Memory and privacy
- ☑ Long-term `PatternRecord`s:
  - view with supporting and counter instances;
  - mark wrong, dismiss, restore, delete;
  - 30-day half-life decay;
  - segmented by game, mode and pack major version;
  - reviewable between sessions.
- ☑ Match history: view, delete per match, delete per game, export everything.
- ☑ Player data is encrypted at rest with `safeStorage` when available, and the UI states which. *Under Xvfb the OS store is unavailable, and the app correctly reports "without encryption".*

### Experience
- ☑ UI v2:
  - setup with pack cards, mode, player context and a policy panel;
  - a live dashboard with status strip, captions, FC scoreboard and zone maps, the AoE economy panel, mistakes and the visible-state table with freshness;
  - Ask with suggestions and a mic;
  - Insights, History, Timeline and Settings (cloud, memory, voice input);
  - the policy registry viewer;
  - a whole-screen warning;
  - no horizontal overflow at 1024–1440 px.
- ☑ Synthetic replay fixtures for the FC and AoE observation streams, labelled as simulations, so the full pipeline can be demoed without a key or a game.

### Documentation
- ☑ README, plan.md, TODO.md, POLICY_REGISTRY.md, docs/QA.md, docs/PRIVACY_AND_INTEGRITY.md.

## v3 delivery

### Downloadable app
- ◐ Windows installer: electron-builder NSIS x64, built by the `coach-windows.yml` workflow on `windows-latest` and uploaded as an artifact. *Packaging was verified locally with a Linux build: the packaged app runs, and the native voice, speech recognition and IPC all work in it. The Windows build result is in GitHub Actions.*
- ☐ Code signing (removes the SmartScreen warning).

### Anti-cheat
- ☑ By design: a separate process, OS window capture, standard global hotkeys; no injection, overlay, input, driver or admin rights. Documented in docs/ANTI_CHEAT.md.
- ☐ Tested on Windows with RICOCHET (MW4) and AoE IV running. It cannot be tested here.

### Talking and listening
- ☑ Natural neural voices on this computer:
  - Jenny (Piper) and 9 Kokoro voices;
  - pinned and checksum-verified downloads, unpacked in a worker thread;
  - sentence-by-sentence playback with the next sentence prepared ahead;
  - a system-voice fallback;
  - a speed measured on the user's PC.

  *Verified in the packaged Electron app: synthesis runs at about 0.19× real time on the build machine.*
- ☑ Configurable global keys (talk toggle, mark moment, stop), validated so a bare letter cannot fire while typing in chat. Conflicts with other programs are reported.
- ☑ Speech recognition in the packaged app transcribes a test recording exactly. Both models are pre-loaded so the first use is quick.

### Understanding the screen
- ☑ Every vision answer names the screen (name, kind, summary).
- ☑ Menus, loading screens and scoreboards are remembered as 64-bit fingerprints (~200 bytes each, at most 400 per game), then recognised locally with no request. They are re-checked once a minute.
- ◐ Questions asked during capture include the current screen; mark-a-moment plus a policy-gated post-match review. *The pipeline is tested with fakes; real Claude answers are untested (no key).*
- ◐ Game knowledge base: web research is structured into 10 sections, cited and dated, stored gzip-compressed, and fed to prompts within budgets (about 14k characters for vision, 60k for answers). *The structuring and validation are tested; real research calls are untested.*

### Games
- ☑ Packs for Call of Duty: Modern Warfare 4 and Age of Empires IV, from launch-week research (marked unverified where sources were thin), with policy entries:
  - live tips only in campaigns, against AI, or against bots;
  - online modes post-match only.
- ◐ HUD reading for both games. *No live captures yet; the HUD layout in MW4 is configurable.*
- ☐ Mistake rules for MW4 beyond the death map, once real sessions show what is reliably visible.

### Experience
- ☑ First-run setup (connect Claude with a key test, game, voice, keys). Decisions tab, game-knowledge panel, screen name on the live dashboard.
- ☑ Fixed: switching tabs crashed the page. Newer Chromium returns a Promise from `scrollTo`, which React took for an effect cleanup.

## v4 delivery

### Advice that is actually useful
- ☑ Fight factors: deaths (and kills, where the player's view shows it) tagged with the visible circumstances of the fight; fights won and lost compared by value, avoidable circumstances counted as a share of deaths, and timing (died within 8 s of your own kill). Graded with Wilson intervals; untagged fights counted, never guessed. *Tested on a synthetic MW4 match with ground truth (`test/positioning.test.ts`).*
- ☑ Benchmarks: a visible number against an explainable curve at checkpoints of the game clock (AoE IV villagers against one Town Center that never stops), with the gap in minutes. *Synthetic AoE IV game.* (The strong-player reference was removed in v15.)
- ☑ Playbooks researched from pro, coach, official, guide and forum sources (MW4 30, AoE IV 26, FC 27 25 entries), validated (sources, no causal or absolute words), linked from findings, mistakes, zone breakdowns and stat warnings. Each fix is said once per summary.
- ☑ FC 27: conceded goals read for the first defensive mistake in the replay; pass accuracy against a 75% warning line.
- ☑ The coach's review (local, from findings and the playbook) with "Read it to me"; Insights panels for fights, benchmarks and the playbook. *Recorded browser run of the MW4 and AoE IV demos.*
- ◐ Claude's deeper review (structured output, validated for size, wording and playbook links). *Validator tested; real API calls untested (no key).*
- ◐ Research quality. *Page fetches were blocked in the build environment, so the playbooks come from search-result summaries; no Reddit threads could be read; pro sources are limited (Scump's course outline, Rambo, RBLZ_Levyfinn, Tekkz/RedLac, a VortiX tip). Entries marked as rules of thumb say so.*
- ◐ Killcam reading accuracy. *Depends on the vision model on real MW4 killcams; untested without captures.*
- ☐ Watch pro VODs (Valdemar, Beastyqt, CDL) to attribute more advice directly and check the one-Town-Center villager curve per civilisation (Order of the Dragon villagers take 23 s).

## v5 delivery

### Talking to the coach
- ☑ Push-to-talk end to end in Electron: the global key pressed through the X server like a real keyboard, Chromium's fake microphone playing a spoken question, local recognition ("Where am I dying most?" transcribed in about half a second), the answer, and the spoken reply. *Recorded run under Xvfb.*
- ☑ Fixed: answers to spoken questions were not spoken when not capturing; the chosen neural voice was not applied until capture started (idle speech fell back to the system voice); a context created by a global key could start suspended. Added listen/send/held/error tones, spoken problems (voice input off, microphone blocked by Windows privacy settings, model missing), and answers when nothing has been recorded yet.
- ◐ A real microphone on Windows. *Not available here.*

### Age of Empires IV battles
- ☑ Battle tracking from fight frames and army size, settled only when both agree; fights compared by location, matchup, numbers, ranged screen, siege splash, enemy siege, reinforcements, and how lost fights ended. Fight log, live tip against the AI, summary line, digest. *Tested on a scripted game with ground truth (`test/battles.test.ts`).*
- ☑ Fifteen battle playbook entries with the reasoning (armor math, bonus damage, arrow ranges, siege resistances, Update 17.0 Ram and Town Center changes), and reasoning added to seven existing entries; shown as "Why it works" and given to Claude.
- ☑ Map and opponent's civilisation read and kept with the session and match records.
- ◐ How well Claude reads fights from real frames (trend, matchup, clumping). *Untested without captures; the research notes one frame cannot show a trend, so outcomes rely on several frames plus the army size.*
- ☐ Check the numbers after Update 17.0 (29 Oct 2026): Town Center build time, Ram health, Mangonel and Springald values, Town Center garrison arrows.

## v6 delivery

- ☑ Live commentary: its own message type and pacing, built only from readings, spoken where live speech is allowed, a one-time notice elsewhere. *Unit tests, and a recorded Electron run of the AoE IV demo with commentary on: 11 spoken lines in 100 seconds.*
- ☑ AoE IV roster: every civilisation (with variants and announced upcoming ones), unique units, landmarks, bonuses, how to play against them; generic units with counters and the reasoning; production buildings; counter rules. Validated so every reference resolves.
- ☑ Scouting intel: the opponent's civilisation, buildings and army read from the player's screen; what each building can make for that civilisation; answers for the player's civilisation; commentary lines, a "Their side" panel, the summary and Claude's digest.
- ☑ Researched reference loaded into the packs: MW4 (weapons, perks, streaks, maps, modes, HUD), EA FC 27 (mechanics, tactics, modes, screens, patch history), AoE II DE (56 civilisations, counters, build orders), with sources.
- ◐ Recognising enemy buildings and units in real frames. *Depends on Claude reading real AoE IV screens; untested without captures.*
- ◐ Research quality: page fetches were blocked; sources are search summaries and aoe4world's open data on GitHub. Uncertain items are marked "(unverified)".

## v7 delivery

- ☑ Vision every 3 seconds for the AoE packs, with up to two reads in flight. Settings shows the cost estimate; the README gives it per model.
- ☑ Every AoE IV civilisation's plans for each age, Dark to Imperial: their usual moves with the signals that give them away, timings, threats, responses and punishes; how to play the civ at that age; power spikes and weak windows. Validated so every building, unit and landmark belongs to that civ.
- ☑ Tactical reads: a plan is called only when scouted landmarks, units, buildings or noted cues support it (score ≥ 2), once, spoken with its response and evidence; the player's own plan per age; "Their usual plan" until then.
- ☑ Per-civilisation unit ages from the game data (English Men-at-Arms in the Dark Age, HRE ones in Feudal, Rus Knights in Feudal, Mongol Horsemen in the Dark Age…), used for what a building can make, the opponent's age and your answers.
- ☑ Scouting news waits for a gap in the commentary instead of being lost to the pacing; reads go first. *Tested on the AoE IV demo played as French (`test/plans.test.ts`).*
- ◐ Plan research: from search summaries and community guides for the current patch; the upcoming civs' plans are marked "(unverified)" and need a check at release.
- ◐ Cue reading (a tower at your gold, a second Town Center) from real frames. *Untested without captures.*

## v8 delivery

- ☑ Screen reading set by the player for each game: refresh time (1–20 s), looks at once (auto or 1–4), detail (768–1600 px); applied mid-capture. *Settings sanitised and tested; sampler reconfigured live.*
- ☑ Thinking off for screen readings (Sonnet 5.5 and Haiku 5.5); matchup-only reference once the opponent's civilisation is read (about 70% less text per look); measured cost per look from the API's token counts.
- ☑ Learning across games for AoE IV, AoE II, MW4 and FC: what each side was seen doing (first-seen order, army mix, tallies, results), filed by opponent, civilisation, map, mode or formation; recalled at the start of the next game of that kind where live coaching is allowed; linked to the civilisation plans; answers, digest, Insights panel. *Tested with each game's demo played several times (`test/learning.test.ts`).*
- ☑ Readings from one match no longer carry into the next (map, opponent).
- ☑ "Look every" on the Coach screen (before and during capture) with reply time and a warning when replies are slower than the pace.
- ☑ Learning settings per game: learn on/off, recall on/off, games needed before recall (1–10), forget what was learned (match records stay).
- ☑ Your own buildings (AoE IV, AoE II) for your build order, without the starting base; each civilisation's plans linked to your games on both sides (met against them, played as yours), by landmark first. *Tested (`test/learning.test.ts`).*
- ◐ New readings from real frames: your army, the opponent's AoE II civilisation and army, your MW4 weapon and enemy killstreaks, the FC opponent and formation. *Untested without captures.*
- ◐ How much thinking costs on Opus 5.5 at low effort, and whether thinking off loses accuracy on Sonnet or Haiku. *Measure on a real session with the new cost breakdown.*

## v9 delivery

- ☑ MW4 position each look (callout and height), killer's spot and weapon from killcams, enemy sightings; the current map's callouts sent with each look.
- ☑ Map model learned from watching: areas, routes, heights, held angles, enemy sightings, spawn deaths, time between deaths, weapon results by range; drawn top-down and in 3D; held-angle tip against bots; answers and digest. *Tested with synthetic data and the demo on a real map (`test/mapmodel.test.ts`).*
- ☑ MW4 arsenal (101 items) and 23 maps researched and merged; answers about any weapon, perk, streak or map; arsenal browser.
- ◐ Callouts: few MW4 callouts are published five days after launch; most names are single-source Hardpoint-hill names and most grid positions are inferred from the official Tac-Map images (marked unverified). Domination flags and most S&D sites are not published. Check in game and correct.
- ◐ Recognising the area, height, killer's spot and weapon from real frames. *Untested without captures; "look" cues are inferred from names and official descriptions.*
- ◐ Equipment, field upgrade and streak numbers (damage, radius, durations) are not published; marked as unknown rather than guessed.

## v10 delivery

- ☑ Pricing corrected:
  - the model that served each reply is billed (a fallback at its own price);
  - web searches at $10 per 1,000;
  - Haiku 5.5's higher rate above 100K-token prompts;
  - 1-hour cache writes;
  - image tokens per 28×28 patch.

  *Tested per model (`test/usage.test.ts`).*
- ☑ A usage history kept across sessions: numbers only, by day, feature, model and game, for 400 days. It gives today, the last 7 days, this month with a projection, all time, and a 30-day history. *Tested, including reading back a damaged file.*
- ☑ The Usage & cost page:
  - figures, with session-cap and monthly-budget meters;
  - the spending rate now, and minutes left to the cap;
  - a 30-day chart by feature with a table view;
  - where the month went by feature, model and game;
  - what playing costs per game and per model (per look, per hour, per typical match);
  - job costs;
  - limits and prices.

  *Browser run with a month of generated usage.*
- ☑ Estimates switch to what the player's own looks cost once there are 20 of a game with a model. The Coach screen, the refresh control, Settings and onboarding all show the same figures. Research and knowledge building show their cost before they run, and the real cost after.
- ☑ A monthly budget: warn from 80%, optionally stop all cloud calls. A spend chip in the top bar; warnings on the Coach screen during capture. *Service stop at the budget tested with a fake client.*
- ◐ Token assumptions for estimates have not been measured on real calls: 350 written tokens per look, the thinking allowance, and the job sizes. *No API key in the build environment. The measured figures replace them as the player plays; compare a session with the Console's usage page.*

## v11 delivery

- ☑ The map stitched from a fixed minimap:
  - each frame's minimap matched against what is stitched so far, about a pixel;
  - the player found again after a respawn;
  - new pieces on unexplored ground, joined when the player walks onto known ground;
  - only windows with the player's arrow at the centre (no death screens or menus);
  - kept per map between matches, gzip, and handed over every few seconds.

  *Tested on a synthetic tac-map with ground truth: 95% of positions within 2.5 px; re-find, new piece and join, save and load (`test/minimap.test.ts`).*
- ☑ Pins on the stitched map:
  - deaths where the player's own minimap cut away;
  - killers where the killcam's minimap ended, else at the callout the killcam named;
  - kills, and the path life by life without the killcams;
  - enemy pings, and callouts and heights where they were read (callouts only once the player is settled in the area).

  *The MW4 demo end to end through the engine: every death pinned, median error under 1 px (`test/mapstitch.test.ts`).*
- ☑ Insights → The map:
  - Overhead: zoom, pan, hover, layers, match playback, deaths table.
  - 3D in three.js, loaded on demand: height field from the minimap's shapes plus learned heights, death beams, killer arcs with tracers, trails, labels, bloom, orbit.
  - Coaching lines from the pins: the spot and the direction of fire, kill clusters, enemy pings, spawn deaths (`test/mapview.test.ts`).

  *Browser run of the demo, including WebGL.*
- ☑ A live mini-map on the Coach screen (where live tips are allowed). Settings → Minimap: align the box on a still, with the arrow check; forget a learned map. A rotating minimap falls back to areas.
- ◐ Real MW4 minimaps: where the minimap sits by default, its zoom, whether the arrow passes the brightness check, whether killcams show the killer's minimap, and whether the minimap's lighter shapes really are buildings (for the 3D). *No captures in the build environment; the defaults are guesses to check with the alignment tool.*
- ◐ Maps whose minimap zooms (vehicles, Ground War) or rotates are not stitched.

## v12 delivery

- ☑ The official overhead map analysed: playable area (inside the boundary, found by flooding in from the edges, gaps closed), walls, solid structures, indoor floors, out of bounds; a score for how much it looks like a tactical map (the real maps 0.88–1, a screenshot of the game 0.33) (`test/official.test.ts`).
- ☑ The stitched minimap placed on it while playing: scale and position by coarse-to-fine matching; ambiguity refused (the margin over the best placement elsewhere, every scale's runner-up included; single-window placements provisional until three frames bear them out); re-finds after a respawn must agree with the official map; every tracked frame pinned to the official map (no drift); points recorded before a piece was placed pinned down afterwards.

  *Measured on the 20 real official map images (12 from the official guide, 8 community-hosted) with simulated minimaps in three art styles: every point placed, none wrong (over 5% off), p95 at most 0.62% of the playable area's width; the worst single point 3.0% (Reactor U92, a brief loss of tracking). A mirrored-wings test never places a piece in the wrong wing.*
- ☑ Getting the images: Download from the official guide page named in the pack (desktop, on click, https and the page's own sites only), Open page, Import (picker or drag and drop, matched to maps by name), Remove. Each image checked before it is kept. Pack v1.5.0 names the official page of the twelve launch maps and a community page for eight more (`test/overhead.test.ts`).
- ☑ The map views on the official map: overhead, live mini-map and 3D floor plan; placed share shown, unplaced points left out (Stitched view shows them); Check placement overlay with nudges; maps stitched earlier placed afterwards; the MW4 demo walks on a kept official map. *Browser and Electron runs of the demo on the official Cachette image: 100% placed.*
- ◐ Downloading from the official pages is untested against the live sites: they were unreachable from the build environment. *The page parser is tested on sample pages; if a page hides its image, saving and importing it works.*
- ◐ Real MW4 minimaps against the official images: art style, zoom and whether the in-game minimap is drawn from the same picture are untested. *The three simulated art styles are the stand-in; QA 13.38–13.41 measure it.*
- ◐ Kill Block has no overhead image (only a screenshot, refused), and Hajin and Zodiac have none; eight maps only have 450 px community images.

## v13 delivery

- ☑ EA SPORTS FC: every player and the ball read from the 2D radar, about twice a second, on this computer (no cloud call): teams by the radar colours set (or two colour groups, yours worked out from the first goal), ends per half, possession, territory, turnovers and regains, shots, kick-offs, goals with their last seconds; average shapes with and without the ball; formations matched against 18 shapes (`test/pitch.test.ts`).

  *Measured on simulated radars: 95–99% of players found within about a metre (p95 0.4–1.2 m), the right team for 97% or more, the ball in about 97% of frames, possession within about 3% of the truth, both ends right in 97% or more of frames, every goal found. Formations: right every time with players about 1 m off their places, 98% at 2 m, 92% at 3 m (the misses are near-identical shapes such as 4-4-1-1 and 4-4-2); a scattered team is never given a shape. At the app's 960 px analysis width the demo radar was read in 955 of 968 frames.*
- ☑ FC insights against real-football tracking figures (low block under 35 m, length 31–46 m, width 35–48 m, defence-to-midfield gap about 10 m, pressing within 5 m, shot value by zone), the opponent's shape with how to beat it, and the playbook entry for each.
- ☑ FC views: Insights → The pitch (shapes, heat, lost and won balls, shots, every goal played back, 3D stadium); the radar live on the Coach screen (offline modes); Settings → Radar (align on a still with what is read drawn on it; the game's settings to use).
- ☑ FC 27 pack v1.4.0: 23 modes (live tips offline against the CPU outside Ultimate Team), 30 more playbook entries (55), 13 formations, 149 more reference items, radar colours as the game lists them.
- ☑ Counter-Strike 2 pack: 13 modes, 10 maps with 258 callouts and the game's radar transforms, 44 weapons and equipment from the game files, 51 playbook entries, 16 fight factors, 309 reference items, policy entries (`test/cs2.test.ts`).
- ☑ CS2 Game State Integration: a listener on 127.0.0.1, off until turned on, token-checked, size-capped (`test/gsi-server.test.ts`); the cfg file saved from Settings; exact kills, deaths (flashed, reloading), the map, side, score, money and phase; the screen's details of each death added to the death the game reported, never counted twice.
- ☑ CS2 rounds and economy: each round's buy, spend, kills, damage, death and result; lines on forcing, pistol rounds, early deaths, unused grenades and each side; the round strip and money chart.
- ☑ CS2 radar stitched and placed on the game's own overview (imported from the game files; never bundled), with the pack's callouts labelled at their places; overview images with a transparent surround use it for the playable area; stricter placement for radars drawn from the overview itself.

  *Measured on the ten real 1024 px overviews with a simulated centred, north-up round radar (teammates, enemies, A/B letters, the player's arrow), 30 runs (three per map, six lives each): no point placed in the wrong spot; 95% of points within 0.21–0.61% of the map's width (2–5 overview pixels, well under a metre) in 29 runs and 2.6% in one (Nuke); 68–93% of the points placed while playing in 29 runs, 17% in one (Cache: its first pieces never matched clearly enough, so they stayed unplaced rather than risk a wrong spot). Before the transparency fix and the stricter rules, Nuke, Vertigo, Ancient and Anubis had pieces placed in the wrong spot; that is what these changes are for.*
- ☑ CS2 demo: a short synthetic match sending Game State Integration updates, the screen's readings and a radar to stitch.
- ◐ Real FC radar captures: how FC draws the ball and the controlled player, the lines, and the radar at set pieces are undocumented; the default box is an estimate. *QA 13.43–13.46.*
- ◐ Real CS2: the radar's look in game (the player's arrow, icons, the lower-level switch) and Game State Integration against a live client are untested here. *QA 13.47–13.50.*

## v14 delivery

- ☑ CS2 pack v1.1.0 on the seven Active Duty maps only (Ancient, Anubis, Cache, Dust II, Inferno, Mirage, Nuke); Overpass, Train and Vertigo left out. Callout spots on the overview for Mirage and Nuke read off the game's radar images and checked against Valve's retake spawns and plant spots.
- ☑ Gun numbers from the game's weapon file for all 34 guns: damage by distance, body part and armour; hits and time to kill; helmet and bare one-tap range; accurate range standing, crouched and scoped; the miss while running; armour penetration, speed, reload (`test/ballistics.test.ts`). The arsenal shows a table per gun and every gun side by side; answers say them.

  *Checked against the well-known counts: AK-47 4 body hits through kevlar (0.30 s), 3 to the stomach, helmet one-tap to about 69 m; M4A4 5 and no helmet one-tap; M4A1-S 4 up close, 5 at 20 m; AWP one chest hit, two to the legs; SG 553 helmet one-tap at any range; AK-47 accurate to 21.4 m standing, 27.7 m crouched.*
- ☑ Grenade mechanics (108 details on the six grenades and 11 spray patterns in the arsenal; throwing, team utility, gun mechanics and 29 patches in the reference), each with its source; those resting only on CS:GO's code or a single guide marked unverified with the reason.
- ☑ 227 grenade lineups: 44 of Valve's own from the game files with exact spots (one of Valve's 45, Anubis "A Main", left out: its landing in the file is nowhere near A Main), 183 researched, 38 executes and retakes (`test/utility.test.ts`). Insights → Grenade lineups draws them on the overview; questions about lineups, executes and retakes are answered from them; the named map's lineups go to the cloud with a question.
- ☑ Researched practice positions (setpos/setang) and aim angles that came from a cheat client's copy of a lineup site were removed; the 61 aims that were only an angle are marked unverified, with the reason.
- ☑ Grenades from Game State Integration: carried, thrown and held at death by kind, times flashed blind and for how long, times caught fire; lines on throwing what you carry (overall or by side), full buys without a smoke or flash, flashes and fire, each with its playbook entry (`test/cs2.test.ts`). The weapon list from the game now replaces the last one, so a thrown last grenade is no longer counted as held.
- ◐ Lineups are not checked in the game here: 86 are marked unverified, and Cache's grenade collision changed twice in 2026. *QA 14.1–14.3.*
- ◐ The 34% accurate-speed line, throw speeds and flash and HE falloff are from CS:GO's code; CS2 may differ.
- ☑ CS2 maps' real layouts from the game's nav meshes (a reader for the .nav format, `scripts/cs2-nav.mjs`; `scripts/build-cs2-layouts.mjs` builds `src/profiles/layouts/cs2/`): drawn from above in the app's style and in 3D at real heights; Nuke's levels apart; shown in Insights → The map (with the learned areas at their real places), the grenade lineups and the demo, which now stitches a radar drawn from the real layout and places it on it (98% placed on Ancient). Callout spots that fell on walls or boxes were moved to the nearest floor (27 of 186, by 2–29 px); every callout and Valve grenade spot is on the floor (`test/layout.test.ts`).

  *The nav floors line up with the game's own radar overviews (checked by eye on Dust II, Mirage and Nuke: the area outlines trace the overview's walkable shapes). Placing your own minimap in real play still needs the game's overview imported: the minimap is the game's art, the drawing is not.*
- ☑ MW4 pack v1.6.0: the map list checked against the launch blog, the wiki and Shipment's 27 Oct release (23 maps, none missing; the solo Mobility Course is not a match map), 253 callouts (from 167): Hajin rebuilt from the game's own tac-map data (48 places with their squares), Wolves Stadium 3 → 14, Kill Block 6 → 18, Coal, Mumbai, Cachette, Rooftops and the Gunfight maps deeper; every existing callout name kept.
- ◐ MW4 core-map callouts still come from rough reads of the tac-maps: the official callout labels could not be fetched here. Thin maps: Reactor U92, Shipment (6), Transit 213 (7), Library, NYC (5). Hardpoint orders rest on one site and disagree with another on Cachette, Reactor U92 and Silkworm.

## v15 delivery

- ☑ AoE IV villager count fixed. The count was too low (about 27 at 13–15 minutes when it was not): it had been added up from the worker numbers beside each resource, which leave out villagers building, walking or carrying. Now:
  - only the one villager total the screen shows counts;
  - a checkpoint is stated only when two or more separate readings agree (one reading lingering on screen counts once);
  - a reading above population is thrown out (`test/positioning.test.ts`).
- ☑ AoE IV: no two- or three-Town-Center talk.
  - Removed: the strong-player reference ("about 45 at 10:00"), the "add a Town Center" lines, "out-boom" and "out-expand", and the "2TC" plans offered to you (Rus, Chinese, Zhu Xi, Abbasid, Malians, Japanese, Order of the Dragon).
  - Added: "build more Town Centers" is prohibited advice. A second Town Center is described as optional.
  - Kept: reading the opponent's second Town Center when scouted.
  - The benchmark reference feature itself was removed from the schema, the chart and the answers.
- ☑ AoE IV floating judged by age, against what the next age costs: over 1,000 in the Dark Age, 2,200 in Feudal, 4,000 in Castle and 3,000 in Imperial. The housed tip says "add population space" (Japanese Farmhouses, Malian houses).
- ◐ Where AoE IV shows the total villager count on the HUD is not confirmed here: the official forums could not be reached. Waiting on a screenshot from a real game. *QA 15.1.*

## Later (P2+)
- ☐ Run the manual QA guide (docs/QA.md) on Windows 11 hardware, covering HDR, exclusive fullscreen, capture-excluded windows and DPI.
- ☐ Native `GraphicsCapturePicker` module (the Windows OS picker).
- ☐ Human review of every policy registry entry. Ask EA and World's Edge for their positions.
- ☐ A generic-pack replay fixture and tests (deaths by minimap area).
- ☐ Measure mistake durations in game time where the HUD shows a clock.
- ☐ Signed pack and registry updates, if remote updates are ever added.
- ☐ Local OCR and a local vision model to reduce cloud dependence and cost.
- ☐ More packs (AoE IV, Rocket League offline, fighting-game training mode), each with a policy review.
