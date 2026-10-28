# Manual QA guide

Run this guide on **Windows 11** before each release; also run it on macOS
where possible. Automated tests cover the engine, policy, storage and cloud
gate (`npm test`), but they cannot cover these:
- real capture;
- real voices and microphones;
- a real API key;
- real games.

Record each result as Pass, Fail or N/A, together with the build, the OS
version and the GPU.

**Setup:**
```bash
cd coach
npm install
npm start
```

Use a fresh user profile for steps 1–3. The data folder is
`%APPDATA%/sightline-coach`. Deleting it resets the app.

## 1. Capture consent

| # | Steps | Expected |
| --- | --- | --- |
| 1.1 | Launch. Do nothing. | The pill reads "Not capturing. Nothing is being watched." The window title has no ● and no capture is running. |
| 1.2 | Coach → **Choose the game window** → Cancel in the picker. | Nothing starts and no error is shown. |
| 1.3 | Choose the game window, select **Windows**, pick the game, then **Share**. | Capture starts. The pill turns red and shows the window name. The OS title bar reads "● Capturing … — Sightline Coach". |
| 1.4 | In the picker, switch to **Screens**. | A warning explains that a whole screen shows notifications and other apps, and recommends the game window. |
| 1.5 | Share a whole screen. | The live dashboard shows the same whole-screen warning for the whole session. |
| 1.6 | Start a share, then close the game window. | Capture stops by itself and the pill returns to "Not capturing". |
| 1.7 | Minimise the shared game window for 10 s. | A warning says the window stopped sending pictures, or that the capture is black or frozen. "Seeing" turns **Stale** and no mistakes or events are inferred during that time. Restore the window: it recovers. |
| 1.8 | Change the game's resolution or window size mid-session. | The session continues. A notice may appear and the state goes stale briefly. Nothing crashes. |
| 1.9 | With HDR on in Windows, share an HDR game. | Note whether the picture looks washed out or black. If the black-frame warning appears, record it: HDR capture is a known risk. |
| 1.10 | Share a protected or DRM window, such as a streaming app. | A black-capture warning appears and nothing is inferred. |

## 2. Stop capture

| # | Steps | Expected |
| --- | --- | --- |
| 2.1 | While capturing, click **Stop capture**. | Capture ends within 1 s, speech stops, the microphone (if open) closes, and the pill returns to idle. The OS screen-sharing indicator disappears. |
| 2.2 | While capturing with the game focused, press **Ctrl+Shift+F12**. | The same as 2.1, without switching to the app. |
| 2.3 | Stop during a spoken message. | The speech is cut off immediately. |
| 2.4 | After stopping, check the data folder. | No image, video or audio files. Besides `settings.json` and `cloud.json`: with memory on, `history/`, `tendencies/` and `notes/` JSON files, encrypted (`"enc": "safeStorage"`) where the OS supports it; `research/` holds plain-JSON research briefs only if you ran research. |

## 3. Speech (TTS) and mute

| # | Steps | Expected |
| --- | --- | --- |
| 3.1 | Settings → Voice output → **Play sample**. | A local voice speaks. If no local voice is installed, a warning explains how to add one, and messages appear as text only. |
| 3.2 | Click **Voice on** in the top bar to toggle it to **Muted** while a message plays. | Speech stops immediately. New messages still appear as captions, marked "Muted"/"Shown, not spoken". |
| 3.3 | Run a demo in Training mode (Arena simulation, *Practice* mode). | Tips are short, spaced by at least the cooldown, never repeated within a few minutes, and each has a caption with "Based on: …". |
| 3.4 | Click **Quiet** while a message plays. | Only the current speech stops; the mode is unchanged. |

## 4. Stale-state suppression

| # | Steps | Expected |
| --- | --- | --- |
| 4.1 | AoE II (with a key): open a menu or pause screen that hides the top bar for 30 s. | The values expire from "What I can see" and the state turns Stale with a reason. No housed or idle mistake is counted during that time. |
| 4.2 | With cloud vision on, disconnect the network for 30 s. | A "Cloud vision: …" warning appears once, sampling backs off, and no new events arrive. Reconnect: it resumes. |
| 4.3 | Arena simulation: drag another window over the shared game window (window capture usually still sees the game; screen capture does not). | With a screen share, "Cannot read the HUD" appears and nothing is inferred while it is covered. |

## 5. Competitive-safe and policy gating

| # | Steps | Expected |
| --- | --- | --- |
| 5.1 | Choose VALORANT in the generic pack ("Any other game" → VALORANT) in any mode, including offline. | The policy badge reads "post-match review only". In Training mode a downgrade notice explains why. Nothing is spoken during play. |
| 5.2 | EA FC → *Ultimate Team: Rivals / Champions*, Training mode. Run the FC replay demo with that mode selected. | Nothing during play. One post-match summary at full time naming the hot spot ("…inside the box, left side…"). |
| 5.3 | EA FC → *Kick-Off vs CPU*, Training mode, FC replay. | Brief training tips during play are possible; the status reads "brief live tips are on". |
| 5.4 | AoE II → *Ranked 1v1*, Training mode, AoE replay. | The mistakes are counted live on the dashboard but **not spoken** until the match ends. |
| 5.5 | Turn on **Tournament or event play** on the Coach screen, then run any demo. | The policy badge reads "coaching disabled". While capturing, nothing is spoken or shown as coaching; a question asked during capture is marked "Not delivered". |
| 5.6 | During restricted play (5.2), ask "Where do I concede the most?". | The answer is marked **Held** ("Held until the match ends: …") and delivered after full time as "Earlier you asked: …". |
| 5.7 | Policy & privacy → Policy registry. | Every entry shows its status, basis, sources (with verification type) and review date. |
| 5.8 | Leave the mode as "Not sure (treated as competitive)". | Post-match review only. |

## 6. Ask the coach and voice input

| # | Steps | Expected |
| --- | --- | --- |
| 6.1 | After an FC replay, ask "Where do I concede the most?" with no key. | An exact local answer with counts ("Of 4 goals you conceded I saw, 4 could be placed…"), marked "Answered on this computer". |
| 6.2 | Ask an open question with no key ("Should I play 4-3-3?"). | It explains that open questions need cloud features. No error. |
| 6.3 | With a key and cloud on, ask the same open question. | The answer is marked "AI-phrased" and shows the model used and its estimated cost. It cites only recorded numbers. |
| 6.4 | Settings → Voice input → on → **Download speech model**. | The progress bar fills, then shows "Speech model installed". |
| 6.5 | Click the top-bar **Talk** button (or press your talk key, F8 by default, with the game focused), ask a question and press again. | The OS microphone indicator is on **only** while recording. The text is transcribed and answered. |
| 6.6 | Turn voice input off, then try to use the microphone (Talk button hidden, shortcut pressed). | The microphone is refused, with a message pointing to Settings. |

## 7. Cloud vision (key required)

| # | Steps | Expected |
| --- | --- | --- |
| 7.1 | Settings → paste an invalid key. | Rejected with "That does not look like an Anthropic API key". |
| 7.2 | Paste a valid key. | Shows `sk-ant-…1234` and "saved, encrypted by your OS". The key file in the data folder is not readable text. |
| 7.3 | Turn on cloud vision, pick Haiku, play 10 minutes of FC Kick-Off. | The status strip shows frames sent, latency and spend. The scoreboard tracks the real score. Goals appear in the zone maps after replays. |
| 7.4 | Set the cap to $0.50 and use Opus. | When the cap is reached, a warning appears and calls stop for the session. |
| 7.5 | Remove the key. | Cloud calls stop and the status reads Off. |

## 8. Memory, data deletion and export

| # | Steps | Expected |
| --- | --- | --- |
| 8.1 | With memory **off**, finish a replay, then open History. | "Only this session's matches are shown and nothing is kept". After a restart, history is empty. |
| 8.2 | Turn memory on and finish two FC replays. | History shows 2 matches, a stats trend, and combined zones. "Last session" says what was saved. |
| 8.3 | In Insights → a pattern, click **That's wrong**. | The pattern is greyed out and marked "You marked this wrong", and is never used for coaching. **Restore** undoes it. |
| 8.4 | Click **Delete its history** on a pattern. | It disappears and does not return after a restart. |
| 8.5 | History → **Export everything (JSON)**. | A JSON file downloads containing matches, patterns, notes and research, and no API key. |
| 8.6 | History → **Delete EA SPORTS FC data…** → Delete. | FC history, patterns, notes and research are gone; other games are untouched. |
| 8.7 | Settings → **Forget everything…** → Delete everything. | All player data is gone; settings are kept. |
| 8.8 | Ask → Context: save notes with memory off. | Save is disabled, with an explanation. |

## 9. Installer, first run, voice and keys

| # | Steps | Expected |
| --- | --- | --- |
| 9.1 | Download the installer artifact from the **Sightline coach Windows installer** workflow and run it. | SmartScreen warns about an unknown publisher (unsigned). The install finishes with Start-menu and desktop shortcuts, and the app opens. |
| 9.2 | First launch. | The setup opens: Welcome → Connect Claude → Your game → Voice → Ready. **Skip setup** closes it, and it does not return. |
| 9.3 | Connect Claude with a valid key → **Save & test**. | "✓ Connected", and cloud features switch on. An invalid key gives a clear error. |
| 9.4 | Voice step → **Download** (Jenny), then **Hear it**. | Progress reaches 100% and a natural British voice speaks. Settings → Coach voice shows "Fast on this PC" after a few phrases. |
| 9.5 | Settings → Coach voice → download Kokoro → pick Emma, George and so on. | Each voice previews on click. If the PC is slow, a warning suggests Jenny. |
| 9.6 | Settings → Keyboard → click the talk key and press **F7**. | The keycap shows F7. With a game focused, F7 starts and stops talking. A key already used by another program shows a warning. |
| 9.7 | Try to bind a bare letter (e.g. **A**). | Refused, with an explanation (it would trigger while typing in chat). |
| 9.8 | With voice input on and the model installed, restart the app and press the talk key at once. | The first question is transcribed without a long pause (the model is pre-loaded). |

## 10. Screen understanding, questions and decisions (key required)

| # | Steps | Expected |
| --- | --- | --- |
| 10.1 | MW4 or AoE IV with cloud on: go through main menu → lobby → loading → match. | The status strip's **Screen** shows sensible names ("Main menu", "Loadout editor", "Loading screen", "In match"). |
| 10.2 | Go back to a menu seen before. | **Screen** shows "· known", and the vision call count does not increase while you stay there (it rechecks once a minute). |
| 10.3 | In a campaign or bot match, press the talk key and ask "What should I do here?". | Claude answers about what is actually on screen, and it is spoken. |
| 10.4 | In online multiplayer, ask the same question. | Marked **Held**, then answered after the match. |
| 10.5 | Press the mark key during a fight, then the talk key, and say what you were thinking. | A chime and a "Moment marked" toast. The Decisions tab shows a thumbnail with your note. |
| 10.6 | During online play, open Decisions and try **Review this decision**. | Disabled: "Moment reviews open after the match in this mode". |
| 10.7 | After the match, **Review this decision**. | A spoken and written review: what was happening, whether it was sound, what to try next. |
| 10.8 | Ask → Game knowledge → **Build game knowledge**. | Progress stages appear. A few minutes later it shows items, sources, the game version, and a small compressed size. Browsing shows sections such as maps and roster. |
| 10.9 | Close and reopen the app. | Marked moments are gone (memory only); learned screens and the knowledge base remain. |

## 11. Positioning, benchmarks and the coach's review

| # | Steps | Expected |
| --- | --- | --- |
| 11.1 | Coach → **Modern Warfare 4 replay** (synthetic). Let it finish, then Insights → **Review this session**. | A headline with a count, a "Good:" line, and up to three numbered items, each with a fix and a link into the playbook. **How your fights go** shows cards per factor with lost/won bars; "not readable" counts appear. |
| 11.2 | Click **What to do →** on a finding. | The matching playbook entry opens and scrolls into view, marked **From your games**, with sources and their kind (Pro, Coach, Official, Guide, Forum). |
| 11.3 | **Read it to me**. | The review is spoken once; nothing else talks over it. |
| 11.4 | Coach → **Age of Empires IV replay**, then Insights. | **Against the clock** shows the dashed expected curve, your readings (red where behind), the strong-player reference rings, and "At 10:00 you had 28 villagers…". |
| 11.5 | MW4 private match with bots only, Training mode, cloud on: die in the open several times. | Once the pattern is moderately clear, one short spoken tip ("Fight from cover, not the open."), at most once per match. |
| 11.6 | MW4 online multiplayer, same play. | Nothing during play; the post-match summary leads with how fights were lost, each with its fix. |
| 11.7 | With cloud on, **Deeper review with Claude**. | A review marked as written by the model with its cost; counts match the panels below. If it fails, the local review stays with a note. |
| 11.8 | Real MW4 killcams: compare 10 deaths' tags (Timeline, event details) with what the killcam showed. | Most tags match; unclear killcams are "unknown", not guessed. Record the agreement rate. |

## 12. Push-to-talk and AoE IV battles

| # | Steps | Expected |
| --- | --- | --- |
| 12.1 | Settings → Voice input on, download the speech model. With the game focused, press the talk key, ask "Where am I dying most?", press it again. | A rising tone, then a falling tone. The question appears as "You (voice)" and the answer is spoken in the chosen voice. |
| 12.2 | Turn voice input off and press the talk key with the game focused. | A low error tone and, when not capturing, the coach says that voice input is off. |
| 12.3 | Windows Settings → Privacy → Microphone → block desktop apps, press the talk key. | The coach says the microphone is blocked and where to allow it. |
| 12.4 | Online match: ask a question by voice. | Two even tones (held); the answer arrives after the match. |
| 12.5 | AoE IV vs AI, Training mode: lose two or three fights under the enemy Town Center. | "Pull back out of their arrow range." once the pattern is clear; Insights → Fights this session lists each fight with the army before and at its lowest. |
| 12.6 | AoE IV ranked: play a full game with several fights, then Insights. | The summary says "Fights I could follow: N won, M lost." Unclear fights are labelled unclear. The map and opponent's civilisation show at the top of Insights. |

## 13. Live commentary and scouting

| # | Steps | Expected |
| --- | --- | --- |
| 13.1 | Top bar → 🎙 Commentary on. AoE IV against the AI (Training mode). | Spoken lines as things happen: villager checkpoints, fights starting and ending, scouted buildings. Each line under about 20 words, never two at once. |
| 13.2 | Same with a ranked game. | The timeline says once that commentary cannot speak during play here; nothing is spoken until the match summary. |
| 13.3 | AoE IV: let the loading screen show; then scout the enemy base with your scout. | "Opponent: <civ>. Watch for <unique units>." then "They have a Stable: expect …". Coach view → Their side lists the buildings, what they can make and the answers. |
| 13.4 | Set your civilisation in setup (e.g. English) and meet enemy Spearmen. | The answer names Longbowmen rather than Archers. |
| 13.4b | Feudal Age against English: scout their Barracks. | Spearmen or Men-at-Arms expected, never Wynguard Footmen (Imperial). When their age-up notice shows, "They reached the Castle Age. Now possible: …". |
| 13.5 | Ask "what are they making?" | A local answer from what was seen; nothing invented when nothing was scouted. |
| 13.6 | AoE IV, any civ vs English in Feudal. Scout their Council Hall and Archery Range. | Their side shows a read card (the plan, the evidence it was based on, the response). Spoken once: "Read: … Seen: Council Hall, Archery Range." Before scouting, the panel shows only "Their usual plan", marked as not yet seen. |
| 13.7 | Set your civilisation, then age up. | "<Age> as <civ>: …" is said once per age; Their side shows "Your plan" for your current age, and their power spikes and weak windows. |
| 13.8 | Ask "what's their plan?" and "what should I do this age?" | The answer names the read (with evidence) or says nothing has been scouted that supports one, then your plan for this age. |
| 13.9 | Settings → model estimate; play 5 minutes of AoE IV. | Frames about every 3 seconds; Settings spend grows by roughly the estimate; at the cap all calls stop with a notice. |
| 13.10 | Settings → Screen reading (MW4): Fast · 1 s, during a bot match. | Looks about every second from then on (Session timeline, Cloud vision counts); estimate about 3× the 3-second figure; "Reset to game defaults" goes back to 2.5 s. |
| 13.11 | Same, after 5+ looks. | "Measured this session" shows the cost per look, per hour at this refresh, and tokens per look (image and question, cached reference, written, thinking). |
| 13.12 | Sonnet 5.5 for vision, thinking Off; play 5 minutes. | No errors; measured thinking tokens near zero; compare the readings with a game on Brief. |
| 13.13 | AoE IV vs the AI, memory on: play two games against the same civilisation. | Second game, once their civilisation is read: "Your last game against …" on the timeline (spoken with commentary on); Their side plan cards show "Met in your last game"; Insights → What the coach has learned shows their order, armies and yours. |
| 13.14 | Same in ranked. | Nothing recalled during play; Insights shows it after the match. |
| 13.15 | MW4 vs bots, two matches on one map; FC vs CPU, two matches against one team. | Insights shows what killed you, your weapons' kills and deaths (MW4); how they scored and how you lost the ball (FC). |

## 14. Accessibility and layout

- Every control is reachable by keyboard, with a visible focus ring.
- Screen readers announce capture-state changes (the pill is
  `role="status"`), and captions use `aria-live`.
- No horizontal scrolling at window widths of 1024, 1280 and 1440 px.
- With reduced motion turned on in the OS, there is no pulsing or animation.
