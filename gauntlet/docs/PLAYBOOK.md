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
5. **Connect and test your keys** on the dashboard's **API Keys** page: paste, Save (checked free), and optionally
   *Send a test message* (costs a fraction of a cent). From a terminal: `node src/cli.ts keys setup`, `node src/cli.ts keys test`.

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
  landing. For a close-up of every answer typing in, with a verdict flash and live commentary, use **Watch it
  think** (see [Recording a run live](#recording-a-run-live)).
* **Run detail → a simulation case → Replay** for the story beats: the Survival Island map, the Escape Room
  moves, The Startup's cash curve against the oracle, the Liar's Table interrogation, the Draw It Blind
  side-by-side, and Fix the Bug's file tree, diffs and test bar going from red to green.
* **Leaderboard** for the reveal: Index with confidence intervals, category heatmap, medal table, and the
  score-vs-cost chart with the Pareto frontier.
* Games from **Build a Game in One Shot** are playable inside the result inspector, and there's a screenshot
  of each.

### What Broadcast mode hides (and translates)

Broadcast mode is written for a viewer who has never heard of Gauntlet. Your normal view keeps every detail;
press **B** and the screen swaps owner-only detail for plain English:

| Where | Normal mode (you) | Broadcast mode (viewers) |
| --- | --- | --- |
| Everywhere | protocol version in the top strip | hidden |
| Leaderboard | fingerprint and protocol chips | hidden; a "Score out of 100" strip says what 100 means and what random guessing scored |
| Podium | "95% CI 72.5–79.3" | "out of 100 · likely range 72.5–79.3" |
| Run detail | run id, fingerprint, harness, git commit, environment, created time | hidden |
| Run detail | "1,575 jobs", "3× repeats · concurrency 6 · temp 0" | "1,575 answers", "Every question asked 3 times" |
| Results matrix | test ids (`reasoning.river-crossing`), "4 cases × 3" | "4 questions × 3 tries" |
| Result inspector | `seed-101`, `c03`, `r2`, "Copy key", hashes line | "World #101", "Question 3", "Try 2"; key and hashes hidden |
| Result inspector | "Extracted answer / Expected" | "Model's answer / Correct answer" |
| Result inspector | Details: `checkScore`, `judgeScore`, `bytes`… | "Automatic checks 80 / 100", "Judges' score", "File size 1.6 KB"… |
| Result inspector | 12 metrics (API calls, cached input, retries…) | 5: time taken, text read, text written, thinking, cost |
| Replay header | "seed 101" | "world #101" |
| Test library / test page | test id, scorer type, version, hash, file path, limits, scorer JSON, reproducibility notes | hidden; "5 cases" → "5 questions", "3 seeds" → "3 game worlds" |
| Runs list | fingerprint, suite id, CSV / JSON / delete | hidden; "jobs" → "answers" |
| Models | model id, price verified, key, enabled, actions, providers | hidden; "In / 1M" → "Price to read 1M tokens" |
| Methodology | the Σ formula | "average of the categories · shown out of 100" |

**Colours now mean the same thing everywhere.** In the results matrix and on score pills: green = mostly right
(80–100), amber = partly right (40–79), red = mostly wrong (0–39). A legend sits above the matrix.

**Model badges.** Every model has a round monogram in its contestant colour (A4 = Atlas-4 Ultra, and so on) on
the leaderboard, podium, matrix, inspector and Models page. The random baseline gets a dashed "?" so it never
looks like a competitor. Change a model's colour in **Models → Edit** and the badge follows.

**Viewer guide.** *About → Viewer guide* explains 15 words a viewer will hear (benchmark, test, question, run,
score out of 100, pass/partial/fail, Gauntlet Index, random baseline, judge, tokens, reasoning tokens, context
length, cost per run, seed, likely range), each with a drawing. Press **Full screen** for one term per screen
(← → or Space to move, Esc to leave) — handy as a 5-second cut-in when you first use a word.

For developers: mark any owner-only element with a `data-dev` attribute (or `.dev-only` class) and it
disappears in Broadcast mode; `<Jargon dev="…" plain="…" />` shows each version in its own mode. Plain labels
for score-detail keys live in `ui/src/components/clarity/plain.ts`.

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

## Running the show (Presenter)

Open a run and press **Present** (or go to `#/present/<run id>`). The Presenter is a full-screen deck made
from the run's recorded results; it plays like a game show, so a viewer can follow it with the sound off.

* **The race so far.** After every test there is a **Standings after N of M tests** slide. It opens on the
  previous standings, the bars grow or shrink to the new overall scores, the rows slide into their new order,
  and then the new leader (crown) and the biggest mover (e.g. "▲2 Nova 3 Pro") are called out. Green and red
  arrows on the right show who moved up or down. The number is the same Gauntlet Index the final table uses,
  calculated on the tests played so far, so the last race slide always matches the final result.
* **The reveal.** Before the final table, the **And the winner is…** slide counts down from last place to
  first, one model per press of **→**. Each model gets the spotlight: its final score counts up, with the test
  it did best and worst on. Third and second step onto the podium, the next press is a drumroll, and the last
  press shows the winner with the trophy and a confetti burst. The full results table follows it.
* **Random guessing line.** If the run includes the Random Baseline, every score bar and the score-vs-cost
  chart get a dashed "random guessing: X%" line, using what random guessing actually scored. It tells the
  viewer whether a score is good or bad. Without the baseline in the run, the line is simply not there.
* **Sound effects: S.** Press **S** (or the *Sound* button in the controls that appear when you move the
  mouse) to switch sound effects on or off. They are made live by the browser, so there are no audio files:
  a tick for each reveal, a ding for a right answer or a new leader, a buzz for a wrong one, a whoosh between
  slides, a drumroll before the winner and a fanfare for the winner. Sound is **off by default** and the
  browser remembers your choice. A small "Sound effects on/off" note shows for one second in the bottom-left
  corner; the slider next to the button sets the volume. Tip: record sound effects on their own track, or
  leave them off and add your own in the edit.
* **Episode auto-play: A.** Press **A** and the deck runs itself. It pauses longer on reveals (the winner gets
  about 11 seconds), never moves on while bars or numbers are still animating, and a small ring next to
  *AUTO* at the top right fills up until the next step. Press **A** again to stop.
* **Address options.** `?race=0` leaves out the race slides (handy for a short episode); `?podium=0` goes
  back to the table that reveals row by row; `?s=12` opens slide 12. Press **?** for every shortcut.
* Motion is reduced automatically (no confetti, no sliding) when Windows' "Show animations" setting is off.

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

## Making a versus video

"Model A vs Model B" uses results you have **already recorded**, so it costs nothing and calls no model.

1. **Run the same tests on both models** (any suite, any number of runs). Only tests *both* models finished
   count, and inside a test only the questions both answered, so a half-finished run can't tilt it.
2. **Open Head to Head** in the sidebar (`#/versus`). It starts with the two best models that share the most
   tests; change the **Left corner** / **Right corner**, swap sides with the arrows button, or pick one run under
   **Results from** (default: every valid result, the same pool as the Leaderboard).
3. **What's on the page**, top to bottom:
   - the face-off: both model badges in their colours, and the **tale of the tape** (maker, price to read and to
     write per million tokens, memory). A ★ marks the better of the two on that line;
   - **round by round**: one card per test with the category icon, both scores as bars growing towards each
     other, a winner badge ("Nova 3 Pro wins by 18 points"; less than **2 points** apart is a **draw**), cost and
     time, and the **decisive moment**: one question where one model was right (green) and the other wrong (red),
     with the correct answer and both replies quoted from the recording (shortened, marked "…");
   - **the result**: rounds won ("Nova 3 Pro wins 7–4"), average score, total cost and time to run these tests
     once, writing speed and **value** (score points per dollar).
   Anything not recorded says "not recorded" — nothing is estimated.
4. **Record it as slides:** press **Present as slides** (or open `#/present/versus?a=<model>&b=<model>`).
   Face-off → one slide per round with a live "score so far" that ticks up after the winner is stamped → final
   result. Same keys as the Presenter: **→ / ←**, **F** full screen, **A** auto (9 s per slide), **?** help.
5. **Shorts card:** at the bottom of the page (and in **Studio → Thumbnails & Shorts**, for a run's models) is a
   vertical 1080×1920 card with the result, up to eight rounds (the most one-sided ones when there are more)
   and the cost. **Download PNG** renders it with Chrome/Edge when the server has one, otherwise in your browser.
6. Say it honestly: costs are what these tests cost *once*; a model run several times is averaged, not added.

Demo it with no keys: `?mock=1#/versus`. Screenshots: `docs/screenshots/next-level/versus/`.

## Answer vs truth: showing one answer on screen

Most prompt tests now draw the model's answer against the answer key, so a viewer can see *why* it scored what
it scored. Open a run, click any cell in the results table, and the **Score** tab starts with the picture:

| Test | What the viewer sees |
|---|---|
| Deduction Grid (+ Extreme) | The whole solution as a row of houses/rooms/seats. The rows the question asked for show the model's answer: green = right, red = its wrong value struck out with the correct one underneath ("Swapped rooms 1 and 2: one swap scores zero"). |
| Knights, Knaves, Spies & Alternators (+ Extreme) | Every islander drawn with their statements in speech bubbles, their true role (colour + badge) and the role the model gave them, ticked or crossed. |
| Shortest Plans (+ Extreme) | "Model said 9 / True minimum 8" in big numbers, then the puzzle itself (jugs, coins, bridge or gondola, Hanoi, sliding tiles, lights out, pancakes, key-and-door maze, traffic jam) playing one optimal plan step by step. Space plays, ←/→ step, 0.5×–4× speed, F for full screen. Puzzles without a drawing (scheduling, the jeep, …) show the plan written in the answer key's notes. |
| Competition / Olympiad Maths, Word Problems | The question typeset (powers, fractions, √), the model's final number against the key, and the working behind a "Show" link. Money problems look like a receipt, payslip or bill. |
| Precision Formatting, Stay In Character, Extreme Constraints, Adversarial System Prompt | The reply with every rule as a checklist beside it. The exact letters that broke a rule are marked red (the banned "e", the comma, the leaked code word, the words past the limit); point at a rule to see its marks. Word/sentence counts are bars against their targets. Multi-turn attacks show as chat bubbles with each pressure turn labelled. |
| Messy Text to JSON, Extraction: Frontier | Answer key vs model field by field (wrong values struck through), and the document beside it with the traps (corrections, cancellations, changes) in amber and the lines holding the model's wrong values in red. |
| Honesty Trap, Pressure Traps | The question with the false claim highlighted (only when the key names it word for word), a big verdict ("Played along with “26.2 km”"), what the model said, and each judge's label and reason. |
| Can It Be Fooled? | The question, the tempting wrong answer, the correct answer and what this model said. |
| Coding tests | A board of hidden tests (green passed, red wrong or crashed, amber too slow), split into small inputs and large stress inputs; click a tile for its input, expected and actual output. The model's code is shown with colours. |

**In the Presenter** add `?truth=1` to the address (e.g. `#/present/<run id>?truth=1`). After each test's
results you get one extra slide: the question the models disagreed on most, drawn the same way for the
strongest model that still got it wrong, with every model's score on that question down the side.

**Honest by design.** The pictures only use what was recorded (the prompt, the reply, the scorer's checks) and
the published answer key. Anything worked out in the browser (the full grid solution, the optimal plan) is
checked against the answer key first and left out if it doesn't match. If anything can't be read cleanly, you
simply get the plain score view, as before. Add `?plain=1` to the address to see the plain view on purpose.

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

## Coding-agent episode: Fix the Bug

**What it is.** Each case drops the model into a small, real JavaScript project (an invoice calculator, a
text-adventure parser, a date library, an API client with a cache; the hard tier has a payroll engine, a
multi-currency ledger and a help-centre search engine). The project's tests are red because of two or three planted bugs.
The model works like a developer in a terminal, one action per turn: list files, read a file, search, edit
(rewrite a file or patch a few lines), run the tests, and finally submit. It never sees the answer.

**How it's scored.** After it submits, a second, larger set of **hidden tests** that it never saw checks the
same behaviour more thoroughly, so hard-coding the visible tests doesn't work. The score is mostly "what share
of the broken hidden tests now pass" (breaking code that used to work counts against it), a bit for the visible
tests, and a small bonus for using fewer actions and tokens. Test files are locked: trying to edit them is
refused and costs points. Doing nothing scores 0, which is exactly what the Random Baseline gets.

**Running it.**

* Standard: hand-pick **Fix the Bug** (`agentic.code-agent`) in New Run: 4 repos, 30 actions each. It's not in
  `core`, so it doesn't change the published Index.
* Hard: **Fix the Bug (Hard)** (`agentic.code-agent-hard`) is part of `frontier`: 3 repos of about 25 files and
  800+ lines, 35 actions (not enough to read everything). Each repo has three bugs. The first one's failing test
  points at the wrong file. The second only appears once the first is fixed. The third is caught **only by the
  hidden tests**: the visible suite goes green without fixing it, so only a model that checks the code against the
  README finds it. The repos are full of red herrings (a deprecated module marked "do not fix", TODOs that look like
  bugs, tests that look flaky but aren't). A model that just makes the visible tests green scores about 0.6, and one
  that fixes everything about 0.98. For the story on screen: "all green… and the hidden tests still fail".
* Cost: roughly 15–25 model calls per case with a growing (but capped) conversation, about 110k input tokens
  per standard case and 230k per hard case. Check the Cost Planner before a big run.
* Manual models work too: every turn appears in the **Manual Inbox**. Paste each new message into the same
  chat (or use the combined prompt in a new chat) and paste the reply back, including its `ACTION:` line.

**Filming it.** Open a case's **Replay** and press **F** (or **B** for Broadcast mode). The left panel is the
repository: the file the model is touching lights up, and changed files get an **M**. The centre shows what it
did this turn: the lines it read, or a red/green diff of its edit. On the right, the **visible tests** bar goes
from red to green each time it runs the tests, with its action and token budgets underneath. The last frame is
the verdict: how many hidden tests pass. The **changes.diff** artifact in the inspector is the model's full
patch, and the score breakdown lists the planted bugs (the answer key) so you can explain them on screen.

## Filming the simulations: island, escape room, startup, liar's table

Open **Run detail → a Survival Island / Escape Room / Startup / Liar's Table case → Replay**, then press **F**
(full screen) or **B** (Broadcast mode). Space plays and pauses, ← → step one move, and the speed menu goes from
0.5× to 4×. Every step has a big headline ("Day 5 · Afternoon: finds fresh water"), green for a good move and red
for a setback, and one plain sentence underneath quoting what the model typed and what happened. Everything on
screen comes from the recorded run. Nothing is re-simulated or made up.

* **Survival Island.** An illustrated map of the island. The castaway walks along a dotted trail, and unexplored
  land stays under fog until the model sees it. Night darkens the map and rain falls across it. The campfire,
  shelter and signal pile appear where they were built, and the ship shows up on the horizon on the days it
  passes. The five stats are meters that flash red when they drop to 20 or below. The inventory is drawn as
  icons. The strip under the map has a column per day with icons for the key events (found water, poison
  berries, shelter, signal fire, ship, rescue). Click an icon to jump to that moment. The last step lifts the fog,
  marks the poisonous bushes and shows the verdict card: "Rescued on day 7", or what went wrong.
* **The Escape Room.** A floor plan of the three rooms. Locks are red while shut and turn green as they open, and
  the doors between the rooms work the same way. The moves meter has a **par** marker (the shortest possible
  solution) and turns amber once the model goes over par. Next to each lock you see every code the model tried:
  wrong codes are struck through in red, with the wrong characters highlighted, and the correct answer appears
  beside them as soon as the lock opens (or at the end).
* **The Startup.** A business dashboard. Market events such as a price war or a supplier price rise appear as
  banners. Cash, equity and the gap to the oracle for the same month are shown as big numbers. The month's price,
  production, marketing and hiring each get a card with an arrow for the change since last month and the oracle's
  choice underneath. A bar shows how many customers wanted the product, how many were sold, how many were turned
  away (sold out) and how many were left on the shelf. The cash chart shows the model against the oracle and the
  autopilot, with the event months shaded.
* **The Liar's Table.** The suspects sit around a table as drawn portraits. The one being questioned lights up
  and answers in a speech bubble. Opened evidence files appear as a folder on the table. Below is the deduction
  board: who says they were where at each time. A cell turns **red** the moment the model's questions expose a
  contradiction. Amber cells are guests who admitted they might be misremembering. The theft time is marked once
  the door log pins it down. At the verdict, the accused and the real thief appear side by side.

**Presenter.** For each of these tests, the deck adds a **Best moment** slide right after the results. It shows
the best-scoring model's run frozen at its turning point (the rescue, the escape, the moment the lie broke, or the
bankruptcy) next to that run's finale card. Runs recorded before this feature still replay in the older
tile-map view, and they get no best-moment slide.

## Long-context, drawing and picture tests on screen

Open a run, click a cell and pick the **Replay** tab (programs) or the **Score** tab (everything else). Every view
starts with a big headline that says what happened, coloured green (right), amber (nearly / fooled) or red
(wrong), with the key number on the right. Use ← → to step, Space to play, the speed menu for 0.5×–4× and **F**
for full screen; press **B** for Broadcast mode.

* **Needle in a Haystack.** The whole document is one long bar, start to end. Each pin is a hidden fact at the
  point where it becomes answerable; pins turn green (found), amber (took the look-alike decoy or the old,
  corrected value) or red (wrong / gave up) as the replay reaches them. The current question's clues are joined
  by an arc and its decoys are amber diamonds. Below: the model's answer next to the correct one, the actual
  sentences zoomed in (with the decoy sentence), and **Who reads to the end?**, the found-rate at the start,
  middle and end of the document.
* **Chain of Whispers.** Every fact is a lane flowing through the rewrites (short summary, long story, again…).
  Solid green = still intact, dashed amber = the wording drifted so it no longer counts (the box shows *Was* and
  *Now*), a red cross = gone. Under it is the text of that rewrite with every surviving fact highlighted, a word
  meter, and the running "still alive" list, which becomes **What survived** on the last step.
* **Draw It Blind.** Step 1 shows the original with numbered shapes next to the model's description; hover a
  highlighted phrase to find its shape (and see deleted numbers struck out). Step 2 compares the pictures three
  ways: side by side with a line and a score between each matched pair, **Onion skin** (drag to blend) and
  **Difference** (black = identical). Then one step per shape with its type / colour / position / size bars.
* **Precise SVG Illustration and Build a Game in One Shot.** A gallery card: the big render, every automatic
  check, the judges as bars with their reasons. SVGs get the prompt's requirements as dashed guides (where the
  clock hands must point, where each bar or chess piece must be) and a table of values the app measured from the
  SVG code; the measurements are for viewers only and never change the score. Games show the checker's
  screenshot, a **Does it work?** list and a big **Play it** button that runs the game in a locked-down frame.
* **Vision tests.** The model's answer is drawn on the picture it saw: for spot-the-difference and board
  questions, the cells it named (green right, red wrong) and the ones it missed (dashed); for counting, every
  shape that should be counted, numbered; for charts, the bars or points the question is about; for handwriting,
  the truly wrong line and the one the model blamed. Its answer sits next to the answer key.

In the **Presenter**, each of these tests gets an extra **answer vs truth** slide after its results: every
model's document strip, the "what survived" grid, the original next to every redrawing, every SVG or game side
by side, or the picture with the answer key and each model's answer. Try it with no keys: open the dashboard
with `?mock=1` and the run *Long context & drawing · replays* (or *Vision · picture questions*).

## Running the Gallery test (AI art)

**The Gallery Masterpiece** asks every model to paint the same eight museum commissions: a lighthouse keeper's
daughter reading at dusk in the manner of Vermeer, an Impressionist harbour at sunrise, a Romantic storm over a
mountain pass, a ukiyo-e bridge in the rain, an Art Nouveau muse, a Renaissance fresco, a Pre-Raphaelite herb
gatherer and a Hudson River School evening. Each brief names six things that must be in the picture, three
things that must not (no lettering, no drawn-in frame, nothing modern) and what the artist may choose freely.
**Painted in Code** gives the same briefs to text models, which paint them as SVG code; it is a separate test
with its own leaderboard column, never mixed with the image generators.

**1. Pick the painters.** Picture-making models are ready in `config/models.json`: **GPT Image 1** (OpenAI,
`OPENAI_API_KEY`), **Gemini 2.5 Flash Image** and **Gemini 3 Pro Image** (`GEMINI_API_KEY`) and **Grok 2 Image**
(`XAI_API_KEY`). They are marked *Makes images* and *Pictures only* (Models → Edit), so text tests skip them. Any
model without *Makes images* is skipped on the image test, never scored 0. Chat apps (ChatGPT, the Gemini app,
Midjourney…) take part as a Manual contestant: set its *vendor* to the right company so its judges are fair.

**2. Check the judges.** Judging uses the judges in Settings (`config/settings.json → judges`). Every painting
needs **at least two judges that can see images, from companies other than the artist's**. With the default
three judges (Anthropic, OpenAI, Google) every painter gets two. If fewer than two are available the painting
is kept and waits for your own rating in **Blind Review** (the New Run page warns you before you start).

**3. Check the cost.** New Run → suite **Art** shows the estimate before you spend anything. One attempt at all
8 commissions costs about $2.02 with GPT Image 1 at high quality (about $0.25 a painting), $0.31 with Gemini 2.5
Flash Image, $1.08 with Gemini 3 Pro Image and $0.56 with Grok 2 Image. Judging costs about $0.06 per painting
per judge-pair. Set `imageOptions.quality` to `"medium"` on GPT Image 1 to paint for about $0.063 each.

**4. Manual chat apps.** When a Manual contestant reaches a commission, the Manual Inbox shows a card marked
**picture reply**. Copy the prompt into a new chat, paste it exactly, and upload the first picture the app makes:
drop the file on the card, click to choose it, or copy the picture in the app and press **Ctrl+V**. Any format
works (WebP and others are converted); a thumbnail or the wrong shape gets a warning. Enter the cost if you know it.

**5. Watch it.** Open the run and press **The Gallery** (or the sidebar's *The Gallery*). Each commission is a
room: every painting in a frame under a spotlight, with a museum placard (title, *Artist: model*, medium, **Brief
followed 8/9**, **Artistry 7.5/10**, cost per image). The rosette marks the best painting of the room. Press
**T** to show or hide the ticks on each painting (green = there, amber = partly, red = missing or broken),
**← →** to walk between rooms and **F** for full screen. Click a painting for the full checklist with every
judge's one-line reason, the six artistry scores with each judge's dot, and how far apart the judges were.
The same view is in the result inspector (Run → click an Art cell).

**6. Present it.** The Presenter adds, after the Art test's results, **one gallery-wall slide per commission**
(every model's painting side by side, with ticks and placards) and a **Masterpiece of the Show** slide: the
highest-scoring painting of the whole test, big, with its brief checklist. Studio → Thumbnails has a **Gallery
thumbnail**: the best painting of the top three artists framed on a museum wall with your headline.

**7. Let people vote.** The Gallery → **Blind vote** shows the paintings of one commission as A, B, C… with the
artists hidden. Click a painting (or press 1 for A, 2 for B…) for each vote from your audience, then **Reveal** (R).
**Save votes** stores each painting's share of the votes as a human score (rater *Blind vote*). It shows as the
*People's choice* and never changes the judged score.

**8. Overrule the judges if you disagree.** Art is subjective, and the app says so. Paintings where the judges'
artistry differs by 2 points or more (or where one judge says yes and another no on a checklist line) are
flagged *judges disagree* and go to the top of **Blind Review**. Every painting can be rated there: your rating
(0–10) **replaces the judges' artistry**; whether the brief was followed stays as the judges checked it. The
placard then says *Artistry · owner*.

**How it's scored, in one line for viewers:** half is how much of the brief it followed (the median judge
verdict on each of the 9 lines), half is artistry (the median of the judges' averages over composition, light,
colour harmony, craft, style and gallery-worthiness, each 1–10). A refusal or no picture scores 0. The Random
Baseline paints coloured noise, so the floor is visible.

**Try it with no keys:** open the dashboard with `?mock=1` and the run *The Gallery · September 2026* (made-up
models, locally drawn placeholder paintings). `node verification/gallery/selfcheck.mts` runs two hand-painted SVG
paintings through the real pipeline with local mock judges (free).

## Arena episode: poker, debates and mock trials

1. **Poker:** Arena → New tournament → *Heads-up Poker*, 4 or 8 models, *Hands per match* 20 (40 for a closer
   result). The note on screen says it: each deal is played twice with cards swapped, so luck cancels out. On the
   live table the viewer sees both hands (each model only saw its own), the board street by street, the pot, the
   stacks and every action with the model's one-line reason. Matches are won on total chips.
2. **Debate / Courtroom:** pick a motion (or a case) or leave it on *a different one for every pairing*. Check the
   judge panel in the estimate: judges never judge a debater from their own vendor, so a pairing may only get one
   or two judges. The live stage shows the two lecterns, the speech streaming into a bubble with a word counter,
   the round indicator and, when the judges have decided, their scorecards one by one (held on screen for a few
   seconds before the next debate starts).
3. **No judge keys?** The debates still run; each game waits with *Awaiting judges*. Open **Judge** on the
   tournament page, read the blinded transcript (Side A / Side B), score it and pick a winner. The model names are
   revealed after you submit, and the tournament continues by itself.
4. **Cards:** *Match cards* show chips per hand for poker and the motion, the sides and each judge's pick for
   debates.

## What viewers see: Arena, Fix the Bug and the live race

Everything below is drawn from what was recorded during the run. Nothing is re-simulated or made up. When a model gave
no reason line, the screen says *not recorded*.

* **Every Arena replay (and the live board)** has a coloured headline above the board that says what just happened
  in one sentence: green for a good move, red for a mistake, blue otherwise. Under it is the model's own one-line
  reason, in quotes. When a move needed a retry, an amber strip shows each rejected try and why it was rejected. It
  turns red when both tries failed and the model got a strike.
* **Connect Four:** a white **WIN** badge sits over any column where a disc would win on the next move, coloured by
  who would win. The empty landing spot in that column has a dashed ring. The headline tells the story: "blocks",
  "misses a win in column 4", "threatens to win". When someone gets four in a row, a line sweeps through the
  winning discs. The key under the board says what each mark means.
* **Chess:** the pieces are drawn by the app, so they look the same on Windows. An orange arrow shows the last
  move. A king in check gets a red glow and a **CHECK** tag. The rail beside the board shows what each side has
  captured and a **material bar**, which counts the pieces left on the board (pawn 1, knight 3, bishop 3, rook 5,
  queen 9). It is not an engine evaluation. The **Material over time** chart on the right follows the replay, and
  you can click it to jump to that point.
* **Poker:** the chip piles for each stack, bet and pot grow and shrink as chips move, and the pot counts up. Under the
  table, the **betting timeline** shows every action street by street, with the cards dealt on each street. At
  a showdown it is replaced by both hands spelled out, with **BEATS** between them, on a hand-rank ladder from
  *High card* to *Straight flush*. In game 2 of a pair, the chips chart shows game 1 (same deals, cards swapped)
  in grey next to game 2, so viewers can see whether the cards or the decisions decided it.
* **Debate / Courtroom:** the stage names both sides. A progress bar has one dot per speech. Each speech has a
  word meter with a tick at the limit, which turns amber near the limit and red over it. In a courtroom, every
  "Exhibit B" in a speech is a link. Click it (or an exhibit chip) and the evidence panel shows that exhibit's
  exact text and how often each side has cited it. At the verdict, the judges' cards turn over one by one, each
  vote dot drops in, and the decision banner lands last. If a level match was settled on the judges' points, a
  gold **Tie-break used** strip shows the totals.
* **Bracket and match cards:** winners slide into the next round one round at a time, and the champion is revealed
  last with a trophy. Match cards slide both players in. They add a pip per game with its winner, and show each
  model's illegal moves and spend. They also show the tie-break strip when it was used.
* **Fix the Bug replay:** a strip of icons across the top tells the whole story (read, search, edit, run tests,
  submit, verdict). Click any tile to jump to it. Diffs are syntax-coloured, and the visible-test bar turns green
  one test at a time. **Actions used** shows the action budget as one block per action, coloured by kind. The
  verdict counts up to the hidden-test score, stamps *Fixed*, *Partly fixed* or *Not fixed*, and shows before
  and after bars. If every visible test was green but hidden tests still fail, a red **A bug the visible tests
  didn't show** box appears (demo: `?mock=1`, Core run, Fix the Bug, *seed-202 r1*).
* **Live Arena:** the header shows **the race**. Each model has a track, its runner sits at the share of cases it
  has finished, and its mean score is on the right. The leader's track and lane card are gold and carry a crown.
  Each lane's score ticks up or down (▲ / ▼) after every graded case. When a run you are watching finishes, a
  checkered-flag **Finish** moment shows the winner and the podium. Replay it with *Replay the finish* on the
  finish-line page, or open the live page with `?finish=1`. A running run's detail page shows the same race above
  the tabs.

## Recording a run live

Use this when you want to film a run while it is happening, not just the results afterwards. Nothing here calls a
model or costs extra: every word and number on screen comes from the run itself.

**Before you start**

1. Start the run as normal (New Run → Start). On the run's page, the **Watch it think** panel appears straight away,
   with a live commentary feed beside it.
2. Click **Watch it think** (or *Full screen for recording*) to open the recording view: `…/#/runs/<run id>/watch`.
3. Press **B** for Broadcast mode. Pick the commentary style at the top before you press B:
   * **Feed**: a column on the right, newest line on top.
   * **Crawl**: a TV-style strip scrolling along the bottom.
   * **Off**: tiles only.
4. Want to rehearse with no API keys? Open the app with `?mock=1` and choose the run *Eight-model quiz · live*
   (8 models) or *Agents Showdown · live* (6 models). The mock run types, thinks, grades and comments on a timer, just
   like a real one.

**What viewers see**

* **One box per model**, with the model's colour along the top and its average score so far on the right. A gold
  crown marks the leader.
* **What it is working on**, in words: "Question 3 of 5", or "Game 2 of 3 · Day 4" for a simulation. The big heading
  at the top names the test most models are on ("Now testing: Knights & Knaves") with its one-line hook.
* **The answer typing in**, in large monospace text. It scrolls by itself, and the newest two lines are bright while
  older lines fade.
* **"Thinking…"** while a model has not written anything yet. Reasoning models often think before they write. Their
  hidden reasoning is never shown, because the providers don't send it.
* **A clock** for the current case, **a token counter** and **a cost ticker**. While the model types, tokens and cost are
  estimates, marked **≈** (about 4 characters per token, times the model's output price). When the case is graded,
  they switch to the exact recorded numbers.
* **The verdict** flashes on the box for about two seconds: a green tick and *Correct 100/100*, a red cross and
  *Wrong 0/100*, or amber for partly right. Under it is the grader's own summary, for example *Chose B · expected D*.
  Then the box moves on to the next case.
* **Live commentary**, written by the app from the results (no AI involved). For example: "Nova 3 Pro gets Knights &
  Knaves Q4 right, in 12.3 s", "Quill Flash misses Probability Traps Q2: said 0.5, answer 0.375", "Kite takes the
  lead from Nova 3 Pro", "3 of 5 models have finished Logic Grid". The random-guessing baseline is left out of the
  commentary and never "leads".

**The OBS overlay for a live run**

If you record your own screen or camera in OBS, add the **Live run** band as a Browser source:
`http://localhost:7777/overlay/live` (it follows whatever run is going on), or `…/overlay/live?run=<run id>` for one
run. Set width 1920 and height 1080. The background is transparent, so only the band shows. The band has the
Gauntlet logo, the **leader** and their average, a **progress bar** ("62% · 148 of 240 answers graded"), the **money
spent so far**, and the **commentary crawl**. Add `&theme=light`, `&theme=solid` or `&pos=top` to change its look
or position. Studio → OBS overlays → **Live run** builds the address and previews it for you. Use `…/overlay/demo?view=live`
to line it up before a real run.

**Good to know**

* Up to 8 models fit on one 1080p screen (4 × 2). More models still work; the boxes just get smaller.
* If a provider cannot stream, its box shows "Thinking…" and then the whole answer at once when it arrives.
* If a call fails and is retried, the half-written text from the failed try is removed, because the recorded result
  only keeps the final answer.
* Watching never changes a run. The live view only shows the text; the prompts, recorded results and scores are
  exactly the same with or without anyone watching (this is covered by an automated test).
* The viewer caption strip (press **C** in Broadcast mode) explains the screen in one sentence. The tiles make room
  for it.

## The Horizon tier: tests built for future models

The Horizon suite is for the question every AI video eventually runs into: *"the best model already gets 100%,
so what now?"* Its five tests are ladders of ten levels, built so that today's best models stall low and
tomorrow's can climb. The story on screen is simple: **how far up the ladder did each model get?**

**Run it.** New Run → suite **Horizon: Tests Built for Future Models** (or `node src/cli.ts run --models a,b --suite horizon --repeats 1 --max-cost 60`).
Use 1 repeat for a first look and 3 for a published result. Replies are long; see the cost note below and set a
spending cap.

**What viewers see.**

* **The explainer slide** asks the question in one line ("Can it run a program for 70,000 steps in its head?").
* **"How far up the ladder"** (a Presenter slide after each Horizon test's results, and at the top of the result
  inspector): a mountain with a ten-rung ladder. Each model's token stands on the highest rung it reached
  without missing one below; the dots on every rung show that level's result (filled = solved, ring = solved
  sometimes or partly, empty = failed, dashed = not run). The summit, level 10, is labelled "the horizon".
  The board on the right gives each model's height in big numbers, and notes a lucky solve higher up.
* **One level, answer vs truth** (click any level in the inspector): a mini ladder showing where the level
  sits, then the model's answer against the key. Numbers are compared digit by digit ("right for 11 digits,
  then wrong"); sliding-puzzle plans are replayed with the first illegal move boxed in red and the proven
  minimum beside the model's move count; nonogram grids are drawn side by side with every wrong cell in red.

**Talking points that are true.**

* Every answer is checked by machine against a key proven by two independent programs; there is no judge and
  no opinion anywhere in this suite.
* Every level can be done with pencil and paper; the top ones would take a person weeks. The difficulty is
  depth and exactness, not trick wording.
* The levels never change. When you film the same suite in two years, a new model is climbing exactly the same
  ladder, so "it got two rungs higher" is a real, comparable result.
* A model that honestly says "I can't do this reliably without a computer" scores 0 on that level, the same as a
  wrong answer. That is fine to show: knowing its limits is good behaviour, but the ladder measures ability.

**Cost.** Every model may write up to its own maximum reply ("model's maximum", e.g. 128,000 tokens for Opus
5.5), because the best answers are long: in the calibration Opus used 67,000–96,000 tokens on some levels it
solved. The estimate is about **$30 per frontier model per repeat** for the whole suite and **$7–8 for a small
model**; the worst case (every level using the whole maximum) is about **$128 for Opus 5.5** and **$16 for
Haiku 4.5**. New Run and the Cost Planner show both numbers before you start. Set a spending cap, or pick
"Same token limit for every model" if you want a cheaper, level playing field.

**What today's models score** (blind calibration, one attempt per level, September 2026; details in
[AUDIT.md](AUDIT.md#horizon-tier-blind-calibration)): Opus 5.5 about a quarter of the suite, clearly solving the
first two to four levels of every ladder; Haiku 4.5 about 2%; random guessing 0%. So a new model that reaches
level 5 anywhere is news.

**Mock mode.** `?mock=1` has a demo run, *Horizon ladders · demo climb*, with fictional models at different
heights, so you can rehearse the slides with no API keys. The screenshots are in `docs/screenshots/horizon/`.

## Running the Game Jam

**The Game Jam** (`creative.game-jam`, version 2, in its own `games` suite) gives every model five full game
design briefs and asks for a complete browser game for each, in one reply, as one HTML file: a **Flappy Bird
remake**, a **Command & Conquer-style RTS**, a **top-down action RPG**, a deliberately hardest **zombie survival**
game (with balanced base building: structures cost scarce materials, get damaged and broken, hordes grow with the
size and noise of your base, and the base is never a win button) and a **racer**. Each brief has 14 to 19 numbered
requirements, controls, screens, feel targets and a "definition of done".

**The visual bar.** Version 2 asks for games that "look like a polished commercial indie game on Steam". Every brief
now carries the same eleven-point visual bar (a stated art direction and palette, crisp full-HD rendering at the
screen's pixel density, parallax depth, dynamic light and glow, particle systems with hundreds of particles,
characters with real animation and squash and stretch, a camera with follow, shake and zoom, post effects, a
designed HUD and title screen, juice, and a steady 60 fps) plus art direction for its genre. WebGL and hand-written
shaders are explicitly encouraged (a real 3D racer is welcome); it must still be one file with no libraries or
downloads. The "surprise us" invitation stays: the setting, story and twist are the model's.

**No artificial limits.** Every model may write up to its **own maximum output** (the test asks for `"model-max"`),
not a fixed 64k: 128,000 tokens for the current Claude and GPT-5.x models, 65,536 for Gemini, 393,216 for DeepSeek
V4 Flash, a conservative 128,000 for Grok (xAI publishes no cap). The numbers, and where each came from, are in
`config/models.json` (`maxOutputTokens` and `maxOutputTokensSource`; "unverified" means we could not check the
provider's own page). There is no file-size cap either, only a 20 MB safety net against runaway output. A case may
run for up to 3 hours, network timeouts are switched off for these long streams, and a failed call is retried once
at most (a retry restarts a paid reply from scratch).

**What it costs.** About 43,000 output tokens per game is typical; the ceiling is every game using the model's
whole allowance. For the whole jam (5 games, 1 repeat), from `node src/cli.ts costs --suite games`, at
£1 = $1.33:

| Model | Typical | Upper bound (every reply at its maximum) |
|---|---:|---:|
| Claude Fable 5.1 | £8.20 ($10.93) | £24.20 ($32.18) |
| Claude Opus 5.5 | £3.30 ($4.37) | £9.70 ($12.87) |
| Claude Sonnet 5 | £1.65 ($2.19) | £4.85 ($6.44) |
| GPT-5.6 Sol | £4.90 ($6.54) | £14.50 ($19.29) |
| GPT-5.6 Terra | £2.45 ($3.27) | £7.25 ($9.64) |
| Gemini 3.1 Pro | £1.95 ($2.62) | £3.00 ($3.97) |
| Gemini 3.5 Flash | £1.45 ($1.96) | £2.25 ($2.98) |
| Grok 4.7 | £1.00 ($1.33) | £2.90 ($3.88) |
| DeepSeek V4 Flash | £0.20 ($0.26) | £1.75 ($2.36) |

Add roughly £1 ($1.30) of judges per model (more at the upper bound, because the judges read the whole file).
New Run and the Cost Planner show both numbers for your exact selection, in pounds. If the upper bound is more than
you want to risk, set a spending limit (next section): the run can then never spend more.

**How to run it.**

```bash
node src/cli.ts run --models claude-opus-5-5,gpt-5.6-sol,gemini-3.1-pro --suite games --repeats 1 --max-cost 40
```

Or **New Run → suite "The Game Jam"**, and pick a spending limit in **4 · Spending limits** (for example £30).
It runs once per model by default.

**How it's scored.** Every game is played by a scripted "robot player" for its genre for **30 seconds** in a
headless browser at **1920×1080** (flaps; box-select and right-click orders; walking and attacking; WASD, aiming
and shooting; accelerating and steering), on a fixed clock with fixed randomness, so a re-run of the same file gives
the same screenshots. **Eight** full-HD screenshots are kept (the title screen at 0.5 s, then 3, 6, 10, 14, 19, 24
and 30 s, in the middle of the action) plus a **motion strip**: six frames a tenth of a second apart at 16 s, so the
judges can see how things animate.

* A quarter of the score: 10 automatic checks (it loads, no downloads from the internet, under the 20 MB safety net,
  no JavaScript errors, draws a canvas, reacts to input, shows a picture, keeps moving, still running after 30 s,
  no errors while playing).
* Three quarters: two or three AI judges from other companies (never the model's own), who read the whole file and,
  when they accept images, see all nine pictures (high-quality JPEGs). Each judge marks every numbered requirement
  PASS, PARTIAL or FAIL and scores five things out of 10 against written anchors. The judge total weights:
  **visual quality & art direction 30%**, **creativity & originality 25%**, requirement checklist 20%, does it
  actually play 10%, game feel & juice (incl. audio) 10%, ambition & depth 5%.
* The visual scale is anchored hard: 10 = could pass for a screenshot of a polished commercial indie game;
  7 = clearly art-directed, lit, animated and cohesive; 5 = clean but simple flat shapes; 3 = basic shapes, little
  animation; 1 = placeholder rectangles. Most games are not a 10.
* A judge that cannot see images grades visuals from the drawing code and is told to be conservative. On the visual
  score, a judge who saw the pictures counts **three times** as much as a text-only judge (other criteria are a
  plain average). The scorecard marks text-only judges "read the code only".
* A game that **froze** or showed a **blank screen**, or that downloads files, can never score above 30.
* A reply that **ran out of output space** (hit its output limit, so the file is cut off) is still saved, tested and
  shown, and says so in plain words; the judges are not asked, so it scores the automatic quarter at most. When one
  of your spending limits set that output limit, it says "stopped by your per-answer spend limit" (or "by your run
  spend limit") instead, so nobody blames the model.

Results from version 1 (the 64k cap, 15-second playtest, creativity-first weights) keep their own weights on the
scorecard and are not mixed with version 2 on the leaderboard (a new test version has a new hash).

**Your override.** Every game lands in **Blind Review** for an optional human rating; when the judges disagree by
more than 3 points out of 10, your rating becomes the score. In the **Grader** you can paste any chatbot's reply to
a round and get the same playtest, checks and judge card.

**On screen.**

* **Run detail → Game Jam**: the cabinet wall. One shelf per model, one arcade cabinet per genre; each screen flips
  through the recorded playtest screenshots, a crown marks the best game of each genre, and the banner names the
  **Game of the Jam** (best average; ties go to the better visuals, then creativity). Click a cabinet for the card.
* The **card** (also in the result inspector and the Grader): **Play it** runs the game in a locked-down frame; the
  filmstrip shows what the robot player saw with "moved" and "input" bars; the motion strip; "How the score adds
  up"; the judges' scorecard with the six criteria and their weights, a dot per judge and each judge's verdict; and
  the brief's numbered requirements with ticks, dashes and crosses.
* The **Presenter** adds one slide per genre, every model's game side by side as cabinets, then the Game of the Jam.
  Its closing methods slide lists the run's limits (see below).
* Try it all with no keys: `?mock=1` → Runs → "The Game Jam · five genres". The demo games are hand-written samples
  that went through the real version 2 playtest; only the demo judges were scripted. The samples are deliberately
  simple, so they score low on visuals, which is what the new scale is for.

**Checking the checker.** `node verification/game-jam/e2e.mts` runs the sample games (working, syntax error,
endless loop, blank canvas, cut-off reply) through the real scorer and asserts each is caught.
`node verification/game-jam/briefs.mts` rebuilds the test file from the briefs.

## Setting a spending limit (in pounds)

Nothing is capped by default: models use their full output and a run finishes whatever it costs. When you want a
ceiling, you have three optional controls, all under **New Run → 4 · Spending limits**.

**Your currency.** Costs are shown in **pounds** everywhere (New Run, the Cost Planner, the run page, Watch it
think, the OBS overlay, the Presenter). Next to the limit you see the rate, e.g. "£1 = $1.33 · edit". Click
**edit** to type today's rate from your bank (or switch to dollars or euros); Gauntlet never looks the rate up
online. Underneath, everything is still measured and stored in US dollars, the unit providers bill in, so changing
the rate never changes a result. The setting lives in `config/settings.json` (`currency`).

**1. Spending limit for the whole run.** Pick **No limit, £5, £10, £30, £50 or Custom**. Models and judges together
never spend more than this:

* Before every call, Gauntlet reserves that call's worst case (its prompt plus its whole output allowance, at the
  model's prices), including calls running at the same time. If the full request does not fit, it waits for
  running calls to finish (they usually cost far less than their worst case); if it still does not fit, it lowers
  that call's output allowance to what the money left can buy, and if not even a short reply is affordable it does
  not start the call.
* **Judges count** towards the limit. The run page shows "Spend · limit" with a bar.
* When the money runs out, the run stops cleanly and says **"Stopped: spend limit"** (amber, not a failure). Every
  finished result is kept; a case that was stopped half-way is not stored (so no model is marked down for it), but
  what it spent is still counted. Press **Resume with a higher limit** to finish.
* In the Arena the same presets are under **Spending limit** when you set up a tournament.

**2. Per-answer limit (optional).** "Any single game may cost at most £2." Pick **Off, £0.50, £1, £2, £5 or Custom**.
Each model's output allowance per reply becomes what that money buys at its own output price, and the estimate
shows the resulting "Output limit per reply" for every model. A reply cut off by it is labelled **"stopped by your
per-answer spend limit"**.

**3. Output limit: each model's own maximum, or the same for every model.** The fair alternative to a money limit.

**Fairness, in one sentence:** a money limit gives models different room (£2 buys about 80,000 output tokens of
Claude Opus 5.5 but about 800,000 of a budget model), so for a like-for-like comparison turn the per-answer limit
off and choose **Same token limit for every model** (e.g. 64,000 tokens; a model whose own maximum is lower keeps
its maximum). A whole-run limit is fair as long as the run finishes; if it stops early, the unfinished models have
fewer results. Whatever you choose is recorded with the run (`settings.limits` in the manifest), shown as chips on
the run page, and printed on the Presenter's closing methods slide, so your video can disclose it.

From the command line: `--max-cost 40` (US dollars), `--per-answer 2.5` (US dollars per reply) and
`--same-tokens 64000`.

### Your own budget

Tired of picking a limit every time? Set your own budget once, on the **Budget** page (left menu, under Lab; New Run
links to it as well). Everything is typed in pounds, with the same **£5 / £10 / … / Custom** buttons, so any amount
works.

* **Monthly budget**, e.g. **£50 a month**. The page shows a meter of what you have spent this month: green, amber
  from 80 %, red once it is used up. Below it are a bar per day and a **Recent spending** list (every run, Arena
  tournament, AI judge grading a pasted answer, and Studio script polish), plus the date it resets. A "month" is the
  calendar month on your computer's clock, so it starts again at midnight on the 1st. Money is counted in the month
  it was spent: finishing an August run in September counts the new spending in September. Manual (copy & paste) and
  Random Baseline contestants cost nothing; the AI judges that grade them do count.
* **Hard stop** (optional, needs a monthly budget). **On:** once the month's budget is used up, no run or tournament
  can start or resume (you get a clear message with the reset date). While money is left, a new run's limit is
  lowered automatically to what is left, and New Run says so: "Limited to £12.40: what's left of your £50 monthly
  budget". The run then stops cleanly like any spend limit and you can resume it next month. **Off:** you only get
  warnings.
* **Default whole-run limit** and **default per-answer limit**. Pre-selected in New Run (and the run limit in new
  Arena tournaments). You can still change them for any single run.

On New Run and the Arena setup, a line above the start button reads e.g. "This month: £40.77 of £50.00 spent · this
run up to £9.23", and warns if the run's upper-bound estimate is more than what is left. It is hidden in Broadcast
mode, so your budget never appears in a recording. The rules are also checked by the server itself, so nothing can
sneak past them (not even the command line).

From the command line: `node src/cli.ts budget` shows the month; `node src/cli.ts budget set monthly 50`,
`budget set hard-stop on`, `budget set run 10`, `budget set per-answer 2` and `budget set monthly off` change it
(amounts in pounds). The settings are saved in `config/settings.json` under `budget`.

## Cost-saving tips

* Iterate on `quick` with 1 repeat. Use `core` with 3 repeats only for the published run.
* Cheap models first: a whole `core` pass on a budget model costs a fraction of a frontier model's.
* Simulations and long-context tests are the priciest per case. For a themed episode, run just those.
* Estimates get sharper after your first real run of each test, because Gauntlet measures actual token usage.
* Judge-scored tests (Honesty Trap, games, SVG) add judge cost. The same-vendor exclusion means each model is
  graded by the other two judges, not all three.
