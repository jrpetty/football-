# Sightline Coach

**A screen-only, local-first AI gameplay coach.** It watches only the window
you choose to share, remembers what visibly happens, notices which of your
responses tend to go well or badly, and gives short spoken feedback when the
evidence and your settings allow it.

It observes pixels, not game internals:
- no game memory or packets;
- no injection, overlays or input;
- no network.

It says how sure it is, and describes patterns as associations, not causes.

> Status: **MVP**, Windows-first Electron app. It was built and tested on
> Linux; Windows hardware validation is the next step (see
> [docs/ROADMAP.md](docs/ROADMAP.md)). The only bundled game profile is
> **Synthetic Arena**, a fictional HUD drawn by the built-in simulator. No
> real game's HUD coordinates or publisher policies are invented here.

## What it does

- **Capture with consent.** The system picker on macOS 15+ (and in browsers),
  otherwise Sightline's picker. A capture is granted only for the source you
  just chose (single use). A pulsing indicator, the window title and a
  prominent **Stop capture** button show what is being captured; Ctrl+Shift+F12
  stops capture from anywhere.
- **Five coaching modes:**
  - *Silent tracker*;
  - *Post-round review*;
  - *Post-match review*;
  - *Training coach* (live tips, only where the profile says the game mode
    allows them);
  - *Competitive-safe*, the default: never live, reflective notes at round or
    match end only.
- **Four tones:** Gentle, Direct, Analytical, High-energy.
- **Voice and pacing:** local voice choice, speed, volume, verbosity, cooldown,
  and a hard mute.
- **Session review:** every visible event with its pixel evidence, every
  inference with its confidence, and every message with why it was spoken,
  held back, or not said.
- **Tendencies:** graded patterns with counts and intervals. Saving them
  between sessions is opt-in (counts only), and you can delete them at any
  time.

## Run it

```bash
cd coach
npm install

npm test               # 60 tests: detectors → policy → end-to-end on synthetic pixels
npm run dev            # browser preview at http://localhost:5174 (simulation enabled in dev)
npm run dev:electron   # the desktop app against the dev server
npm start              # production build + desktop app
npm run profile:check -- path/to/profile.json
```

To see the coach work without a game, choose **Try the simulation** (or
**Simulation at 4×**) on the Coach screen. For live tips, pick *Training
coach* and the *Practice range (single-player)* mode. With *Ranked* selected,
the same tips are queued for the round review instead.

## Layout

```
coach/
  electron/        main process (consent, network lock, storage), sandboxed preload
  shared/          bridge contract and IPC channel names
  src/core/        the engine: detectors, tracker, events, episodes, evidence, policy, language
  src/profiles/    game profiles (JSON, validated at load)
  src/worker/      analysis worker + testable host
  src/capture/     screen and simulation frame sources
  src/speech/      local-only TTS controller
  src/sim/         Synthetic Arena simulator (tests and demo)
  src/ui/          React UI
  test/            node:test suites
  docs/            architecture, privacy & integrity, profile authoring, roadmap
```

## Read next

- [Architecture and trade-offs](docs/ARCHITECTURE.md)
- [Privacy and competitive integrity](docs/PRIVACY_AND_INTEGRITY.md)
- [Writing a game profile](docs/PROFILE_AUTHORING.md)
- [Plan, status and open questions](docs/ROADMAP.md)
