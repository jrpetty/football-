# Privacy and competitive integrity

## What Sightline can touch

| Capability | Status | Enforced by |
| --- | --- | --- |
| Pixels of a window or screen the player picked | Yes | OS/Chromium display capture; single-use consent in `electron/consent.ts` and `electron/main.ts` |
| Game process memory, files, modules, handles | **No** | No such API is used or exposed; the renderer is sandboxed with no Node |
| Network (upload, telemetry, cloud AI, cloud TTS) | **No** | Every `http(s)`/`ws(s)` request is cancelled in `hardenSession()`; CSP `connect-src 'self'` |
| Keyboard/mouse input, automation, aiming | **No** | No input API is used. The single global shortcut (Ctrl+Shift+F12) only stops capture |
| Drawing over the game | **No** | The app has one normal window; nothing is ever composited onto other windows |
| Camera / microphone | **No** | Permission handler refuses every request except display capture |
| Hidden information (fog of war, enemy positions off-screen) | **No** | Only profile-declared HUD regions are read; the profile schema has no way to describe hidden state |

## Data lifecycle

| Data | Where | How long |
| --- | --- | --- |
| Captured frames | Video element → ImageBitmap → worker → discarded | Milliseconds. At most 2 in flight; dropped (and `close()`d) when analysis falls behind |
| Alignment-check still | Canvas in the app window | Only while that panel is open; cleared on close |
| Picker thumbnails | Renderer memory, in the picker only | Until the picker closes |
| Session timeline (events, inferences, decisions) | Renderer memory | Until the next session starts or the app closes |
| Settings | `userData/settings.json` | Until changed |
| Tendency counts (opt-in, off by default) | `userData/tendencies/<profile>.json` | Until “Forget saved tendencies”. Counts only: no timestamps, images, audio or text |

Stored files are written atomically and validated on read. Profile ids are
pattern-checked, which prevents path traversal. Main refuses to save
tendencies unless the stored setting says the player opted in, whatever the
renderer asks.

## Competitive integrity policy

1. **Live tactical tips exist only in Training mode.**
2. Training mode stays live only when the profile marks the selected game
   mode as one of:
   - **single-player and non-competitive**; or
   - **`liveCoaching.status: "approved"`, with a `source` (the publisher's
     policy) and a `reviewedAt` date.** The profile validator rejects
     "approved" without both.
3. In every other case, including "not sure what I'm playing", the governor
   runs **competitive-safe**:
   - Live tips are converted to *queued* notes for the reflective review.
   - Reflective speech happens only at a visible round or match end, or one
     the player marks by hand.
   - Speech still playing when the next round visibly starts is cancelled.
4. A profile can mark a mode `prohibited`, which blocks live tips even for
   single-player play.
5. OS screen-capture permission is **not** publisher permission. The app says
   so on the Privacy screen.

### What we have not verified

- No real game's publisher policy has been researched. The only bundled
  profile is the fictional Synthetic Arena. Each real profile must cite the
  publisher's rules for every mode it marks as approved.
- Anti-cheat compatibility is unknown. Sightline uses the same OS capture
  path as streaming and recording software and never interacts with game
  processes. Whether any particular anti-cheat or tournament ruleset permits
  a coaching app at all is a per-title, per-event question.
- Some games block capture (protected or excluded windows produce black
  frames) or use exclusive fullscreen. The coach then reports "Cannot read
  the HUD" and infers nothing. It does not try to work around the block.

## Honesty in what it says

- It reasons only about visible events, and records the pixel evidence for
  each ("Defeat banner appeared · visual confidence 92%").
- An unknown signal is unknown, not zero. Episodes it could not observe
  properly are excluded from evidence.
- Claims are graded. Wording strength follows the grade, and templates never
  use causal or absolute words. A test enforces this.
