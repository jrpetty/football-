# Sightline architecture

Sightline is a screen-only gameplay coach. It turns the pixels of a window the
player chose to share into structured, visible events. It learns which visible
responses tend to go well or badly, and it speaks short, evidence-graded
feedback when the player's mode and settings allow it.

## The pipeline, mapped to code

```text
OS capture consent and target selection   electron/consent.ts, electron/main.ts, src/ui/components/SourcePicker.tsx
  → capture adapter                        src/capture/sources.ts (ScreenCaptureSource, SimulatedSource)
  → bounded volatile frame queue           src/core/queue.ts, src/worker/host.ts (+ renderer flow control in src/ui/session.ts)
  → preprocessing and region extraction    src/worker/pipeline.worker.ts (decode), src/core/frame.ts (regions, HSV)
  → visual recognition / event detection   src/core/detectors/detectors.ts, src/core/events.ts
  → visible-state tracker                  src/core/tracker.ts
  → event timeline and outcome evaluator   src/core/episodes.ts (+ timeline entries from src/core/engine.ts)
  → pattern memory and evidence scorer     src/core/patterns.ts, src/core/evidence.ts
  → game knowledge / profile package       src/core/profile/schema.ts, src/profiles/*.json
  → policy and coaching-mode governor      src/core/policy.ts
  → coach planner and language renderer    src/core/engine.ts (planning), src/core/language.ts
  → local text-to-speech engine            src/speech/speech.ts
  → session review UI and optional profile src/ui/views/*, electron/storage.ts
```

`src/core/` is pure TypeScript with no DOM, Electron or Node dependencies. The
same code runs in the Web Worker, in Node tests, and on synthetic frames. That
is the main structural decision: everything that judges what to say can be
tested deterministically, end to end, without a game.

### Processes and trust boundaries

| Process | Has | Does not have |
| --- | --- | --- |
| Electron main | `desktopCapturer`, file storage in `userData`, a global shortcut | any game/process API, input APIs, network (all requests cancelled) |
| Renderer (sandboxed) | `getDisplayMedia` stream, the 12-method `window.sightline` bridge | Node, `require`, filesystem, `window.open`, navigation |
| Analysis worker | frames (transferred ImageBitmaps), the engine | DOM, network, storage |

Only structured results leave the worker: events, episodes, insights,
decisions and utterances. Frames are never posted back, drawn into the page,
or stored. The one exception is the alignment check, which is user-initiated,
takes a single still, shows it in the app window, and discards it on close.

## Key decisions and trade-offs

### Desktop shell: Electron (Windows-first, portable)

- **Chosen:** Electron 44, React and TypeScript, matching this repository's stack.
  Chromium's display capture uses the platform's supported path: Windows
  Graphics Capture / DXGI on Windows, ScreenCaptureKit on macOS, and X11 or
  PipeWire on Linux. macOS 15+ gets the OS picker via `useSystemPicker`.
- **Alternatives:** Tauri plus Rust capture crates. This is lighter, but means
  hand-binding WGC, a separate frame path into the webview, and more
  untestable native code. A native C++/C# app is the most control and the
  slowest to build.
- **Cost:** about 100 MB installed, and Chromium's memory footprint. That is
  acceptable for an MVP, and revisitable because the core is shell-agnostic.
- **Windows caveat:** Windows has no OS-level permission prompt for desktop
  screen capture, and Electron does not expose `GraphicsCapturePicker`. The app
  therefore shows its own picker, built from OS window enumeration. It
  enforces consent in main: a grant is single-use, lasts 15 s, and is valid
  only for a source that was actually offered in the picker. Using the native
  WGC picker would need a native module (see the roadmap).

### Vision: deterministic, profile-driven detectors (no ML model in the loop)

- Three primitives cover a large share of HUDs:
  - **bar fill:** contiguous colour run, track-colour presence and margin trimming;
  - **colour coverage:** with hysteresis;
  - **grayscale template correlation.**
- They are explainable ("health bar 71% → 24% in 0.8s"), fast (~1–3 ms per
  frame at 640–960 px wide in Node), and cannot hallucinate.
- They are designed to say "can't read" rather than guess. A missing bar is
  not an empty bar: without a track colour, a 0% reading is never confident.
  Signals go unknown after 750 ms without a confident reading. Nothing is
  inferred while the core HUD is unreadable.
- **Trade-off:** each game needs a maintained profile with region coordinates
  and colour ranges. That is deliberate (principle 6). A generic vision model
  could describe scenes, but its claims are not verifiable per frame.
- **Not built:** OCR, and a local vision-language model. OCR fits as another
  detector kind behind the same `Reading` interface.

### Learning: episodes and graded evidence, not frame chatter

- An *episode* is: profile-defined trigger → the first visible response before
  the outcome → the first visible outcome within a window.
- Episodes are excluded from evidence, but kept in the timeline, when:
  - a round boundary cut them short;
  - the HUD was unreadable for more than 40% of the window;
  - the session ended while they were still open.
- Each episode type compares responses pairwise. A grade needs all of the
  following to clear its threshold:
  - a Beta-posterior probability;
  - minimum cases on both sides;
  - a minimum count of bad outcomes;
  - a minimum rate difference;
  - for moderate and strong, non-overlapping Wilson intervals at 80% and 90%.
- Grades gate what may be said. Weak evidence is only voiced as "Early sign,
  not settled yet" in detailed verbosity. Live tips need moderate evidence or
  better.
- Verified against ground truth. With the simulator, the planted pattern (heal
  at low health → fewer eliminations) reaches moderate, then strong. The
  unplanted one (escape ability → no effect) never reaches strong across six
  seeds (`test/engine.e2e.test.ts`).
- **Limits:** this is association. Situations confound choices: a player may
  heal only when they are already safe. The UI and the analytical tone say so.
  With many episode types, a multiple-comparison correction will be needed.

### Language: templates, not an LLM

- Every sentence is a template filled with counts the coach actually observed.
  Strength words are tied to the grade, and four tones change the delivery,
  not the claim.
- A test sweeps every tone and grade for causal or absolute words ("caused",
  "because", "always", "definitely") and enforces word limits:
  - live cues: 12;
  - round reviews: 45;
  - match summaries: 90.
- **Trade-off:** less varied phrasing. An optional local LLM paraphraser could
  sit after the governor later, constrained to the template's facts.

### Policy governor

Every candidate message gets exactly one of four decisions, each with its
reasons:

- **spoken**;
- **queued** (a live tip deferred to the reflective review);
- **text-only**;
- **suppressed**.

The gates are applied in this order:

1. Word limit.
2. Effective mode. Training is downgraded to competitive-safe unless the
   profile mode is single-player and non-competitive, or has documented
   approval with a source and a review date.
3. Evidence grade.
4. Visual confidence.
5. Freshness. A live tip expires with the moment it refers to.
6. Mute.
7. Cooldown.
8. Topic repetition window.
9. Per-minute cap.

Outside training, a reflective message still playing when the next round
starts is cancelled.

### Speech: platform voices, local only

The Web Speech API is used, filtered to voices with `localService === true`.
On Windows these are the SAPI/OneCore voices. If no local voice exists, the
coach falls back to text and never to a cloud voice. Playback is one utterance
at a time, the backlog is at most two, expired utterances are dropped, and
everything is interruptible (mute, Quiet, Stop capture).

**Trade-off:** voice quality depends on the OS. A bundled neural TTS such as
Piper is the upgrade path; it is not built.

## Performance (measured in Node in the development container; not yet measured on Windows hardware)

| Analysis width | Engine time / frame |
| --- | --- |
| 256 px | ~0.5 ms |
| 640 px | ~1.0 ms |
| 960 px | ~3.0 ms |

Capture samples at 5 fps and downscales to at most 960 px wide. Analysis is
not the bottleneck. With the worker busy, the source skips sampling (at most
two frames in flight), and the worker queue drops the oldest frame. Latency
stays bounded instead of growing.

## Testing

`npm test` runs 60 tests (Node's test runner, TypeScript run directly):

- **Vision:** detector accuracy, missing-bar handling, smoothing, staleness,
  and rule semantics.
- **Reasoning:** profile validation, episodes and exclusions, statistics,
  grades, memory merge and version gating, and language guardrails.
- **Policy:** the mode × game-mode matrix, and every gate.
- **End to end:** simulated pixels in, decisions out, checked against
  simulator ground truth.
- **Pipeline:** queue, bitmap release, and error containment.
- **Speech:** local-only voices, expiry, interruption.
- **Shell:** consent, storage validation, channel parity.

The packaged Electron app was also driven with Playwright under Xvfb. That
run checked:

- no Node globals in the renderer, blocked `fetch`, blocked `window.open`;
- capture denied without consent, a malformed source id rejected;
- a full picker → capture → analyse → stop flow, with the OS window title
  showing capture state.
