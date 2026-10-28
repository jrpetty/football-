# Sightline Coach

**An AI coach that watches your game with you.**
- It reads whatever is on your screen: menus, HUD, scoreboards, the action.
- It remembers your matches.
- It talks to you in a natural voice, and you talk back with a key on your
  keyboard.
- It tells you where things go wrong: where you die or concede, when you're
  housed or idle, which habits cost you.
- It reads how you lose fights (in the open, sprinting, out-ranged, alone,
  from below) and turns each finding into a fix from a playbook researched
  from pro players, coaches, official guides and forums.
- You can mark a moment mid-game and ask about that decision after the match.

It works by looking at the screen, never by touching the game:
- no memory reading, injection, overlays or input;
- it stays within what each game allows: live tips in campaigns and against
  AI or bots, post-match coaching in online and ranked play;
- it fails closed.

> **Status:** v11, Windows-first.
>
> **Game packs:**
> - Call of Duty: Modern Warfare 4;
> - Age of Empires IV;
> - EA SPORTS FC (FC 27);
> - Age of Empires II: DE;
> - a generic pack where Claude works out any other game;
> - a synthetic demo.
>
> **Not verified yet:**
> - real Anthropic API calls (there was no key in the build environment);
> - recognition accuracy on real game screens;
> - Windows hardware with each game's anti-cheat active.
>
> See [TODO.md](TODO.md) and [docs/QA.md](docs/QA.md).

## Get it

**Windows installer.** Each push builds `Sightline-Coach-Setup-<version>.exe`
on GitHub Actions:
1. Open the **Sightline coach Windows installer** workflow.
2. Open the latest run, then download the **Sightline-Coach-Windows**
   artifact.

Pushing a tag such as `coach-v0.2.0` attaches the installer to a GitHub
release.

The installer is not code-signed yet, so Windows SmartScreen will say
"unknown publisher": click *More info → Run anyway*. A signing certificate
removes the warning (see [docs/ANTI_CHEAT.md](docs/ANTI_CHEAT.md)).

**From source:**

```bash
cd coach
npm install
npm start              # build + run the desktop app
npm run dist:win       # build the Windows installer (on Windows)
npm test               # 112 tests (2 more run with the model archives present)
npm run dev            # browser preview (no cloud, voice or global keys)
```

## First run

A short setup walks you through four things:
1. **Connect Claude.** Paste your Anthropic API key (console.anthropic.com →
   API keys). A free test call confirms it works.
   - The key is stored encrypted on your PC and sent only to Anthropic.
2. **Pick your game and mode.** For example, MW4 Campaign or AoE IV Ranked
   1v1.
3. **Voice.**
   - Download the natural voice *Jenny* (67 MB, runs on your PC).
   - Turn on talking to the coach (a 108 MB local speech model).
   - Choose your keys. Defaults:
     - **F8** to talk (press, speak, press again);
     - **F9** to mark a moment;
     - **Ctrl+Shift+F12** to stop.
4. **Build game knowledge** (optional, on the Ask screen). Claude researches
   the game for a few minutes, typically $1–4 once.

Then press **Choose the game window**, play, and listen.

## What it does

- **Watches live.**
  - Every few seconds one downscaled frame (or only the pack's HUD crops)
    goes to Claude with your key.
  - Claude reports what it sees into the pack's fixed schema: scores, timers,
    resources, kills and deaths, and what screen this is.
  - Every answer is validated, and treated as an observation with a
    confidence, never as a fact.
- **Understands new screens and remembers them.**
  - The first time it meets a menu, loading screen or scoreboard, Claude
    names it and says what it is for.
  - Sightline stores a 64-bit fingerprint of it, about 200 bytes and no
    image.
  - Next time it recognises that screen on your PC, for free.
- **Knows the game before it starts.**
  - Each pack ships a curated, dated reference: modes, maps, screens, HUD,
    roster, mechanics.
    - MW4 (launch, Oct 2026): all 33 launch weapons with class, fire rate,
      magazine, best range, community time-to-kill where published,
      strengths, weaknesses, how to beat each and its meta build; 11
      lethals, 9 tacticals, 18 perks, 12 field upgrades and 18 killstreaks
      with how each works and how to counter it; 23 maps with 167 named
      areas (callouts), links, power positions, sightlines and Hardpoint
      rotations; movement, mechanics and every mode's rules.
    - AoE IV covers all 25 civilisations, landmarks and economy numbers.
  - **Build game knowledge** adds a much larger, cited knowledge base that
    Claude researches on the web. It is stored compressed and fed to Claude
    within size budgets, so cost stays bounded.
- **Analyses that matter.**
  - **MW4 positioning and gunfights:** every death is read from the
    killcam for cover, movement, range, height, nearby teammates, reloading
    and how many enemies; every kill for range, movement and height. The
    coach compares fights won and lost ("You lost 6 of 7 fights at long
    range"), counts avoidable circumstances ("8 of your 12 deaths came while
    you were in the open"), and notices deaths seconds after your own kill.
    Killcams are reconstructions, so findings are "likely", and unreadable
    ones are counted, never guessed. Plus where you die and what kills you.
  - **MW4: the real map, stitched from your minimap, with every death
    pinned to the spot.** With a fixed (north-up) minimap, each frame's
    minimap is a window onto the same overhead map. The coach matches each
    window against what it has stitched so far, which follows you to about
    a pixel, then paints the window in. The map grows as you play and is
    kept for your next match on it.
    - After a respawn it finds you again on the known map. A spawn on
      unexplored ground starts a new piece, which joins the rest once you
      walk onto ground already seen.
    - Only windows with your arrow at the centre are used, so death screens
      and menus are never stitched.
    - Each death is pinned where your own minimap was just before it cut
      away. Each killer is placed where the killcam's minimap ended, or
      roughly at the callout the killcam named.
    - Also on the map: your kills, your path life by life, enemy pings, and
      callouts and heights where they were read.

    In Insights → The map:
    - **Overhead:** a tactical map with glowing walls. Zoom, pan, hover a
      marker for details, toggle the layers, and play a match back.
    - **3D:** structures stand up from the minimap's own shapes, and upper
      floors and roofs are raised where you were seen. Red beams mark
      deaths, amber arcs with tracers show the line of fire, and your
      trails and callout labels sit on top. You can orbit the view.
    - **What it says:** "Most deaths in one spot: 4 of 11 near Courtyard,
      3 of them shot from the north-east. Pre-aim or smoke the north-east
      before you cross it." It also says where your kills cluster, where
      enemy pings show up, and when deaths come soon after spawning.

    During play where live tips are allowed, the Coach screen shows a live
    mini-map centred on you. Settings → Minimap aligns the minimap box on a
    still (it checks your arrow is at the centre) and forgets a map. With a
    rotating minimap, deaths are placed by area only. The MW4 demo draws a
    synthetic minimap, so you can watch a map being built. Tested on
    synthetic maps; not yet checked on real MW4 minimaps.
  - **MW4: knows where you are, and learns the map by watching.** Each look
    names the area you are in (a callout of the current map, from your own
    view: buildings, signs, objectives, minimap) and your height (ground,
    upper floor, roof, underground); killcams give the killer's spot and
    weapon; enemy pings give where they tend to be. From that the coach
    builds a model of each map: time and kills and deaths per area, the
    routes you take, the height layers, and held angles (where killers shot
    from and where you died). Insights draws it from the pack's rough
    layout and your routes, top-down or in 3D by height, with red arrows
    for held angles and rings on power positions. It tells you where you
    die and from where ("Most deaths at Central Containers, 6 of them shot
    from Armory"), your best area, where enemies show up, your time on
    power positions, spawn deaths ("4 deaths within 15 s of the one before:
    they are holding your spawn's exits"), time between deaths, and
    whether your weapon suits the fights you take with it ("M4 is built for
    mid-long range, but you lost 5 of 6 close-range fights with it, mostly
    to SMGs"). Against bots it calls a held angle on the second death from
    it, and death lines say where and from where. Ask about any weapon,
    perk, streak or map ("how do I beat the Rezi 12?", "best M4 build?",
    "power positions on Rooftops?", "where am I?"). The Coach screen shows
    where you are; Insights has the arsenal, searchable, with your record
    and what killed you. The map is a sketch, not a survey: positions come
    from rough 3×3 grid placements and the routes you take.
  - **AoE IV battles:** each fight is followed across the frames where your
    army is fighting, and read for where it happened (near their Town
    Center or Keep, near your defences, open field, choke, sacred site),
    the unit matchup, army size, whether your ranged units were screened,
    clumping under Mangonel fire, their siege left alone, and reinforcements
    trickling in. A fight is called won or lost only when what the frames
    show and your army size (population minus villagers) agree; the rest
    are "unclear". The coach then compares fights won and lost ("All 3
    fights lost came near their Town Center, towers or Keep") and gives the
    fix, with the reasoning behind it (arrow ranges, bonus damage, armor
    math, Update 17.0 changes).
  - **AoE IV economy:** your villager count against one Town Center that
    never stops (21 at 5:00, 36 at 10:00) with a strong-player reference;
    housed time, idle villagers, floating resources, age-up timing. The
    map and your opponent's civilisation are read from the loading screen
    and kept for the review.
  - **AoE II:** housed time, idle villagers, floating resources, and age-up
    timing against your targets.
  - **EA FC 27:** where you concede and score and how, the first defensive
    mistake in each goal replay (a defender pulled out of the line, an
    unmarked far-post runner, an open cutback lane…), where you lose the
    ball, and stats against warning lines.
  - **Patterns** like "deaths after pushing alone": graded, with
    counterexamples.
- **Advice that comes with a fix, and the reason.** Each pack has a playbook
  (MW4 30 entries, AoE IV 41, FC 27 25) of what good players do, what it looks like
  on screen, the fix and a drill, with its sources (pro, coach, official,
  guide, forum). Every finding the coach speaks carries its fix, once.
- **The coach's review.** After a session, Insights gives a headline, what
  went well, and the three things most worth working on, each with the
  count, the principle, the fix and a drill. It is built on your PC; with
  cloud features on, Claude can write a deeper one from the same data.
  "Read it to me" speaks it.
- **Live commentary** (🎙 in the top bar). Short spoken lines as things
  happen: a fight starting and how it ended ("Fight lost near their Town
  Center, towers or Keep. Army 34 to 16."), a death and how it happened, a
  goal, a scouted building ("They have a Stable: expect Horsemen or Knights.
  Spearmen answer them."), a villager checkpoint. It speaks during play only
  where the game and mode allow it (campaigns, against the AI or bots); in
  online play it says so once at the start and you get the review after.
- **Knows the opponent (AoE IV).** Their civilisation is read from the
  loading screen, and every civilisation's unique units, landmarks and
  bonuses are in the pack, including upcoming ones. When your screen shows
  their buildings or army, the coach works out what those buildings can make
  for that civilisation, and which of your units beat them (for your own
  civilisation). Ages are respected: a unit is expected only once they can
  have reached its age (Wynguard Footmen need the Imperial Age; English
  Men-at-Arms come in the Dark Age, other civs' in the Castle Age), answers are
  ones you can build at your age (with the better later one marked, e.g.
  "Arbalétriers from the Castle Age"), and their age-ups are announced with
  what becomes possible. Their age comes from the screen when it shows it,
  else a lower bound from what they have fielded, else yours (said as an
  assumption). Nothing in the fog of war is guessed.
- **Reads their plan (AoE IV).** Every civilisation has its usual plans
  for each age, from Dark to Imperial: openings, the timings to watch,
  their power spikes, and how to respond. When something you scouted
  matches one of those plans (a Council Hall and an Archery Range for
  English, say), the coach calls the likely plan with its timing, the
  response and the evidence ("Read: …, Feudal. <the response>. Seen:
  Council Hall, Archery Range."). A plan
  is called only once the screen supports it (a landmark counts most,
  then units, then buildings and cues like a tower at your gold or a
  second Town Center). Before that it gives what that civilisation
  usually does, marked as their usual plan rather than a read. It also
  gives your own civilisation's plan for your current age, and their
  next timing window.
- **You set how it looks, per game** (Settings → Screen reading, and the
  "Look every" control on the Coach screen, before and during capture,
  which also shows how long replies take and warns when they are slower
  than your pace). Look every 1–20 seconds (the defaults are 2.5 s for MW4 and FC, 3 s for AoE),
  how many looks run at once (auto keeps a short interval even when a
  reply takes longer), and the detail of each look (768 to 1600 pixels
  wide). Changes apply from the next look, even mid-game. With Sonnet 5.5
  or Haiku 5.5 you can turn thinking off for screen readings; once the
  opponent's civilisation is read, each look carries only this matchup's
  units and buildings. Settings shows the estimate and, after a few looks,
  the measured cost per look and per hour.
- **Learns from what it sees, across games.** Each game is filed under
  what it was (AoE: the opponent's civilisation, yours, the map; MW4: the
  map and mode; FC: the opponent and their formation), and the coach keeps
  what each side was seen doing:
  - AoE IV and II: their buildings and army in the order they first showed
    up on your screen, their age-ups, your own buildings (your build order,
    without what you start with), your army and your age-ups. Later it
    tells you their usual order and army mix against you, yours, and what
    went with wins and losses ("When they had Council Hall: you lost 2 of
    2"). Each civilisation's plans are linked to your games: the plans you
    met against them and the plans you played as your civilisation, by
    their landmarks or unique units ("Met in 3 of your last 5 games").
  - MW4: what killed you and from what range on each map, the enemy's
    killstreaks, and your kills and deaths with each weapon you held.
  - FC: how each opponent scored against you (crosses, cutbacks…), your
    defending on those goals, how you scored and how you lost the ball.
  When the next game of that kind starts it says what it learned ("Your
  last 3 games against English: …"), where live coaching is allowed;
  otherwise it waits for Insights. Settings → Learning, per game: learn
  on or off, recall at the start of a game on or off, how many games of
  that kind before it recalls anything, and forget what was learned. Ask "what do they usually build?" or
  "what's my usual build?". It keeps learning between sessions with
  "Remember my games" on. "First seen" means first on your screen, not
  when it was made.
- **Talks with you.**
  - Natural neural voices run on your PC: Jenny (British, fast on any PC),
    or Kokoro's 9 American and British voices (best quality, needs a
    quicker CPU).
  - Press your talk key (F8 by default) and ask anything: "What should I do
    here?", "Where am I dying most?", "How did my fights go?". A rising tone
    means it is listening; press the key again to send (a falling tone). It
    answers out loud. If something is wrong (voice input off, no microphone,
    the speech model not downloaded) it says so, out loud when you are not
    mid-match.
  - During capture, Claude sees your screen with the question.
  - Common questions are answered on your PC from the recorded data.
- **Decisions.** Press your mark key mid-game. After the match Claude looks
  at that exact screen, plus what you said about it, and tells you:
  - what was happening;
  - whether the decision was sound;
  - what to try next time.
- **Plays fair.** A [policy registry](POLICY_REGISTRY.md) decides when the
  coach may speak:
  - live tips only where no other players are affected;
  - post-match review in every online mode;
  - VALORANT: post-match only, always;
  - tournament switch: all output off.
- **Memory you control** (opt-in):
  - match records, patterns and notes are encrypted on your PC;
  - patterns fade over time;
  - you can mark any pattern wrong or delete it, export everything, or
    forget everything.
  - Frames, video and audio are never stored. Marked moments stay in memory
    for the session only.

## Anti-cheat

Sightline is designed to look like OBS or Discord to anti-cheat:
- it is a separate program using the OS's window-capture API;
- standard global hotkeys;
- no injection, no overlay, no input, no driver, no admin rights.

Nobody can promise a game will never object, and Sightline never tries to
hide. Details, and per-game notes for RICOCHET (MW4) and AoE IV, are in
[docs/ANTI_CHEAT.md](docs/ANTI_CHEAT.md).

## Cost

Your Anthropic account pays for cloud vision, answers, reviews, research and
knowledge building. **Usage & cost** (in the side bar) shows:
- what you have spent this session, today, in the last 7 days, this month
  (with a projection to the month's end) and in all;
- a 30-day chart by feature, and where the month went by feature, model and
  game, with token counts;
- what playing each game costs per look, per hour and per typical match, at
  your settings, with each model;
- what an answer, a review, a research run and a knowledge build cost,
  before you run them;
- the session cap and a monthly budget (warn from 80%, optionally stop);
- the prices used and the date they were checked.

How it is counted: every reply carries its exact token counts (input, cache
reads and writes, output with thinking, web searches). The app multiplies
them by the list price of the model that actually served the reply. If a
request falls back to another model, that model's price is used. Haiku's
higher rate above 100K-token prompts and $10 per 1,000 web searches are
included. Only the numbers are kept, on this computer, for 400 days.

Taxes, credits and discounts are not included. The Console's bill is the
final word.

Estimates at the packs' default settings, at list prices, from no thinking
to a little (still screens are skipped, so real play usually costs less):

| Game (default look) | Opus 5.5 | Sonnet 5.5 | Haiku 5.5 |
| --- | --- | --- | --- |
| Modern Warfare 4 (2.5 s), per hour | $20–27 | $11–13 | $0.56–0.67 |
| Modern Warfare 4, 10-minute match | $3.40–4.60 | $1.89–2.25 | $0.09–0.11 |
| Age of Empires IV (3 s), per hour | $18–24 | $11–13 | $0.55–0.64 |
| Age of Empires IV, 30-minute game | $9.30–12.30 | $5.55–6.45 | $0.28–0.32 |
| EA SPORTS FC (2.5 s), per hour | $17–24 | $9–11 | $0.46–0.57 |

Each look writes about 350 tokens, so output is most of the cost on Opus and
Sonnet. After 20 looks of a game with a model, the app shows what those
looks actually cost instead of the estimate.

Other jobs:

| Job | Opus 5.5 | Sonnet 5.5 |
| --- | --- | --- |
| Answer | $0.03–0.16 | $0.01–0.08 |
| Review | $0.11–0.22 | $0.06–0.11 |
| Research | $0.17–0.72 | $0.10–0.42 |
| Knowledge build | $1.50–4.40 | $0.85–2.40 |

On Haiku, an answer costs under a cent. Research and knowledge building need
Opus or Sonnet.

The per-session cap (default $2) stops all calls when it is reached. On Opus,
Modern Warfare 4 reaches it in about 5 minutes. Raise it on the Usage & cost
page (up to $20), or pick Sonnet or Haiku for long sessions. Known menus cost
nothing after the first time.

## Layout

```
coach/
  electron/        main process: consent, network lock, encrypted storage, global keys,
                   cloud (key, Claude calls, knowledge builder), models (download/verify),
                   local speech recognition and neural voices (worker threads)
  shared/          bridge contract, IPC channels, cloud and voice types
  src/core/        engine: observations, visible-state fusion, frame health, analyses, evidence,
                   pattern memory, policy, planner, Q&A, vision contract/sampler/screens, knowledge base,
                   fight factors, benchmarks, the coach's review; map/: the map stitched from the
                   minimap, pins on it, what the map views draw
  src/policy/      registry.json (publisher-policy records)
  src/profiles/    game packs (JSON, validated at load)
  src/worker/      analysis worker (frame queue, screen memory, vision relay, snapshots)
  src/capture/     screen, simulation and replay sources
  src/speech/      speech queue with neural or system voice; push-to-talk recorder
  src/ui/          React UI
  build/           installer icon
  test/            node:test suites
  docs/            architecture, privacy, anti-cheat, pack authoring, QA guide
```

## Read next

- [docs/ANTI_CHEAT.md](docs/ANTI_CHEAT.md): what the app does and never does
- [POLICY_REGISTRY.md](POLICY_REGISTRY.md): what the coach may do per game and mode, with sources
- [docs/QA.md](docs/QA.md): the manual QA guide
- [TODO.md](TODO.md): what is verified and what is not
- [plan.md](plan.md): the architecture and design
- [docs/PRIVACY_AND_INTEGRITY.md](docs/PRIVACY_AND_INTEGRITY.md)
