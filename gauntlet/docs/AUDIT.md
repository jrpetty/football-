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
