# Episode playbook: from idea to published result

A practical checklist for running a benchmark you can put on video and defend in the comments.

## 1. Plan (5 minutes, $0)

1. **Pick the question** the episode answers: "Which model is the best agent?", "Is the new model worth 5× the
   price?", "Do old models really do worse?" Choose the suite or tests that answer it:
   * a head-to-head across everything: `core`;
   * a themed episode: hand-pick tests (e.g. all simulations for "AI Survivor");
   * a cheap first look: `quick`.
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
  side-by-side, and Fix the Bug's file tree, diffs and test bar going from red to green.
* **Leaderboard** for the reveal: Index with confidence intervals, category heatmap, medal table, and the
  score-vs-cost chart with the Pareto frontier.
* Games from **Build a Game in One Shot** are playable inside the result inspector, and there's a screenshot
  of each.

## 5. Review

* **Blind Review**: rate the games and illustrations, and settle any case where the judges disagreed. Model
  identities are hidden until you've scored.
* Skim errors and refusals in the run detail. Don't quietly re-run until they vanish: they're part of the
  result.

## 6. Publish

* `node src/cli.ts report <runId> --format md` produces a Markdown leaderboard for the video description.
* **Export JSON** gives the full manifest, every prompt, response and score, and the fingerprint for anyone
  who wants to audit.
* Say on screen: models, repeats, fingerprint, "differences inside the confidence intervals are ties",
  and which prices were verified on which date.
* Don't publish the prompt book for tests you want to keep reusing. Keep a held-out set in `tests/private/`.

## Coding-agent episode: Fix the Bug

**What it is.** Each case drops the model into a small, real JavaScript project (an invoice calculator, a
text-adventure parser, a date library, an API client with a cache; the hard tier has an inventory service, a
Markdown converter and a booking calendar). The project's tests are red because of two or three planted bugs.
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
* Hard: **Fix the Bug (Hard)** (`agentic.code-agent-hard`) is part of `frontier`: 3 bigger repos, 40 actions.
* Cost: roughly 15–25 model calls per case with a growing (but capped) conversation, about 110k input tokens
  per standard case and 210k per hard case. Check the Cost Planner before a big run.
* Manual models work too: every turn appears in the **Manual Inbox**. Paste each new message into the same
  chat (or use the combined prompt in a new chat) and paste the reply back, including its `ACTION:` line.

**Filming it.** Open a case's **Replay** and press **F** (or **B** for Broadcast mode). The left panel is the
repository: the file the model is touching lights up, and changed files get an **M**. The centre shows what it
did this turn: the lines it read, or a red/green diff of its edit. On the right, the **visible tests** bar goes
from red to green each time it runs the tests, with its action and token budgets underneath. The last frame is
the verdict: how many hidden tests pass. The **changes.diff** artifact in the inspector is the model's full
patch, and the score breakdown lists the planted bugs (the answer key) so you can explain them on screen.

## Cost-saving tips

* Iterate on `quick` with 1 repeat. Use `core` with 3 repeats only for the published run.
* Cheap models first: a whole `core` pass on a budget model costs a fraction of a frontier model's.
* Simulations and long-context tests are the priciest per case. For a themed episode, run just those.
* Estimates get sharper after your first real run of each test, because Gauntlet measures actual token usage.
* Judge-scored tests (Honesty Trap, games, SVG) add judge cost. The same-vendor exclusion means each model is
  graded by the other two judges, not all three.
