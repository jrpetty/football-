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
- ☑ Benchmarks: a visible number against an explainable curve at checkpoints of the game clock (AoE IV villagers against one Town Center that never stops), with the gap in minutes and a strong-player reference. *Synthetic AoE IV game.*
- ☑ Playbooks researched from pro, coach, official, guide and forum sources (MW4 30, AoE IV 26, FC 27 25 entries), validated (sources, no causal or absolute words), linked from findings, mistakes, zone breakdowns and stat warnings. Each fix is said once per summary.
- ☑ FC 27: conceded goals read for the first defensive mistake in the replay; pass accuracy against a 75% warning line.
- ☑ The coach's review (local, from findings and the playbook) with "Read it to me"; Insights panels for fights, benchmarks and the playbook. *Recorded browser run of the MW4 and AoE IV demos.*
- ◐ Claude's deeper review (structured output, validated for size, wording and playbook links). *Validator tested; real API calls untested (no key).*
- ◐ Research quality. *Page fetches were blocked in the build environment, so the playbooks come from search-result summaries; no Reddit threads could be read; pro sources are limited (Scump's course outline, Rambo, RBLZ_Levyfinn, Tekkz/RedLac, a VortiX tip). Entries marked as rules of thumb say so.*
- ◐ Killcam reading accuracy. *Depends on the vision model on real MW4 killcams; untested without captures.*
- ☐ Watch pro VODs (Valdemar, Beastyqt, CDL) to attribute more advice directly and check the AoE IV villager reference per civilisation.

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

## Later (P2+)
- ☐ Run the manual QA guide (docs/QA.md) on Windows 11 hardware, covering HDR, exclusive fullscreen, capture-excluded windows and DPI.
- ☐ Native `GraphicsCapturePicker` module (the Windows OS picker).
- ☐ Human review of every policy registry entry. Ask EA and World's Edge for their positions.
- ☐ A generic-pack replay fixture and tests (deaths by minimap area).
- ☐ Measure mistake durations in game time where the HUD shows a clock.
- ☐ Signed pack and registry updates, if remote updates are ever added.
- ☐ Local OCR and a local vision model to reduce cloud dependence and cost.
- ☐ More packs (AoE IV, Rocket League offline, fighting-game training mode), each with a policy review.
