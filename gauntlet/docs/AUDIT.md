# Blind audit of the test library

Before any money was spent on API runs, every test was checked in two ways.

1. **Keys are machine-verified.** Every answer key is computed by generator code, and most by a second,
   independent solver: SAT and CP-SAT for the logic grids, exhaustive search for truth-teller puzzles, Dijkstra
   and BFS in Python and JavaScript for shortest plans, and exact arithmetic for maths and extraction. The
   code is in `verification/`.
2. **Tests are blind-played.** A strong model (Claude Opus) and a small model (Claude Haiku) each received
   only the exact rendered prompts. They had no answer keys, no tools, no code execution and no web access.
   Their replies went through Gauntlet's copy & paste pipeline (a *manual* contestant) and were graded by
   the real scorers. The Random Baseline ran alongside to show the floor.

The questions this answers: *Can the tests be solved? Are the keys right? Do the tests separate strong
models from weak ones? Can guessing score?*

These are single samples (1 repeat), and the models were used through a chat-style harness rather than
the API, so read the numbers as a calibration check, not as a leaderboard.

## Standard tier (`core`)

| Test | Opus | Haiku | Random |
|---|---:|---:|---:|
| Deduction Grid | 100% | 7% | 0% |
| Knights, Knaves, Spies & Alternators | 100% | 7% | 0% |
| Shortest Plans | 100% | 27% | 0% |
| Competition Maths | 100% | 5% | 0% |
| Real-World Word Problems | 100% | 65% | 0% |
| Algorithms Under Test | 100% | 94% | 0% |
| Edge-Case Minefield | 100% | 77% | 0% |
| Hard Mode Engineering | 100% | 63% | 0% |
| Precision Formatting | 100% | 96% | 0% |
| Stay In Character | 100% | 75% | 0% |
| Messy Text to Exact JSON | 100% | 97% | 2% |
| The Honesty Trap (judge-graded) | 100% | 97% | — |

## Frontier tier (`frontier`)

| Test | Opus | Haiku | Random |
|---|---:|---:|---:|
| Deduction Grid: Extreme | 100% | 0% | 0% |
| Knights, Knaves, Spies & Alternators: Extreme | 100% | 0% | 0% |
| Olympiad Maths | 100% | 0% | 0% |
| Shortest Plans: Extreme | 90% (third release, see below) | 0% | 0% |
| Frontier Engineering | 100% | 13% | 0% |
| Extraction: Frontier | 100% | 20% | 0% |
| Extreme Constraints | 100% | 17% | 0% |
| Adversarial System Prompt | 100% | 50% | 0% |
| Pressure Traps (judge-graded) | 100% | 63% | — |

*Shortest Plans: Extreme.* In the first audit the long hand-solving transcripts kept tripping an automated filter on the
solver side, so no strong model played it blind. Its keys are proven optimal by two independent searches
(Python and JavaScript) that agree on all 10 cases. It was blind-played in the third release (see below).

## Simulations (Opus only, one seed each)

| Simulation | Opus | Random | | Hard variant | Opus | Random |
|---|---:|---:|---|---|---:|---:|
| Survival Island | 92% | 10% | | Survival Island (Hard) | 73% (third release) | 0% |
| The Escape Room | 98% | 0% | | The Escape Room (Hard) | 98% | 5% |
| The Startup | 55% | 0% | | The Startup (Volatile Market) | 35% | 0% |
| The Liar's Table | 97% | 0% | | The Liar's Table — Hard | 80% | 0% |
| Draw It Blind | 99% | 9% | | Draw It Blind — Hard | 91% | 4% |
| Chain of Whispers | 98% | 0% | | | | |

The Startup is scored against a perfect-information oracle, so even excellent play stays well below 100%.

## New tests (second release)

Same method: the models saw only the rendered prompt (and the attached images), with no answer keys and no tools.

| Test | Suite | Opus | Haiku | Random | Separates models? |
|---|---|---:|---:|---:|---|
| Modified Classics | `trick` | 100% | 46% | ≤10% | **Yes** |
| False Premise | `trick` | 100% | 93% | ≤10% | Barely: even small models spot these |
| Lightning Traps | `trick` | 100% | 95% | ≤10% | Barely |
| Read the Chart | `vision` | 100% | 63% | 0% | **Yes** |
| Spot the Difference | `vision` | 100% | 20% | 0% | **Yes** |
| Count & Locate | `vision` | 100% | 50% | 0% | **Yes** |
| Handwritten Maths | `vision` | 100% | 100% | 0% | No: small models read handwriting well too |
| Fix the Bug (hard) | `frontier` | 98–99% | not measured* | 0% | See below |

Opus answered every case correctly, which independently confirms every answer key.

**False Premise, Lightning Traps and Handwritten Maths** were rebuilt once to be harder after the first audit, when Haiku scored
100% on all three. Now-famous traps (months with 28 days, Einstein's "second Nobel") were replaced with obscure but checkable
ones. Even so, today's small models still score above 90%. We did not keep tweaking them until the numbers looked good,
because that would tune the test to the audit. They stay as fast, fun Shorts material. On real API runs their time limit can
still catch slow, deep-thinking models. For separating models, use Modified Classics, the vision tests and the other suites.

**Fix the Bug (hard)** was also rebuilt after the first audit (Opus scored 100% in about par actions):
- The repos are now 800 lines and 25 files each.
- One bug in each repo is caught only by hidden tests, and the README is the only way to find it.
- Symptoms point at the wrong module, and each repo has red herrings and a 35-action budget.

Opus still fixed all three repos: every hidden test passed, taking 18–22 actions against a par of 15–16. It found each
hidden-only bug by reading the spec. A model that only makes the visible tests pass scores about 60%, so that is where
weaker models separate.

\*The small-model stand-in could not be measured fairly on this interactive test. Twice it edited the fixture files on disk
instead of using the test's tools, so both runs were discarded and the files restored. A real model under test cannot do this:
it only ever gets the in-memory tools. The test hash also covers every fixture file, so any tampering would make results stale.

## The Arena (head-to-head), real matches

Real models played through the Manual Inbox. Each move was a fresh prompt, and the players saw only what an API model sees.

| Format | Match | Result | What the match changed |
|---|---|---|---|
| Connect Four | Opus vs Haiku, 2 games (colours swapped) | **Opus 2–0**, no illegal moves | Nothing needed |
| Heads-up poker | Opus vs Haiku, 10 hands (duplicate: each deal played twice with cards swapped) | **Opus by 63 chips**, no illegal actions | Game 2 of a duplicate pair now starts only after game 1 finishes, so a player with memory (a human pasting into a chat app) can't learn the opponent's cards. Also: per-player decision counter, "You check" grammar, bet/raise wording, previous-hand result shown |
| Courtroom ("The Missing Violin") | Opus vs Haiku, both sides each, blinded judge | 1–1: Defence won both games | Case was lopsided. Now: advocacy framing, a stronger prosecution file, a points tie-break before sudden death, fabrication penalty in the rubric, word-count rule stated, prompt typo fixed |

The Courtroom judge's packet contained no model names. Opus lost the prosecution game mainly for going over the word limit
twice (it was cut off, and the judge marked "rules" down), which is the rubric working as intended.

## Third release: closing the gaps

The first two audits left eight tests without a blind play or with an untested scoring path. This pass closed them.

**How it was run, and its limits.** No separate player models were available in this session, so the auditor (an
Opus-class model) played every test itself. It used only the rendered prompts, through the Manual Inbox or the copy &
paste grader, and never read answer keys, seeds or generator output. **No small model played, so there are no Haiku
numbers in this section.** The separation claims rest on Opus against the Random Baseline, plus the check that filler
earns nothing. Two further caveats:
- For the two judge-graded artifact tests, the auditor had already read the automatic checks before drawing, and it
  then graded its own work as the blinded judge, using the exact judge prompt and rubric. Treat those two numbers as a
  sanity check that the pipeline works, not as a calibration.
- Chain of Whispers was played by a player who remembers earlier rounds. A real API contestant sees only the previous
  text, so a real run is at least as hard.

`git status` was clean after every interactive session, so no fixture was touched.

| Test | Opus (auditor) | Random | Separates? | Notes |
|---|---:|---:|---|---|
| Needle in a Haystack | 100% (10/10) | 0% | Yes | Every needle answer was also re-derived by hand from the text: keys correct |
| Needle in a Haystack — Hard | 100% (12/12) | 0% | Yes | 78k words, 3-hop needles; keys correct |
| Chain of Whispers — Hard | 83% (17/20) | 0% | Yes | 18/20 under the fixed scorer; the two real losses were details the player dropped |
| Survival Island (Hard) | 73% | 0% | Yes | Survived all 13 days but was not rescued: too little wood to light the signal on day 13 |
| Fix the Bug | 98% | 0% | Yes | All 13 hidden tests pass; 12 actions against a par of 8 |
| Shortest Plans: Extreme | 90% (9/10) | 0% | Yes | The one miss (151 moves against a proven 149) was the player's error; the other 9 keys were matched independently |
| Precise SVG Illustration | 94% (4 cases) | 0% | Yes | Self-judged (see above): 8, 10, 10, 8 out of 10. The misses were a moon that didn't render and rank labels one row off |
| Build a Game in One Shot | 95% (1 of 3 cases) | 0% | Yes | Portal Snake only; played in a real browser; self-judged 9/10 |

**What this pass fixed** (each fix has a regression test in `test/audit2.test.ts` or `test/providers.test.ts`):

* **Placeholder artifacts earned points.** On One-Shot Games and SVG Illustration the automatic checks are only
  hygiene checks (it renders, it's small, a canvas exists). An empty canvas passed 5 of the 6 game checks and one grey
  circle passed 2–3 of the SVG checks. So even with the judges giving 0/10, the Random Baseline would have scored 42% on
  every game and 7–20% on the SVGs. Now the rubric's "automatic zero" holds: a 0/10
  from the judges zeroes the whole score (`zeroIfJudgedZero`, opt-in per test, so other stored results are unchanged).
  The inspector headline now says "Judged 0/10 … so it scores 0" instead of "Ships a game, but fails …".
* **SVG checks failed valid single-quoted XML.** `viewBox='0 0 512 512'` failed a check written for double quotes.
  The checks now look for the numbers, so either quote style passes.
* **Chain of Whispers missed facts that were really there.** "a cave in the mountainside, its walls glittering with
  blue crystals" is 9 words apart, beyond the 8-word window, so the fact was marked lost in round 1. The window is now
  12. Compressed ages ("oldest Brenna Brodsky, 59") are also accepted, tested across 40 seeds.
* **Chain of Whispers reported the wrong "died at" round.** A fact that dropped out of one 60-word summary and came
  back later showed as dead from the first gap. It now shows the start of the final absence.
* **Needle answers in a Markdown table were not read.** Rows such as `| A3 | 879 |` scored as unanswered. They are
  now parsed.
* **Provider adapters** (checked against the public docs as far as this sandbox allowed):
  - Groq returns streamed usage in `x_groq.usage`, which is now read.
  - Mistral does not document `stream_options`, so it is no longer sent there. Mistral sends usage in the final chunk anyway.

Version bumps, because the model's view or the scoring changed: One-Shot Games 1.1.0, SVG Illustration 1.1.0, Chain of
Whispers 1.2.0, Chain of Whispers — Hard 1.1.0, Needle in a Haystack 1.2.0, Needle — Hard 1.1.0. Stored results for
these tests are shown as stale, which is intended.

**End-to-end smoke.** All 46 tests were run through the real run engine with the Random Baseline and the mock
contestant: 840 results, all finished, 0 errors. The judge-graded tests went through a blinded manual judge. Every
result of both contestants, plus the blind-play run, was then opened in the dashboard in headless Chromium, and every
inspector tab was clicked (Score, Transcript, Artifacts, Replay): 102 results, 0 page or console errors. The Random
Baseline scored 0% on every test except two. Survival Island scored 10% (days survived while idle). The Liar's Table —
Hard scored 20%, from one lucky accusation in three cases: guessing one of five suspects is inherent to the format.

**Not verified here (needs the owner's API keys):** no live provider call could be made from this sandbox, and the docs
sites for Groq and Mistral were unreachable. These are unconfirmed:
- that Mistral streams usage without `stream_options`;
- where Groq, Together, OpenRouter, xAI and DeepSeek report usage and reasoning tokens in streams today;
- which `thinkingLevel` values each Gemini 3 model accepts;
- which OpenAI models accept `reasoning_effort` on Chat Completions;
- how real judge models apply the new automatic-zero rule.

A one-case run per provider with real keys would settle all of these.

## Horizon tier: blind calibration

The Horizon suite (five ten-level ladders, see [METHODOLOGY §7d](METHODOLOGY.md#7d-the-horizon-tier-tests-built-for-future-models))
must be solvable at the bottom, far out of reach at the top, and worth about 0 to a guesser. This was measured, not assumed.

**How it was run.** Every level's exact rendered prompt was sent, alone, to a fresh headless Claude session (`claude -p`)
started in an empty folder outside the repository, with **every tool disabled** (`--tools=`: no files, no code
execution, no web) and a neutral system prompt. The solver saw only the prompt text; no key, generator or repo file
was reachable. Opus 5.5 ran at high effort with its 128,000-token maximum, Haiku 4.5 with its 64,000-token maximum
(the `model-max` setting the tests now use). One attempt per level. The replies were graded with Gauntlet's real
ladder scorer (`src/scoring/ladder.ts`). `git status` was clean after every round. The Random Baseline was run through
the real engine: `run --models random-baseline --suite horizon --repeats 3`, 150 results.

**Keys.** Every key is computed twice by independent programs (Python generators; `verify.mjs` re-parses each puzzle
from the prompt text and recomputes it in JavaScript with a different algorithm). After the ladders were changed, all
50 keys were re-verified: all match, and every nonogram has exactly one solution (CP-SAT and a JavaScript
backtracking solver).

**Result, final levels** (each cell: Opus 5.5 / Haiku 4.5; ✓ = full marks, a number = partial credit for a valid but
longer sliding plan, ✗ = wrong answer):

| Level | Run It In Your Head | No Calculator | The Picture Logic Ladder | The Sliding Ladder | Count Every Tiling |
|---|---|---|---|---|---|
| L01 | ✓ / ✗ | ✓ / ✗ | ✓ / ✓ | ✓ / ✗ | ✓ / ✗ |
| L02 | ✓ / ✗ | ✓ / ✗ | ✓ / ✗ | over limit / ✗ | ✗ / ✗ |
| L03 | blocked / ✗ | ✗ / ✗ | ✓ / ✗ | ✓ / ✗ | ✓ / ✗ |
| L04 | ✓ / ✗ | no answer / ✗ | ✗ / no answer | time-out / ✗ | ✓ / ✗ |
| L05 | ✗ / ✗ | ✗ / ✗ | ✓ / no answer | time-out / ✗ | no answer / ✗ |
| L06 | no answer / ✗ | no answer / ✗ | over limit / no answer | time-out / ✗ | no answer / ✗ |
| L07 | no answer / ✗ | no answer / ✗ | no answer / no answer | time-out / ✗ | no answer / ✗ |
| L08 | no answer / ✗ | no answer / ✗ | no answer / no answer | 0.12 / ✗ | no answer / ✗ |
| L09 | blocked / ✗ | no answer / ✗ | no answer / no answer | 0.16 / ✗ | no answer / ✗ |
| L10 | ✗ / ✗ | no answer / ✗ | over limit / no answer | 0.13 / ✗ | no answer / ✗ |
| **Score** | **30% / 0%** | **20% / 0%** | **40% / 10%** | **24% / 0%** | **30% / 0%** |

| | Opus 5.5 | Haiku 4.5 | Random Baseline |
|---|---:|---:|---:|
| Whole suite | **28.8%** (14.4 of 50 levels) | **2.0%** (1 level) | **0%** (0 of 150 results) |
| Ladder height (single attempt) | 2 · 2 · 3 · 1 · 1 | 0 · 0 · 1 · 0 · 0 | 0 everywhere |

**Reading the table.**
- *no answer*: the model worked (often for 100,000+ tokens) and then said it could not give a verified value
  without running code. Scored 0, as in a real run.
- *over limit*: the reply hit the model's 128,000-token maximum before answering. Gauntlet does not retry this, so it
  scores 0. (The CLI solver retries on its own; only the first attempt is counted.)
- *time-out*: no answer within 75 minutes; the tests allow 60, so 0.
- *blocked*: the solver harness's safety filter stopped the request within seconds, three times in a row
  (`[reasoning_extraction]`), for Run It In Your Head L03 and L09. **These two levels are unmeasured** for Opus;
  they count as 0 above. Opus's suite score over the 48 measured levels is 30.0%; if it had solved both blocked
  levels it would be 32.8%. Whether the plain API (what Gauntlet calls) blocks them too is unknown.
- Opus is clearly above zero on the first rungs of every ladder (11 of the 15 levels 1–3 solved), which shows the
  tests are well-posed and solvable. Its misses are real slips (the L02 tiling count 9,214 against 9,610; a 20×20
  nonogram with 2 of 400 cells wrong; a modular power wrong in the low digits).

**What the calibration changed.** The first round (before this section) had Opus solving the Picture Logic Ladder to
level 6 (20×20) and Count Every Tiling to level 7 (12×10, a 10-digit count): about 39% overall. Those two ladders
were made harder, never trickier:
- Picture Logic: levels 4–10 are now 20×20 to 50×50 with 30–60% of the grid left open by line-by-line logic (was 15×15
  to 35×35). Levels 1–3 are unchanged.
- Count Every Tiling: levels 5–10 are now 12×12 to 18×18 boards (counts of 12 to 31 digits; was 10×8 to 16×14).
  Levels 1–4 are unchanged.
- All five tests now ask for each model's own maximum output (`model-max`, one retry at most). A fixed 64,000
  would have been unfair: Opus used up to 117,000 tokens on a nonogram level it solved.
- The output estimate is now 60,000 tokens per level (Opus averaged 63,000, counting over-limit replies as
  128,000; Haiku averaged 16,000).

**Cost of this calibration** (as reported by the CLI, all rounds including the replaced levels and the solver's own
retries): Opus about $175, Haiku about $12. A real Gauntlet run is cheaper per model because over-limit replies are
not retried: estimate $60 for Opus, ceiling $128; estimate $15 (real about $4) for Haiku, ceiling $16.

**Not measured.** Only one attempt per level, so "solved reliably" (2 of 3) was not tested and the heights above are
single-attempt heights. The two blocked Opus levels. No non-Anthropic model was run. The timings come from the CLI,
not the Gauntlet engine.

## Business and Everyday Life: blind calibration (November 2026)

**Method.** Fresh Claude agents of three strengths (a small, a mid-sized and a top-tier model) took two cases of every
written-answer Business and Everyday Life test (a middle case and the hardest case), seeing **only** the rendered prompt a
model receives through the API (fair-play notice included). No tools, no code execution, no files other than the prompt.
Their replies were marked by Gauntlet's real scorers. Two rounds: before and after a hardening pass.

**Limits of this calibration.** One model family only (Claude), so it measures how well a test separates
*strengths*, not how vendors compare. Two cases per test, one reply each: a single slip moves a test's number a lot. The
agent simulations (Business Ladder levels 4 and 5) and the five operations tests (stock reorder, rota, van routes, reply
within policy, board report) were not blind-played; their spreads come from scripted players only.

| Test | Round 1: small / mid / top | Round 2 (hardened): small / mid / top |
|---|---|---|
| Monday Inbox | 100 / 100 / 100 | 99 / 100 / 100 |
| Find a Slot | 100 / 100 / 100 | 81 / 100 / 100 |
| Expense Policy Check | 93 / 93 / 100 | 87 / 88 / 88 (ambiguity, fixed; re-check: mid 100 and 98) |
| Reconcile the Account | 100 / 100 / 100 | 100 / 100 / 100 |
| What Changed in the Contract? | 93 / 100 / 100 | 95 / 99 / 100 |
| Invoices to Pay | 99 / 100 / 100 | 100 / 100 / 100 |
| Customer Support Queue | 100 / 100 / 100 | 99 / 100 / 100 |
| Shortlist the Candidates | 57 / 60 / 95 | 82 / 100 / 100 |
| Check the Payslips | 100 / 100 / 100 | 46 / 98 / 100 |
| Clean the Customer List | 99 / 99 / 100 | 58 / 100 / 100 |
| Ask the Database | 100 / 100 / 100 | 100 / 100 / 100 |
| Ask the Database: Hard | 100 / 100 / 100 | 100 / 100 / 100 |
| Read an Invoice | 92 / 100 / 100 | 100 / 100 / 100 |
| Tag the Ticket | 100 / 100 / 100 | 100 / 100 / 100 |
| Read a CV | 100 / 100 / 100 | 100 / 100 / 100 |
| What Do I Need to Do? | 100 / 100 / 100 | 100 / 100 / 100 |
| Read the Spreadsheet | 100 / 100 / 100 | 90 / 100 / 100 |
| Check the Delivery | 100 / 100 / 100 | 100 / 100 / 100 |
| Weekend Away | 70 / 100 / 100 | 40 / 100 / 100 |
| Splitting the Bills | 100 / 100 / 100 | 100 / 100 / 100 |
| Cheapest Deal | 53 / 78 / 78 | 75 / 100 / 100 |
| Cook for Twelve | 100 / 100 / 100 | 50 / 100 / 100 |
| Pay It Off Faster | 20 / 60 / 70 | 20 / 90 / 90 |
| Pack for the Flight | 100 / 100 / 100 | 100 / 100 / 100 |
| **All 24 tests** | **91 / 95 / 98** | **84 / 99 / 99** |

**What it found.**

* **Answer keys corrected because the prompt could be read two ways.** Calibration's most useful result. When every
  strength agrees against the key, the prompt is at fault: Cheapest Deal (how gas and electricity are rounded; whether a free
  month includes a price rise), Expense Policy Check (one meal limit per day across cities, or one per city), Check the
  Payslips (a rate that seemed to apply only to the last item in a list). All four were reworded so only one reading is
  possible, every other rule block was re-read for the same pattern, and the keys were recomputed.
* **The hardening separates smaller models.** After the hardening round the small model falls clearly behind on long,
  exact work (payroll for 40 staff, 520 customer records, a cheapest trip with cancellations, a scaled shopping list,
  20 years of month-by-month interest).
* **Careful document work is close to solved for strong models.** The mid-sized and top models scored 99% even on
  54-document invoice runs and 520-record de-duplications; where they lost points it was by pennies (a rounding step, a
  long interest schedule). Separating the strongest models is the job of the Level 4–5 agent simulations and the Horizon
  tier; real runs will show how well they do it.

## Difficulty ladders: blind calibration (November 2026)

The 13 Frontier and Business difficulty ladders ([METHODOLOGY §7q](METHODOLOGY.md#7q-difficulty-ladders)) were built
to design targets: levels 1–2 for a small model, 3–4 for a mid-sized one, 5–7 for today's top model, 8–10 beyond any
model today. This round checked those targets against what models actually do. For many ladders they were wrong, and
the app now shows what was measured instead.

**Method.** Fresh Claude agents of three strengths (a small, a mid-sized and a top-tier model) each read **only** the
rendered prompt a model receives through the API, fair-play notice included. No tools beyond reading the prompt and
writing the answer, no code execution, no web. One attempt per level, on a spread of levels (the Frontier ladders
L02, L04, L06 and L08, plus L09 and L10 where a model passed L08; the Business ladders two levels per strength, chosen around each strength's design
target: small L03 and L05, mid L05 and L07, top L07 and L09). Replies were marked by Gauntlet's real scorers and code
sandbox against the machine-checked keys, with each test's pass mark (full marks; 90% for the Business ladders). Every
solver's tool log was checked.

The results are in `config/ladders.json` (`calibration` per test). The ladder view draws them down its left edge: a
slot per model size on every rung, ✓ where it passed and ✕ where it failed. The five Horizon ladders have their own
calibration above and keep showing their design targets, labelled as such.

**Frontier ladders** (exact answers, full marks to pass; L = level, one attempt each):

| Ladder | Small model | Mid-sized model | Top model | Verdict |
|---|---|---|---|---|
| Deduction Grid (`reasoning.grid-ladder`) | passed L2; failed L4, L6, L8 | passed L2, L4; failed L6, L8 | passed L2, L4, L6; failed L8 | separates small, mid and top |
| Knights & Knaves (`reasoning.truth-ladder`) | passed L2; failed L4, L6, L8 | passed L2, L4; failed L6, L8 | passed L4, L6; failed L2, L8 | separates (the top model's L2 miss was a copying slip: its reasoning had the right answer) |
| Shortest Plans (`reasoning.plans-ladder`) | failed L2, L4, L6, L8 | passed L4; failed L2, L6, L8 | passed L2, L4, L6, L8; failed L9, L10 | separates (the top model's L9 and L10 plans were each one move over the proven minimum: 59 for 58, 71 for 70) |
| Exact Maths (`math.exact-ladder`) | passed L2, L4, L6; failed L8 | passed L2, L4, L6; failed L8 | passed L2, L4, L6, L8; failed L9, L10 | partly: small and mid came out level, top got further |
| Engineering (`coding.engineering-ladder`) | passed L2, L4; failed L6 (16 of 17 hidden tests), L8 | passed L2, L4, L6, L8, L9, L10 | passed L2, L4, L6, L8, L9, L10 | top clears: separates small models only |

**Business ladders** (partial credit, 90% of a level's marks to pass):

| Ladder | Small model | Mid-sized model | Top model | Scores |
|---|---|---|---|---|
| Invoices to Pay (`work.invoices-ladder`) | passed L3, L5 | passed L5, L7 | passed L7, L9 | 99–100% throughout |
| Check the Payslips (`work.payroll-ladder`) | passed L3, L5 | passed L5, L7 | passed L7, L9 | 99–100% throughout |
| Expense Policy Check (`work.expenses-ladder`) | passed L3, L5 | passed L5, L7 | passed L7, L9 | 99–100% throughout |
| Reconcile the Account (`work.reconciliation-ladder`) | passed L3, L5 | passed L5, L7 | passed L7, L9 | 99–100% throughout |
| Clean the Customer List (`work.crm-ladder`) | passed L3; **failed L5** | passed L5, L7 | passed L7, L9 | small 100 / 58; mid 100 / 99; top 100 / 99 |
| What Changed in the Contract? (`work.contract-ladder`) | passed L3; **failed L5** | passed L5, L7 | passed L7, L9 | small 100 / 88; mid 100 / 96; top 100 / 100 |
| Ask the Database (`work.sql-ladder`) | passed L3, L5 | passed L5, L7 | passed L7, L9 | 100% throughout |
| Customer Support Queue (`work.support-ladder`) | passed L3, L5 | passed L5, L7 | passed L7, L9 | small 99 / 93; mid 100 / 100; top 100 / 99 |

**What it found.**

* **Deduction Grid, Knights & Knaves and Shortest Plans separate the three strengths as designed**, though lower
  down than the targets: the top model stops around L6–L8, not at L10.
* **Exact Maths tells the top model apart, not the small from the mid-sized one**: both stop at the same level.
* **Engineering is cleared by today's mid-sized and top models.** Both wrote the specialist segment-tree-beats design
  correctly, without running any code, up to L10. It separates small models only.
* **Today's top models clear the eight Business ladders.** The mid-sized and top models scored 96–100% on every level
  they were tested on, up to L9; the small model passed L5 on six of the eight. These ladders do not pick out the
  cleverest top model. What they still show is which models stay error-free as the work piles up, and where smaller,
  cheaper models start to slip (the small model failed L5 of the customer list and the contract).

**History.**

* **Business ladders: v1 → v2 → kept, reported honestly.** Version 1 grew only in volume, with every item decided on
  its own: a small model passed L05 and the top model passed L08–L10. Version 2 (the current one, §7r and §7s) made each
  answer depend on state built up across items (pools of documents drawing on each other, months on the cumulative
  basis, merges processed in order, account limits used up in date order). The mid-sized and top models still scored
  96–100% up to L07 and L09. The owner decided to keep the ladders and say so plainly in the app, on the public site
  and in the suite description: today's top models clear them; they measure reliability at scale and separate smaller
  models.
* **Engineering: v1 replaced by v2.** Version 1 (a mini key-value store) was passed at L09 by all three strengths. It was
  replaced by the array engine of §7t, whose spec grows one range operation per level up to segment tree beats with
  historic values. Version 2 separates small models; mid-sized and top models clear it.
* **Exact Maths and the logic ladders** were kept as built.

**Limits of this calibration.**

* **One attempt per level.** The height rule counts a level as passed only when at least two of three attempts pass
  (§7q). These are single-attempt results, so they say where a model *can* get, not where it gets *reliably*.
* **Claude-family solvers only**, one model per strength. It measures how well a ladder separates strengths, not how
  vendors compare.
* **A small sample.** Four to six levels per Frontier ladder, two per strength on the Business ladders; untested levels are
  left blank on the strip, not guessed.
* **The top model's single-attempt slips show why the height rule uses repeated attempts.** It missed Knights &
  Knaves L2 through a copying slip while passing L4 and L6; one attempt can understate (or, by luck, overstate) a model.
* **Tool logs.** One top-tier solver once used a shell command to copy its own answer file; it did not run code or read
  anything else. Every other solver used only Read and Write, as its tool log confirms.

## What the audit changed

The blind play found real problems, and each was fixed before release:

* **Random filler scored 76% on Stay In Character.** Checks rewarded *not* doing things, so saying nothing
  passed. The cases are now all-or-nothing and include positive requirements. Filler scores 0%, and a
  regression test (`test/content.test.ts`) keeps every auto-graded test at ≤10% for filler.
* **Draw It Blind (Hard): hyphenated compounds dodged the word limit.** Words are now split on hyphens,
  and "triangle direction" is defined.
* **Survival Island: the rescue ship's schedule couldn't be deduced.** A logbook, sightings and
  proof-of-life signalling make it fair.
* **Deduction Grid: Extreme marked a correct answer wrong.** Opus wrote "volcano" for "a volcano".
  Leaving out an article isn't a reasoning error, so both forms are now accepted.
* **Extraction: Frontier gave too much partial credit.** Haiku got 89% of fields right but only 1 of 5
  documents fully right. The test is now all-or-nothing per document, and the prompt says so.
* **Spot the Difference would have failed "color" for "colour".** The JSON scorer now accepts listed alternative
  spellings (`aliases`).
* **Can It Be Fooled?, Handwritten Maths and Fix the Bug (hard) were too easy in the first audit.** All were rebuilt
  (see above).
