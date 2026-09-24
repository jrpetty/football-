# Episode playbook: from idea to published result

A practical checklist for running a benchmark you can put on video and defend in the comments.

## 1. Plan (5 minutes, $0)

1. **Pick the question** the episode answers: "Which model is the best agent?", "Is the new model worth 5× the
   price?", "Do old models really do worse?" Choose the suite or tests that answer it:
   * a head-to-head across everything: `core`;
   * a themed episode: hand-pick tests (e.g. all simulations for "AI Survivor");
   * a cheap first look: `quick`.
   * "Can it see?": the `vision` suite (charts, spot the difference, handwriting, counting). Check each model's
     *Accepts images* switch on the Models page: models without it are skipped on picture questions, and the
     leaderboard and Presenter say so on screen. For chat apps, the Manual Inbox has **Copy image** and
     **Download** buttons next to the prompt.
2. **Pick the models.** Include at least one reference point people know, and the **Random Baseline** (it's
   free and shows the floor).
3. **Check the price** in **Cost Planner** or with `node src/cli.ts costs --suite core --models a,b,c`. The
   table shows every test × model and a conservative upper bound.
4. **Verify pricing** for every model you'll show costs for (Models → edit → set *verified* date).
5. **Test your keys**: `node src/cli.ts ping <model>` for each model (costs a fraction of a cent).

## 2. Dry run (free)

```bash
node src/cli.ts run --models random-baseline --suite quick --repeats 1 --yes
```

This checks the install, the dashboard, the Live Arena and the replays without spending anything.

## 3. Run for real

* In the dashboard: **New Run** → suite, models, **3 repeats**, and a **spending cap** (about 1.25× the
  estimate) → Start.
* Or from the terminal: `node src/cli.ts run --models a,b,c --suite core --repeats 3 --max-cost 25 --name "Episode 12"`.
* If you hit the cap, or a provider has an outage: **Runs → Resume**. Finished cases are kept; only missing or
  errored ones re-run.
* To test a model that has no API (a chat app, a model that isn't out yet): add a **manual** model (Models →
  Add → provider *Manual*) and answer its prompts in the **Manual Inbox**.

## 4. Record

* Press **B** for Broadcast mode (no chrome, big type, 16:9 friendly).
* **Live Arena** while the run is going: every model streaming side by side, spend ticking up, scores
  landing.
* **Run detail → a simulation case → Replay** for the story beats: the Survival Island map, the Escape Room
  moves, The Startup's cash curve against the oracle, the Liar's Table interrogation, the Draw It Blind
  side-by-side.
* **Leaderboard** for the reveal: Index with confidence intervals, category heatmap, medal table, and the
  score-vs-cost chart with the Pareto frontier.
* Games from **Build a Game in One Shot** are playable inside the result inspector, and there's a screenshot
  of each.

## 4b. Making the video (Studio)

Open **Studio** in the sidebar (or the **Studio** button on a run's page). It reads the run you just finished and
builds the raw material for the video. Nothing here calls a model or costs money unless you press *Polish with AI*.

1. **Highlights** — the moments worth showing, ranked most dramatic first: upsets (a cheap model beating an
   expensive one), photo finishes, clean sweeps, disasters (died on day 2, went bankrupt, accused the wrong
   suspect), confident wrong answers and invented facts on the Honesty tests, the fastest right answer, the
   priciest wrong one, the best value, and a model that gave different answers to the same question. Every card
   shows the numbers behind the pick and, under *Why this was picked*, the rule that chose it.
   * **Replay at step N / Open case / Open slide** jumps straight to the footage. Record it there.
   * The grey cue (e.g. `[Replay: Survival Island, Opus, step 14]`) is for your edit notes; the copy button
     copies it. The clip length is a suggestion.
2. **Video script** — a narration draft in the Presenter's running order: a 10-second hook, the contestants and
   their prices, one segment per test (what it is, who won, one highlight), a mid-video recap, the final reveal
   (last place to first) and an outro. `[Slide 7]` means "cut to slide 7 of the Presenter"; open the Presenter
   with `?s=7` to land on it.
   * Edit it right in the box (your edits stay in this browser). **Copy**, **.md** and **.txt** export it; the
     .txt version is clean for a teleprompter.
   * The green bar confirms every number in the script exists in the run's data. If you type a number that
     doesn't, it turns amber and lists it.
   * **Polish with AI** (optional) rewrites the draft to sound more natural. Pick a model, press *Show the cost
     first*, and only then *Polish*. The result is number-checked the same way; *Undo* brings your draft back.
3. **Thumbnails & Shorts** — pick a format (YouTube thumbnail 1280×720, or a vertical 1080×1920 card:
   final standings, one test's result, a "question" card built from the test's hook, or a highlight card) and a
   style (Versus, Bold, Clean, Neon). Type your own headline if you like. **Download PNG** saves the picture;
   **Export all** saves every thumbnail style, all cards, the script and `highlights.json` into the run's folder
   (`data/runs/<run>/studio/`).
   * PNGs are drawn by Google Chrome or Microsoft Edge running invisibly on your computer. Gauntlet finds an
     installed Chrome or Edge automatically (Windows, macOS and Linux). If neither is installed, the PNGs are
     drawn by your web browser instead and downloaded one by one — same pictures.
4. **OBS overlays** — for live streams. Choose what to show (scoreboard, results ticker, "Now testing" lower
   third, or a test-by-test board), the look and the corner, then copy the address. In OBS: **Sources → + →
   Browser**, paste it as the URL, set **1920 × 1080**, and leave OBS's Custom CSS alone — the page background is
   transparent, so only the panels appear over your scene. Tick *Refresh browser when scene becomes active*.
   * Use **Always the latest** (`/overlay/latest?view=…`) and you never have to change the address: it follows
     whatever run is going on. It updates live as results arrive.
   * Use **Demo** (`/overlay/demo?view=…`) to position everything before you go live; it animates made-up data.
   * Address options: `view=scoreboard|ticker|lower-third|bracket-lite`, `theme=glass|solid|light|minimal`,
     `pos=tl|tr|bl|br` (or `top|bottom` for the ticker), `safe=0` to ignore the 5% TV-safe margin, `scale=1.3`
     to make it bigger.

## Making a "Can It Be Fooled?" Short

1. **New Run → suite "Can It Be Fooled?"** (`trick`). Three tests, 71 short questions, 3 attempts each. It is
   cheap (see the estimate before you start) and needs no judges: every answer is checked exactly.
2. **Open the run in the Presenter.** After each trick test's results you get up to three extra slides: the
   questions the models disagreed on most. Each slide has three steps (press → or Space):
   the question on its own ("What would you answer?"), then every model's actual answer, then the verdicts: a
   tick or a cross per model, "took the bait" when it gave the tempting answer, the tempting answer crossed
   out next to the correct one, and how many models were fooled. A clock means the model ran out of time.
3. **For Shorts (vertical video)** add `?vertical=1` to the Presenter address, e.g.
   `#/present/<run id>?vertical=1`. You get only the trick slides on a 1080×1920 stage. Make the browser window
   tall (or use full screen on a portrait monitor) and record it; the stage scales to fit.
4. Press **A** for auto mode: each step waits 3.5 s, so a slide plays in about 16 s — a Short with three traps
   is under a minute.
5. Say it honestly on screen: the time limit is the same for every model, but API speed differs by provider,
   so the score only counts right and wrong. Response times are shown under each model's name.

## 5. Review

* **Blind Review**: rate the games and illustrations, and settle any case where the judges disagreed. Model
  identities are hidden until you've scored.
* Skim errors and refusals in the run detail. Don't quietly re-run until they vanish: they're part of the
  result.

## 6. Publish

* **Publish** (dashboard) or `node src/cli.ts publish --zip` exports your public leaderboard website; put it
  online free with Netlify Drop or GitHub Pages ([PUBLISHING.md](PUBLISHING.md)). New model launched? Use
  **New Model Day** ([CHANNEL.md](CHANNEL.md)).
* `node src/cli.ts report <runId> --format md` produces a Markdown leaderboard for the video description.
* **Export JSON** gives the full manifest, every prompt, response and score, and the fingerprint for anyone
  who wants to audit.
* Say on screen: models, repeats, fingerprint, "differences inside the confidence intervals are ties",
  and which prices were verified on which date.
* Don't publish the prompt book for tests you want to keep reusing. Keep a held-out set in `tests/private/`.

## Arena episode (models play each other)

1. **Dry run for free:** `node src/cli.ts arena new --game connect4 --models random-baseline,<cheap model> --yes`.
2. **Plan:** Arena → New tournament. Pick the game, 8 models, *Knockout*, 2 games per pairing, seeding by
   Gauntlet Index. Read the estimate (central and upper bound) and keep the prefilled spending cap.
3. **Record live:** open the tournament, press **B**. The Live view shows the board, both models' clocks, the
   reasoning streaming in and the running cost; switch to **Bracket** between matches to show winners advancing.
   Set *Games at once* to 1 so there is always exactly one game to watch.
4. **Story beats:** click any game for a replay (Space to play, ← → to step, F full screen). Rejected moves show
   in red with the reason; forfeited moves are marked "random".
5. **Cards:** *Match cards* gives full-screen 1920×1080 slides: the bracket, one card per match with every final
   board, and the champion.
6. **If it stops** (spending cap, outage, Ctrl+C): Resume. Finished games are kept; games that were in progress
   start again from the beginning.

## Cost-saving tips

* Iterate on `quick` with 1 repeat. Use `core` with 3 repeats only for the published run.
* Cheap models first: a whole `core` pass on a budget model costs a fraction of a frontier model's.
* Simulations and long-context tests are the priciest per case. For a themed episode, run just those.
* Estimates get sharper after your first real run of each test, because Gauntlet measures actual token usage.
* Judge-scored tests (Honesty Trap, games, SVG) add judge cost. The same-vendor exclusion means each model is
  graded by the other two judges, not all three.
