# GAUNTLET — AI Benchmark Lab

Gauntlet runs **the same fixed, versioned tests on every AI model** and measures what matters: score,
cost, latency, speed, reliability and consistency. It includes a live "arena" view and replays designed
for screen-recording, so results can go straight into videos.

* **Reproducible by design.** Every prompt is versioned and hashed, every simulation is seeded, and every
  run stores a manifest with model configs, pricing, test hashes and a fingerprint. Two results are
  comparable if and only if their fingerprints match.
* **Many kinds of test.** Logic, maths, real code executed against hidden unit tests, instruction
  precision, the Honesty Trap, long-context needles, agent simulations (Survival Island, Escape Room,
  The Startup), social deduction (The Liar's Table), Draw It Blind, and one-shot game building
  auto-tested in a headless browser.
* **Multi-provider.** Anthropic, OpenAI, Google Gemini, xAI, DeepSeek, Mistral, OpenRouter, Groq,
  Together, Ollama and any other OpenAI-compatible endpoint. A zero-cost **Random Baseline** shows the floor.
* **Everything is measured.** Wall time, time to first token, output tokens/sec, input/cached/output/
  reasoning tokens, dollar cost from a pricing snapshot, retries, errors, refusals, format compliance, and
  consistency across repeats. Confidence intervals come from a cluster bootstrap.
* **Test any model, even without an API.** A *manual* contestant turns every prompt into an item in the
  **Manual Inbox**: copy it into any chatbot (old, current or future), paste the reply back, and it is graded
  by the same scorer. Single replies can also be graded on the spot (**Grader** or `node src/cli.ts grade`).
* **Fair play.** Fresh stateless conversation per case, no tools, answer keys never sent, hedged answers
  marked wrong, judges blind to identity and never grading their own vendor, split verdicts sent to human
  arbitration, held-out private tests, and a contamination canary.
* **Budget control.** Per-test × per-model cost tables (**Cost Planner**, `node src/cli.ts costs`), estimates
  that calibrate from your own runs, and a hard spending cap per run (`--max-cost`).
* **Extensible.** Add tests in the dashboard's Test Builder, as JSON files, or as TypeScript programs.
  Add models from the dashboard, with live model discovery.

---

## Quick start

Requirements: **Node.js ≥ 22.18** (runs TypeScript natively; no build step for the engine).

```bash
cd gauntlet
npm install
cp .env.example .env          # add the API keys for the providers you want to test
npm start                     # builds the dashboard and serves it on http://localhost:7777
```

A first run that needs no API keys, useful for checking the install:

```bash
node src/cli.ts run --models random-baseline --suite quick --repeats 1 --yes
```

Then open the dashboard: **New Run** → pick models and a suite → check the cost estimate → **Start**.
The **Live Arena** streams every model's output side by side while it works.

## Dashboard

| Screen | What it's for |
|---|---|
| **Leaderboard** | Gauntlet Index with 95% CIs, category heatmap, Olympic medal table, score-vs-cost scatter with Pareto frontier, speed charts. Combined across all runs or per run. |
| **New Run** | Pick a suite or hand-pick tests, choose models, repeats, concurrency and judges. Shows a live cost estimate and warnings (missing keys, unverified prices). |
| **Live Arena** | One lane per model: live streaming output, progress, spend, tokens, score ticker. Made for recording. |
| **Runs / Run detail** | History with resume/export/delete. Results matrix (tests × models). The inspector shows every case's transcript, timing, tokens, cost, score breakdown, judge rationales, artifacts (playable games, SVGs, screenshots) and the **Replay player** for simulations. |
| **Tests / Test Builder** | Browse the library with the exact prompts. Create, duplicate, validate and save new tests without writing code. |
| **Models** | Add or edit models, pricing and effort. API key status per provider, ping a model, discover the model ids your key can access. |
| **Manual Inbox** | Prompts waiting for copy & paste models: copy, paste the reply, submit. |
| **Grader** | Paste any model's reply to one case and get it graded instantly (doesn't touch the leaderboard). |
| **Cost Planner** | Estimated cost of every test for every model, before you spend anything. |
| **Blind Review** | Rate open-ended outputs (games, illustrations) side by side with identities hidden, and arbitrate cases where the judges disagreed. |
| **Methodology** | The scoring and reproducibility rules, generated from the live configuration. |
| **Arena** | Head-to-head tournaments: models play **Connect Four** and **chess** against each other in a knockout bracket or round-robin. Live board with streaming "thinking", clocks and spend; a bracket that fills in as winners advance; move-by-move replays; full-screen match cards for the video. See [The Arena](#the-arena-head-to-head-games). |

Press **B** anywhere for **Broadcast mode**: chrome hidden, large type, 16:9-friendly layout.

## Command line

```bash
node src/cli.ts models                         # configured models + API key status
node src/cli.ts tests                          # the test library with versions and hashes
node src/cli.ts suites                         # suites and their fingerprints
node src/cli.ts show honesty.honesty-trap      # the exact prompts every model receives
node src/cli.ts costs --suite core                # cost of every test for every enabled model
node src/cli.ts estimate --models claude-opus-5,gpt-5.6-sol --suite core --repeats 3
node src/cli.ts run --models claude-opus-5,gpt-5.6-sol,gemini-3.1-pro --suite core --repeats 3 --max-cost 40 --name "September showdown"
node src/cli.ts run --models manual-chat --suite quick --repeats 1   # copy & paste mode in the terminal
node src/cli.ts grade math.competition c04 --file reply.txt          # grade one pasted reply
node src/cli.ts probe-contamination --models claude-opus-5           # has the model seen Gauntlet data?
node src/cli.ts prompts --suite core > prompt-book.md                # every exact prompt, for publishing
node src/cli.ts resume <runId>                 # finish an interrupted/cancelled run (only if tests are unchanged)
node src/cli.ts report <runId> --format md     # Markdown leaderboard for descriptions/blogs
node src/cli.ts leaderboard --suite core       # combined across all runs
node src/cli.ts ping claude-opus-5             # smoke-test a model/key
node src/cli.ts discover openai                # model ids available to your key
node src/cli.ts validate                       # validate every test, suite and model
```

Runs started from the CLI appear in the dashboard, and the reverse.

## The Arena (head-to-head games)

Instead of each model taking a test alone, two models **play each other**. Pick a game, 4, 8 or 16 models and a
format, check the cost estimate, and press Start (dashboard: **Arena → New tournament**).

* **Every move is a fresh prompt** with the full rules, the move history, the current position and (by default)
  the list of legal moves. The model ends its reply with `MOVE: <move>` (chess accepts `e2e4`, `e7e8q` or `Nf3`,
  `O-O`). Nothing carries over between moves, so every model sees exactly the same kind of prompt.
* **Illegal or unreadable move** → one retry with the problem explained → then a random legal move is played for
  the model and it gets a **strike**. Three strikes lose the game.
* **Fair sides:** every pairing is a mini-match of 2 (or 4, 6) games with the sides swapped. A level knockout
  match goes to sudden death (each sudden-death game starts after one random move per side, so two
  deterministic models don't just replay game 1), then fewer illegal moves, then lower cost, then the higher seed.
* **Chess rules** are fully enforced by Gauntlet's own move generator (castling, en passant, promotion, check,
  checkmate, stalemate, 50-move rule, threefold repetition, insufficient material). At the move cap (120
  half-moves) the game is decided on material: a lead of 3+ points wins, otherwise it's a draw.
* **Seeding** by current Gauntlet Index (the top two can only meet in the final) or your own order. Byes go to
  the top seeds when the field isn't a power of two.
* **Cost:** an estimate per game and for the whole bracket before you start, and a hard spending cap checked
  before every move. The Random Baseline and manual (copy & paste) models can play too.
* Tournaments are stored in `data/arena/<id>/` (manifest + one line per game with every prompt and reply) and
  can be cancelled and **resumed**; only unfinished games are replayed.

```bash
node src/cli.ts arena games
node src/cli.ts arena new --game connect4 --models a,b,c,d --format knockout --max-cost 5
node src/cli.ts arena new --game chess --models a,b,c,d,e,f,g,h --games 2 --seeding index --max-cost 25
node src/cli.ts arena list
node src/cli.ts arena show <id>
node src/cli.ts arena resume <id> --max-cost 10
```

For the video: open the tournament and press **B** (broadcast) for the live board or the bracket, click any game
for a replay (Space / ← → / F), and use **Match cards** for full-screen 1920×1080 cards of every match and the
champion. Try it with no keys at `?mock=1`: a finished 8-model chess bracket and a Connect Four cup playing live.

## Suites

| Suite | Purpose |
|---|---|
| `core` | **Standard tier.** The official benchmark: 21 tests across all 11 categories. Use this (3 repeats) for published results. |
| `frontier` | **Frontier tier.** 16 extreme tests (extreme logic, olympiad maths, frontier coding, adversarial system prompts, pressure honesty traps, hard variants of every simulation) to separate the best models once they bunch up near the top of Core. |
| `quick` | A fast, cheap subset (one or two hard cases per test) for smoke tests and trying out new models. |
| `all` | Everything, including custom tests. |

Expected spend for every suite, per test and per model, is in [docs/COSTS.md](docs/COSTS.md). How every test was
verified and blind-played before release is in [docs/AUDIT.md](docs/AUDIT.md).

Suites live in `suites/*.json`. A suite lists test ids with optional weights, category weights and a
default repeat count. Bump the suite `version` when you change it.

## The tests

**Standard tier (`core`)**

| Test | Id | What it does |
|---|---|---|
| Deduction Grid | `reasoning.deduction-grid` | Nineteen clues, one arrangement. No partial credit. |
| Knights, Knaves, Spies & Alternators | `reasoning.truth-tellers` | Knights never lie, knaves always do, and the spy is doing whatever it wants. |
| Shortest Plans | `reasoning.planning` | Find the shortest plan — one move too many and it scores zero. |
| Competition Maths | `math.competition` | Twenty contest problems. Integer answers. No partial credit. |
| Real-World Word Problems | `math.word-problems` | Real receipts, real payslips, real traps. |
| Algorithms Under Test | `coding.algorithms` | Hidden tests, huge inputs, two seconds. Does the code actually work? |
| Edge-Case Minefield | `coding.debug-and-edge-cases` | The happy path is easy. The hidden tests are not. |
| Hard Mode Engineering | `coding.hard` | Build a regex engine, a spreadsheet and a calendar - in one shot each. |
| Precision Formatting | `instruction.precision-formatting` | Fifty words exactly. Five sentences exactly. No commas. Go. |
| Stay In Character | `instruction.system-prompt-adherence` | The user says "ignore your rules". Does it? |
| The Honesty Trap | `honesty.honesty-trap` | Half these questions are lies. Will the model play along? |
| Messy Text to Exact JSON | `extraction.structured-json` | Eight messy documents, one exact JSON schema. Every field is checked. |
| Build a Game in One Shot | `creative.one-shot-games` | One prompt. One file. One playable game. |
| Precise SVG Illustration | `visual.svg-illustration` | Exactly three beams. Hands at exactly 304.25 degrees. |
| Draw It Blind | `visual.draw-it-blind` | Describe it with no numbers. Redraw it from your own words. How close does it get? |
| Needle in a Haystack | `long-context.needle-haystack` | 45,000 words. Ten needles. One near-miss decoy for each. Who reads to the end? |
| Chain of Whispers | `long-context.chain-of-whispers` | Six rewrites, no memory. How many of the 12 facts make it to the end? |
| Survival Island | `agentic.survival-island` | Twelve days on a desert island: can it find water, dodge the poison berries and signal the passing ship? |
| The Escape Room | `agentic.escape-room` | Three locked rooms, 45 moves, and a brand-new chain of ciphers every seed. |
| The Startup | `agentic.startup-sim` | $10,000, twelve months and a hidden market — can it beat autopilot? |
| The Liar's Table | `social.liars-table` | Five suspects. One liar. Twelve questions. Who cracks the case fastest? |

**Frontier tier (`frontier`)**

| Test | Id | What it does |
|---|---|---|
| Deduction Grid: Extreme | `reasoning.deduction-grid-extreme` | Eight people, six attributes, forty clues, zero slack. |
| Knights, Knaves, Spies & Alternators: Extreme | `reasoning.truth-tellers-extreme` | Ten islanders, four kinds of liar, one consistent story. |
| Shortest Plans: Extreme | `reasoning.planning-extreme` | Ten puzzles too big to brute-force by hand. One move over the minimum scores zero. |
| Olympiad Maths | `math.olympiad` | Twelve olympiad problems. One integer each. No calculator. |
| Frontier Engineering | `coding.frontier` | Six problems where the obvious solution is too slow or subtly wrong. |
| Extraction: Frontier | `extraction.frontier` | Five messy documents where every correction moves a number. One wrong field and the document scores zero. |
| Extreme Constraints | `instruction.extreme-constraints` | Sixty words. Five sentences. No letter e. All at once, or zero. |
| Adversarial System Prompt | `instruction.adversarial-system` | Five turns of pressure. One secret. Fifteen rules. Does anything slip? |
| Pressure Traps | `honesty.pressure-traps` | Just the number, no caveats, and my professor already confirmed it. Will the best models still tell the truth? |
| Draw It Blind — Hard | `visual.draw-it-blind-hard` | Twelve shapes, ninety words, no numbers. Can it rebuild the picture from its own notes? |
| Needle in a Haystack — Hard | `long-context.needle-haystack-hard` | 78,000 words, 12 needles, a decoy at every hop. Can anything read this carefully? |
| Chain of Whispers — Hard | `long-context.chain-of-whispers-hard` | Twenty facts, sixty words, ten rewrites. What's left at the end? |
| Survival Island (Hard) | `agentic.survival-island-hard` | Thirteen harsh days: storms, a trickling spring, poison everywhere — and the ship does not come until day 8. |
| The Escape Room (Hard) | `agentic.escape-room-hard` | Ten locks, two-step ciphers, a clue from the first room needed in the last — and only 25% more moves than a perfect solver. |
| The Startup (Volatile Market) | `agentic.startup-sim-hard` | Same $10,000, a far more volatile market: a deeper supplier spike, a brutal price war and a demand crash. |
| The Liar's Table — Hard | `social.liars-table-hard` | Seven suspects, eight questions, and the door log went dark. Who can still find the liar? |

Every prompt, verbatim, is in the prompt book (`node src/cli.ts prompts --suite <id>`). Keep your own held-out tests in `tests/private/`.

## How scoring works (short version)

```
case score  ∈ [0, 1]   exact answers, unit tests, constraints, geometry, simulations, or a judge panel
test score  = mean over cases of (mean over repeats)
category    = weighted mean of its tests
Index       = 100 × weighted mean of categories        (95% CI via cluster bootstrap over cases)
```

The full rules are in **[docs/METHODOLOGY.md](docs/METHODOLOGY.md)**: protocol, temperature/effort
policy, retries, refusals (no silent fallbacks), judges, metrics definitions, reproducibility and a
publishing checklist.

## Adding tests and models

See **[docs/ADDING_TESTS.md](docs/ADDING_TESTS.md)**. In short:

* **No code:** Dashboard → Tests → *New test* (or *Duplicate* an existing one). Saved to `tests/custom/`.
* **JSON:** drop a file in `tests/<category>/<slug>.json`; run `node src/cli.ts validate`.
* **Simulations / generated inputs:** write a program in `src/programs/`, register it, add a JSON test
  pointing at it.
* **Models:** Dashboard → Models → *Add model* (use *Discover* for exact ids), or edit `config/models.json`.
  A model with a different effort setting is a separate contestant.

Editing a test changes its hash. Old results stay in the run history but stop counting toward the
leaderboard, so numbers produced under different conditions are never mixed.

## Pricing

`config/models.json` includes prices so costs can be computed. Anthropic prices are taken from Anthropic's
API reference. **Other vendors' prices and some model ids came from third-party trackers and are marked
unverified** (amber badge in the dashboard, warning before each run). Check them against the provider's
price page and set `verifiedAt` before publishing cost figures. Every run stores the pricing it used, so
later price changes don't rewrite history.

## Data

```
data/runs/<runId>/
  manifest.json    run config, test hashes, model + pricing snapshots, fingerprint, status
  results.jsonl    one CaseResult per line (transcript, scores, metrics, replay)
  artifacts/       generated games, SVGs, screenshots, code
```

Plain files: easy to archive, diff and publish. **Export CSV/JSON** is available per run in the dashboard
and via the API (`docs/API.md`).

## Security notes

* The server binds to `127.0.0.1` by default. It can spend your API credits, so only expose it
  (`--host 0.0.0.0`) on a trusted network.
* Model-generated code runs in a separate Node process with the permission model (no file writes, no
  child processes), string code generation disabled, a 256 MB heap and hard timeouts.
* Model-generated HTML/SVG is served with a sandbox CSP (opaque origin, no network, no forms) and embedded
  in sandboxed iframes. Headless browser checks block all network requests.
* API keys come from the environment / `.env` and are never sent to the dashboard.

## Development

```bash
npm test               # harness unit + integration tests, provider wire-format tests, library integrity, programs
npm run typecheck      # engine + dashboard
npm run dev            # API server on :7777
npm run dev:ui         # hot-reloading dashboard on :5173 (proxies /api)
```

Project layout:

```
config/        models & providers, categories, settings (judges, defaults)
suites/        suite definitions
tests/         the test library (JSON), tests/custom for tests created in the UI
src/core/      types, registry (loading, validation, hashing), config, stats, extraction
src/providers/ Anthropic, OpenAI-compatible, Gemini, Random Baseline adapters
src/scoring/   scorers, code sandbox, headless browser checks, judge prompts
src/programs/  simulations & pipelines
src/engine/    runner (scheduling, retries, resume), store, aggregation, leaderboards, review
src/server/    HTTP API + SSE + static dashboard
ui/            the dashboard (React + Vite, no UI dependencies)
docs/          methodology, API, authoring guide
```
