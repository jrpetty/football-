# Privacy and competitive integrity

## What Sightline can touch

| Capability | Status | Enforced by |
| --- | --- | --- |
| Pixels of a window or screen the player picked | Yes | OS/Chromium display capture. Single-use consent in `electron/consent.ts` and `electron/main.ts`. A whole-screen share is warned about, in the picker and for the whole session |
| Game process memory, files, modules, handles | **No** | No such API is used or exposed. The renderer is sandboxed and has no Node |
| Renderer network access | **No** | Every `http(s)`/`ws(s)` request from the page is cancelled in `hardenSession()`; the CSP is `connect-src 'self'` |
| Network from the main process | **Only if the player opts in** | Three services, all off by default: (1) cloud features, which call `api.anthropic.com` with the player's own key, with a fixed `baseURL`; (2) one-time model downloads (speech recognition and natural voices) from GitHub releases, each checked against a pinned SHA-256; (3) on a click of **Download** for an official overhead map: the map's guide page named in the pack and the images on it, https only, on that site family only (`callofduty.com`, `activision.com`, or the community page `mw4camos.com`), size-capped; (4) AoE IV only, once the player has found themselves on AoE4World (History → "Is the coach reading your games right?"): `aoe4world.com` over https, after a match and never during one, to fetch the public summary of that match. The address carries the player's AoE4World profile number (and, for the search, the name they typed); redirects off the site and replies over 8 MB are refused (`electron/aoe4world.ts`). With the profile linked: the player's public games list (up to 200 games) and the summaries of their recent games, one request every 1.5 s, at most 30 per sync, each fetched once. For a build order, on request: one game the player pastes, or AoE4World's public list of recent Ranked games and the summary of one of them. Nothing else about the player is sent |
| A local listener for Counter-Strike 2's Game State Integration | **Only if the player turns it on** (Settings → Game data) | `electron/gsi.ts`: an HTTP listener on 127.0.0.1 only (never the network), off by default, that accepts an update only with this computer's random token, refuses anything over 512 KB, and passes updates to the window. Nothing is sent to the game. The cfg file the game reads is saved by the player through a save dialog; no other game file is touched. While playing, the game sends only the player's own state (no positions, no other players) |
| Keyboard/mouse input, automation, aiming | **No** | No input API is used and no keyboard hook. Three global keys (you choose them) only start and stop talking, mark a moment, and stop capture. A held talk key is told from a tap by the operating system's own repeated reports of that one key; no other key is seen. See [ANTI_CHEAT.md](ANTI_CHEAT.md) |
| Drawing over the game | **No** | The app has one normal window. Nothing is ever composited onto other windows |
| Camera | **No** | The permission handler refuses it |
| Microphone | **Only with voice input on.** With the talk key, only while talking. With **Always listening** (opt-in), while the app runs | Permission is granted for audio-only requests, and only while `voiceInput` is on. With the talk key, the stream closes when recording stops (at most 15 s). With Always listening, the stream stays open until you switch it off, pause it (top bar) or close the app. Speech is found by loudness on this computer and checked by a local voice detector (Silero), which drops game sound and music before anything is transcribed; each stretch is transcribed locally and then dropped. Only a question (with the wake word on, speech that starts with "Coach") is acted on, and only its words go to Claude, as with a typed question |
| Hidden information (fog of war, enemy positions off-screen) | **No** | Only what is on screen is read. The vision prompt and the validator accept only the pack's declared, visible fields |

## Data lifecycle

| Data | Where | How long |
| --- | --- | --- |
| Captured frames | Video element → ImageBitmap → worker → discarded | Milliseconds. At most 2 in flight; dropped (and `close()`d) when analysis falls behind |
| Sampled frames for cloud vision (opt-in) | Downscaled JPEG (or only the pack's HUD crops) → main process → Anthropic API | Not stored by Sightline. Anthropic's API data policy applies to the request |
| Voice input audio (opt-in: talk key or always listening) | Renderer memory → main → local recogniser (worker thread) | Discarded after transcription. Never stored or sent. The last thing heard is shown as text in Settings (memory only) |
| Questions and digests sent to Claude (opt-in) | Main process → Anthropic API | Not stored by Sightline beyond the in-memory conversation |
| The coach's review | Built in renderer memory from the session's counts. **Deeper review with Claude** sends the same structured digest (no images) when you click it | Until the next session or a game change |
| Session timeline, conversation | Renderer memory | Until the next session, a game change, or app close |
| Marked moments (the mark key) | A downscaled screenshot in renderer memory, plus your note | This session only: never written to disk. Deletable one by one; sent to Anthropic only when you ask for a review |
| A question asked during capture (cloud on) | The current screen, downscaled, sent with the question to Anthropic | Not stored by Sightline |
| Learned screens (menus, loading screens, scoreboards) | `userData/knowledge/<pack>.screens.json`: a 64-bit fingerprint, name and one-line summary per screen (~200 bytes each, at most 400). No images | Until "Forget everything" or deleting that game's data |
| Game knowledge base (when you build one) | `userData/knowledge/<pack>.kb.gz`, gzip-compressed JSON of public web research, with sources | Until deleted on the Ask screen |
| Neural voice and speech models | `userData/models/…`, downloaded once on request, checksum-verified | Until removed in Settings |
| Settings, cloud settings | `userData/settings.json`, `userData/cloud.json` | Until changed. There is no key in these files |
| API key | `userData` key file, encrypted with OS `safeStorage`. If the OS cannot encrypt, it is held in memory for the session only | Until removed in Settings |
| Match records (including per-match fight-factor counts and benchmark readings: numbers only), pattern counts, notes (opt-in, off by default) | `userData/history/`, `tendencies/`, `notes/`, encrypted with `safeStorage` where available (the UI says which) | Until deleted per game, per pattern or per match, or with "Forget everything". Exportable as JSON |
| Maps stitched from the minimap (packs with a minimap) | `userData/knowledge/<pack>.maps/<map>.json.gz`: the minimap's own picture of each map (grey levels), and the callouts and heights placed on it. No frames, no other part of the screen. Demo maps are never saved | Until "Forget everything", forgetting that game's data, or forgetting the map in Settings → Minimap |
| Official overhead maps (packs with maps) | `userData/knowledge/<pack>.official/<map>.png`: the published tactical map images the player imported or downloaded, re-encoded as PNG. Public artwork, not player data; never bundled with the app and never uploaded. Placements of stitched maps on them are kept with the stitched maps | Until removed in Settings → Official overhead maps |
| Counter-Strike 2 game data (Game State Integration, opt-in) | Main process → window → worker, while capturing only | Not stored as such. What the coach draws from it (each round's buy, kills, damage, death and result, and the player's own grenades carried, thrown and held at death, times flashed and caught fire) is kept in the match record when match history is on. `userData/gsi.json` holds only whether the listener is on, its port and its token |
| The football radar (EA SPORTS FC) | Read from the captured frame in the worker, on this computer | The frame is discarded as usual. The match record (when history is on) keeps numbers: average shapes, heat counts, events with their pitch positions, and the last seconds before each goal as positions only |
| HUD digit shapes (Settings → "Read numbers on this PC") | `userData/knowledge/<pack>.hud.json`: where the player marked each number, and the learned shape of each digit as 12×12 grey levels (a few kilobytes). No frames or screenshots | Until "Forget everything" or deleting that game's data |
| AoE4World history (AoE IV, linked profile; "Remember my games") | `userData/aoe4world/aoe4.json`, encrypted like the match history: per public game the date, map, civilisations, result and rating, and for recent games the villager counts at 3–15 minutes, age-up times, the build (times of buildings, units and technologies) and the opponent's three most-made units. Without "Remember my games", memory only | Until "Forget", or deleting that game's data |
| Practice plan ("Remember my games") | `userData/practice/<pack>.json`, encrypted: the foci, targets and each game's number | Until ended, "Forget", or deleting that game's data |
| Build orders | `userData/builds/<pack>.json`, encrypted: the builds the player saved (from their own games or public AoE4World games) and the one in use per civilisation | Until deleted, "Forget", or deleting that game's data |
| Readings checked against the real game | In the match record (history opt-in): the coach's readings every 20 s (numbers only), and the comparison with AoE4World's summary or with the numbers the player typed. AoE4World's raw reply is not kept | With the match record |
| Usage history (cloud calls only) | `userData/usage.json`, plain JSON: per day, feature, model and game, the number of calls, token counts and estimated USD. No prompts, screens or answers | 400 days, kept whatever the memory setting (it is a spending record); cleared with "Forget the usage history" on the Usage & cost page |
| Research briefs (when the player runs research) | `userData/research/<pack>.json`, plain JSON. Public web guidance about the game, not player data | Until deleted |

- Stored files are written atomically and validated on read.
- Pack ids are pattern-checked, which prevents path traversal.
- The main process refuses to save player data unless the stored setting
  says the player opted in, whatever the renderer asks.

## Competitive integrity policy

1. **When the coach may speak is decided by the policy registry**
   ([POLICY_REGISTRY.md](../POLICY_REGISTRY.md)), per game and mode, and it
   fails closed:
   - an unknown game or mode, "not sure", or a stale review → post-match
     review only;
   - tournament play → nothing at all.
2. **Live tips exist only in Training mode**, and only where the resolved
   policy includes live play. Today that means:
   - offline play against the CPU/AI in the FC and AoE II packs, which is
     Sightline's internal policy and needs sign-off;
   - a declared offline single-player mode of an unlisted game;
   - the fictional Synthetic Arena.
3. **VALORANT is post-match only in every mode.**
4. Everywhere else:
   - live tips become notes for the review;
   - reflective speech happens only at a visible (or player-marked) round or
     match end;
   - speech still playing when the next round starts is cut off;
   - answers to the player's own questions are held until the match ends.
5. The OS's permission to capture the screen is **not** the publisher's
   permission. The app says so on the Policy & privacy screen.

### What we have not verified

- **Publisher policies.** No publisher has been asked. Several registry
  entries rest on search excerpts or on Sightline's own judgement; each needs
  human review before release.
- **Anti-cheat compatibility.** Sightline uses the same OS capture path as
  streaming and recording software and never interacts with game processes.
  Whether a given anti-cheat or tournament ruleset permits a coaching app at
  all is a per-title, per-event question.
- **Blocked capture.** Some games block capture (protected or excluded
  windows give black frames) or use exclusive fullscreen. The coach then
  reports that it cannot see, and infers nothing. It does not try to work
  around the block.

## Honesty in what it says

- It reasons only about visible events and records the evidence for each,
  for example "scoreboard 1–1 → 1–2" or "goal replay on screen · confidence
  91%".
- An unknown value is unknown, not zero. Readings expire, and a stale state
  stops all inference.
- **A vision model's answer is an observation, not a fact.** It is validated
  against the pack, carries a confidence, and needs confirmation where it
  matters: a goal needs a scoreboard change read twice, or once with high
  confidence.
- **Claims are graded,** with counterexamples shown. Wording strength follows
  the grade, and templates never use causal or absolute words; a test
  enforces this.
- **AI-phrased answers are labelled,** and are built only from the recorded
  numbers.
