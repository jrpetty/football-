# Sightline: plan (v2)

> Status: implemented. What is verified, and what is not, is tracked in
> [TODO.md](TODO.md).

**Product:** a screen-only AI gameplay coach. Windows 11 first; the
architecture is portable to macOS and Linux.

**Use cases:**
- live training coaching in single-player and offline-vs-AI play;
- post-match review everywhere else.

v1 (already built) proved the local, deterministic pipeline:
- pixel detectors;
- an episode/evidence engine;
- a policy governor;
- local TTS;
- an Electron shell with a consent-gated picker and a network lock;
- 60 tests.

v2 makes the coach **useful across many games** and adds:
- an optional cloud vision model (the player's own Anthropic API key);
- game packs for EA SPORTS FC and Age of Empires II, plus a generic pack;
- spatial and mistake analyses;
- match history;
- "ask the coach" by text or voice;
- a publisher-policy registry that fails closed.

## Feasibility: "as many games as possible"

It is possible at **two depths**, and the product must be honest about which
one is active.

| Depth | How | What it can say | Confidence |
| --- | --- | --- | --- |
| **Generic (any game)** | Cloud vision model reads sampled frames against a generic schema: game phase, deaths/eliminations, round and match results, scoreboard, visible health, and minimap area when one is visible | "You died 7 times; 5 were in the first 30 s of a round", "Most deaths were in the north-west of the minimap", match results over time | Moderate, and lower for spatial claims. No game-specific advice beyond general principles |
| **Game pack** | A maintained JSON pack per game: what to read, which state keys matter, zone layout, mistake rules, coaching concepts, and modes plus policy keys | FC: where goals are conceded (pitch zones), how they were conceded, pass-accuracy trend from the stats screen, turnovers by zone (sampled, partial). AoE2: housed time, idle villagers, floating resources, age-up times, trends | Higher; every rule is explicit and testable |

**What is not possible from pixels alone, and is not attempted:**
- every pass or every death. Frames are *sampled* (default every 2–4 s), so
  brief events are missed; analyses say "sampled";
- exact positions;
- hidden information;
- anything the screen does not show.

The deterministic pixel path (v1) remains for packs that define colour
regions. It is cheaper, faster and offline.

## Architecture (v2)

```text
Player picks a window (consent token, single use)       electron/consent.ts, main.ts, SourcePicker
  → FrameSource (screen | synthetic)                      src/capture/sources.ts
  → worker: bounded queue → decode → FrameHealthMonitor   src/worker/host.ts, src/core/frameHealth.ts
      ├─ pixel recognisers (pack signals) → Observations  src/core/detectors, tracker, observations.ts
      └─ VisionSampler decides when to sample a JPEG ──┐  src/core/vision/sampler.ts
  renderer ──IPC──► main: CloudVision (Anthropic SDK) ◄┘  electron/cloud/*.ts (key in OS keychain via safeStorage)
      ◄── validated Observations (never trusted raw)     src/core/vision/contract.ts
  → VisibleStateFuser (TTL expiry, conflicts, stale)       src/core/visibleState.ts
  → EventTimeline (append-only) + phase→boundary detector src/core/engine.ts, src/core/analyses/boundaries.ts
  → Analyses:                                             src/core/analyses/*
       episodes/patterns (v1) · zone heatmaps · sustained-condition mistakes
       · score-change goals · match stat records
  → PatternStore (session + opt-in long-term, decay,     src/core/memory/*.ts, electron/storage.ts
       dismiss/dispute/delete/export, encrypted at rest)
  → PolicyRegistry → PolicyStatus (fail closed)           src/core/policy/registry.ts + src/policy/registry.json
  → Governor (category × delivery window × status × mode) src/core/policy/governor.ts
  → Planner → CoachingMessage (templates; spoken coaching   src/core/planner.ts, src/core/language.ts
       is never AI-generated)
  → Speech: priority queue, pre-emption, expiry, captions src/speech/speech.ts
  → Ask the coach: text or push-to-talk (local STT) →     src/speech/recorder.ts, electron/stt/*, src/core/qa/ask.ts
       local answers for known intents, Claude for open questions, deferred during
       restricted play
  → UI: live dashboard, heatmaps, mistakes, patterns, review, history, policy, settings
```

### Where truth lives

- **Visible state** comes only from Observations: pixel recognisers or a
  schema-validated vision response. Each carries confidence, freshness and
  evidence.
- **The language model is never the source of truth.** It may *describe a
  frame* into a fixed schema; the validator drops unknown types, clamps
  confidence, and rejects values outside the pack's declared ranges. It may
  also *phrase* a post-match summary or *answer a question* from a structured
  digest, labelled as AI-written.
- **The policy decision never depends on the model's output.** It depends on
  the registry, the player's selected mode, the tournament toggle, and the
  delivery window.

### Policy model

`PolicyStatus = approved_for_scope | noncompetitive_only | post_match_only | needs_publisher_review | disabled`

The status is resolved from `src/policy/registry.json` by (game, mode), most
specific entry first:
- an entry older than `reviewMaxAgeDays` (180) is downgraded to
  `needs_publisher_review`;
- an unknown game or mode gets `needs_publisher_review`;
- the tournament toggle forces `disabled`.

**Delivery windows:**
- `during_play`;
- `round_break`;
- `post_match`;
- `idle` (capture stopped).

**Message categories:**
- `descriptive`;
- `reflective`;
- `post_event`;
- `training`;
- `live_tactical`.

| Status | during_play | round_break | post_match / idle |
| --- | --- | --- | --- |
| approved_for_scope | training, live_tactical, descriptive (Training mode only) | reflective | all |
| noncompetitive_only | as above **if** the selected mode is single-player and non-competitive, else as post_match_only | as left | all |
| post_match_only / needs_publisher_review | nothing (questions are deferred) | nothing | post_event, reflective, descriptive |
| disabled | nothing | nothing | nothing |

The player's coaching mode can only narrow this further. For example,
competitive-safe never speaks during play, and silent never speaks at all.
VALORANT is `post_match_only`, per Riot's published third-party application
policy.

### Cloud vision (opt-in)

- **Off by default.** Turning it on shows exactly what is sent: downscaled
  JPEGs, or only the pack's declared crops when it has them, at the sampling
  interval.
- **Key storage.** The key is encrypted with Electron `safeStorage` (DPAPI on
  Windows), lives in the main process only, and the renderer never sees it.
- **Network.** The only network code in the app lives in `electron/cloud`. It
  uses `baseURL: https://api.anthropic.com` explicitly, and the renderer
  remains blocked from the network.
- **Model.** `claude-opus-5-5` by default (Anthropic's current default), with
  structured outputs (Zod), `effort: low` for live frames, and server-side
  `fallbacks: "default"`. The model is user-selectable, and the settings show
  an *estimated* cost per hour. Opus 5.5 is not cheap at a 3 s interval;
  Haiku 5.5 is much cheaper. This is the player's choice, not a hidden
  downgrade.
- **Limits.** A per-session spend cap is enforced in main. At most one request
  is in flight, with backoff on 429 and 5xx errors and change-detection
  skipping of near-identical frames.
- **Latency.** Vision observations arrive 1–5 s late. Their freshness is
  measured from the frame's capture timestamp, and live cues built on them
  respect each pack's `liveFreshnessMs`.

### Ask the coach

- **Input.** Typed, or push-to-talk: the microphone is used only while the
  button or key is held or toggled. Transcription runs locally in a worker
  thread of the main process: sherpa-onnx (WASM) with the Moonshine tiny
  English model, downloaded once on explicit opt-in from GitHub releases and
  checked against a pinned SHA-256. Hugging Face and the jsdelivr CDN were
  unreachable from the build environment, which ruled out transformers.js
  with Whisper.
- **Local answers.** Known intents are answered from structured data without
  any API: where do I concede or die most, my mistakes, how am I doing, what
  patterns do you see.
- **Claude answers.** Open questions go to Claude with a structured session
  digest, the game pack's concepts, the player's notes and goals, and the
  cached research brief. Answers are short and separate "seen this session"
  from "general advice".
- **Restricted play.** During `during_play` under a restricted status, the
  question is queued and answered after the match. The player is told why.

### Game research

The "Research this game" button calls Claude with the web search tool. It
produces a cited brief covering fundamentals, common mistakes, what good play
looks like, and patch-sensitive notes, dated and stored locally. The brief
informs answers and summaries but is never treated as visible fact.

### Storage

- **Encryption.** JSON files with schema versions and migrations. Files
  holding player data are encrypted with `safeStorage` when available;
  otherwise the UI says they are plaintext. SQLite was considered: Electron
  needs a native rebuild for `better-sqlite3`, and the data is small
  (hundreds of records), so JSON is simpler and safer for the MVP.
- **Opt-in only.** Long-term patterns and match history are saved only with
  the opt-in. They can be viewed, dismissed, marked wrong, deleted, and
  exported as JSON.
- **Decay.** Old evidence halves in weight every 30 days. Patterns are
  segmented by game, mode and pack major version.

## Stack decision (vs the brief's Tauri recommendation)

**Kept: Electron 44 + React + TypeScript.** It is already built and verified.

- **Capture parity.** On Windows, Chromium's window capture is Windows
  Graphics Capture under the hood: the same OS API the brief prefers, with no
  hooking.
- **The gap.** Electron does not expose WGC's own `GraphicsCapturePicker`, so
  on Windows the picker is ours. It is built from OS window enumeration, and
  consent is enforced in main: single-use, 15 s, only offered sources.
- **Closing the gap.** A small native module wrapping `GraphicsCapturePicker`
  (TODO P2) would close it without changing the rest. Tauri would also need a
  native WGC binding, so switching shells does not remove that work.
- **Isolation.** Native capture and ML sit behind interfaces
  (`FrameSource`, `SpeechToText`, `VisionClient`), so they can be replaced.

## Design system

- **Theme.** Dark-first with tokens in `styles.css`: deep slate surfaces, teal
  accent, red reserved for "capturing / stop" and the live badge.
- **Fonts.** System fonts (no network fonts).
- **Accessibility.** Visible focus, ARIA live regions for captions, and
  reduced motion.
- **No overlay.** Nothing is drawn over the game.

## Risks

1. **Vision accuracy.** Zone estimates from a broadcast camera are
   approximate. This is mitigated by confidence, a "sampled" label, minimum
   counts before claims, and no claims from a single event.
2. **Cost.** Opus-class vision at short intervals is expensive. This is
   mitigated by a cap, change detection, crops, and a cost estimate in the UI.
3. **Policy drift.** Registry entries expire after 180 days and require
   sources; unknown means fail closed.
4. **Voice input.** The STT model download could not be verified from the
   build environment, because Hugging Face is blocked here. The typed path
   always works.
5. **Windows-specific behaviour is untested here:** HDR, exclusive
   fullscreen, capture-excluded windows. Frame-health checks surface
   black/frozen frames instead of guessing.

## Scoped MVP acceptance

The checklist from the brief is tracked in [TODO.md](TODO.md). Each item is
marked done only when tested, and verification notes are in
[docs/QA.md](docs/QA.md).
