# TODO: outcome-based delivery items

Legend: ☑ done and verified (test or recorded run) · ◐ implemented, not
verifiable in the build environment · ☐ not started

## v1 foundation (done)

- ☑ The player picks a window; capture needs a single-use consent token; Stop capture works and is always visible.
- ☑ A bounded, drop-oldest frame queue; frames are never persisted.
- ☑ Pixel recognisers that report "can't read" instead of guessing.
- ☑ Episode evidence grading; no causal language (guardrail test).
- ☑ Local TTS: local voices only, interruptible, expiring.
- ☑ The renderer has no network access and no Node; display capture is the only permission.

## v2 delivery

### Safety and policy
- ☐ A typed `PolicyStatus` registry with sources, review dates, staleness downgrade and a tournament override. *Outcome:* an unknown, stale or tournament scope never yields live output.
- ☐ A governor keyed on category × delivery window × status × user mode. *Outcome:* competitive-safe and post-match-only scopes block every live message (tested matrix).
- ☐ VALORANT registered `post_match_only` with a Riot source. *Outcome:* no live output is possible for VALORANT in any mode.
- ☐ Questions asked during restricted play are deferred to the end of the match.

### Seeing more games
- ☐ Domain types: `Observation`, `VisibleState`, `CoachingMessage`, `PatternRecord`, `PolicyStatus`.
- ☐ The visible-state fuser handles TTL expiry, conflicts and staleness (paused capture, target change, black or frozen frames).
- ☐ Frame-health monitor: black, frozen and resolution change.
- ☐ Pack schema v2: vision schema, zones, mistake rules, knowledge, modes, window-title hints.
- ☐ Packs: generic, EA SPORTS FC, Age of Empires II DE; the synthetic arena migrated.
- ◐ Cloud vision: opt-in, key in safeStorage, structured output, a validator, a sampler (interval, change detection, one in flight, backoff), and a cost estimate and cap. *Verified with a fake client; real API calls are untested here (no key).*
- ☐ Game suggestion from the window title (and optional vision identify), confirmed by the player.

### Analyses players asked for
- ☐ FC: goals detected from scoreboard changes; conceded-goal zones and build-up type; sampled turnovers by zone; stats-screen match records (possession, shots, pass accuracy).
- ☐ AoE2: housed time, idle villagers, floating resources, age-up times versus the player's targets.
- ☐ Generic: deaths by minimap area, deaths early in a round, results over time.
- ☐ Heatmap and trend views; cross-match history (opt-in).

### Coaching and conversation
- ☐ The planner produces `CoachingMessage`s with category, evidence summary, delivery and suppression reasons, and expiry.
- ☐ Live training tips from mistake rules (single-player only) and pattern cues.
- ☐ Ask the coach: local intents (concede and death zones, mistakes, summary, patterns) without an API; Claude for open questions with a structured digest.
- ◐ Push-to-talk with local Whisper STT (opt-in model download). *Recorder and pipeline tested with a fake engine; the model download is untested here.*
- ☐ Player notes and goals that feed the planner, Q&A and summaries.
- ◐ Game research brief (Claude + web search, cited, dated, cached, deletable).
- ☐ Speech priority with pre-emption, a stop-speaking control, and a caption for every message.

### Memory and privacy
- ☐ Long-term `PatternRecord`s: view, dismiss, mark wrong, restore, delete, export; 30-day half-life decay; segmentation by game, mode and pack major version.
- ☐ Match history: view, delete, export.
- ☐ Player data encrypted at rest with safeStorage when available; the UI states which.

### Experience
- ☐ UI v2:
  - a live dashboard (state, freshness, policy badge, captions);
  - an FC pitch heatmap and an AoE economy panel;
  - the Ask panel with mic;
  - patterns and history management;
  - cloud settings with a disclosure and cost meter;
  - the policy registry viewer;
  - a warning on whole-screen capture.
- ☐ A synthetic replay fixture for FC and AoE observation streams, so the full pipeline is demoable without a key or a game.

### Documentation
- ☐ README, plan.md, TODO.md, POLICY_REGISTRY.md, docs/QA.md.

## Later (P2+)
- ☐ Native `GraphicsCapturePicker` module (Windows OS picker).
- ☐ Windows hardware validation pass (HDR, exclusive fullscreen, capture-excluded windows, DPI).
- ☐ Signed profile and registry updates if remote updates are ever added.
- ☐ Local OCR and a local vision model to reduce cloud dependence.
- ☐ More packs (AoE IV, Rocket League offline, fighting-game training mode), each with a policy review.
