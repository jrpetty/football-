# What will it cost?

Estimated spend for each built-in suite, per test and per model, at **one repeat**. A published run uses
3 repeats, so multiply by 3. The planner in the dashboard (**Cost Planner**) and
`node src/cli.ts costs --suite <id> --models a,b` give the same numbers for any model you pick, and
**New Run** shows the estimate and lets you set a hard spending cap before you start.

How to read these tables:

* Numbers come from each test's declared token budget until you've run it. After your first real run,
  Gauntlet uses the **measured** token usage instead, so estimates sharpen over time.
* "Conservative upper bound" assumes every case uses its full output allowance. Real runs usually land
  well under it.
* **Judges** is the extra cost of the cross-vendor judge panel for judge-scored tests (Honesty, games,
  illustrations). It's shared across all models in the table.
* Anthropic prices are verified. Other vendors' prices are marked *unverified* in `config/models.json`. Check
  them (Models → edit) before quoting costs on video.
* The Random Baseline and Manual (copy & paste) contestants cost $0.

Regenerate this file with:

```bash
for s in quick core frontier; do node src/cli.ts costs --suite $s --models <ids> --format md; done
```

## Budget playbook

| Goal | What to run | Rough cost (10 current models) |
|---|---|---|
| Try a new model | `quick`, 1 repeat, that model only | $0.30–$7 |
| Check your setup for free | `quick` with `random-baseline` | $0 |
| Head-to-head of two flagships | `core`, 3 repeats, 2 models | ~$100–$280 |
| Full standard episode | `core`, 3 repeats, all 10 | ~$700 |
| Frontier showdown (top 4 only) | `frontier`, 3 repeats, 4 flagships | ~$450 |
| Themed episode (e.g. simulations only) | hand-picked tests in New Run | a few $ per model |

Tips: iterate on `quick`, cut cheap models first, and set **Spending cap** to about 1.25× the estimate.
If you hit the cap, **Resume** later. Finished cases are never paid for twice.

## Quick Look (`quick`)


| Test | Cases | Claude Opus 5.5 | Claude Sonnet 5 | Claude Haiku 4.5 | GPT-5.6 Sol | GPT-5.6 Terra | GPT-5.6 Luna | Gemini 3.1 Pro | Gemini 3.5 Flash | Grok 4.7 | DeepSeek V4 Flash | Judges |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Deduction Grid | 1 | $0.24 | $0.12 | $0.06 | $0.36 | $0.18 | $0.07 | $0.15 | $0.11 | $0.07 | $0.01 | — |
| Knights, Knaves, Spies & Alternators | 1 | $0.22 | $0.11 | $0.06 | $0.33 | $0.17 | $0.07 | $0.13 | $0.10 | $0.07 | $0.01 | — |
| Shortest Plans | 1 | $0.24 | $0.12 | $0.06 | $0.36 | $0.18 | $0.07 | $0.14 | $0.11 | $0.07 | $0.01 | — |
| Competition Maths | 2 | $0.36 | $0.18 | $0.09 | $0.54 | $0.27 | $0.11 | $0.22 | $0.16 | $0.11 | $0.02 | — |
| Real-World Word Problems | 2 | $0.16 | $0.08 | $0.04 | $0.24 | $0.12 | $0.05 | $0.10 | $0.07 | $0.05 | $0.0098 | — |
| Algorithms Under Test | 1 | $0.20 | $0.10 | $0.05 | $0.30 | $0.15 | $0.06 | $0.12 | $0.09 | $0.06 | $0.01 | — |
| Edge-Case Minefield | 1 | $0.16 | $0.08 | $0.04 | $0.24 | $0.12 | $0.05 | $0.10 | $0.07 | $0.05 | $0.0098 | — |
| Hard Mode Engineering | 1 | $0.32 | $0.16 | $0.08 | $0.48 | $0.24 | $0.10 | $0.19 | $0.15 | $0.10 | $0.02 | — |
| Precision Formatting | 2 | $0.10 | $0.05 | $0.03 | $0.15 | $0.08 | $0.03 | $0.06 | $0.05 | $0.03 | $0.0063 | — |
| Stay In Character | 2 | $0.07 | $0.03 | $0.02 | $0.10 | $0.05 | $0.02 | $0.04 | $0.03 | $0.02 | $0.0041 | — |
| The Honesty Trap | 4 | $0.12 | $0.06 | $0.03 | $0.18 | $0.09 | $0.04 | $0.07 | $0.05 | $0.04 | $0.0073 | $2.05 |
| Messy Text to Exact JSON | 1 | $0.05 | $0.03 | $0.01 | $0.08 | $0.04 | $0.02 | $0.03 | $0.02 | $0.02 | $0.0032 | — |
| Build a Game in One Shot | 1 | $0.48 | $0.24 | $0.12 | $0.72 | $0.36 | $0.14 | $0.29 | $0.22 | $0.15 | $0.03 | $1.00 |
| Precise SVG Illustration | 1 | $0.14 | $0.07 | $0.04 | $0.21 | $0.11 | $0.04 | $0.08 | $0.06 | $0.04 | $0.0085 | $0.63 |
| Draw It Blind | 1 | $0.24 | $0.12 | $0.06 | $0.36 | $0.18 | $0.07 | $0.15 | $0.11 | $0.07 | $0.01 | — |
| Chain of Whispers | 1 | $0.31 | $0.16 | $0.08 | $0.47 | $0.23 | $0.09 | $0.19 | $0.14 | $0.10 | $0.02 | — |
| The Startup | 1 | $0.43 | $0.21 | $0.11 | $0.62 | $0.31 | $0.12 | $0.25 | $0.19 | $0.14 | $0.03 | — |
| The Liar's Table | 1 | $0.71 | $0.36 | $0.18 | $1.05 | $0.53 | $0.21 | $0.42 | $0.32 | $0.23 | $0.04 | — |
| **Total** | | **$4.58** | **$2.29** | **$1.15** | **$6.83** | **$3.41** | **$1.37** | **$2.73** | **$2.05** | **$1.41** | **$0.28** | **$3.68** |

Grand total ≈ $29.77 (conservative upper bound $47.27).

## Core Gauntlet: standard tier (`core`)


| Test | Cases | Claude Opus 5.5 | Claude Sonnet 5 | Claude Haiku 4.5 | GPT-5.6 Sol | GPT-5.6 Terra | GPT-5.6 Luna | Gemini 3.1 Pro | Gemini 3.5 Flash | Grok 4.7 | DeepSeek V4 Flash | Judges |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Deduction Grid | 15 | $3.65 | $1.83 | $0.91 | $5.47 | $2.73 | $1.09 | $2.19 | $1.64 | $1.11 | $0.22 | — |
| Knights, Knaves, Spies & Alternators | 15 | $3.34 | $1.67 | $0.83 | $5.00 | $2.50 | $1.00 | $2.00 | $1.50 | $1.01 | $0.20 | — |
| Shortest Plans | 15 | $3.62 | $1.81 | $0.91 | $5.43 | $2.71 | $1.09 | $2.17 | $1.63 | $1.09 | $0.22 | — |
| Competition Maths | 20 | $3.61 | $1.81 | $0.90 | $5.42 | $2.71 | $1.08 | $2.17 | $1.62 | $1.09 | $0.22 | — |
| Real-World Word Problems | 17 | $1.38 | $0.69 | $0.34 | $2.06 | $1.03 | $0.41 | $0.83 | $0.62 | $0.42 | $0.08 | — |
| Algorithms Under Test | 8 | $1.61 | $0.81 | $0.40 | $2.42 | $1.21 | $0.48 | $0.97 | $0.73 | $0.49 | $0.10 | — |
| Edge-Case Minefield | 6 | $0.97 | $0.49 | $0.24 | $1.46 | $0.73 | $0.29 | $0.58 | $0.44 | $0.29 | $0.06 | — |
| Hard Mode Engineering | 5 | $1.62 | $0.81 | $0.40 | $2.42 | $1.21 | $0.48 | $0.97 | $0.73 | $0.49 | $0.10 | — |
| Precision Formatting | 15 | $0.78 | $0.39 | $0.19 | $1.16 | $0.58 | $0.23 | $0.46 | $0.35 | $0.24 | $0.05 | — |
| Stay In Character | 12 | $0.40 | $0.20 | $0.10 | $0.59 | $0.29 | $0.12 | $0.23 | $0.18 | $0.13 | $0.02 | — |
| The Honesty Trap | 30 | $0.91 | $0.45 | $0.23 | $1.36 | $0.68 | $0.27 | $0.54 | $0.41 | $0.27 | $0.05 | $15.36 |
| Messy Text to Exact JSON | 8 | $0.42 | $0.21 | $0.11 | $0.63 | $0.31 | $0.13 | $0.25 | $0.19 | $0.13 | $0.03 | — |
| Build a Game in One Shot | 3 | $1.45 | $0.72 | $0.36 | $2.17 | $1.08 | $0.43 | $0.87 | $0.65 | $0.44 | $0.09 | $3.00 |
| Precise SVG Illustration | 4 | $0.57 | $0.28 | $0.14 | $0.85 | $0.42 | $0.17 | $0.34 | $0.25 | $0.17 | $0.03 | $2.53 |
| Draw It Blind | 3 | $0.73 | $0.37 | $0.18 | $1.09 | $0.55 | $0.22 | $0.44 | $0.33 | $0.22 | $0.04 | — |
| Needle in a Haystack | 3 | $1.79 | $0.89 | $0.45 | $2.48 | $1.24 | $0.49 | $0.99 | $0.74 | $0.70 | $0.12 | — |
| Chain of Whispers | 3 | $0.94 | $0.47 | $0.24 | $1.40 | $0.70 | $0.28 | $0.56 | $0.42 | $0.29 | $0.06 | — |
| Survival Island | 3 | $3.30 | $1.65 | $0.82 | $4.77 | $2.38 | $0.95 | $1.91 | $1.43 | $1.13 | $0.21 | — |
| The Escape Room | 3 | $3.30 | $1.65 | $0.82 | $4.80 | $2.40 | $0.96 | $1.92 | $1.44 | $1.11 | $0.21 | — |
| The Startup | 3 | $1.28 | $0.64 | $0.32 | $1.87 | $0.93 | $0.37 | $0.75 | $0.56 | $0.42 | $0.08 | — |
| The Liar's Table | 3 | $2.14 | $1.07 | $0.53 | $3.15 | $1.57 | $0.63 | $1.26 | $0.94 | $0.68 | $0.13 | — |
| **Total** | | **$37.81** | **$18.90** | **$9.45** | **$55.98** | **$27.99** | **$11.20** | **$22.39** | **$16.79** | **$11.92** | **$2.31** | **$20.89** |

Grand total ≈ $236 (conservative upper bound $375).

## Frontier Gauntlet (`frontier`)


| Test | Cases | Claude Opus 5.5 | Claude Sonnet 5 | Claude Haiku 4.5 | GPT-5.6 Sol | GPT-5.6 Terra | GPT-5.6 Luna | Gemini 3.1 Pro | Gemini 3.5 Flash | Grok 4.7 | DeepSeek V4 Flash | Judges |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Deduction Grid: Extreme | 10 | $5.69 | $2.85 | $1.42 | $8.51 | $4.26 | $1.70 | $3.41 | $2.55 | $1.73 | $0.34 | — |
| Knights, Knaves, Spies & Alternators: Extreme | 10 | $5.25 | $2.62 | $1.31 | $7.86 | $3.93 | $1.57 | $3.14 | $2.36 | $1.58 | $0.32 | — |
| Shortest Plans: Extreme | 10 | $4.82 | $2.41 | $1.20 | $7.22 | $3.61 | $1.44 | $2.89 | $2.17 | $1.45 | $0.29 | — |
| Olympiad Maths | 12 | $5.29 | $2.65 | $1.32 | $7.94 | $3.97 | $1.59 | $3.17 | $2.38 | $1.59 | $0.32 | — |
| Frontier Engineering | 6 | $2.43 | $1.22 | $0.61 | $3.64 | $1.82 | $0.73 | $1.46 | $1.09 | $0.73 | $0.15 | — |
| Extreme Constraints | 12 | $3.87 | $1.94 | $0.97 | $5.80 | $2.90 | $1.16 | $2.32 | $1.74 | $1.17 | $0.23 | — |
| Adversarial System Prompt | 8 | $1.66 | $0.83 | $0.42 | $2.44 | $1.22 | $0.49 | $0.98 | $0.73 | $0.54 | $0.10 | — |
| Pressure Traps | 30 | $1.23 | $0.61 | $0.31 | $1.83 | $0.92 | $0.37 | $0.73 | $0.55 | $0.37 | $0.07 | $15.68 |
| Draw It Blind — Hard | 3 | $1.10 | $0.55 | $0.27 | $1.64 | $0.82 | $0.33 | $0.66 | $0.49 | $0.33 | $0.07 | — |
| Needle in a Haystack — Hard | 2 | $1.92 | $0.96 | $0.48 | $2.64 | $1.32 | $0.53 | $1.06 | $0.79 | $0.77 | $0.13 | — |
| Chain of Whispers — Hard | 3 | $1.63 | $0.82 | $0.41 | $2.43 | $1.22 | $0.49 | $0.97 | $0.73 | $0.50 | $0.10 | — |
| Survival Island (Hard) | 3 | $3.60 | $1.80 | $0.90 | $5.21 | $2.60 | $1.04 | $2.08 | $1.56 | $1.24 | $0.23 | — |
| The Escape Room (Hard) | 3 | $3.65 | $1.82 | $0.91 | $5.31 | $2.65 | $1.06 | $2.12 | $1.59 | $1.22 | $0.23 | — |
| The Startup (Volatile Market) | 3 | $1.28 | $0.64 | $0.32 | $1.87 | $0.93 | $0.37 | $0.75 | $0.56 | $0.42 | $0.08 | — |
| The Liar's Table — Hard | 3 | $2.34 | $1.17 | $0.58 | $3.45 | $1.73 | $0.69 | $1.38 | $1.03 | $0.75 | $0.14 | — |
| **Total** | | **$45.76** | **$22.88** | **$11.44** | **$67.78** | **$33.89** | **$13.56** | **$27.11** | **$20.34** | **$14.41** | **$2.80** | **$15.68** |

Grand total ≈ $276 (conservative upper bound $439).
