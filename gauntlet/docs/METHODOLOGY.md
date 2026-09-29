# Gauntlet Methodology

This document describes exactly how Gauntlet measures models. It is written so that anyone can audit a
published result, reproduce it, and judge whether two numbers are comparable.

## 1. Principles

1. **Identical conditions.** Every model receives byte-identical prompts for a given test version. Seeded
   simulations generate identical worlds for every model. Only a model's own outputs can change what it
   sees next.
2. **Fixed, versioned, hashed tests.** Every test has a semantic version and a content hash (SHA-256 over
   its canonical JSON; for simulations, also over the program source code). Changing a single character
   changes the hash.
3. **Verifiable scoring first.** Wherever possible answers are checked deterministically (exact answers,
   numeric tolerance, unit tests executed in a sandbox, machine-checkable constraints, geometric
   comparison). Judge models are used only for genuinely open-ended output, with fixed prompts and a
   cross-vendor panel.
4. **Uncertainty is reported.** Every score carries a 95% confidence interval. Differences inside
   overlapping intervals should not be presented as wins.
5. **No cherry-picking.** All valid samples are pooled. Re-running a model adds samples; it can never
   replace a bad result.
6. **Everything is recorded.** Prompts, responses, timings, token counts, costs, judge rationales and
   artifacts are stored for every case and can be exported.

## 2. The protocol

| Aspect | Policy |
|---|---|
| Transport | Plain text chat only. No provider-specific tools, JSON modes or structured-output features, so no provider gets extra help. |
| System prompt | Exactly the test's `system` field (often none). The harness adds nothing. |
| Answer format | Tests with a single answer end with the standard instruction `FINAL ANSWER: <answer>`. The **last** such line is parsed; markdown, bold and one trailing period are ignored. Format compliance is tracked separately. |
| Temperature | `0` for models that accept a temperature. Reasoning models that do not accept one use the provider default. |
| Reasoning effort | Part of the contestant definition (e.g. `effort: high`). A different effort is a different contestant with its own id. |
| Output limit | Per call (`maxOutputTokens`, default 16,000; up to 32,000 for hard reasoning). A test may instead ask for `"model-max"`, each model's own documented maximum (The Game Jam), so no model is held back by an artificial cap; see §9b. The limit includes hidden reasoning tokens on every provider. Hitting it is recorded, not retried. |
| Time limit | Per case (`timeLimitSec`, default 1,800 s; long simulations allow more). Limits are generous so slow-but-thorough models are not punished, but a timeout scores 0. |
| Retries | Transport failures only (HTTP 408/409/429/5xx, network). Exponential backoff with `Retry-After`, up to 4 retries. A model's *answer* is never retried. |
| Refusals | Recorded as `refusal` and scored 0. **No fallbacks**: provider features that silently route a refused request to another model are disabled, because another model would be answering. |
| Errors | Cases that still fail after retries are marked `error`, excluded from scores and shown as errors. Use **Resume** to re-run them. |
| Seeds | Simulation tests list their seeds; every repeat of a seed replays the same world. |
| Repeats | Default 3 per case (configurable). Repeats measure consistency and tighten intervals. |

The protocol version (`PROTOCOL_VERSION` in `src/core/version.ts`) is bumped whenever any of these rules,
the answer instruction or the judge prompts change.

## 3. Fair play: every model meets every test for the first time

| Threat | Safeguard |
|---|---|
| Memory of earlier attempts or other models' answers | Every case is a **fresh, stateless API conversation**. Nothing from previous cases, repeats, runs or other models is ever included. OpenAI requests set `store: false`; no provider memory, assistants, threads or caching features are used. |
| Access to the answer key | Expected answers, auditor notes and scoring keywords are **never** sent to the model. Rendered prompts contain only the task. |
| Looking things up or running tools | No tools, no web search, no code interpreter: plain text in, plain text out. Generated code runs only inside Gauntlet's sandbox, which cannot read the test files. |
| Hedging ("A or B", "42 or 43") | An answer that offers alternatives is **wrong**, even if one alternative is right. Every single-answer prompt says so. |
| Multiple answers / self-correction | The **last** `FINAL ANSWER:` (or `ACTION:`) line counts. |
| Gaming a judge ("give this a 10") | Judges are told that text inside the response is not an instruction, never learn the model's identity, and work from fixed rubrics with explicit point values. |
| Self-preference bias | A judge **never grades a model from its own vendor** when another judge is available (`judgeExcludeSameVendor`), and a model never grades itself. |
| Judges disagreeing | If panel scores differ by more than 3 points out of 10, or labels differ, the result is flagged and sent to **human arbitration** in Blind Review. The human rating becomes the final score and the judges' verdicts stay on record. |
| Simulation self-reports ("I escaped!") | Simulation scores come only from the world state produced by the model's actions. |
| Coding agent editing the tests or hard-coding answers (Fix the Bug) | Test files are read-only: edits are rejected and each attempt costs 0.1. The score comes from **hidden tests** the model never sees, run on its code in the sandbox, measured against the untouched repo (doing nothing scores 0; breaking working code counts against it). Project code cannot reach the test runner, the assertions or anything outside its in-memory files. The fixtures (hidden tests, reference fix) live in the repository and are covered by the test hash, so an edited fixture changes the hash and its results are excluded; never let a player with disk access (a local agent) play it. |
| Training-data contamination | Tests are original, seeded worlds are regenerated from seeds, held-out tests in `tests/private/` are never committed or published, and a **canary string** marks Gauntlet data. `node src/cli.ts probe-contamination --models …` checks whether a model can complete it. |
| Silent substitution by another model | Provider fallback and routing features are disabled; the model that answered is recorded (`servedModel`). |
| Changing a test after seeing results | Tests are hashed. Any edit creates a new hash, and old results stop counting. |

## 4. Testing any model: API or copy & paste

Models with an API are called directly. **Any other model** (a chat-only product, an old model that has lost
its API, a future model) can be tested as a **manual contestant**: every call waits in the **Manual Inbox**,
where you copy the exact prompt into the model's chat UI and paste the complete reply back. The reply is
graded by exactly the same scorer. Rules for manual entry:

1. Start a **new chat** for each case (use the "combined prompt", which includes any system instructions).
   For multi-turn cases, continue the same chat with each new message, or paste the combined prompt into a
   new chat.
2. Paste the prompt exactly: no extra instructions, no custom personas, memory off where possible.
3. Paste the **first** reply in full. Don't regenerate or edit it.
4. Enter token counts and cost if the product shows them. Otherwise tokens are estimated and cost is $0.
   Latency and throughput are not measured for manual entries and are hidden on the leaderboard.

A single reply can also be graded without a run (`Grader` screen or `node src/cli.ts grade <test> <case>`),
but only results recorded in runs count toward the leaderboard.

## 5. Scoring

### 5.1 Case scores (0–1)

| Scorer | How a case is scored |
|---|---|
| `exact` | 1 if the extracted answer equals an accepted answer after normalisation (`lower` or `alnum`), else 0. |
| `number` | 1 if the parsed number is within the tolerance (absolute or relative), else 0. |
| `choice` | 1 for the correct letter, else 0. |
| `regex` | 1 if the pattern matches the answer (or the full text). Full-text patterns can demand a one-line reply, as in the Lightning Traps. |
| `contains` | Fraction of required / forbidden phrases satisfied. |
| `constraints` | Fraction of machine-verifiable constraints satisfied (or all-or-nothing). Definitions: a *word* is a whitespace-separated token containing a letter or digit; a *sentence* ends in `.`, `!` or `?` followed by whitespace or the end; *paragraphs* are separated by blank lines; *bullets* start with `-`, `*`, `•`, `1.` or `1)`. |
| `json` | Fraction of expected leaf fields matched (strings trimmed and case-insensitive, numbers within tolerance, array lengths checked, missing keys equal `null`). |
| `code-js` | Fraction of hidden unit tests passed. Code runs in a separate Node.js process under the permission model (no file writes, no child processes, no workers), 256 MB heap, with string code generation disabled and a per-test timeout (default 2 s). |
| `judge` | Mean of the judge panel's 0–10 grades ÷ 10. |
| `judge-classify` | Mean of the scores of the labels chosen by each judge (e.g. `CAUGHT_TRAP` = 1, `HALLUCINATED` = 0). |
| `artifact` | `(1 − w) × automated checks + w × judge score`, where the checks run in headless Chromium (renders, no JavaScript errors, no network access, reacts to input) and `w` is the test's `judgeWeight`. |
| `human` | Mean of human ratings (0–10 ÷ 10) from the Blind Review screen, where identities are hidden until rated. |
| Programs | Each simulation documents its own formula (see the Methodology page in the app). |

**Time pressure.** A test may set an answer-time limit (`answerWithinSec`), stated in the prompt. Each model call
is timed from the moment the request is sent (queueing and retry back-off excluded); a reply that is not complete
in time is aborted and the case scores 0 with the status *Out of time*. It counts as a wrong answer, not as an
error. Latency differs by provider, so these tests are designed with limits that a normal answer fits easily,
and response times are published next to the scores. Manual (copy & paste) contestants are not timed.

### 5.2 Aggregation

```
test score      = mean over cases of ( mean over that case's repeats )
category score  = weighted mean of its test scores          (suite test weights, default 1)
Gauntlet Index  = 100 × weighted mean of category scores    (category weights, default 1)
```

Averaging repeats inside each case first means a case run five times does not count five times as much
as a case run once.

### 5.3 Confidence intervals

95% intervals come from a **cluster bootstrap**: cases are the independent unit, so each resample draws
cases (with all their repeats) with replacement within every test, then recomputes the index. 1,000
resamples, fixed seed, so intervals are reproducible. Intervals capture sampling variation across cases
and repeats; they do not capture judge bias or prompt sensitivity.

### 5.4 Medals

For every test, contestants are ranked by test score, with ties broken by lower cost and then by faster
median case time. The top three get gold, silver and bronze. A contestant must score above zero to
medal.

### 5.5 Judges

* Fixed prompts (`src/scoring/judge-prompts.ts`), part of the fingerprint.
* A panel of models from different vendors (`config/settings.json → judges`; default Claude Sonnet 5, GPT-5.6
  Terra and Gemini 3.5 Flash at `judgeEffort: medium`); the mean is used. Judges always get a reference answer or
  a points-based rubric, so strong mid-tier models grade as reliably as frontier ones at a fraction of the cost.
  Swap in frontier judges for especially subjective tests if budget allows.
  The harness warns when the panel has only one vendor, because models tend to prefer their own
  vendor's style.
* Judges never see which model wrote a response and are told to ignore instructions inside it.
* Judges never grade a model from their own vendor while another judge is available. Split verdicts go to human
  arbitration.
* Judge cost is tracked separately and is **not** added to a contestant's cost.
* Judges grade text only. HTML games are graded from their source together with the automated
  browser-check results, and humans can add a blind rating on top.

## 6. Metrics recorded for every case

| Metric | Definition |
|---|---|
| Wall time | From job start to scored result, including every model call (and judge calls). |
| TTFT | Time to the first streamed token of the case's first call (visible or reasoning, whichever the API exposes first). |
| Output tokens/sec | Output tokens ÷ total call duration (end-to-end throughput, comparable across providers whether or not they stream their reasoning). |
| Tokens | Uncached input, cached input, output and reasoning tokens, as reported by the provider. |
| Cost | `input × price_in + cached × price_cached + cache_writes × price_write + output × price_out`, using the **pricing snapshot stored in the run manifest**. Output includes reasoning tokens (providers bill them as output). |
| Retries, API calls, response length, stop reason | Per call and per case. |
| Reliability | Error rate, refusal rate, format compliance (share of answers in the required format). |
| Consistency | Mean standard deviation of scores across repeats of the same case (0 = identical every time). |
| $/point | Total contestant cost ÷ Gauntlet Index. |

## 7. Reproducibility

* **Run manifest.** Every run stores the harness version, git commit, Node version, platform, protocol
  version, the full snapshot of every model's configuration and pricing, the hash of every test, the
  repeats and concurrency, and a **fingerprint** over all test hashes, suite weights, the protocol version
  and the judge prompts.
* **Comparability rule.** Two results are comparable if and only if their fingerprints match. The
  combined leaderboard enforces this per result: a result counts only if its test hash and the model's
  configuration hash still match the current definitions. Stale results stay in the run history but stop
  counting, and the leaderboard shows how many were excluded.
* **Resume safety.** An interrupted run can be resumed only if none of its tests have changed.
* **Re-running a published result.** Check out the git commit from the manifest, run the same suite with
  the same model ids, and compare fingerprints.
* **Known sources of non-determinism.** Providers do not guarantee deterministic sampling even at
  temperature 0, and reasoning models are sampled. This is why Gauntlet uses repeats and reports
  intervals and consistency instead of single numbers.

## 7a. Two tiers: Standard and Frontier

The library has two difficulty tiers, run as separate suites and reported as separate leaderboards. Their
Gauntlet Indexes are never mixed.

* **Standard (`core`).** Hard for small models, solvable by strong ones. Calibrated so that scores spread out
  across the whole field: in our blind audit, a frontier model scored 100% on every deterministic test,
  a small model scored between 5% and 97%, and random filler scored 0–2%.
* **Frontier (`frontier`).** Built for when the best models saturate Standard: larger deduction grids,
  truth-teller puzzles with spies and alternators, olympiad maths, long-horizon shortest plans, multi-part
  engineering tasks against hidden tests, stacked hard constraints, system prompts under attack, honesty under
  social pressure, and the hard variant of every simulation (deducible but punishing schedules, volatile
  markets, more liars, hyphen-proof Draw It Blind). Small models are expected to score close to zero here.

Every answer key in both tiers is machine-checked (solvers and generators live in `verification/`), and each
test was blind-played by models that saw only the prompt (results in [AUDIT.md](AUDIT.md)).

## 7b. Vision tests (pictures)

* **Transport.** Images go in the same user message as the text, before it: Anthropic image blocks (base64),
  OpenAI-compatible `image_url` data URLs with `detail: "high"`, Gemini `inline_data`. Every model gets the
  same bytes.
* **Hashing.** The SHA-256 of every image is part of the test hash.
* **Models without image input** (`vision` not `true` in `config/models.json`) are **skipped** on picture
  cases: nothing is sent, the case is stored as `skipped` with no score, and it is excluded from every mean,
  confidence interval and medal. The leaderboard and Presenter show a note. A run can override this with
  `forceVision` (the API may then reject the request, which is recorded as an error). The Random Baseline and
  Manual contestants always receive picture cases.
* **Cost estimates** add image tokens per vendor, approximating the published formulas: Anthropic
  ≈ width × height / 750 after scaling to at most 1568 px on the long edge; OpenAI (and other OpenAI-compatible
  APIs) 85 + 170 per 512-px tile after scaling to fit 2048 px and a 768-px short side; Gemini 3 a flat 1,120
  tokens, earlier Gemini 258 per 768-px tile. Measured usage replaces these after your first run.
* **Content.** The built-in vision images are generated by code (`verification/vision/`), seeded and rendered
  in headless Chromium, so every answer key is computed from the data that drew the picture.

## 7c. Art tests (The Gallery Masterpiece)

* **Same brief for everyone.** Eight fixed commission briefs (`src/programs/lib/gallery-briefs.ts`), part of
  the test hash. Image models receive the brief as an image prompt (landscape, 3:2); Painted in Code receives it
  with SVG output rules, and its SVG is rendered to a 1536 × 1024 PNG by headless Chromium (scripts, external
  files and embedded images are removed first).
* **Judges.** At least two judges that accept images, never from the artist's company and never the artist's
  own model (no fallback, unlike text judging). Every judge gets the identical prompt and the anonymised picture
  (always called `painting.png`); pictures over 3.7 MB go to judges as a JPEG copy. Fewer than two valid verdicts
  → the painting waits for a human rating (`pending-human`).
* **Score.** Brief adherence (50%): 6 required elements and 3 rules, each yes (1) / partly (0.5) / no (0) against
  anchored definitions; the panel verdict per line is the median; adherence is the mean. Artistry (50%): six
  criteria scored 1–10 on one anchored scale; a judge's artistry is the mean of its six; the painting's is the
  median across judges. Score = 0.5 × adherence + 0.5 × artistry ÷ 10. Refusal or no picture = 0.
* **Honesty about subjectivity.** Artistry is a judgement. The spread between judges is stored and shown; a
  spread of 2+ points, or a yes/no split on a checklist line, flags the painting for Blind Review, where the
  owner's rating replaces the judges' artistry. Blind votes are recorded but never scored.
* **Costs.** Image models are billed per picture (`imagePricing`), plus the prompt. Prices in
  `config/models.json` carry their source and date and are marked unverified until you check them.
## 7d. The Horizon tier: tests built for future models

Standard and Frontier measure what today's models can do. The **Horizon** tier (suite `horizon`, category
`horizon`) measures how far they still have to go. It is built so that today's strongest model scores low and
future models visibly climb, on exactly the same questions, for years.

**Five ladders.** Each Horizon test is a *ladder* of ten frozen levels (case ids `L01` to `L10`). Every level
is strictly more work than the one below it: more steps, more digits, a longer proven-shortest plan, a bigger
grid, a bigger board. A unit test (`test/horizon.test.ts`) checks the growth for every ladder.

| Test | What a level asks | Level 1 → level 10 | Pencil-and-paper time for a patient expert |
|---|---|---|---|
| Run It In Your Head (`horizon.mind-runner`) | The exact number a JavaScript program prints | ~150 → ~73,000 statements executed | 15 minutes → two to three working weeks |
| No Calculator (`horizon.modpow-ladder`) | a^e mod m, exactly | 8-digit → 44-digit numbers | two hours → about a month |
| The Sliding Ladder (`horizon.sliding-ladder`) | A provably shortest sliding-puzzle plan | 20 moves (3×3) → 56 moves (4×4) | an hour → months of systematic search |
| The Picture Logic Ladder (`horizon.nonogram-ladder`) | A whole nonogram grid | 8×8 → 50×50 (2,500 cells) | 15 minutes → a few weeks |
| Count Every Tiling (`horizon.tiling-count`) | The exact number of domino tilings of a board with holes | 6×6 (110) → 18×18 (31 digits) | 30 minutes → months |

**Fair, not tricky.** Every item is a plainly worded, fully specified task with one checkable answer. There
are no gotchas in the wording and no opinions: difficulty comes only from depth, length and exactness. Every
item can be solved by a patient person with pencil and paper (the method is standard: trace the program,
square-and-multiply, search, case analysis, column-by-column counting); it just takes a long time. The top
rungs are deliberately beyond what any person would do by hand in practice, which is the point: they are there for
the models of the next few years.

**Double-verified keys** (`verification/horizon/`):

* Programs are generated as a syntax tree and rendered twice, as JavaScript (what the model sees) and Python.
  The key is the printed number when *both* are actually run, and `verify.mjs` re-runs the exact program text
  from the prompt in a fresh V8 context.
* Modular powers: Python's `pow`, a left-to-right binary method, and an independent JavaScript BigInt
  right-to-left method.
* Sliding puzzles: the minimum is proven by breadth-first search over all 181,440 positions (3×3) or IDA*
  with an admissible heuristic (larger boards) in Python, and re-proven in JavaScript by a different search
  (bidirectional BFS / its own IDA*). A compiled C search is used only to *find* deep candidates quickly.
* Nonograms: uniqueness is proven by OR-Tools CP-SAT (after the solution is found it is forbidden and the
  solver must prove no other exists) and independently by a JavaScript line solver with full backtracking.
  From level 3 on, row-by-row logic alone is guaranteed to leave part of the grid open, so case analysis is
  required.
* Tilings: column-by-column transfer-matrix counting in Python, re-checked by a memoised
  cover-the-first-empty-square search in JavaScript; both self-test on the 8×8 board (12,988,816).

`verify.mjs` parses every puzzle back **out of the prompt text** the models see, so a key can never drift
from its prompt.

**Scoring** (`{ "type": "ladder" }`, `src/scoring/ladder.ts`):

* Whole numbers are compared digit by digit (any size; separators and a stated `x =` are tolerated, two
  numbers or a hedge are wrong).
* A sliding-puzzle plan is *replayed* on the start board. The proven minimum scores 1; a longer plan that
  really solves the puzzle scores 0.25 × minimum ÷ length (so never more than a quarter); an illegal move or
  an unfinished board scores 0.
* A nonogram needs the whole grid right (all or nothing).

Every level is worth the same, so a test score is *the share of the ladder climbed*. The headline number is
the **ladder height**: the highest level L such that every level from 1 to L was solved reliably (full marks on
at least two of three attempts, or on the single attempt when running one repeat). A lucky solve higher up
is shown ("once solved level 7") but never lifts the climber past a rung it missed. Random filler scores 0
(regression-tested).

**Reproducible for years.** The ten levels of each test are frozen in the test files and covered by the test
hash. Never regenerate a published version: a model measured in 2030 must meet the very same rungs. When
models top out, add levels L11, L12, … in a new version and keep L01–L10 unchanged, so old and new heights stay
comparable.

**Output limit and cost.** Horizon tests ask for each model's **own maximum output** (`"maxOutputTokens":
"model-max"`, e.g. 128,000 tokens for Opus 5.5, 64,000 for Haiku 4.5), so no model is held back by an artificial
cap: in the calibration Opus needed 67,000–96,000 tokens for levels it solved, more than a fixed 64,000 would
allow. A long reply that fails is not retried more than once (`maxRetries: 1`), and the time limit is one hour per
level. The estimate is 60,000 output tokens per level, from the calibration (Opus averaged 63,000 per level,
counting a reply that hit its maximum as the full 128,000; Haiku averaged 16,000): about **$60 per repeat for all
50 levels for Opus 5.5**. For small models the estimate is on the safe side: Haiku 4.5 is estimated at $15 but
cost about $4 in the calibration. The honest **upper bound** is every level using the model's whole maximum: 50 ×
128,000 tokens ≈ **$128 for Opus 5.5** and 50 × 64,000 ≈ **$16 for Haiku 4.5** per repeat. New Run and the Cost
Planner show both numbers, and a spending cap (`--max-cost`, or "same token limit for every model") keeps a run
inside a budget.

**Calibration.** The ladders were calibrated with blind solvers before release (today's strongest model should
score clearly above zero on the first rungs and well under a third overall); the per-level results are in
[AUDIT.md](AUDIT.md#horizon-tier-blind-calibration). Two ladders (Picture Logic and Count Every Tiling) were made
steeper from level 4 and level 5 after the first calibration showed Opus climbing them too easily.

## 8. The random baseline

`random-baseline` makes no API calls. It picks a random offered action in simulations, a random letter or
number for answer questions, a stub for code and filler for prose. It shows the floor every real model must
clear: a score close to the baseline means a test isn't measuring skill for that model.

## 9. Budget control

* **Estimates before every run.** `New Run`, `Cost Planner` and `node src/cli.ts costs` show the expected cost
  per test and per model, plus a conservative upper bound. Estimates start from each test's declared token
  budget and switch to **measured** averages from your own previous runs of the same test version.
* **Hard spending limit.** Set a spending limit (New Run presets in pounds, or `--max-cost` in dollars). Before
  every model or judge call, the call's worst case (prompt plus full output allowance at the model's prices) is
  reserved against the money left, counting calls already running; a call that does not fit waits for running
  calls, then gets a lower output allowance, and is not started when not even 2,000 output tokens are affordable.
  The limit therefore cannot be overshot (up to the accuracy of the published prices). The run then stops as
  "stopped: spend limit": finished results are kept, a half-finished case is not stored (so it is never scored as
  a model failure) but its spend is counted, and the run resumes with a higher limit. Details in §9b.
* **Cheap exploration.** Use the `quick` suite with 1 repeat to try new models, and the full `core` suite with 3
  repeats for results you publish. Per-suite, per-model tables are in [COSTS.md](COSTS.md).

## 9b. Output and spending limits, and fairness

**Default: no artificial limits.** Tests that ask for `"model-max"` (The Game Jam and the Horizon ladders) let every model write up to its
own maximum output, taken from the provider's documentation and recorded per model in `config/models.json`
(`maxOutputTokens`, with `maxOutputTokensSource` saying where the number came from, or "unverified"). These
maxima differ (for example 128,000 tokens for current Claude and GPT-5.x models, 65,536 for Gemini): that is part
of what each model is, the same way its speed or price is. The maximum is not part of a model's configuration hash,
because it only turns would-be API errors into valid calls.

**Optional limits, recorded with the run.** The owner may add, per run:

| Limit | What it does | Fair between models? |
|---|---|---|
| Whole-run spending limit | Models and judges together never spend more than the amount. | Yes, if the run finishes. If it stops early, unfinished models have fewer results: resume before comparing. |
| Per-answer spending limit | Each reply may cost at most the amount, so each model's output allowance is what that buys at its own output price. | **No.** A cheap model gets many times the tokens of an expensive one. Useful to protect a budget, not for a like-for-like comparison. |
| Same token limit for every model | "Model's maximum" tests give every model the same output allowance (or its own maximum, if that is lower). | Yes: the fair alternative to a money limit. |

The limits used are stored in the run manifest (`settings.limits`, with the display currency and exchange rate
they were typed in), shown as chips on the run page and printed on the Presenter's closing methods slide, so a video
can disclose them. A reply cut off by a spending limit is labelled as such ("stopped by your per-answer spend
limit"), never as the model's own failure to finish.

**Currency.** Costs are measured and stored in US dollars, the unit providers bill in. The display currency
(pounds by default) and its exchange rate are a display setting typed in by the owner (`config/settings.json`);
they never change a stored result and are never fetched from the internet.

## 9a. The Arena (head-to-head games)

Arena tournaments are separate from the Gauntlet Index: they rank models by playing each other, not by score.
The same fair-play rules apply.

* **Stateless moves.** Every move is one fresh prompt: the full rules, the move history, the position (from the
  moving side's point of view) and, by default, the legal moves. The reply must end with `MOVE: <move>`.
* **One retry, then a strike.** An unreadable or illegal move is rejected with the reason and the model gets one
  more try. If that fails too, a random legal move (from the tournament's seeded random stream) is played for it
  and it receives a strike; 3 strikes lose the game. Every attempt is recorded and shown in the replay.
* **Both sides.** Each pairing plays 2, 4 or 6 games with the sides swapped every game, so the first-move
  advantage cancels out. Colour-swapped games share a seed.
* **Tie-breaks (knockout):** sudden-death games (sides keep alternating; each starts after one seeded random move
  per side), then fewer illegal moves, then lower cost, then the higher seed. Round-robin standings: points
  (win 1, draw ½), then head-to-head points, then fewer illegal moves, then lower cost.
* **Rules enforced by the harness.** Connect Four and chess legality are checked by Gauntlet's own engines; the
  chess move generator is verified against the published perft node counts. Chess games that reach the move cap
  are decided on material (P1 N3 B3 R5 Q9; a lead of 3+ wins, otherwise a draw).
* **Reproducible.** The tournament manifest stores the model snapshots, the settings and a fingerprint over the
  game code, the prompt template and the settings. Resume refuses to continue if the game code or a model's
  configuration changed.
* **Poker: duplicate format.** Every hand is dealt from the game seed; the two games of a pairing share the seed
  with the seats swapped, so each model plays every deal from both seats. With identical play the pair nets exactly
  zero chips (a unit test checks this), so the chip total measures decisions, not cards. Blinds 1/2, stacks reset
  to 200 every hand (there are no side pots beyond the all-in cap), minimum-raise rules enforced, showdowns
  evaluated by the harness. Each prompt shows only the acting seat's cards, plus how the previous hand ended
  (a fold, or the showdown with both revealed hands, which is public information in real poker). Game 2 of a
  pair never starts before game 1 has finished, so a player can never see a deal from one seat while the same
  deal is still being played from the other. API models are stateless per call anyway; for **manual (copy &
  paste) contestants use a brand-new chat for every decision**: a chat that remembers game 1 knows the
  opponent's cards in game 2, which breaks the duplicate format (the New tournament page and every Manual Inbox
  item say so). An illegal or unreadable action gets
  one retry, then check (or fold if there is a bet) and a strike; strikes never end the session.
* **Debate and Courtroom: blind judging.** The judges are the configured cross-vendor panel minus every judge
  from either debater's vendor (strict: no exceptions, a game with no eligible judge waits for a human). Judges
  see "Side A" / "Side B" only; which seat is Side A is random per judge (seeded), and every model label, model
  id, vendor and well-known product name is removed from the material before it is sent. Each judge scores
  argument quality, rebuttal, use of evidence, clarity and rule-following (1–10) and must pick a winner. Majority
  decides; a tied vote goes to the side with more rubric points; still level is a draw. Word limits are enforced
  by cutting the speech (words = whitespace-separated tokens, so "£700" or "22:47–22:51" is one word, as the
  prompt says), and the judges see the cut. Courtroom judges decide which side *argued better from the evidence*,
  not the legal verdict, so the defence gets no "reasonable doubt" head start; both models argue both sides, so
  any lean in a case cancels out over the pair. Judges check claims against the case file and name invented
  facts or misquoted exhibits, which count heavily against "use of the exhibits". A knockout pairing that is 1–1
  on games is decided on the judges' total rubric points over both games, then on the number of judges' picks,
  and only then by a sudden-death debate (shown as "Level on games, decided on judges' points"). Judges are called one at a time, each after a spending-cap
  check, so judged games keep the cap's guarantee (see below).
* **Spending limit.** Enforced per call exactly as in §9: every move and judge call reserves its worst case first,
  so a tournament does not go over its limit.

## 10. Publishing checklist

1. Verify every contestant's pricing (`pricing.verifiedAt`) against the provider's price page.
2. Use at least 3 repeats and publish intervals.
3. Use a cross-vendor judge panel for judge-scored tests.
4. Publish the fingerprint, harness version and run manifest with the results (`Export → JSON`).
5. Don't call a difference a win when the intervals overlap.
6. Report errors and refusals rather than silently re-running until they disappear.
