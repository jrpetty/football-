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
| 13.16 | Coach screen, cloud vision on: change "Look every" before and during capture. | The next looks follow the new pace; during capture the reply time shows, with a warning when replies are slower than the pace. |
| 13.17 | Settings → Learning (AoE IV): recall after 3 games; play two games vs English, then a third. | Nothing recalled in the second game; recalled in the third. Learn off: the next game keeps nothing new. Forget: Insights → What the coach has learned is empty; History still lists the games. |
| 13.18 | AoE IV with your civilisation set, two games. | Insights → You: your buildings in order (no starting Town Center); "<your civ> plans you played"; Them: "<their civ> plans you met". |
| 13.19 | MW4 vs bots on a core map, cloud vision on. Walk through several named areas, go upstairs, die twice from the same angle. | Coach → Where you are names the area (and "upper" upstairs) within a few seconds; the second death from the same spot: "2 deaths at … from …: held angle."; death lines say "At …, shot from …". Note any wrong area names. |
| 13.20 | After the match: Insights → The map, learned from watching. | The map drawn with the areas you visited, your routes, a red arrow for the held angle, rings on power positions; 3D lifts upper floors; the table lists time, kills, deaths. |
| 13.21 | Ask "is the M4 good?", "best M4 build?", "what does Ghost do?", "power positions on Rooftops?". | Answers from the pack in plain words; the build only for the second question. |
| 13.22 | MW4 ranked or public match. | Nothing about the map or the held angle during play; Insights says it opens after the match. |
| 13.23 | Cloud on, play 5 minutes of any game, then open Usage & cost. | This session, Today and This month match each other and the top-bar chip; the chart shows today in Screen reading; Where it went lists the game and the model that served the calls. |
| 13.24 | Compare with the Anthropic Console (Usage and Cost pages) for the same day. | Token counts match the Console; cost within a few percent (list prices, no account discounts). Note any difference. |
| 13.25 | Play 20 or more looks of a game, then look at What playing costs. | That game's row says "Your N looks" and its figures match the measured cost per look; other models still say "List prices". |
| 13.26 | Set a monthly budget just above this month's spend, Stop at budget on, then ask a question. | A warning at 80% (page, chip, Coach screen during capture); at the budget the call is refused with "Monthly budget … reached". With stop off, calls go on with a warning. |
| 13.27 | Run Research and Build game knowledge. | The estimate shows before each run; the real cost shows after (toast, Last build) and is recorded under Research and Knowledge building. |
| 13.28 | Forget the usage history. | The history and charts empty; this session's total and cap are unchanged. |
| 13.29 | MW4 vs bots, minimap set to fixed. Start capture, then Settings → Minimap → Align on the screen. | The box sits on the minimap with "Your arrow is at the centre". If not, drag it onto the minimap and save. Note the position your HUD needs. |
| 13.30 | Play a match on one map with cloud vision on; walk most of it. | Coach → Where you are shows the map stitched around you, your dot moving with you. After the match, Insights → The map → Overhead shows the map, recognisably the game's minimap. |
| 13.31 | Die several times; look at the pins after the match. | Each death is where you were when you died (within a few steps). Killers are where the killcam ended (solid line), or roughly at the callout (dashed). Hover shows the time, area, weapon and range. |
| 13.32 | Play the same map again, spawning on the other side. | It finds you on the saved map, or starts a piece that joins up as you cross known ground. The map grows; "Matches" shows both. |
| 13.33 | 3D view of the map. | Buildings stand where the minimap shows them. Upper floors and roofs where you were seen are higher. Beams on deaths, arcs from killers, labels. Orbits smoothly; no errors. |
| 13.34 | Set the minimap to rotating on the Coach screen. | Nothing is stitched. Where you are says to set it to fixed; deaths are placed by area. |
| 13.35 | Ranked match. | No live mini-map during play; the map and its pins appear after the match. |
| 13.36 | Settings → Official overhead maps → Download on a launch map (desktop app). | The official image is kept (a thumbnail, "Kept"); or a clear message to open the page, save the image and import it. Note which maps' pages work. |
| 13.37 | Open page on a map, save its tac map image, drop it on the panel (named after the map), then a screenshot of the game. | The tac map is kept; the screenshot is refused as not a tactical map. A file named after no map asks which map it is. |
| 13.38 | Play a match on a map with its official map kept (fixed minimap). | Coach → Where you are shows the official map with your dot on the right spot within the first minute of walking. Insights → The map: Official view, "100% placed" (or a share with the reason). |
| 13.39 | Die in recognisable spots (a doorway, a window, a container). | Each death pin sits on that spot of the official map. Note the error in metres for each. |
| 13.40 | Check placement. | The stitched map's walls sit on the official walls everywhere you walked. If not: note where, nudge it, and confirm the pins move with it. |
| 13.41 | A symmetric map (Shipment, Library): spawn on one side, then the other. | No piece is drawn on the wrong side; a piece that saw only the shared middle stays unplaced until you reach a part only one side has. |
| 13.42 | 3D view on the official map. | Walls and buildings stand on the official drawing; out of bounds is lower and dimmed; pins and arcs on top; no errors. |
| 13.43 | FC: in the game set Radar 2D, User Radar Colour Blue, Opponent Radar Colour Red; pick the same on the Coach screen. Kick-Off vs CPU; Settings → Radar → Align on the screen. | The still shows the pitch outline drawn on the radar and a dot on each player ("Radar read: … 11 and 11 players"). If not, fit the box and note the position your screen needs. |
| 13.44 | Play a half. Coach → The radar. | Every player and the ball where the radar shows them, your team attacking right (after half-time too). Possession moves with the ball. |
| 13.45 | After the match: Insights → The pitch (Shape, Heat, Lost & won, Goals, 3D). | Shapes look like your formation and the opponent's (note the formation named). Lost balls where you lost them; every goal listed and its seconds played back; the stadium shows the shapes. Note what is wrong. |
| 13.46 | FC radar colours left at Default; then set to Black. | Default: a warning on the Coach screen and in Settings; teams told apart and yours worked out after the first goal. Black: the warning says Black cannot be read. |
| 13.47 | CS2: Settings → Game data → Listen; Save the cfg file (into …\game\csgo\cfg); restart CS2 (the console shows "Loading Game State Integration"). | The panel shows updates arriving. Nothing arrives with the listener off; a request without the token (e.g. curl to 127.0.0.1) is refused (403). |
| 13.48 | CS2 Practice with bots, capture on, Game data on. Get kills, die flashed once and once while reloading. | Coach → Rounds: each round with your buy, kills, death. Insights after the match: the round strip, the money chart, and deaths flashed and reloading counted under the factors. |
| 13.49 | CS2: Radar Is Rotating: No, Radar Centers The Player: Yes, Radar Map Zoom 0.5. Export the map's radar overview from the game files and import it in Settings → Official overhead maps. | The overview is kept; the preview shows the pack's callouts labelled where they are (on maps with callout positions). Play: the stitched radar is placed on it; deaths pinned on the overview. Note any wrong placement. |
| 13.50 | CS2 Premier or Competitive match with capture on. | No live tips, no live rounds strip during the match ("open after the match"); everything appears after it. |
| 14.1 | CS2: Insights → Grenade lineups. Pick each map, then T, CT, and each grenade type; pick an execute. | Lineups drawn from where they are thrown to where they land (solid for Valve's, dashed for the rest); the execute's grenades numbered in order. With the map's overview imported, they sit on it. Note any that land in the wrong place. |
| 14.2 | In Practice, throw five Valve lineups (marked Valve) and five researched ones, following Stand, Aim and Throw. | Each lands where the coach says. Note the ones that do not (map, name, where it went). |
| 14.3 | Ask: "what smokes for an A execute on Mirage?", "where do I throw the Xbox smoke?", "how long does a smoke last?", "how many body shots does the M4A1-S need?" | Each answered from the pack: the execute in order; the lineup with stand, aim and throw; about 18 s of cover; 4 up close, 5 at 20 m. |
| 14.4 | CS2 Practice with Game data on: buy two flashes and a smoke, throw one flash, get flashed by a bot, stand in a molotov, die holding the rest. | Insights → Rounds: the round says 1 of 3 thrown, flashed once, caught fire once, 2 held at death; the grenade table matches. |
| 14.6 | CS2 with no overview imported: Insights → The map, each Active Duty map; From above, 3D, and on Nuke Upper and Lower level. | The real layout of each map (walls round the floor, darker low, paler high), callouts on the floor; 3D at real heights; Nuke's lower level shows B, Ramp's bottom, Secret and Decon with their callouts. Note any area missing or misplaced. |
| 14.7 | Play a CS2 Practice match with Radar Is Rotating: No and no overview imported, then import the overview and play again. | First match: the stitched radar on its own (no placement on the drawing); the areas list on the real map by callout. After importing: the minimap placed on the game's overview. |
| 14.5 | Insights → Arsenal → AK-47, then Compare guns. | The damage table (142 to the head at 5 m, 110 through a helmet, 4 body hits through kevlar) and the compare table sort by each column. |

## 14. Accessibility and layout

- Every control is reachable by keyboard, with a visible focus ring.
- Screen readers announce capture-state changes (the pill is
  `role="status"`), and captions use `aria-live`.
- No horizontal scrolling at window widths of 1024, 1280 and 1440 px.
- With reduced motion turned on in the OS, there is no pulsing or animation.
