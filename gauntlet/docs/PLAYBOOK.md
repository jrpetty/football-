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
  side-by-side.
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
