import { createRng } from '../core/rng.ts';
import { bootstrapCi, clusterMean, mean, median, stdDev } from '../core/stats.ts';
import type { CaseResult, CategoryInfo, Contestant, Leaderboard, LeaderboardRow, MedalEntry, TestAggregate } from '../core/types.ts';

export interface AggregateTest {
  id: string;
  name: string;
  category: string;
  weight: number;
  version: string;
  hash: string;
}

/** Results that count toward scores (errors / cancellations are excluded and can be resumed). */
export function isScored(r: CaseResult): boolean {
  return r.score !== null && r.status !== 'error' && r.status !== 'cancelled' && r.status !== 'pending-human';
}

function round(n: number, digits = 4): number {
  const f = 10 ** digits;
  return Math.round(n * f) / f;
}

/** Group scored results of one contestant+test into per-case clusters of repeat scores. */
function clustersOf(results: CaseResult[]): number[][] {
  const byCase = new Map<string, number[]>();
  for (const r of results) {
    if (!isScored(r)) continue;
    const arr = byCase.get(r.caseId) ?? [];
    arr.push(r.score!);
    byCase.set(r.caseId, arr);
  }
  return [...byCase.values()];
}

function aggregateTest(testId: string, results: CaseResult[]): TestAggregate {
  const clusters = clustersOf(results);
  const scored = results.filter(isScored);
  const score = clusters.length ? clusterMean(clusters) : null;
  const ci = clusters.length ? bootstrapCi(clusters, clusterMean, 1000, 7) : null;
  const perCaseSd = clusters.filter((c) => c.length > 1).map((c) => stdDev(c)!);
  // Summary of the median-scoring case (useful for program tests on video).
  const sortedByScore = scored.slice().sort((a, b) => (a.score ?? 0) - (b.score ?? 0));
  const mid = sortedByScore[Math.floor(sortedByScore.length / 2)];
  return {
    testId,
    score: score === null ? null : round(score),
    ci95: ci ? [round(ci[0]), round(ci[1])] : null,
    n: scored.length,
    passRate: scored.length ? round(scored.filter((r) => r.passed).length / scored.length) : null,
    costUsd: round(results.reduce((s, r) => s + r.metrics.costUsd, 0), 6),
    medianCaseMs: median(scored.map((r) => r.metrics.wallMs)),
    repeatStdDev: perCaseSd.length ? round(mean(perCaseSd)!) : null,
    errors: results.filter((r) => r.status === 'error').length,
    pendingHuman: results.filter((r) => r.status === 'pending-human').length,
    ...(results.some((r) => r.status === 'skipped') ? { skipped: results.filter((r) => r.status === 'skipped').length } : {}),
    summary: mid?.summary ?? (results.length && results.every((r) => r.status === 'skipped') ? 'Skipped — model has no image input' : undefined),
  };
}

/** Weighted mean over non-null entries. */
function weightedMean(entries: Array<{ value: number | null; weight: number }>): number | null {
  let s = 0;
  let w = 0;
  for (const e of entries) {
    if (e.value === null || e.weight <= 0) continue;
    s += e.value * e.weight;
    w += e.weight;
  }
  return w > 0 ? s / w : null;
}

function indexFromTestScores(tests: AggregateTest[], testScores: Map<string, number | null>, categoryWeights: Record<string, number>): { index: number | null; categories: Record<string, number | null> } {
  const categories: Record<string, number | null> = {};
  const cats = [...new Set(tests.map((t) => t.category))];
  for (const cat of cats) {
    categories[cat] = weightedMean(tests.filter((t) => t.category === cat).map((t) => ({ value: testScores.get(t.id) ?? null, weight: t.weight })));
  }
  const index = weightedMean(cats.map((c) => ({ value: categories[c] ?? null, weight: categoryWeights[c] ?? 1 })));
  return { index: index === null ? null : index * 100, categories };
}

/**
 * Build a leaderboard.
 * Index = weighted mean of category scores × 100, where a category score is
 * the weighted mean of its test scores and a test score is the mean over
 * cases of the mean over repeats. 95% CIs come from a cluster bootstrap that
 * resamples cases (with their repeats) within every test.
 */
export function buildLeaderboard(opts: {
  scope: Leaderboard['scope'];
  fingerprint: string;
  categories: CategoryInfo[];
  categoryWeights?: Record<string, number>;
  tests: AggregateTest[];
  contestants: Contestant[];
  results: CaseResult[];
  staleExcluded?: number;
}): Leaderboard {
  const { tests, contestants, results } = opts;
  const categoryWeights: Record<string, number> = {};
  for (const c of opts.categories) categoryWeights[c.id] = opts.categoryWeights?.[c.id] ?? c.weight ?? 1;
  const testIds = new Set(tests.map((t) => t.id));

  const rows: LeaderboardRow[] = [];
  for (const c of contestants) {
    const mine = results.filter((r) => r.contestantId === c.id && testIds.has(r.testId));
    if (mine.length === 0) continue;
    const perTest: Record<string, TestAggregate> = {};
    const testScores = new Map<string, number | null>();
    const clustersByTest = new Map<string, number[][]>();
    for (const t of tests) {
      const tr = mine.filter((r) => r.testId === t.id);
      if (tr.length === 0) continue;
      const agg = aggregateTest(t.id, tr);
      perTest[t.id] = agg;
      testScores.set(t.id, agg.score);
      clustersByTest.set(t.id, clustersOf(tr));
    }
    const { index, categories } = indexFromTestScores(tests, testScores, categoryWeights);

    // Cluster bootstrap of the index.
    let indexCi: [number, number] | null = null;
    if (index !== null) {
      const rng = createRng(99);
      const samples: number[] = [];
      for (let i = 0; i < 1000; i++) {
        const sampled = new Map<string, number | null>();
        for (const [tid, clusters] of clustersByTest) {
          if (clusters.length === 0) {
            sampled.set(tid, null);
            continue;
          }
          let s = 0;
          for (let k = 0; k < clusters.length; k++) s += mean(clusters[Math.floor(rng.next() * clusters.length)]!)!;
          sampled.set(tid, s / clusters.length);
        }
        const v = indexFromTestScores(tests, sampled, categoryWeights).index;
        if (v !== null) samples.push(v);
      }
      samples.sort((a, b) => a - b);
      indexCi = [round(samples[Math.floor(0.025 * (samples.length - 1))]!, 2), round(samples[Math.ceil(0.975 * (samples.length - 1))]!, 2)];
    }

    const scored = mine.filter(isScored);
    const totalCost = mine.reduce((s, r) => s + r.metrics.costUsd, 0);
    const genSeconds = mine.reduce((s, r) => s + (r.metrics.outputTokensPerSec && r.metrics.outputTokensPerSec > 0 ? r.metrics.outputTokens / r.metrics.outputTokensPerSec : 0), 0);
    const outTokensForSpeed = mine.reduce((s, r) => s + (r.metrics.outputTokensPerSec && r.metrics.outputTokensPerSec > 0 ? r.metrics.outputTokens : 0), 0);
    const withFormat = mine.filter((r) => typeof r.scoreDetail.formatOk === 'boolean');
    const sds = Object.values(perTest).map((t) => t.repeatStdDev).filter((v): v is number => v !== null);
    rows.push({
      contestantId: c.id,
      label: c.label,
      vendor: c.vendor,
      color: c.color,
      rank: 0,
      index: index === null ? null : round(index, 2),
      indexCi95: indexCi,
      categoryScores: Object.fromEntries(Object.entries(categories).map(([k, v]) => [k, v === null ? null : round(v)])),
      tests: perTest,
      coverage: round(tests.filter((t) => perTest[t.id]?.score !== null && perTest[t.id] !== undefined).length / Math.max(1, tests.length)),
      medals: { gold: 0, silver: 0, bronze: 0 },
      totals: {
        costUsd: round(totalCost, 6),
        judgeCostUsd: round(mine.reduce((s, r) => s + r.metrics.judgeCostUsd, 0), 6),
        inputTokens: mine.reduce((s, r) => s + r.metrics.inputTokens + r.metrics.cachedInputTokens, 0),
        outputTokens: mine.reduce((s, r) => s + r.metrics.outputTokens, 0),
        reasoningTokens: mine.reduce((s, r) => s + r.metrics.reasoningTokens, 0),
        apiCalls: mine.reduce((s, r) => s + r.metrics.apiCalls, 0),
        cases: mine.length,
        errors: mine.filter((r) => r.status === 'error').length,
        refusals: mine.filter((r) => r.status === 'refusal').length,
        wallMs: mine.reduce((s, r) => s + r.metrics.wallMs, 0),
        ...(mine.some((r) => r.status === 'skipped') ? { skipped: mine.filter((r) => r.status === 'skipped').length } : {}),
      },
      speed: {
        medianTtftMs: median(scored.map((r) => r.metrics.ttftMs).filter((v): v is number => v !== null)),
        medianCaseMs: median(scored.map((r) => r.metrics.wallMs)),
        outputTokensPerSec: genSeconds > 0 ? round(outTokensForSpeed / genSeconds, 1) : null,
      },
      costPerPoint: index && index > 0 ? round(totalCost / index, 6) : null,
      reliability: {
        errorRate: round(mine.filter((r) => r.status === 'error').length / Math.max(1, mine.filter((r) => r.status !== 'skipped').length)),
        refusalRate: round(mine.filter((r) => r.status === 'refusal').length / Math.max(1, mine.filter((r) => r.status !== 'skipped').length)),
        formatCompliance: withFormat.length ? round(withFormat.filter((r) => r.scoreDetail.formatOk).length / withFormat.length) : null,
      },
      consistency: sds.length ? round(mean(sds)!) : null,
    });
  }

  // Medals per test: higher score wins; ties broken by lower cost, then faster median time.
  const medals: MedalEntry[] = [];
  for (const t of tests) {
    const ranked = rows
      .filter((r) => r.tests[t.id]?.score !== null && r.tests[t.id] !== undefined)
      .sort((a, b) => {
        const d = b.tests[t.id]!.score! - a.tests[t.id]!.score!;
        if (Math.abs(d) > 1e-9) return d;
        const c = a.tests[t.id]!.costUsd - b.tests[t.id]!.costUsd;
        if (Math.abs(c) > 1e-9) return c;
        return (a.tests[t.id]!.medianCaseMs ?? Infinity) - (b.tests[t.id]!.medianCaseMs ?? Infinity);
      })
      .filter((r) => (r.tests[t.id]!.score ?? 0) > 0);
    const entry: MedalEntry = { testId: t.id };
    if (ranked[0]) {
      entry.gold = ranked[0].contestantId;
      ranked[0].medals.gold++;
    }
    if (ranked[1]) {
      entry.silver = ranked[1].contestantId;
      ranked[1].medals.silver++;
    }
    if (ranked[2]) {
      entry.bronze = ranked[2].contestantId;
      ranked[2].medals.bronze++;
    }
    medals.push(entry);
  }

  rows.sort((a, b) => (b.index ?? -1) - (a.index ?? -1) || b.medals.gold - a.medals.gold || a.totals.costUsd - b.totals.costUsd);
  rows.forEach((r, i) => (r.rank = r.index === null ? 0 : i + 1));

  return {
    generatedAt: new Date().toISOString(),
    scope: opts.scope,
    fingerprint: opts.fingerprint,
    categories: opts.categories.filter((c) => tests.some((t) => t.category === c.id)),
    categoryWeights,
    tests,
    rows,
    medals,
    staleExcluded: opts.staleExcluded ?? 0,
  };
}
