import { contestantConfigHash, loadCategories, loadContestants, loadProviders } from '../core/config.ts';
import { fingerprint, getSuite, loadTests, resolveTests } from '../core/registry.ts';
import type { CaseResult, Contestant, Leaderboard } from '../core/types.ts';
import { buildLeaderboard, type AggregateTest } from './aggregate.ts';
import { listRunIds, readManifest, readResults } from './store.ts';

function markManual(board: Leaderboard, contestants: Contestant[]): Leaderboard {
  const providers = loadProviders();
  const manual = new Set(contestants.filter((c) => providers.find((p) => p.id === c.provider)?.type === 'manual').map((c) => c.id));
  for (const row of board.rows) if (manual.has(row.contestantId)) row.manual = true;
  return board;
}

export function runLeaderboard(runId: string): Leaderboard | null {
  const manifest = readManifest(runId);
  if (!manifest) return null;
  const tests: AggregateTest[] = manifest.tests.map((t) => ({ id: t.id, name: t.name, category: t.category, weight: t.weight, version: t.version, hash: t.hash }));
  const suite = manifest.suiteId ? getSuite(manifest.suiteId) : undefined;
  return markManual(buildLeaderboard({
    scope: { kind: 'run', runId },
    fingerprint: manifest.fingerprint,
    categories: loadCategories(),
    categoryWeights: suite?.categoryWeights,
    tests,
    contestants: manifest.contestants,
    results: readResults(runId),
  }), manifest.contestants);
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
  const all = loadTests();
  const tests = resolveTests({ suiteId }, all);
  const suite = getSuite(suiteId);
  const hashById = new Map(tests.map((t) => [t.definition.id, t.hash]));
  const contestants = loadContestants();
  const configHash = new Map(contestants.map((c) => [c.id, contestantConfigHash(c)]));

  const results: CaseResult[] = [];
  let stale = 0;
  for (const runId of listRunIds()) {
    for (const r of readResults(runId)) {
      const currentTestHash = hashById.get(r.testId);
      const currentConfig = configHash.get(r.contestantId);
      if (currentTestHash === undefined || currentConfig === undefined) continue;
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
  const board: Contestant[] = contestants.filter((c) => withResults.has(c.id));
  return markManual(buildLeaderboard({
    scope: { kind: 'combined', suiteId },
    fingerprint: fingerprint(tests),
    categories: loadCategories(),
    categoryWeights: suite?.categoryWeights,
    tests: aggTests,
    contestants: board,
    results,
    staleExcluded: stale,
  }), board);
}
