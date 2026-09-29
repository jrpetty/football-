/**
 * "Best on each test": every test, every model that has taken it (API and copy & paste together).
 *
 * Uses the same pool as the combined leaderboard: every stored result whose test hash and model config hash still
 * match the current definitions (a changed prompt or model setting starts a clean slate), results that ran through
 * OpenRouter get their own "(via OpenRouter)" row, and copy & paste results moved to a named model count only there.
 */
import { contestantConfigHash, loadCategories, loadContestants, loadProviders } from '../core/config.ts';
import { loadTests } from '../core/registry.ts';
import type { CaseResult, Contestant } from '../core/types.ts';
import { buildLeaderboard, isScored, type AggregateTest } from '../engine/aggregate.ts';
import { routedRowId } from '../engine/leaderboards.ts';
import { listRunIds, readManifest, readResults } from '../engine/store.ts';
import type { BestData, BestEntry, BestModel } from './best-rank.ts';
import { copiedByHandText } from './identity.ts';
import { loadCatalog } from './store.ts';

/** Pool results across every run (see file comment). */
export function pooledResults(contestants: Contestant[], tests: Array<{ id: string; hash: string }>): { results: CaseResult[]; routedRows: Contestant[]; stale: number } {
  const hashById = new Map(tests.map((t) => [t.id, t.hash]));
  const configHash = new Map(contestants.map((c) => [c.id, contestantConfigHash(c)]));
  const results: CaseResult[] = [];
  const routedRows = new Map<string, Contestant>();
  let stale = 0;
  for (const runId of listRunIds()) {
    const routedHash = new Map<string, string>();
    for (const s of readManifest(runId)?.contestants ?? []) if (s.route && s.route.directHash === configHash.get(s.id)) routedHash.set(s.id, s.configHash);
    for (const r of readResults(runId)) {
      if (r.reassignedTo) continue;
      const testHash = hashById.get(r.testId);
      const current = configHash.get(r.contestantId);
      if (testHash === undefined || current === undefined) continue;
      if (r.testHash === testHash && routedHash.has(r.contestantId) && r.contestantHash === routedHash.get(r.contestantId)) {
        const base = contestants.find((c) => c.id === r.contestantId)!;
        const id = routedRowId(base.id);
        if (!routedRows.has(id)) routedRows.set(id, { ...base, id, label: `${base.label} (via OpenRouter)` });
        results.push({ ...r, contestantId: id });
        continue;
      }
      if (r.testHash !== testHash || r.contestantHash !== current) {
        stale++;
        continue;
      }
      results.push(r);
    }
  }
  return { results, routedRows: [...routedRows.values()], stale };
}

export function bestPerTest(): BestData {
  const loaded = loadTests();
  const tests: AggregateTest[] = loaded.map((t) => ({ id: t.definition.id, name: t.definition.name, category: t.definition.category, weight: 1, version: t.definition.version, hash: t.hash }));
  const contestants = loadContestants();
  const { results, routedRows, stale } = pooledResults(contestants, tests);
  const withResults = new Set(results.map((r) => r.contestantId));
  const board = [...contestants.filter((c) => withResults.has(c.id)), ...routedRows];
  const lb = buildLeaderboard({ scope: { kind: 'combined', suiteId: 'all-tests' }, fingerprint: '', categories: loadCategories(), tests, contestants: board, results });

  const providers = loadProviders();
  const manualProvider = new Set(providers.filter((p) => p.type === 'manual').map((p) => p.id));
  const catalog = loadCatalog();
  const vendorOf = (name: string) => catalog.vendors.find((v) => v.id === name);
  const models: BestModel[] = board
    .filter((c) => providers.find((p) => p.id === c.provider)?.type !== 'mock')
    .map((c) => {
      const manual = manualProvider.has(c.provider);
      return {
        id: c.id,
        label: c.label,
        vendor: c.vendor,
        color: c.color,
        ...(c.family ? { family: c.family } : {}),
        releaseDate: c.releaseDate || null,
        manual,
        ...(c.manualModel ? { manualModel: c.manualModel } : {}),
        howLabel: c.manualModel ? copiedByHandText(c.manualModel, vendorOf(c.vendor)) : manual ? 'copied by hand · model not recorded' : '',
        ...(c.route || c.id.endsWith('~openrouter') ? { routed: true } : {}),
      };
    });
  const keep = new Set(models.map((m) => m.id));

  // Per contestant × test: when last tested, in how many runs, how many answers were moved here later.
  const extra = new Map<string, { last: string; runs: Set<string>; attempts: number; reassigned: number }>();
  for (const r of results) {
    const k = `${r.contestantId}|${r.testId}`;
    const x = extra.get(k) ?? { last: '', runs: new Set<string>(), attempts: 0, reassigned: 0 };
    if (r.finishedAt > x.last) x.last = r.finishedAt;
    x.runs.add(r.runId);
    x.attempts++;
    if (r.manualOrigin?.how === 'reassign' && isScored(r)) x.reassigned++;
    extra.set(k, x);
  }

  const out: BestData['tests'] = [];
  for (const t of tests) {
    const entries: BestEntry[] = [];
    for (const row of lb.rows) {
      const agg = row.tests[t.id];
      if (!agg || !keep.has(row.contestantId)) continue;
      const x = extra.get(`${row.contestantId}|${t.id}`);
      if (agg.score === null && (x?.attempts ?? 0) === (agg.skipped ?? 0)) continue; // only skipped (e.g. no image input): not a real attempt
      entries.push({ contestantId: row.contestantId, score: agg.score, ci95: agg.ci95, n: agg.n, attempts: x?.attempts ?? agg.n, passRate: agg.passRate, lastTestedAt: x?.last || null, runs: x?.runs.size ?? 0, reassigned: x?.reassigned ?? 0 });
    }
    if (entries.length) out.push({ id: t.id, name: t.name, category: t.category, entries });
  }
  return { generatedAt: new Date().toISOString(), categories: loadCategories().filter((c) => out.some((t) => t.category === c.id)), tests: out, models: models.filter((m) => out.some((t) => t.entries.some((e) => e.contestantId === m.id))), staleExcluded: stale };
}
