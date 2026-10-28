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

> **Status:** v6, Windows-first.
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
    - MW4 covers all 33 launch weapons, 17 killstreaks, 18 perks and the
      launch maps.
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
  have reached its age (Wynguard Footmen need the Imperial Age), answers are
  ones you can build at your age (with the better later one marked, e.g.
  "Arbalétriers from the Castle Age"), and their age-ups are announced with
  what becomes possible. Their age comes from the screen when it shows it,
  else a lower bound from what they have fielded, else yours (said as an
  assumption). Nothing in the fog of war is guessed.
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

Your Anthropic account pays for:
- cloud vision (choose the model; Settings shows an estimate per hour);
- answers;
- knowledge building.

A per-session spending cap (default $2) stops all calls when reached. Known
menus cost nothing after the first time. Claude Opus 5.5 is the default
because it reads busy screens best; Claude Haiku 5.5 is a small fraction of
the cost.

## Layout

```
coach/
  electron/        main process: consent, network lock, encrypted storage, global keys,
                   cloud (key, Claude calls, knowledge builder), models (download/verify),
                   local speech recognition and neural voices (worker threads)
  shared/          bridge contract, IPC channels, cloud and voice types
  src/core/        engine: observations, visible-state fusion, frame health, analyses, evidence,
                   pattern memory, policy, planner, Q&A, vision contract/sampler/screens, knowledge base,
                   fight factors, benchmarks, the coach's review
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
