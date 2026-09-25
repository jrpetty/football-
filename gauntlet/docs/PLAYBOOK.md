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
  landing.
* **Run detail → a simulation case → Replay** for the story beats: the Survival Island map, the Escape Room
  moves, The Startup's cash curve against the oracle, the Liar's Table interrogation, the Draw It Blind
  side-by-side, and Fix the Bug's file tree, diffs and test bar going from red to green.
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

## Cost-saving tips

* Iterate on `quick` with 1 repeat. Use `core` with 3 repeats only for the published run.
* Cheap models first: a whole `core` pass on a budget model costs a fraction of a frontier model's.
* Simulations and long-context tests are the priciest per case. For a themed episode, run just those.
* Estimates get sharper after your first real run of each test, because Gauntlet measures actual token usage.
* Judge-scored tests (Honesty Trap, games, SVG) add judge cost. The same-vendor exclusion means each model is
  graded by the other two judges, not all three.
