/**
 * "Answer vs truth" Presenter slides: for each test, pick the case the models
 * disagreed on most, and the model to feature on it (the strongest model on
 * this test that still got the case wrong, so the slide shows a real miss).
 */
import type { ResultStatus } from '../../core/types.ts';
import { disagreementOf } from '../trick-highlights.ts';

export interface InterestResultLike {
  key: string;
  contestantId: string;
  testId: string;
  caseId: string;
  repeat: number;
  status: ResultStatus | string;
  score: number | null;
}

export interface InterestPick {
  testId: string;
  caseId: string;
  /** Result key of the featured attempt (a miss when there is one). */
  featuredKey: string;
  featuredContestantId: string;
  /** Mean score per contestant on this case (0..1), baseline excluded. */
  perModel: Array<{ contestantId: string; mean: number; attempts: number }>;
  disagreement: number;
}

const SCORED = new Set(['ok', 'timeout', 'refusal']);

export function pickInterestingCase(testId: string, results: InterestResultLike[], contenders: Array<{ id: string; baseline?: boolean }>): InterestPick | null {
  const ids = contenders.filter((c) => !c.baseline).map((c) => c.id);
  const mine = results.filter((r) => r.testId === testId && ids.includes(r.contestantId) && SCORED.has(r.status) && typeof r.score === 'number');
  if (!mine.length) return null;
  // Strength of each model on this whole test (to pick "even the best one missed it").
  const testMean = new Map<string, number>();
  for (const id of ids) {
    const xs = mine.filter((r) => r.contestantId === id).map((r) => r.score as number);
    if (xs.length) testMean.set(id, xs.reduce((a, b) => a + b, 0) / xs.length);
  }
  const byCase = new Map<string, InterestResultLike[]>();
  for (const r of mine) byCase.set(r.caseId, [...(byCase.get(r.caseId) ?? []), r]);
  let best: InterestPick | null = null;
  let bestKey: [number, number, number] = [-1, -1, -1];
  for (const [caseId, rs] of [...byCase.entries()].sort((a, b) => a[0].localeCompare(b[0]))) {
    const perModel = ids
      .map((id) => {
        const xs = rs.filter((r) => r.contestantId === id).map((r) => r.score as number);
        return { contestantId: id, mean: xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : NaN, attempts: xs.length };
      })
      .filter((m) => m.attempts > 0);
    if (perModel.length < 2) continue;
    const d = disagreementOf(perModel.map((m) => m.mean));
    const misses = perModel.filter((m) => m.mean < 0.999).length;
    // Rank: disagreement, then a miss by a strong model, then fewer misses (a surprise beats a massacre).
    const strongestMiss = Math.max(-1, ...perModel.filter((m) => m.mean < 0.999).map((m) => testMean.get(m.contestantId) ?? 0));
    const key: [number, number, number] = [Math.round(d * 1000), Math.round(strongestMiss * 1000), -misses];
    if (key[0] > bestKey[0] || (key[0] === bestKey[0] && (key[1] > bestKey[1] || (key[1] === bestKey[1] && key[2] > bestKey[2])))) {
      const missers = perModel.filter((m) => m.mean < 0.999).sort((a, b) => (testMean.get(b.contestantId) ?? 0) - (testMean.get(a.contestantId) ?? 0) || a.mean - b.mean);
      const featured = missers[0] ?? [...perModel].sort((a, b) => (testMean.get(b.contestantId) ?? 0) - (testMean.get(a.contestantId) ?? 0))[0]!;
      const attempt = rs.filter((r) => r.contestantId === featured.contestantId).sort((a, b) => (a.score as number) - (b.score as number) || a.repeat - b.repeat)[0]!;
      best = { testId, caseId, featuredKey: attempt.key, featuredContestantId: featured.contestantId, perModel, disagreement: Math.round(d * 1000) / 1000 };
      bestKey = key;
    }
  }
  return best;
}
