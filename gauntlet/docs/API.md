# Gauntlet HTTP API

The Gauntlet server (`node src/cli.ts serve`, default port **7777**) exposes a JSON API under `/api`
and serves the built dashboard from `ui/dist`. All shapes referenced below are defined in
[`src/core/types.ts`](../src/core/types.ts). Errors are returned as `{ "error": string, "details"?: unknown }`
with a 4xx/5xx status.

## Meta

| Method | Path | Returns |
|---|---|---|
| GET | `/api/meta` | `{ harnessVersion, protocolVersion, categories: CategoryInfo[], providers: Array<ProviderConfig & { hasKey: boolean }>, settings: Settings, programs: Array<{ id, name, description, scoring }>, browserChecks: boolean }` |

`Settings` = `{ judges: string[], defaultRepeats: number, defaultConcurrency: number, temperature: number, defaultMaxOutputTokens: number, defaultTimeLimitSec: number, maxRetries: number, judgeExcludeSameVendor: boolean }`.

`LeaderboardRow.manual === true` marks contestants whose replies were pasted in by hand: show a "manual" badge and
treat their latency/throughput as not comparable (human time), and their cost as user-entered.

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
  source: 'builtin' | 'custom' | 'private'; file: string;   // private = held-out, git-ignored tests/private/
  estimate: { inputTokens: number; outputTokens: number; calls: number }; // per case
}
interface RenderedCase { caseId: string; system?: string; turns: string[]; expected?: unknown; notes?: string }
```

**Vision tests.** A case may list `images` (paths relative to the test file). `TestSummary.imageCases` counts
the cases with images and `RenderedCase.images` is `Array<{ turn: number; file: string; path?: string }>` where
`path` is relative to the tests folder.

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/test-files/<path>` | – | The PNG/JPEG at `tests/<path>` (images only; nothing outside the tests folder) |
| POST | `/api/test-images` | `{ testId, name, data }` (`data` = base64 or a data URL) | `{ file, path, width, height, bytes, mediaType }` — saved as `tests/custom/images/<testId>/<name>`; put `file` in the case's `images` |
| GET | `/api/manual/:requestId/images/:message/:image` | – | One image of a pending Manual Inbox request (listings leave out the bytes) |

`RunRequest.forceVision: true` sends picture cases to models without `vision: true`; otherwise those cases are
stored with `status: "skipped"`, `score: null`, and counted in `TestAggregate.skipped` / `LeaderboardRow.totals.skipped`.
Stored transcripts keep image metadata (`name`, `sha256`, `width`, `height`, `path`) but not the bytes.

For program tests, `rendered` lists one entry per seed with `turns: []` (prompts are generated at run time
from the seed; the program description explains them).

## Runs

| Method | Path | Body | Returns |
|---|---|---|---|
| POST | `/api/estimate` | `RunRequest` | `RunEstimate` (see below) |
| POST | `/api/runs` | `RunRequest` | `{ runId: string }` — starts immediately |
| GET | `/api/runs` | – | `RunListItem[]` (newest first) |
| GET | `/api/runs/:id` | – | `{ manifest: RunManifest, leaderboard: Leaderboard, results: CaseResultLite[], progress: { completed, total, costUsd }, active: boolean }` |
| GET | `/api/runs/:id/results/:key` | – | `CaseResult` (full transcript + replay). `key` must be URL-encoded. |
| POST | `/api/runs/:id/cancel` | – | `{ ok: true }` |
| POST | `/api/runs/:id/resume` | `{ maxCostUsd?: number \| null }` (optional new budget cap; `null` removes it) | `{ ok: true }` — re-runs only the missing / errored jobs |
| DELETE | `/api/runs/:id` | – | `{ ok: true }` |
| GET | `/api/runs/:id/events` | – | **Server-Sent Events** stream of `RunEvent` JSON (`data: {...}\n\n`). Sends a `run.progress` snapshot on connect. `job.delta` events are throttled (~10/s per job). |
| GET | `/api/runs/:id/export.csv` | – | CSV of every case result |
| GET | `/api/runs/:id/export.json` | – | `{ manifest, leaderboard, results: CaseResult[] }` |
| GET | `/api/runs/:id/artifacts/:file` | – | Artifact file. HTML is served with a strict sandbox CSP; embed it in `<iframe sandbox="allow-scripts">`. |

`RunRequest.maxCostUsd` is a hard budget cap (USD, contestant + judge cost). When reached, the run stops starting
new cases, ends with status `cancelled` and `manifest.error` explains why; resume with a higher cap to finish.

```ts
interface RunEstimate {
  jobs: number; calls: number;
  perContestant: Array<{ contestantId: string; jobs: number; estCostUsd: number; estCostUsdHigh: number; manual: boolean }>;
  perTest: Array<{ testId: string; name: string; category: string; cases: number;
                   perContestant: Record<string, number>;   // USD for all cases × repeats
                   judgeUsd: number;
                   basis: 'measured' | 'measured-other-models' | 'definition' }>;
  judgeCostUsd: number;
  estCostUsd: number;        // central estimate
  estCostUsdHigh: number;    // conservative upper bound
  fingerprint: string; warnings: string[];
}
```

Estimates use the average token usage measured in previous runs of the *same test version* (per model when
available, otherwise across models), falling back to each test's declared `estimate`.

## Cost planner

| Method | Path | Returns |
|---|---|---|
| GET | `/api/costs?suite=core&repeats=1&models=a,b` | `RunEstimate` for the suite. `models` defaults to every enabled API model (manual and baseline excluded). Use `perTest` to render a test × model cost table. |

## Manual (copy & paste) contestants

A contestant whose provider has `type: "manual"` never calls an API: every model call becomes a pending
`ManualRequest` (see `types.ts`). A person copies the prompt into any chatbot and pastes the reply back; it is
then graded exactly like an API reply. Manual jobs run in their own queue and have a 7-day time limit.

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/manual?runId=` | – | `ManualRequest[]` waiting for a reply (oldest first). Poll every ~2 s, or listen for `manual.request` / `manual.resolved` events on the run's SSE stream. |
| POST | `/api/manual/:requestId` | `{ text: string, inputTokens?, outputTokens?, reasoningTokens?, costUsd? }` | `{ ok: true }` — optional numbers let you record real usage/cost if the chat UI shows them; otherwise tokens are estimated from text length and cost is $0 |
| POST | `/api/manual/:requestId/fail` | `{ reason?: string }` | `{ ok: true }` — the case is recorded as an error (e.g. the chatbot refused to load or crashed) |

Show `combinedPrompt` for a **new** chat (it includes any system prompt and earlier turns) and, when
`isContinuation` is true, offer `latestUserMessage` for continuing the **same** chat.

## Grade a pasted reply (no run needed)

| Method | Path | Body | Returns |
|---|---|---|---|
| POST | `/api/grade` | `{ testId, caseId, response: string, vendor?: string, judgeIds?: string[] }` | `{ outcome: { score: number \| null, passed, summary, detail: ScoreDetail, pendingHuman? }, rendered: RenderedCase, judgeCostUsd, judgeTranscript: TranscriptEntry[], artifacts: ArtifactRef[], gradeId }` — prompt tests only. Artifacts are served from `/api/graded/<artifact.file>` |

## Leaderboard

| Method | Path | Returns |
|---|---|---|
| GET | `/api/leaderboard?suite=core` | `Leaderboard` combining the most recent valid result for every (contestant, test, case, repeat) across all runs. Results whose test hash or contestant config hash no longer match are excluded (`staleExcluded`). |

## Studio (video kit) and OBS overlays

`:runId` may be `latest` (the run in progress, else the newest run). Shapes are in
[`src/media/types.ts`](../src/media/types.ts).

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/studio/:runId` | – | `StudioPayload`: ranked `highlights` (type, title, why, evidence, rule, deep `link`, `cue`, `clipSec`), Presenter `slides`, the template `script` (+ `markdown`, `text`), `cardData` for the card templates, `allowedNumbers` (every number the data backs up), `browser.available`, `exportDir` |
| POST | `/api/studio/:runId/polish/estimate` | `{ modelId, text }` | `PolishEstimate` — tokens and cost (central + upper bound). No model call. |
| POST | `/api/studio/:runId/polish` | `{ modelId, text, confirmCostUsd }` | `PolishResult` `{ text, costUsd, unverified: string[] }`. Refused (409) unless `confirmCostUsd` ≥ the estimate's upper bound, i.e. the cost was shown first. |
| POST | `/api/studio/:runId/render` | `{ spec: CardSpec }` | `{ png: dataUrl, width, height, fileName }`; 409 when no Chrome/Edge/Chromium is available (render in the browser instead) |
| POST | `/api/studio/:runId/export` | `{ style?, headline?, markdown?, text? }` | `ExportResult` — writes PNGs, `script.md`, `script.txt`, `highlights.json` to `data/runs/<id>/studio/` |
| GET | `/api/overlay/:runId` | – | `OverlayData` — compact standings, latest-results ticker, "now testing", per-test winners, progress |

`GET /overlay/<runId>?view=…` (outside `/api`) redirects to the dashboard's transparent overlay page
(`/#/overlay/<runId>?view=…`), which live-updates from `/api/runs/:id/events`. `runId` may also be `demo`.

## Human review

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/review/queue?testId=` | – | `Array<{ runId, key, testId, caseId, contestantId, status, score, humanScores, reason: 'human-scored' \| 'judge-disagreement' \| 'second-opinion' }>` — `human` scorer tests, results where the judge panel disagreed (the human rating becomes the final score) and artifact tests (anonymise in UI) |
| POST | `/api/review/score` | `{ runId, key, score: number /* 0..1 */, rater: string, note?: string }` | `CaseResultLite` |

## Channel tools

Shapes are in [`src/channel/types.ts`](../src/channel/types.ts). None of these routes calls a model: the
New Model Day ping uses `POST /api/contestants/:id/ping` and runs use `POST /api/runs` (with `maxCostUsd`).

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/channel/site` | – | `SiteConfig` (`config/site.json` merged over defaults) |
| PUT | `/api/channel/site` | `Partial<SiteConfig>` | `SiteConfig` |
| POST | `/api/channel/publish` | `{ suites?: string[], zip?: boolean }` | `PublishResult` — writes the static site to `gauntlet/site` (and `site.zip`) |
| GET | `/api/channel/publish.zip?suites=core,frontier` | – | the site as a zip download |
| GET | `/api/channel/preview/<path>` | – | files of the last export (open `/api/channel/preview/index.html`) |
| POST | `/api/channel/newmodel/prepare` | `NewModelInput` | `NewModelPrepared` — adds/updates the contestant, checks the id against the provider's model list |
| GET | `/api/channel/newmodel/:id/costs?repeats=` | – | `NewModelSuiteCost[]` for quick, core, frontier |
| GET | `/api/channel/newmodel/:id/headline?suite=core` | – | `NewModelHeadline` — rank on the combined leaderboard, neighbours, best/worst category, title ideas |
| GET | `/api/channel/history?suite=core&metric=index&tiers=flagship` | – | `HistoryData` (`metric` = `index` or a category id) |
| GET | `/api/channel/challenge?season=` | – | `{ seasons: string[], queue: ChallengeQueue }` |
| POST | `/api/channel/challenge/:season/import` | `{ text: string, format?: 'csv' \| 'json' \| 'auto' }` | `ChallengeImportResult` |
| PUT | `/api/channel/challenge/:season/items/:id` | `{ question?, answer?, answerType?, alternatives?, viewerName?, viewerHandle?, notes?, credit?, status? }` | `ChallengeQueue` (400 when approving an item with errors) |
| DELETE | `/api/channel/challenge/:season/items/:id` | – | `ChallengeQueue` |
| POST | `/api/channel/challenge/:season/write` | `{ category?: string }` | `ChallengeWriteResult` — writes `tests/private/viewer-challenge-<season>.json` |
| GET | `/api/channel/challenge/:season/slides` | – | `ChallengeSlide[]` — approved questions with the latest outcome per model |

Contestants accept three optional fields used by History and the public site: `family`, `releaseDate`
(YYYY-MM-DD) and `tier` (`flagship` \| `mid` \| `small`). Tests and suites accept `publishPrompts: false`
(hide example prompts on the public site; not part of the test hash).

## Arena (head-to-head tournaments)

Shapes are defined in [`src/arena/types.ts`](../src/arena/types.ts). Tournaments are stored in
`data/arena/<id>/` (`manifest.json` + append-only `games.jsonl`, latest line per game key wins).

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/arena/games` | – | `Array<{ id, name, version, tagline, description, sides, rules, moveHelp, capRule, defaults: { maxPlies, listLegalMoves }, estimate, engine: 'board' \| 'turns' \| 'debate', gamesPerMatchOptions, options: ArenaOptionSpec[], judged, unit? }>` |
| POST | `/api/arena/estimate` | `ArenaRequest` | `ArenaEstimate`: games, moves, central and upper-bound USD, per-model cost per game, first-round pairings, fingerprint, warnings |
| POST | `/api/arena/tournaments` | `ArenaRequest` | `{ tournamentId }`, starts immediately |
| GET | `/api/arena/tournaments` | – | `TournamentListItem[]` (newest first) |
| GET | `/api/arena/tournaments/:id` | – | `TournamentDetail` = `{ manifest, state: TournamentState, games: ArenaGameLite[], active, live: LiveGame[] }` |
| GET | `/api/arena/tournaments/:id/games/:key` | – | `ArenaGameRecord` (every move with its board snapshot, every attempt, both transcripts) |
| POST | `/api/arena/tournaments/:id/cancel` | – | `{ ok: true }`: games in progress stop at their next move |
| POST | `/api/arena/tournaments/:id/resume` | `{ maxCostUsd?: number \| null }` | `{ ok: true }`: plays only missing games; 409 if the game code or a model's config changed |
| DELETE | `/api/arena/tournaments/:id` | – | `{ ok: true }` |
| GET | `/api/arena/tournaments/:id/judging` | – | Judged games waiting for a human verdict: `Array<{ key, matchId, roundName, gameNo, gameId, material, rubric, note? }>`. `material` is the blinded judge packet (Side A / Side B, no model names). |
| POST | `/api/arena/tournaments/:id/games/:key/verdict` | `{ winner: 'A' \| 'B', scores?: { side_a, side_b }, rationale? }` | `{ ok, winner, decision, resumed }`: records a human verdict (A/B as shown in the packet); the tournament continues when nothing else is waiting |
| GET | `/api/arena/tournaments/:id/export.json` | – | `{ manifest, state, games: ArenaGameRecord[] }` |
| GET | `/api/arena/tournaments/:id/events` | – | **Server-Sent Events** stream of `ArenaEvent`. On connect: `tournament.progress`, `tournament.status` and a `game.started` (with the moves so far) for every game in progress. |

```ts
interface ArenaRequest {
  game: 'connect4' | 'chess' | 'poker' | 'debate' | 'courtroom';
  contestantIds: string[];                 // 2–16
  format?: 'knockout' | 'round-robin';     // default knockout
  seeding?: 'index' | 'manual';            // Gauntlet Index (default) or the order given
  gamesPerMatch?: 2 | 4 | 6;               // sides swap every game (default 2)
  suddenDeath?: number;                    // knockout tie-break games (default 2)
  maxStrikes?: number;                     // default 3
  maxPlies?: number;                       // move cap (chess default 120)
  listLegalMoves?: boolean;                // default true
  concurrency?: number;                    // games at once (default 2)
  maxCostUsd?: number;                     // hard cap, checked before every model call
  seed?: number; name?: string; notes?: string;
  options?: Record<string, string>;        // poker { hands: '10'|'20'|'40'|'60' }; debate / courtroom { topic: 'random' | <id> }
}

type ArenaEvent =
  | { type: 'tournament.status'; status; error? }
  | { type: 'tournament.progress'; gamesDone; gamesTotal; costUsd }
  | { type: 'game.started'; game: LiveGame }                 // players, initial board, moves so far
  | { type: 'game.turn'; key; side; at }                     // a seat starts thinking (its clock starts)
  | { type: 'game.phase'; key; phase; at }                   // e.g. 'judging' after the last speech
  | { type: 'game.thinking'; key; side; text; attempt }      // streamed reply text (append), ~7/s
  | { type: 'game.move'; key; move: ArenaMove; strikes; metrics }
  | { type: 'game.finished'; game: ArenaGameLite }
  | { type: 'match.finished'; match: MatchState }
  | { type: 'log'; level; message }
  | { type: 'manual.request'; request } | { type: 'manual.resolved'; requestId };
```

Poker and judged games add optional fields: `ArenaGameRecord.margin` (chips per seat), `ArenaGameRecord.judging`
(`{ status, verdicts[], winner, votes, decision, split, excludedVendors, costUsd, transcript }`, judge cost counts
towards the cap), `ArenaMove.note` (the model's one-line `REASON:`) and `kind: 'verdict'` for the judges' step,
`MatchState.unit` ('chips') when a match is scored on margin, and game status `'awaiting-judges'`.

`TournamentState.matches[]` is derived from the finished games every time (never stored), so it is always
consistent after a crash. Each `MatchState` has `players` (null = decided by an earlier match), `games` (one
slot per game, with sudden-death slots added as they become necessary), `score` (win 1, draw ½), `illegal`,
`cost`, `winner`, `decidedBy` (`games` · `sudden-death` · `fewer illegal moves` · `lower cost` · `higher seed` ·
`bye`) and a one-line `summary`. Manual (copy & paste) contestants' moves appear in `GET /api/manual` with
`runId` = the tournament id.
