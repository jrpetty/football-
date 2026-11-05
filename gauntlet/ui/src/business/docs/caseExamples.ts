/**
 * "See an example" for a test that is not a ladder: which run, which case and which model to show first.
 * The newest run with answers to the test; the case where the models' scores differed most (ties: the lower mean);
 * the model with some right and some wrong (the lowest score above zero), else the first.
 */
import { api } from '../../api.ts';
import type { RunDetail } from '../../types.ts';

export const isBaseline = (id: string) => /(^|[-_.])(random|baseline)([-_.]|$)/i.test(id);

const runCache = new Map<string, Promise<RunDetail>>();
export function loadRunCached(id: string): Promise<RunDetail> {
  let p = runCache.get(id);
  if (!p) {
    p = api.run(id);
    p.catch(() => runCache.delete(id));
    runCache.set(id, p);
  }
  return p;
}

const latestCache = new Map<string, Promise<{ runId: string; run: RunDetail } | null>>();
/** The newest run with a scored answer to this test (null: none in the newest dozen runs). */
export function latestRunWith(testId: string): Promise<{ runId: string; run: RunDetail } | null> {
  let p = latestCache.get(testId);
  if (!p) {
    p = api.runs().then(async (runs) => {
      for (const r of [...runs].sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, 12)) {
        try {
          const run = await loadRunCached(r.id);
          if (run.results.some((x) => x.testId === testId && x.status === 'ok')) return { runId: r.id, run };
        } catch {
          /* the next run */
        }
      }
      return null;
    });
    p.catch(() => latestCache.delete(testId));
    latestCache.set(testId, p);
  }
  return p;
}

/** The case whose scores differ most between models (ties: the lower mean). */
export function interestingCase(results: Array<{ caseId: string; contestantId: string; score: number | null; status: string }>, caseIds: string[]): string | null {
  let best: { id: string; spread: number; mean: number } | null = null;
  for (const id of caseIds) {
    const xs = results.filter((r) => r.caseId === id && r.status === 'ok' && typeof r.score === 'number' && !isBaseline(r.contestantId)).map((r) => r.score as number);
    if (!xs.length) continue;
    const spread = Math.max(...xs) - Math.min(...xs);
    const mean = xs.reduce((a, b) => a + b, 0) / xs.length;
    if (!best || spread > best.spread + 1e-9 || (Math.abs(spread - best.spread) < 1e-9 && mean < best.mean)) best = { id, spread, mean };
  }
  return best?.id ?? null;
}

/** The model to show first: some right and some wrong (the lowest mean above zero), else the weakest, else the first. */
export function interestingModel(rows: Array<{ id: string; scores: number[] }>): string | null {
  const mean = (xs: number[]) => (xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : null);
  const real = rows.filter((r) => !isBaseline(r.id));
  const mixed = real.filter((r) => {
    const m = mean(r.scores);
    return m !== null && m > 0 && m < 0.9995;
  });
  const pool = mixed.length ? mixed : real;
  return [...pool].sort((a, b) => (mean(a.scores) ?? 2) - (mean(b.scores) ?? 2))[0]?.id ?? rows[0]?.id ?? null;
}
