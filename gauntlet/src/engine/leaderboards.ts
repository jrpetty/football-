import { contestantConfigHash, loadCategories, loadContestants, loadProviders } from '../core/config.ts';
import { fingerprint, getSuite, loadTests, resolveTests } from '../core/registry.ts';
import type { CaseResult, Contestant, Leaderboard } from '../core/types.ts';
import { buildLeaderboard, type AggregateTest } from './aggregate.ts';
import { toScoreAnswer, type ScoreAnswer } from '../core/score-explain.ts';
import { listRunIds, readManifest, readResults } from './store.ts';
import { isScored } from './aggregate.ts';
import { buildBusinessLadderView, ladderTestInfo, type BusinessLadderView, type ModelClusters } from '../core/business-ladder.ts';
import { loadBusinessLadder } from '../core/business-ladder-file.ts';
import { buildBusinessJobsView, type BusinessJobsView, type JobTestInfo } from '../core/business-jobs.ts';
import { loadBusinessJobs } from '../core/business-jobs-file.ts';
import { BUSINESS_SUITE_ID } from '../core/areas.ts';
import { explainerFor } from '../core/explainers.ts';
import { attachLadderHeights, ladderClimbs, ladderSpec, type LadderBoard } from '../core/ladders.ts';
import { loadLadderRules, loadLadders } from '../core/ladders-file.ts';

function markManual(board: Leaderboard, contestants: Contestant[]): Leaderboard {
  const providers = loadProviders();
  const manual = new Set(contestants.filter((c) => providers.find((p) => p.id === c.provider)?.type === 'manual').map((c) => c.id));
  for (const row of board.rows) if (manual.has(row.contestantId)) row.manual = true;
  // Copy & paste models from the catalogue: interface + settings for the "copied by hand · …" label.
  for (const row of board.rows) {
    const info = contestants.find((c) => c.id === row.contestantId)?.manualModel;
    if (info) row.manualModel = info;
  }
  return board;
}

export function runLeaderboard(runId: string): Leaderboard | null {
  const manifest = readManifest(runId);
  if (!manifest) return null;
  const tests: AggregateTest[] = manifest.tests.map((t) => ({ id: t.id, name: t.name, category: t.category, weight: t.weight, version: t.version, hash: t.hash }));
  const suite = manifest.suiteId ? getSuite(manifest.suiteId) : undefined;
  const results = readResults(runId);
  const board = buildLeaderboard({
    scope: { kind: 'run', runId },
    fingerprint: manifest.fingerprint,
    categories: loadCategories(),
    categoryWeights: suite?.categoryWeights,
    tests,
    contestants: manifest.contestants,
    results,
  });
  return markManual(attachLadderHeights(board, results, loadLadderRules()), manifest.contestants);
}

/** Leaderboard row id for a model's results that ran through OpenRouter. */
export function routedRowId(contestantId: string): string {
  return `${contestantId}~openrouter`;
}

/**
 * Combined leaderboard for a suite across every run.
 *
 * Every valid sample is pooled (re-running a model adds samples; it can never
 * overwrite a bad result). A result counts only if its test hash and the
 * model's config hash still match the current definitions — so changing a
 * prompt or a model setting starts a clean slate instead of mixing
 * incomparable numbers.
 */
export function combinedLeaderboard(suiteId: string): Leaderboard {
  const { tests, suite, aggTests, board, results, stale } = combinedPool(suiteId);
  const lb = buildLeaderboard({
    scope: { kind: 'combined', suiteId },
    fingerprint: fingerprint(tests),
    categories: loadCategories(),
    categoryWeights: suite?.categoryWeights,
    tests: aggTests,
    contestants: board,
    results,
    staleExcluded: stale,
  });
  return markManual(attachLadderHeights(lb, results, loadLadderRules()), board);
}

/** GET /api/ladders/:testId: every model's climb on one difficulty ladder (pooled, hash-checked results). */
export function ladderBoard(testId: string): LadderBoard {
  const spec = ladderSpec(loadLadders(), testId) ?? null;
  if (!spec || spec.planned) return { testId, spec, climbs: [] };
  const { board, results } = combinedPool('all');
  const climbs = ladderClimbs(results, testId, { passMark: spec.passMark, levels: spec.rungs?.length });
  return {
    testId,
    spec,
    climbs: climbs.flatMap((c) => {
      const who = board.find((b) => b.id === c.contestantId);
      return who ? [{ id: who.id, label: who.label, color: who.color, climb: c }] : [];
    }),
  };
}

/** The results a combined leaderboard counts (same filters), so drill-downs can never disagree with the scores. */
function combinedPool(suiteId: string) {
  const all = loadTests();
  const tests = resolveTests({ suiteId }, all);
  const suite = getSuite(suiteId);
  const hashById = new Map(tests.map((t) => [t.definition.id, t.hash]));
  const caseFilter = new Map(tests.filter((t) => t.caseFilter).map((t) => [t.definition.id, new Set(t.caseFilter)]));
  const contestants = loadContestants();
  const configHash = new Map(contestants.map((c) => [c.id, contestantConfigHash(c)]));

  const results: CaseResult[] = [];
  // Results that ran "via OpenRouter" get their own row ("… (via OpenRouter)"), never pooled with direct results.
  const routedRows = new Map<string, Contestant>();
  let stale = 0;
  for (const runId of listRunIds()) {
    const routedHash = new Map<string, string>();
    for (const s of readManifest(runId)?.contestants ?? []) {
      if (s.route && s.route.directHash === configHash.get(s.id)) routedHash.set(s.id, s.configHash);
    }
    for (const r of readResults(runId)) {
      const currentTestHash = hashById.get(r.testId);
      const currentConfig = configHash.get(r.contestantId);
      if (currentTestHash === undefined || currentConfig === undefined) continue;
      if (caseFilter.get(r.testId) && !caseFilter.get(r.testId)!.has(r.caseId)) continue;
      if (r.testHash === currentTestHash && routedHash.has(r.contestantId) && r.contestantHash === routedHash.get(r.contestantId)) {
        const base = contestants.find((c) => c.id === r.contestantId)!;
        const id = routedRowId(base.id);
        if (!routedRows.has(id)) routedRows.set(id, { ...base, id, label: `${base.label} (via OpenRouter)` });
        results.push({ ...r, contestantId: id });
        continue;
      }
      if (r.testHash !== currentTestHash || r.contestantHash !== currentConfig) {
        stale++;
        continue;
      }
      results.push(r);
    }
  }
  const aggTests: AggregateTest[] = tests.map((t) => ({
    id: t.definition.id,
    name: t.definition.name,
    category: t.definition.category,
    weight: t.weight,
    version: t.definition.version,
    hash: t.hash,
  }));
  const withResults = new Set(results.map((r) => r.contestantId));
  const board: Contestant[] = [...contestants.filter((c) => withResults.has(c.id)), ...routedRows.values()];
  return { tests, suite, aggTests, board, results, stale };
}

/**
 * Every answer behind one leaderboard cell (one model on one test), for "Why these scores?".
 * `scope` is `run:<id>` or `suite:<id>`. Each answer keeps the run it came from, so it can be opened.
 */
export function scoreAnswers(scope: string, contestantId: string, testId: string): ScoreAnswer[] {
  let pool: CaseResult[];
  if (scope.startsWith('run:')) pool = readResults(scope.slice(4));
  else if (scope.startsWith('suite:')) pool = combinedPool(scope.slice(6)).results;
  else throw new Error(`Unknown scope: ${scope}`);
  return pool.filter((r) => r.contestantId === contestantId && r.testId === testId && !r.reassignedTo).map((r) => toScoreAnswer(r));
}

/**
 * The Business Ladder (GET /api/business-ladder): every model's ladder per scenario, computed from the same
 * pooled, hash-checked results as the combined leaderboard of `suiteId` (default: the Business suite when it
 * exists, else every test). `compare` lists 2–4 model ids for paired comparisons on shared cases.
 */
export function businessLadder(suiteId?: string, compare: string[] = []): BusinessLadderView {
  const pool = suiteId || (getSuite(BUSINESS_SUITE_ID) ? BUSINESS_SUITE_ID : 'all');
  const definition = loadBusinessLadder();
  const all = loadTests();
  const byId = new Map(all.map((t) => [t.definition.id, t.definition]));
  const tests = ladderTestInfo(
    definition,
    (id) => {
      const d = byId.get(id);
      return d ? { name: d.name, difficulty: d.difficulty, cases: d.kind === 'prompt' ? d.cases.length : d.seeds.length } : undefined;
    },
    explainerFor,
  );
  const { board, results } = combinedPool(pool);
  const onLadder = new Set(Object.keys(tests));
  const clusters = new Map<string, ModelClusters>();
  for (const r of results) {
    if (r.reassignedTo || !onLadder.has(r.testId) || !isScored(r)) continue;
    const m = clusters.get(r.contestantId) ?? {};
    const t = (m[r.testId] ??= {});
    t[r.caseId] = [...(t[r.caseId] ?? []), r.score!];
    clusters.set(r.contestantId, m);
  }
  const models = board.filter((c) => clusters.has(c.id)).map((c) => ({ id: c.id, label: c.label, color: c.color, vendor: c.vendor, clusters: clusters.get(c.id)! }));
  return buildBusinessLadderView({ definition, suiteId: pool, tests, models, compare });
}

/**
 * "Which model for this job?" (GET /api/business-jobs): every model's verdict, score, cost, speed and common
 * mistakes per business job (config/business-jobs.json), from the same pooled, hash-checked results as the
 * combined leaderboard. Pooled over every test, so the Business Ladders' ten-rung tests count too.
 */
export function businessJobs(): BusinessJobsView {
  const config = loadBusinessJobs();
  const byId = new Map(loadTests().map((t) => [t.definition.id, t.definition]));
  const tests: Record<string, JobTestInfo> = {};
  for (const id of new Set(config.jobs.flatMap((j) => j.tests))) {
    const d = byId.get(id);
    tests[id] = d ? { name: d.name, exists: true, kind: d.kind === 'program' ? 'program' : 'prompt', cases: d.kind === 'prompt' ? d.cases.length : d.seeds.length } : { name: id, exists: false };
  }
  const { board, results } = combinedPool('all');
  const baseline = (id: string) => /(^|[-_.])(random|baseline)([-_.]|$)/i.test(id);
  const models = board.filter((c) => !baseline(c.id)).map((c) => ({ id: c.id, label: c.label, color: c.color, vendor: c.vendor }));
  return buildBusinessJobsView({ config, tests, models, results });
}
