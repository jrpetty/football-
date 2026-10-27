# Sightline Coach

**A screen-only AI gameplay coach.** It watches only the window you choose to
share and remembers what visibly happens across your games. It tells you
where things go wrong, such as where you concede, where you lose the ball,
when you get housed or leave villagers idle, and which habits go with worse
outcomes. You can talk to it.

It observes pixels, never game internals:
- no game memory, packets, injection, input or overlays;
- it says how sure it is, and describes patterns as associations, not causes;
- **it stays within what each game's publisher allows**: live tips only where
  a reviewed policy permits them, post-match review everywhere else, and it
  fails closed.

> **Status:** v2. It is a Windows-first Electron app, built and tested on
> Linux; Windows hardware validation is still to do.
>
> **Game packs:**
> - EA SPORTS FC and Age of Empires II: DE, both of which need cloud vision;
> - a generic pack for any other game;
> - *Synthetic Arena*, a fictional HUD for demos and tests.
>
> **Not yet verified here:**
> - real Anthropic API calls (no key in the build environment);
> - the in-app voice-model download (the recogniser itself is verified);
> - Windows capture edge cases.
>
> See [TODO.md](TODO.md).

## What it does

- **Watches live, with consent.** You pick the game window; a capture is
  granted only for that choice (single use). Sightline warns if you share a
  whole screen.
  - A red indicator, the window title and a **Stop capture** button
    (Ctrl+Shift+F12 from anywhere) show that it is watching.
  - It notices black, frozen or resized captures and a window that stopped
    sending pictures, and stops inferring until the picture is back.
- **Sees more games, in two ways:**
  - *Pixel recognisers*: fast, local and free, for packs that define HUD
    regions.
  - *Cloud vision* (opt-in, your own Anthropic API key): a downscaled frame,
    or only the pack's HUD crops, every few seconds, read by Claude into the
    pack's fixed schema. The answer is validated, so unknown fields are
    dropped and values are clamped. It is only ever treated as an
    observation with a confidence, never as truth.
- **Analyses that players ask for:**
  - **EA SPORTS FC:**
    - goals detected from scoreboard changes (a misread digit is not a goal);
    - where you concede and score from, and how (cutback, cross, through
      ball…), taken from replays;
    - where you lose the ball (sampled);
    - statistics from the end-of-match screen, with trends across matches.
  - **Age of Empires II:**
    - housed time, idle villagers and floating resources;
    - Feudal and Castle Age timing against *your* targets;
    - all from the visible top bar.
  - **Any game (generic):** deaths by minimap area (where visible), round and
    match results over time.
  - **Patterns:** for example, "losing the ball in your own third was
    followed by a goal against in 3 of 5". These are graded (early sign,
    moderate, strong) and come with their counterexamples.
- **Coaching that respects the rules.** There are five modes:
  - Silent;
  - Post-round;
  - Post-match;
  - Training (live tips);
  - Competitive-safe (the default).

  There are four tones, local voices, speech priority with pre-emption, and
  captions for everything said. A **policy registry** decides *when* the
  coach may speak, game by game and mode by mode. Unknown games, unreviewed
  modes, stale reviews and tournament play all fall back to post-match
  review or silence.
- **Ask the coach**, by typing or push-to-talk (Ctrl+Shift+F11):
  - "Where do I concede the most?", "What mistakes am I making?" and "How
    did that match go?" are answered on this computer from what was
    recorded;
  - open questions go to Claude with a compact digest of your data, your
    notes and an optional cited **research brief** on the game;
  - during restricted play, answers wait until the match ends;
  - voice is recognised locally (Moonshine via sherpa-onnx, a one-time
    ~108 MB download), and audio is never stored or sent.
- **Memory you control** (opt-in):
  - match records, pattern counts and notes are kept, encrypted with your OS
    account where available;
  - research briefs (public guidance about a game, not your data) are cached
    as plain JSON and can be deleted;
  - patterns fade with a 30-day half-life;
  - you can mark a pattern wrong or dismiss it (the coach stops using it),
    or delete it;
  - you can export everything as JSON or forget everything;
  - frames, video and audio are never stored.

## Run it

```bash
cd coach
npm install

npm test               # 103 tests (the local speech test is skipped without the model archive)
npm run dev            # browser preview at http://localhost:5174
npm run dev:electron   # the desktop app against the dev server
npm start              # production build + desktop app
npm run profile:check -- path/to/pack.json
```

**Try it without a game or a key.** On the Coach screen, under *Or watch a
demo*, there are three options:
- **FC match replay**: a synthetic 90-minute match in a few minutes, in which
  the scripted player concedes mostly from cutbacks into the left of the box;
- **Age of Empires II replay**: a game in which the player gets housed twice,
  idles villagers, floats resources and reaches Feudal late;
- **Arena simulation**: the pixel pipeline on a fictional HUD.

The replays are the observations a vision model would produce, with known
ground truth. They are labelled as simulations everywhere.

**Use it on a real game:**
1. Open **Settings**, add your Anthropic API key, turn on cloud features and
   pick a vision model.
2. Choose the pack and your mode on the Coach screen.
3. Choose the game window.

Cloud vision costs money on your Anthropic account. The settings show an
estimate per hour for each model, and a per-session spending cap stops all
calls when it is reached. Claude Opus 5.5 is the default because it reads
busy screens best, but it is also the most expensive; Claude Haiku 5.5 costs
a small fraction of that.

## Where live tips are allowed (summary)

| Game | Offline vs CPU/AI | Online / ranked / multiplayer |
| --- | --- | --- |
| EA SPORTS FC | Live tips (internal policy, needs sign-off) | Post-match only (needs publisher review) |
| Age of Empires II: DE | Live tips (internal policy, needs sign-off) | Post-match only (needs publisher review) |
| VALORANT | Post-match only | Post-match only |
| League of Legends | Post-match only | Post-match only |
| Any other game | Live tips in declared offline single-player only | Post-match only |
| Any game, tournament switch on | No output | No output |

Details and sources: [POLICY_REGISTRY.md](POLICY_REGISTRY.md). **Every entry
still needs human review before release.**

## Layout

```
coach/
  electron/        main process: consent, network lock, encrypted storage, cloud (key, Claude calls), local STT
  shared/          bridge contract, IPC channels, cloud types
  src/core/        engine: observations, visible-state fusion, frame health, analyses, episodes, evidence,
                   pattern memory, policy registry + governor, planner, language, Q&A, vision contract/sampler/cost
  src/policy/      registry.json (publisher-policy records)
  src/profiles/    game packs (JSON, validated at load)
  src/worker/      analysis worker + testable host (frame queue, vision sampling relay)
  src/capture/     screen, simulation and replay sources
  src/speech/      local TTS with priority; push-to-talk recorder
  src/sim/         Synthetic Arena simulator; FC and AoE vision replays
  src/ui/          React UI (coach setup + live dashboard, insights, ask, history, timeline, settings, policy)
  test/            node:test suites
  docs/            architecture, privacy & integrity, pack authoring, QA guide, roadmap
```

## Read next

- [plan.md](plan.md): the v2 architecture, feasibility (generic depth vs pack depth) and risks
- [TODO.md](TODO.md): what is done, what is implemented but unverified, what is next
- [POLICY_REGISTRY.md](POLICY_REGISTRY.md): policy status by game and mode, with sources and review state
- [docs/QA.md](docs/QA.md): the manual QA guide
- [docs/PRIVACY_AND_INTEGRITY.md](docs/PRIVACY_AND_INTEGRITY.md)
- [docs/PROFILE_AUTHORING.md](docs/PROFILE_AUTHORING.md)
