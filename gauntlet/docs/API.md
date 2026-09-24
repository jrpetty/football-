# Gauntlet HTTP API

The Gauntlet server (`node src/cli.ts serve`, default port **7777**) exposes a JSON API under `/api`
and serves the built dashboard from `ui/dist`. All shapes referenced below are defined in
[`src/core/types.ts`](../src/core/types.ts). Errors are returned as `{ "error": string, "details"?: unknown }`
with a 4xx/5xx status.

## Meta

| Method | Path | Returns |
|---|---|---|
| GET | `/api/meta` | `{ harnessVersion, protocolVersion, categories: CategoryInfo[], providers: Array<ProviderConfig & { hasKey: boolean }>, settings: Settings, programs: Array<{ id, name, description, scoring }>, browserChecks: boolean }` |

`Settings` = `{ judges: string[], defaultRepeats: number, defaultConcurrency: number, temperature: number, defaultMaxOutputTokens: number }`.

## Contestants (models)

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/contestants` | – | `ContestantView[]` |
| PUT | `/api/contestants/:id` | `Contestant` | `ContestantView` (create or replace) |
| DELETE | `/api/contestants/:id` | – | `{ ok: true }` |
| POST | `/api/contestants/:id/ping` | – | `{ ok: boolean, text?: string, totalMs?: number, ttftMs?: number \| null, usage?: TokenUsage, costUsd?: number, error?: string }` |
| GET | `/api/providers/:id/models` | – | `{ models: string[] }` — live model discovery from the provider's list endpoint |

## Tests & suites

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/tests` | – | `TestSummary[]` |
| GET | `/api/tests/:id` | – | `{ definition: TestDefinition, summary: TestSummary, rendered: RenderedCase[], program?: { id, name, description, scoring } }` |
| POST | `/api/tests/validate` | `{ definition: TestDefinition }` | `{ ok: boolean, errors: string[], hash?: string }` |
| POST | `/api/tests` | `{ definition: TestDefinition }` | `TestSummary` — saves to `tests/custom/<id>.json` (409 if id exists) |
| PUT | `/api/tests/:id` | `{ definition: TestDefinition }` | `TestSummary` — custom tests only; version auto-bumped (patch) if content changed and version not changed |
| DELETE | `/api/tests/:id` | – | `{ ok: true }` — custom tests only |
| GET | `/api/suites` | – | `Array<Suite & { fingerprint: string, testCount: number }>` |

```ts
interface TestSummary {
  id: string; version: string; hash: string; name: string; category: string;
  kind: 'prompt' | 'program'; difficulty: Difficulty; description: string; hook?: string;
  tags: string[]; caseCount: number; scorerType: string;   // e.g. 'exact', 'code-js', or 'program:survival-island'
  source: 'builtin' | 'custom'; file: string;
  estimate: { inputTokens: number; outputTokens: number; calls: number }; // per case
}
interface RenderedCase { caseId: string; system?: string; turns: string[]; expected?: unknown; notes?: string }
```

For program tests, `rendered` lists one entry per seed with `turns: []` (prompts are generated at run time
from the seed; the program description explains them).

## Runs

| Method | Path | Body | Returns |
|---|---|---|---|
| POST | `/api/estimate` | `RunRequest` | `{ jobs: number, calls: number, perContestant: Array<{ contestantId, jobs, estCostUsd }>, estCostUsd: number, fingerprint: string, warnings: string[] }` |
| POST | `/api/runs` | `RunRequest` | `{ runId: string }` — starts immediately |
| GET | `/api/runs` | – | `RunListItem[]` (newest first) |
| GET | `/api/runs/:id` | – | `{ manifest: RunManifest, leaderboard: Leaderboard, results: CaseResultLite[], progress: { completed, total, costUsd }, active: boolean }` |
| GET | `/api/runs/:id/results/:key` | – | `CaseResult` (full transcript + replay). `key` must be URL-encoded. |
| POST | `/api/runs/:id/cancel` | – | `{ ok: true }` |
| POST | `/api/runs/:id/resume` | – | `{ ok: true }` — re-runs only the missing / errored jobs |
| DELETE | `/api/runs/:id` | – | `{ ok: true }` |
| GET | `/api/runs/:id/events` | – | **Server-Sent Events** stream of `RunEvent` JSON (`data: {...}\n\n`). Sends a `run.progress` snapshot on connect. `job.delta` events are throttled (~10/s per job). |
| GET | `/api/runs/:id/export.csv` | – | CSV of every case result |
| GET | `/api/runs/:id/export.json` | – | `{ manifest, leaderboard, results: CaseResult[] }` |
| GET | `/api/runs/:id/artifacts/:file` | – | Artifact file. HTML is served with a strict sandbox CSP; embed it in `<iframe sandbox="allow-scripts">`. |

## Leaderboard

| Method | Path | Returns |
|---|---|---|
| GET | `/api/leaderboard?suite=core` | `Leaderboard` combining the most recent valid result for every (contestant, test, case, repeat) across all runs. Results whose test hash or contestant config hash no longer match are excluded (`staleExcluded`). |

## Human review

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/review/queue?testId=` | – | `Array<{ runId, key, testId, caseId, contestantId, status, score, humanScores }>` — results from `human` scorers and artifact tests (anonymise in UI) |
| POST | `/api/review/score` | `{ runId, key, score: number /* 0..1 */, rater: string, note?: string }` | `CaseResultLite` |
