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

> **Status:** v14, Windows-first.
>
> **Game packs:**
> - Call of Duty: Modern Warfare 4;
> - Counter-Strike 2;
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
      with how each works and how to counter it; all 23 maps in the game on 28 Oct 2026 (12 core and Shipment, Kill Block, 4 Gunfight, 3 Ground War, Hajin for DMZ and Zodiac for Warzone) with 253 named
      areas (callouts), links, power positions, sightlines and Hardpoint
      rotations; movement, mechanics and every mode's rules.
    - AoE IV covers all 25 civilisations, landmarks and economy numbers.
    - Counter-Strike 2 (game files of 22 Oct 2026, Premier Season Five):
      - **Guns:** all 34, with the numbers worked out from the game's own
        weapon file: damage at 5–50 m to the head (with and without a
        helmet), body (with and without kevlar), stomach and legs, hits and
        time to kill, how far one headshot kills through a helmet, how far
        the first shot stays on a head standing and crouched (scoped for
        sniper rifles), how far it misses while running, armour
        penetration, speed and reload. The AK-47's 4 body hits through
        kevlar (0.30 s) and helmet one-tap to about 69 m, the M4A4's 5 and
        no helmet one-tap, the M4A1-S's 4 up close and 5 at 20 m match the
        well-known figures. A sortable table puts every gun side by side.
      - **Grenades:** smoke, flash, HE, molotov, incendiary and decoy,
        each with how it works (fuses, fire spread and duration, how
        smoke puts out fire, flash strength, HE damage through armour),
        how to use it and the usual mistakes, throwing (jump-throws, the
        three throw strengths, the line-up reticle), team utility and the
        utility patches since 2023. Details that rest only on CS:GO's
        code or a single guide (50 of 236, spray patterns included) are
        marked unverified, with the reason.
      - **Maps:** the seven Active Duty maps (Ancient, Anubis, Cache,
        Dust II, Inferno, Mirage, Nuke), each with its **real layout**
        built from the game's own nav mesh (the walkable floor the game
        uses for bots: 1,969–3,040 floor areas a map, with heights; Nuke's
        upper and lower levels apart). The coach draws it from above in its
        own style and in 3D at the real heights, so every map view works
        without importing anything, and no game art is shipped. Every
        callout spot and every one of Valve's grenade spots stands on that
        floor (checked in the tests). 186 callouts, links, power positions,
        sightlines, bomb sites and the radar coordinates; and **227 grenade
        lineups**: 44
        from Valve's own guide in the game files with the exact spot to
        stand and where it lands, and 183 from lineup guides (where to
        stand and aim, the throw, what it does), plus 38 executes and
        retakes that put them in order. 86 are marked unverified, each
        with the reason (often: the aim is not described in words).
      - The economy (loss bonus, rewards, plant bonus), timings, HUD, 13
        modes, 51 playbook entries and 426 reference items.
    - FC 27 (Title Update 1.1.0): 23 modes, the radar and HUD, FC IQ
      roles and tactics, skill moves, set pieces, 13 formations with how
      each looks on the radar, its weak spots and how to beat it, 55
      playbook entries and real-football benchmarks (block heights, team
      length and width, line distances, shot values by zone).
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
  - **MW4: everything on the official overhead map.** Give the coach a
    map's official tac map (Settings → Official overhead maps) and it places
    your stitched minimap on it by itself while you play: the scale and
    position are found by matching the two, and then every frame is pinned
    to the official map, so the stitched map no longer drifts. Deaths,
    kills, killers, paths, pings and callouts are then drawn on the real
    map, overhead and in 3D (a floor plan raised from the official drawing).
    - Ambiguity is refused, not guessed: a placement must beat the best one
      elsewhere by a clear margin (two look-alike wings tie, so a piece
      that has only seen the shared middle stays unplaced until you walk
      somewhere only one wing has), and a placement from a single minimap
      view is shown only once the next frames bear it out. The map view says
      what share of your points is placed; the Stitched view shows them all.
    - **Check placement** lays your stitched map over the official one (its
      walls should sit on the official walls), with nudge buttons to adjust
      it by hand.
    - Maps stitched before you added the official map are placed on it
      afterwards, once.
    - The images are the publisher's artwork, so the app does not ship
      them. On the desktop app, **Download** fetches the map's official
      guide page (named in the pack) and keeps the image on it that looks
      most like a tactical map; or open the page, save the image and
      import it (drag and drop works; files are matched to maps by name).
      Each image must look like a tactical map to be kept (a screenshot of
      the game is refused). Kept on this computer only.
    - Accuracy, measured on the 20 real official map images with simulated
      minimaps in three art styles: every point placed, none in the wrong
      place, 95% within 0.62% of the playable area's width (a few pixels on
      a 2048 px map); the worst single point was 3% off (a moment of lost
      tracking). Not yet measured on real MW4 minimap captures.
      With an official map kept, the MW4 demo walks on it, so you can watch
      the whole flow.
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
    never stops (21 at 5:00, 36 at 10:00). There is no comparison with two-
    or three-Town-Center play, and no advice to build more Town Centers.
    The count is taken only from the one villager total the screen shows,
    never added up from the workers beside each resource (that leaves out
    villagers building, walking or carrying). A checkpoint is stated only
    when at least two separate readings agree, and a reading above your
    population is thrown out. Also covered: housed time, idle villagers,
    floating resources judged against what the next age costs, and age-up
    timing. The map and your opponent's civilisation are read from the
    loading screen and kept for the review.
  - **AoE II:** housed time, idle villagers, floating resources, and age-up
    timing against your targets.
  - **EA FC 27:** where you concede and score and how, the first defensive
    mistake in each goal replay (a defender pulled out of the line, an
    unmarked far-post runner, an open cutback lane…), where you lose the
    ball, and stats against warning lines.
  - **EA FC 27: the whole pitch, from the radar.** The 2D radar at the
    bottom of the screen shows every player and the ball; the coach reads
    them about twice a second, on this computer, with no cloud call:
    - every player to about a metre and the ball, both teams told apart
      by the radar colours you set (or worked out from the first goal when
      left at Default), which end each team defends (half-time included);
    - possession and territory, lost and won balls and how fast you won
      them back, shots with the usual value of where they were taken,
      kick-offs, and the seconds before every goal;
    - each team's average shape with and without the ball, and the
      formation it makes: the ten outfield players are matched against 18
      shapes (4-4-2 to 5-2-1-2), so the opponent's 4-3-1-2 is named and
      linked to how to beat it;
    - your back line's height (low block, mid block, high line), the
      team's length and width, the gap between defence and midfield, and
      how often someone was within 5 m of the ball, against real-football
      tracking figures (FC publishes none).

    In Insights → The pitch: both shapes on a pitch, heat, lost and won
    balls and shots, every goal played back, and a floodlit 3D stadium with
    the shapes or a goal. The Coach screen shows the radar live in offline
    modes. Settings → Radar: align the box on a still (what is read is drawn
    on it) and the game settings to use (2D radar; two strong radar colours
    such as Blue and Red: Black cannot be told from the dark radar).
    Measured on simulated radars: 95–99% of players found within about a
    metre, the right team for 97% or more, the ball in about 97% of frames,
    possession within about 3% of the truth, every goal found; the formation
    reader names the right shape every time with players 1 m off their
    places on average, 98% at 2 m. Not yet checked on real FC captures:
    how FC draws the ball and the player you control is not documented.
  - **Counter-Strike 2: the game's own data, the death panel and the
    radar.**
    - **Game State Integration** (Settings → Game data, off until you turn
      it on): Counter-Strike 2 sends your own state to a listener on this
      computer (127.0.0.1 only, with this computer's token). The coach then
      counts every kill and death exactly, knows if you were flashed or
      reloading when you died, and keeps each round: your money, what you
      spent, the buy it made (pistol, eco, force, full), kills, headshots,
      damage, when you died and with how many grenades unused, and the
      result. While you play the game sends nothing about other players,
      so nothing hidden is ever known. Save the cfg file from Settings into
      the game's cfg folder once and restart the game.
    - **Every death read for how it happened** (with cloud vision): traded
      or not, one enemy or two, moving or still, blind, through smoke or a
      wall, the range, alone or with a teammate, the opening duel or not,
      utility first, what you were doing (peeking, holding, crossing,
      reloading, defusing), the buy against theirs, the numbers, what
      killed you and deaths straight after your own kill: 16 factors, each
      with its fix. Killer Replay is off in Premier and Competitive, so
      these come from the death panel, the freeze-cam, the kill feed and
      the radar. A death the game reported gets the screen's details added,
      never counted twice.
    - **Rounds and economy** (Insights): a round strip (won or lost, your
      buy, kills, death, grenades thrown of those carried), your money
      against what you carried in, and lines on forcing ("you force-bought
      5 times and lost 4; 3 times you could not buy the round after"),
      pistol rounds, early deaths and each side.
    - **Your grenades, from the game's data:** what you carried each round
      by kind, what you threw, what was still in your hands when you died,
      how often you were flashed blind (and for how long) and caught fire.
      Lines such as "As T you threw 3 of the 8 grenades you carried; 2 were
      still in your hands when you died (1 smoke, 1 flash)", full buys
      without a smoke or a flash, being flashed and standing in fire, each
      linked to its playbook entry. Whose flash it was is not in the data.
    - **Grenade lineups** (Insights): each map's lineups drawn from where
      they are thrown to where they land, on the game's overview once you
      import it (else on a sketch of the callouts), filtered by side and
      grenade; pick an execute or retake to see its grenades numbered in
      throw order. Ask "what smokes for an A execute on Mirage?" or "where
      do I throw the Xbox smoke?" and the coach answers from them.
    - **The radar stitched, with deaths pinned to the spot,** as for MW4
      (Radar Is Rotating: No). Every map view shows the map's real layout
      from the start (Insights → The map: from above, in 3D, by level on
      Nuke; your areas, deaths, kills and killers' arrows at their real
      places; grenade lineups on it). To have your own minimap placed on
      the map while you play, import the game's radar overview from the
      game files (the app does not ship Valve's images): the stitched radar
      is matched against the game's own art. The demo draws its radar from
      the real layout and is placed on it. Nuke's lower level is kept as its own piece of the map.
      Measured on the real overviews with a
      simulated radar (30 runs): no point placed in the wrong spot, 95%
      within 0.6% of the map's width in 29 runs (2.6% once, on Nuke), and
      68–93% of points placed while playing (17% once, on Cache: unclear
      pieces wait rather than guess). Not yet checked on real CS2 radar
      captures.
    - Live tips only in Practice, Workshop maps and your own server with
      bots; Premier, Competitive, Wingman, FACEIT and the rest are reviewed
      after the match.
  - **Patterns** like "deaths after pushing alone": graded, with
    counterexamples.
- **Advice that comes with a fix, and the reason.** Each pack has a playbook
  (MW4 30 entries, CS2 51, AoE IV 41, FC 27 55) of what good players do, what it looks like
  on screen, the fix and a drill, with its sources (pro, coach, official,
  guide, forum). Every finding the coach speaks carries its fix, once.
- **The coach's review.** After a session, Insights gives a headline, what
  went well, and the three things most worth working on, each with the
  count, the principle, the fix and a drill. It is built on your PC; with
  cloud features on, Claude can write a deeper one from the same data.
  "Read it to me" speaks it.
- **Live commentary** (🎙 in the top bar). Short spoken lines as things
  happen:
  - a fight starting and how it ended ("Fight lost near their Town Center,
    towers or Keep. Army 34 to 16.");
  - a death and how it happened, or a goal;
  - a scouted building ("They have a Stable: expect Horsemen or Knights.
    Spearmen answer them.");
  - a villager checkpoint, or your age-up ("You reached the Feudal Age at
    4:48.");
  - what it sees of a mistake ("Housed at 40 of 40."), unless Training
    mode already gives the tip.

  After a quiet spell (about a minute), it reads out what is on screen now:
  "8:09, population 76 of 84, Feudal Age, food 291, wood 251, gold 122."
  That way silence never means it stopped watching.

  It speaks during play only where the game and mode allow it: campaigns,
  or against the AI or bots. In ranked or online play, or with the game
  mode not set, it says so out loud when capture starts ("Commentary is on,
  but in Ranked 1v1 I stay quiet until the match ends…"), and you get the
  review after. The 🎙 button then turns amber: "Commentary: after the
  match", or "set your mode".
- **Why didn't it say that?** A list on the live Coach screen of the lines
  the coach had but kept to itself, newest first, each with the deciding
  reason: the rules for this game and mode, pacing, low confidence, or the
  moment passed.
- **Ready check** (Coach screen, before you start). One list says what will
  happen this session: the game mode and whether it allows live tips,
  whether you can hear the coach (it plays a line), live commentary, the
  speech model, your microphone (a 2.5 s level test), your talk key (press
  it; nothing is recorded), always listening, Claude, the HUD numbers you
  marked and the AoE4World check. Each line that is not ready has a button
  that fixes it or goes to the right setting.
- **Checks itself against the real game** (History → "Is the coach reading
  your games right?"). What the coach read, compared with what really
  happened:
  - AoE IV: AoE4World's summary of the match. Link your AoE4World profile
    once (Ready check → Link, or here, by name or profile number) and every
    Ranked and Quick Match game is checked a few minutes after it ends. It
    has your exact villagers every 20 s, your age-up times and resources.
    Only public Ranked and Quick Match games are on AoE4World.
  - Any match: type your villagers at 5, 10 and 15 minutes from the
    post-game statistics.
  - Counter-Strike 2: the screen readings against the game's own data
    (Game State Integration), during play.

  Each match shows "12 of 13 right" with every line (the coach's number,
  the real one), and the totals over your checked matches. Nothing is sent
  to AoE4World but your profile number (and the name you type when you
  search for yourself), never during a match.
- **Reads HUD numbers on this PC** (Settings → Screen reading → "Read
  numbers on this PC"). Mark where a number sits (AoE IV: food, wood,
  gold, stone, population, villagers, idle villagers and the game clock;
  scores and clocks for MW4, CS2 and FC 27).
  Sightline then learns the game's digits from the model's own readings:
  a digit shape is learned only when two readings agree, and a number is
  trusted only after it agreed with the model at least 8 times (95% or
  more). Trusted numbers update every frame on your PC, and with "Save
  cost" on the model is asked half as often. The learned digit shapes (a
  few kilobytes, no screenshots) are kept in `hud.json`.

  *Measured on six fonts: trusted after 15 to 21 model readings; 93–100% of
  numbers read; none read wrong.*
- **Progress** (its own tab).
  - **Your whole AoE IV history.** With your AoE4World profile linked, the
    coach reads every public Ranked and Quick Match game you play, also the
    ones without the coach running. It shows:
    - **Your week** against the 4 weeks before: games and wins; Feudal and
      Castle times; villagers at 10:00; the matchup going worst, with what
      they made in the losses. For example: "Feudal Age 44 s faster than the
      4 weeks before (4:38 against 5:22; 6 games this week, 19 before)."
    - **Trends**, game by game, for all civilisations or one.
    - **Win rates** by matchup, civilisation and map.

    The summaries are read slowly (one every 1.5 s, at most 30 per sync) and
    each is fetched once, as AoE4World asks of apps.
  - **Build-order trainer (AoE IV).** A build is made from a real game: one
    of yours, any AoE4World game you paste, or a suggested recent win by a
    high-rated player with your civilisation. It holds the age-ups, the first
    of each building, unit and technology, and villager counts, with their
    times.
    - Against the AI, the coach calls the next steps as you play: "Build:
      Barracks at 3:10, then Feudal Age (Council Hall) by 4:30."
    - After any game, each step shows as on time, early, late or not made,
      biggest gaps first: "Feudal Age 0:45 late (5:15; the build 4:30)."

    The game's record has no information on which resource each villager
    worked, so builds don't claim it.
  - **Practice plans.** The two or three weaknesses that stand out in your
    recent games: villagers at 10:00, Feudal and Castle times, or a
    recurring mistake (housed, idle villagers, floating).
    - Each comes with a target set from your own numbers, a drill and 3
      games. Games count for the focus that was current when you played them.
    - It then shows before and after: "3 of 3 games: 34 villagers against 29
      before (target 33). Target reached."
    - The focus is said at the start of a game where live coaching is
      allowed, and shown on the Coach screen.
- **Opponent card (AoE IV).** One screen on the civilisation you face:
  - their usual plans age by age, with timings and how to answer them;
  - their strengths and weak windows;
  - their unique units, with what beats them for your civilisation;
  - your own plan for the age.

  Before the match, pick their civilisation from the loading screen (any
  mode). During play it opens by itself where live coaching is allowed. It
  comes from the coach's notes on each civilisation, not from your
  opponent's own games.

  **Their record (optional, before the match only).** Type their name and
  the coach looks up what anyone can see on AoE4World:
  - their rating, the civilisations they play and how often they win with
    each, and their last 10 results;
  - how they opened in their last three 1v1 games: landmark, Feudal time
    and first two military units.

  Their most-played civilisation is picked as a first guess. Nothing is
  looked up during play, and nothing is looked up unless you ask.
- **Counter-Strike 2 demo review** (Progress tab, in the desktop app).
  Open a match demo (.dem), from the game (Watch → Your Matches) or from
  FACEIT, and pick yourself once (your SteamID is remembered). The coach
  reads the demo on your PC in about two seconds and shows:
  - rounds won and lost; kills, deaths and assists; damage a round; KAST;
    headshot kills;
  - opening duels won and lost; your trade kills, and your deaths a
    teammate traded within 5 seconds;
  - grenades thrown, enemies and teammates flashed;
  - where you died, most first, and your kills by weapon;
  - your kills and deaths on the map's real layout, upper and lower level
    on Nuke;
  - a table of every round: side, result, kills, damage, opening duel,
    where you died and to what, your buy against your team's;
  - the two or three things most worth working on, with their numbers. For
    example: "You died first in 6 of 19 rounds (won 1 of 7 opening duels),
    2 times at Trophy. Peek with a teammate ready to trade, or let them
    take first contact."

  Knife rounds and warm-up before the match start are left out. A demo
  holds both teams' positions, so it is only ever read after the match.
- **Match summary to your phone (Discord, optional).** Make a webhook in
  your own Discord server (Server settings → Integrations → Webhooks), paste
  it in Settings → Discord, and after each captured match the coach posts a
  short summary: the result, the numbers it read against the benchmarks,
  age-up times, fights, mistakes, the review's points to work on and your
  practice focus. Discord delivers it to your phone like any message. Only
  that text is sent, only to that webhook; the Test button sends a sample.
- **Your usual.** Once three of your matches have a reading at a
  checkpoint, the coach compares you with yourself as well as with the
  curve: "At 10:00 you had 31 villagers; one Town Center that never stops
  has 36. Your usual is 28 villagers (your last 6 games with this
  civilisation)."
  It uses the real number from a checked match where there is one.
- **Villager curves per civilisation (AoE IV).** From the training times
  in the game files: Order of the Dragon Gilded Villagers take 23 s (about
  2.6 a minute), French villagers 19 s, Viking villagers 22 s, the rest
  20 s. Golden Horde villagers take 37 s in the game files; until that is
  checked in real games their count is not judged, and the session says
  so. Water maps use the same curve, since boats come from the Dock and
  do not slow the Town Center.
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
  - Ask anything: "What should I do here?", "Where am I dying most?", "How
    many villagers should I have at ten minutes?". It answers out loud. Two
    ways (Settings → Voice input):
    - **Talk key** (F8 by default): hold it while you ask and let go, or tap
      it, ask, and tap it again. A rising tone means it is listening, a
      falling tone that it is thinking.
    - **Always listening**: the microphone stays open and it hears you
      whenever you speak. By default only speech that starts with "Coach"
      is a question ("Coach, what beats knights?"; just "Coach" and it waits
      a few seconds for the question), so talk to your team is not. It
      pauses while it is talking itself, and the top-bar button pauses it.
      Best with a headset.
  - Speech recognition runs on your PC: Moonshine tiny (108 MB, fast) or
    Moonshine base (251 MB, more accurate), with the Silero voice detector,
    which keeps game sound and music out before anything is transcribed.

    *Measured on 45 recorded questions: tiny 44 right, base 45. The voice
    detector found no speech in synthetic game sound.*
  - If something is wrong (voice input off, no microphone, the speech model
    not downloaded), it says so, out loud when you are not mid-match.
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
| Counter-Strike 2 (2.5 s), per hour | $20–27 | $11–13 | $0.56–0.67 |
| Counter-Strike 2, 40-minute match | $13.50–18.30 | $7.50–8.95 | $0.38–0.45 |

The FC radar and Counter-Strike 2's Game State Integration cost nothing:
both are read on this computer.

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
                   minimap, pins on it, the official overhead map and placing on it, what the map
                   views draw
  src/policy/      registry.json (publisher-policy records)
  src/profiles/    game packs (JSON, validated at load)
  src/worker/      analysis worker (frame queue, screen memory, vision relay, snapshots)
  src/capture/     screen, simulation and replay sources
  src/speech/      speech queue with neural or system voice; talk-key gesture; recorder;
                   always-listening voice detection
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
