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
| Output limit | Per call (`maxOutputTokens`, default 16,000; up to 32,000 for hard reasoning). The limit includes hidden reasoning tokens on every provider. Hitting it is recorded, not retried. |
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

## 8. The random baseline

`random-baseline` makes no API calls. It picks a random offered action in simulations, a random letter or
number for answer questions, a stub for code and filler for prose. It shows the floor every real model must
clear: a score close to the baseline means a test isn't measuring skill for that model.

## 9. Budget control

* **Estimates before every run.** `New Run`, `Cost Planner` and `node src/cli.ts costs` show the expected cost
  per test and per model, plus a conservative upper bound. Estimates start from each test's declared token
  budget and switch to **measured** averages from your own previous runs of the same test version.
* **Hard spending cap.** Set `Spending cap` (or `--max-cost`) on a run. Once the cap is reached, no new cases
  start. Cases already in flight finish, so the overshoot is at most one case per concurrent worker. Resume
  later with a higher cap. Nothing is lost.
* **Cheap exploration.** Use the `quick` suite with 1 repeat to try new models, and the full `core` suite with 3
  repeats for results you publish. Per-suite, per-model tables are in [COSTS.md](COSTS.md).

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
  by cutting the speech, and the judges see the cut. Judges are called one at a time, each after a spending-cap
  check, so judged games keep the cap's guarantee (see below).
* **Spending cap.** Checked before every model call (players and judges). Each game has at most one call in
  flight, so a tournament can go over its cap by at most one call per game running at the same time.

## 10. Publishing checklist

1. Verify every contestant's pricing (`pricing.verifiedAt`) against the provider's price page.
2. Use at least 3 repeats and publish intervals.
3. Use a cross-vendor judge panel for judge-scored tests.
4. Publish the fingerprint, harness version and run manifest with the results (`Export → JSON`).
5. Don't call a difference a win when the intervals overlap.
6. Report errors and refusals rather than silently re-running until they disappear.
