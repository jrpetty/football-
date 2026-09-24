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
| Shortest Plans: Extreme | not blind-played (see below) | 0% | — |
| Frontier Engineering | 100% | 13% | 0% |
| Extraction: Frontier | 100% | 20% | 0% |
| Extreme Constraints | 100% | 17% | 0% |
| Adversarial System Prompt | 100% | 50% | 0% |
| Pressure Traps (judge-graded) | 100% | 63% | — |

*Shortest Plans: Extreme.* The long hand-solving transcripts kept tripping an automated filter on the
solver side, so no strong model played it blind. Its keys are proven optimal by two independent searches
(Python and JavaScript) that agree on all 10 cases.

## Simulations (Opus only, one seed each)

| Simulation | Opus | Random | | Hard variant | Opus | Random |
|---|---:|---:|---|---|---:|---:|
| Survival Island | 92% | 10% | | Survival Island (Hard) | — | — |
| The Escape Room | 98% | 0% | | The Escape Room (Hard) | 98% | 5% |
| The Startup | 55% | 0% | | The Startup (Volatile Market) | 35% | 0% |
| The Liar's Table | 97% | 0% | | The Liar's Table — Hard | 80% | 0% |
| Draw It Blind | 99% | 9% | | Draw It Blind — Hard | 91% | 4% |
| Chain of Whispers | 98% | 0% | | | | |

The Startup is scored against a perfect-information oracle, so even excellent play stays well below 100%.

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
