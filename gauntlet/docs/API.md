# Gauntlet HTTP API

The Gauntlet server (`node src/cli.ts serve`, default port **7777**) exposes a JSON API under `/api`
and serves the built dashboard from `ui/dist`. All shapes referenced below are defined in
[`src/core/types.ts`](../src/core/types.ts). Errors are returned as `{ "error": string, "details"?: unknown }`
with a 4xx/5xx status.

## Meta

| Method | Path | Returns |
|---|---|---|
| GET | `/api/meta` | `{ harnessVersion, protocolVersion, categories: CategoryInfo[], providers: Array<ProviderConfig & { hasKey: boolean }>, settings: Settings, programs: Array<{ id, name, description, scoring }>, browserChecks: boolean }` |

`Settings` = `{ judges: string[], defaultRepeats: number, defaultConcurrency: number, temperature: number, defaultMaxOutputTokens: number, defaultTimeLimitSec: number, maxRetries: number, judgeExcludeSameVendor: boolean, currency?: { code, usdPerUnit, rateDate? } }` (costs are always USD; `currency` is for display, GBP by default).

`LeaderboardRow.manual === true` marks contestants whose replies were pasted in by hand: show a "manual" badge and
treat their latency/throughput as not comparable (human time), and their cost as user-entered.

## API keys

Keys are stored in `gauntlet/.env` (git-ignored) and applied immediately; the full key is never returned.

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/keys` | – | `{ file, keys: KeyStatus[] }`: every provider that needs a key (`set`, `source`: `file` \| `system` \| null, `masked`, `getKeyUrl`, `steps`, `models`) |
| PUT | `/api/keys/:provider` | `{ key }` | `{ ok, saved, warning?, check: { ok, models?, error?, rejected? }, status }`. The key is checked for free first (listing models); a key the provider rejects is **not** saved. Accepts pasted `NAME=value` lines and quotes. Loopback only. |
| DELETE | `/api/keys/:provider` | – | `{ ok, status }`: removes a key saved in `.env` (keys from the system environment are left alone). Loopback only. |
| POST | `/api/keys/:provider/test` | `{ send?: boolean, model? }` | Free check by default; `send: true` sends a one-word message to the cheapest enabled model of that provider (a fraction of a cent) |

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
| GET | `/api/tests/:id` | – | `{ definition: TestDefinition, summary: TestSummary, rendered: RenderedCase[], program?: { id, name, description, scoring }, explainer?: TestExplainer }` — `explainer` is the plain-English explainer (see `src/core/explainers.ts`); custom tests without one get a plain version built from their description (`generated: true`) |
| GET | `/api/tests/:id/sample` | `?reveal=1` optional | `TestSample` — the first case as a viewer sees it: the question trimmed for the screen (`text`, and for book-length prompts the `tail` with the questions plus `skippedWords`), its pictures (`images[].path` → `/api/test-files/<path>`), shared instructions (`context`, `contextKind`). Programs: `situation` plus the real first message of seed 1, captured without calling any model. Arena games use the id `arena.<gameId>` and return the opening position. The answer (`answer`, `answerNote`) is only included with `?reveal=1`; `hasAnswer` says whether there is one. Held-out tests return `private: true` and no question |
| GET | `/api/explainers` | – | `{ explainers: Record<testId, TestExplainer> }` — every hand-written explainer, Arena games as `arena.<gameId>` |
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
| GET | `/api/runs/:id/events` | – | **Server-Sent Events** stream of `RunEvent` JSON (`data: {...}\n\n`). Sends a `run.progress` snapshot on connect. `job.delta` events are throttled (~10/s per job); each carries one model call's text (`label`), and `reset: true` means drop what that call streamed so far (a failed attempt is being retried). Providers that cannot stream send the whole answer as one `job.delta` when it arrives. `job.finished` also carries `passed`. Streamed text is display-only: it never changes what is recorded. |
| GET | `/api/runs/:id/export.csv` | – | CSV of every case result |
| GET | `/api/runs/:id/export.json` | – | `{ manifest, leaderboard, results: CaseResult[] }` |
| GET | `/api/runs/:id/artifacts/:file` | – | Artifact file. HTML is served with a strict sandbox CSP; embed it in `<iframe sandbox="allow-scripts">`. |

`RunRequest.maxCostUsd` is a hard spending limit (USD, contestant + judge cost). Every call reserves its worst case
first, so it is never exceeded. When the money left cannot pay for the next call, the run ends with status
`cancelled`, `manifest.stopReason: 'spend-limit'` and a plain `manifest.error`; half-finished cases are not stored
(their spend goes to `manifest.unrecordedCostUsd`); resume with a higher limit to finish.

`RunRequest.limits` (optional): `{ perAnswerUsd?: number; sameOutputTokens?: number; currency?: { code, usdPerUnit } }`.
`perAnswerUsd` lowers each model call's output limit to what that money buys at the model's price;
`sameOutputTokens` replaces "model's maximum" for every model; `currency` records what the owner typed the limits
in. The limits used are stored in `manifest.settings.limits`. The estimate's `perContestant[]` rows carry
`maxOutputTokens` and `outputLimitBy` (`'model-max' | 'same-tokens' | 'per-answer'`) and `estCostUsdMax` (the
upper bound if every "model's maximum" reply used its whole allowance).

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/settings/currency` | – | `{ code: 'GBP' \| 'USD' \| 'EUR', usdPerUnit: number, rateDate?: string }` |
| PUT | `/api/settings/currency` | `{ code, usdPerUnit }` | the saved setting (from this computer only). The rate is typed by the owner; nothing is fetched. |

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

## Your own budget

"My budget" settings live in `config/settings.json` under an optional `budget` key; all amounts are US dollars
(the UI converts to the display currency). Spend is read from the stored runs (`results.jsonl`), tournaments
(`games.jsonl`) and `data/budget/spend-log.jsonl` (AI judges grading a pasted reply, Studio script polish), counted in
the calendar month (server's local time) in which it was spent. Manual and Random Baseline contestants cost 0; their
judges still count.

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/budget` | – | `BudgetStatus`: `{ settings, currency, month: { key, label, start, nextReset, nextResetLabel }, spentUsd, committedUsd, remainingUsd, availableUsd, fraction, tone: 'none'\|'ok'\|'warn'\|'over', blocked, items: BudgetItem[], days: Array<{ date, spentUsd }> }` |
| PUT | `/api/budget` | `{ monthlyUsd?, hardStop?, defaultRunUsd?, defaultPerAnswerUsd? }` | `BudgetStatus`. A number sets a value, `null` clears it, a missing field keeps it. `hardStop` needs a monthly budget. Loopback only. |

`BudgetSettings` = `{ monthlyUsd?, hardStop?, defaultRunUsd?, defaultPerAnswerUsd? }` (see
[`src/budget/budget.ts`](../src/budget/budget.ts)). `committedUsd` is what running runs and tournaments may still
spend under their limits; `availableUsd` = monthly − spent − committed.

**Enforcement.** With `hardStop` on, `POST /api/runs`, `POST /api/runs/:id/resume`, `POST /api/arena/tournaments` and
`POST /api/arena/tournaments/:id/resume` refuse to start (4xx with a plain-English `error` naming the reset date) when nothing is
left, and otherwise lower the whole-run limit to what is left (for a resume: what the job already spent plus what is
left). A lowered limit is recorded as `settings.limits.budgetNote` (runs) or `settings.budgetNote` (tournaments),
e.g. "Limited to £12.40: what's left of your £50.00 monthly budget." With `hardStop` off the budget only warns.

CLI: `node src/cli.ts budget` (show), `budget set monthly 50` (display currency), `budget set run 10`,
`budget set per-answer 2`, `budget set hard-stop on|off`, `budget set monthly off`.

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

## The Gallery (picture replies)

| Method | Path | Body | Returns |
|---|---|---|---|
| POST | `/api/manual/:requestId/image` | `{ data: base64 or data: URL (PNG/JPEG, ≤ 20 MB), costUsd?, note? }` | `{ ok: true, width, height, bytes, mediaType }` — answers a Manual Inbox request whose `expects` is `"image"` (it also carries `aspectRatio`). A text reply to such a request is refused with 400. |

Gallery results carry `scoreDetail.gallery` (`GalleryDetail` in `src/programs/lib/gallery-judge.ts`): the
brief, the painting's size, every checklist line with each judge's verdict and reason, the six artistry criteria
with each judge's score, the judges' spread and whether they disagree. The painting is the artifact
`painting.png` (or `painting.jpg`; Painted in Code also stores `painting.svg`). Owner ratings and blind votes use
`POST /api/review/score`: a rater named `Blind vote` is recorded only; any other rater's score (0–1) becomes the
painting's artistry (× 10) and the score is recomputed. Skipped results say why in `summary`: *Skipped — model
has no image output* or *Skipped — picture-only model*.

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

## Head to Head (versus)

Read-only; no model is called. Shapes are in [`src/versus/types.ts`](../src/versus/types.ts).
Without `run`, every stored result whose test hash and model config still match is pooled (same rule as the
combined leaderboard); `suite` optionally limits the tests.

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/versus?a=<id>&b=<id>[&run=<runId>][&suite=<id>]` | – | `VersusData`: both fighters (label, vendor, colour, price per 1M tokens, context), `rounds` (per test: both scores on the cases both answered, samples, cost and time for one pass, `winner` `a`/`b`/`tie`, `margin`, `moment` = a case one got right and the other wrong with truncated quotes), `skipped` tests, `totals` (rounds won, average score, cost, time, tokens/s, value = points per $), `winner`, `tieMargin` (2 points). 404 when a model is not in the run / config, 400 for the same model twice. |
| GET | `/api/versus/options[?run=<runId>]` | – | `VersusOptions`: models with results in that scope (+ test count), a `suggested` pair, and runs with results |
| POST | `/api/versus/render` | `{ a, b, run? }` | The vertical 1080×1920 Shorts card: `{ png: dataUrl, width, height, fileName }`; 409 when no Chrome/Edge/Chromium is available (render in the browser instead) |

## Human review

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/review/queue?testId=` | – | `Array<{ runId, key, testId, caseId, contestantId, status, score, humanScores, reason: 'human-scored' \| 'judge-disagreement' \| 'second-opinion' }>` — `human` scorer tests, results where the judge panel disagreed (the human rating becomes the final score) and artifact tests (anonymise in UI) |
| POST | `/api/review/score` | `{ runId, key, score: number /* 0..1 */, rater: string, note?: string }` | `CaseResultLite` |

## Grading Station

People, AI judges or both grade stored results (`src/grading/`; METHODOLOGY 5.6). Types: `src/grading/types.ts`,
`GradingSpec` in `src/grading/spec.ts`.

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/grading/settings` | – | `{ official: 'methodology' \| 'human' \| 'ai' \| 'average', policies }` |
| PUT | `/api/grading/settings` | `{ official }` | Same. Saved to `config/settings.json → gradingOfficial`. |
| GET | `/api/grading/spec/:testId?case=` | – | `GradingSpec`: kind, `gradedOn`, `humanRole` (`grade` \| `second-opinion` \| `dispute`), `aiRole`, checklist, rubric `criteria` (points, anchors, requirement lists), `labels`, `judgeWeight`, `rules`, `answerKey` (with `case`). `arena.<game>` for judged Arena games. |
| GET | `/api/grading/runs` | – | `{ runs: Array<{ id, name, createdAt, status, results, todo, gradable }>, arena: Array<{ id, name, game, gameName, pending }>, official }` |
| GET | `/api/grading/queue?runId=` | – | `{ items: QueueItem[], official }` — one per result with `need` (`grade` \| `judge-failed` \| `arbitrate` \| `second-opinion` \| `review`), `todo`, counts of human/AI grades and disputes |
| GET | `/api/grading/item/:runId/:key` | – | `StationItem`: full `CaseResult`, the case's `GradingSpec`, rendered prompt, test and contestant info, explainer, policy, official source, human-vs-AI `agreement`, and the AI panel that would be used (or why none can) |
| POST | `/api/grading/human` | `{ runId, key, rater, criteria?: Record<id, points>, requirements?: Record<'<criterion>:<req>', 'met' \| 'partial' \| 'missed'>, label?, score? /* 0..1, only for single-criterion specs */, note?, blind? }` | `CaseResultLite` with the updated `humanScores` and official score. 400 for machine-scored (dispute-only) results. |
| POST | `/api/grading/dispute` | `{ runId, key, rater, note }` | `CaseResultLite` (`disputes` appended; score unchanged) |
| POST | `/api/grading/ai/estimate` | `{ runId, keys: string[] }` | `{ items: Array<{ key, ok, reason?, judges: Array<{ id, label, vendor, vision, images, estUsd }>, estUsd }>, totalUsd, totalUsdHigh, gradable }` — no model is called |
| POST | `/api/grading/ai/grade` | `{ runId, keys, confirmCostUsd }` | `{ outcomes: Array<{ key, ok, error?, costUsd, result? }>, costUsd }`. 409 unless `confirmCostUsd` covers a fresh estimate. |
| POST | `/api/grading/runs/:id/reapply` | – | `{ updated }` — re-scores graded results under the current policy |
| GET | `/api/grading/runs/:id/summaries` | – | `{ template: Record<'contestantId\|testId', string>, ai: Record<…, { text, writerId, writerLabel, vendor, costUsd, at, basis, stale }> }` |
| POST | `/api/grading/runs/:id/summaries/estimate` | `{ pairs?: string[] }` | `{ pairs: Array<{ key, contestantId, testId, writer, estUsd, reason?, cached }>, totalUsd, totalUsdHigh }` |
| POST | `/api/grading/runs/:id/summaries` | `{ pairs?, confirmCostUsd }` | `{ summaries, costUsd, errors }` — AI-written summaries (≤ 30 words), cached in the run folder |

`CaseResult` gained optional fields (older results have none): `humanScores[].criteria / requirements / label / blind`,
`aiGrades: AiGrade[]`, `disputes`, and `scoreDetail.official = { source, policy, why }`, `scoreDetail.autoScore`.
`GET /api/studio/:runId` now includes `facts: StudioFact[]` (the summaries, for the script). Stored artifacts are
served with a MIME type for images, audio, video, PDF, CSV, Markdown and ZIP files as well.

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
