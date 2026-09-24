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
| **Blind Review** | Rate open-ended outputs (games, illustrations) side by side with identities hidden. |
| **Methodology** | The scoring and reproducibility rules, generated from the live configuration. |

Press **B** anywhere for **Broadcast mode**: chrome hidden, large type, 16:9-friendly layout.

## Command line

```bash
node src/cli.ts models                         # configured models + API key status
node src/cli.ts tests                          # the test library with versions and hashes
node src/cli.ts suites                         # suites and their fingerprints
node src/cli.ts show honesty.honesty-trap      # the exact prompts every model receives
node src/cli.ts estimate --models claude-opus-5,gpt-5.6-sol --suite core --repeats 3
node src/cli.ts run --models claude-opus-5,gpt-5.6-sol,gemini-3.1-pro --suite core --repeats 3 --name "September showdown"
node src/cli.ts resume <runId>                 # finish an interrupted/cancelled run (only if tests are unchanged)
node src/cli.ts report <runId> --format md     # Markdown leaderboard for descriptions/blogs
node src/cli.ts leaderboard --suite core       # combined across all runs
node src/cli.ts ping claude-opus-5             # smoke-test a model/key
node src/cli.ts discover openai                # model ids available to your key
node src/cli.ts validate                       # validate every test, suite and model
```

Runs started from the CLI appear in the dashboard, and the reverse.

## Suites

| Suite | Purpose |
|---|---|
| `core` | The official Gauntlet benchmark: every built-in test. Use this for published results. |
| `quick` | A fast, cheap subset (one test per category, fewer cases) for smoke tests and trying out new models. |
| `all` | Everything, including custom tests. |

Suites live in `suites/*.json`. A suite lists test ids with optional weights, category weights and a
default repeat count. Bump the suite `version` when you change it.

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
