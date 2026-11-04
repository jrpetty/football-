/**
 * Paired comparison of two models on the same questions (pure; used by the Business Ladder and reusable by
 * Head to Head and the leaderboard).
 *
 * Both models answered the same cases, so the fair question is "how big is the gap on those cases, and could
 * it be luck?". The gap is the mean over tests of the mean over shared cases of (A's case score − B's case
 * score), each case score being the mean of its repeats. Its 95% interval comes from a paired cluster
 * bootstrap: each resample draws shared cases with replacement within every test and keeps each case's A and B
 * scores together. A model is "ahead" only when the whole interval is on its side of zero; otherwise the two
 * are "statistically tied". A tie is never presented as a win.
 */
import { createRng } from './rng.ts';

/** One test's per-case repeat scores (0..1) for each model, keyed by case id. */
export interface PairedTestInput {
  a: Record<string, readonly number[]>;
  b: Record<string, readonly number[]>;
}

export type PairedVerdict = 'a-ahead' | 'b-ahead' | 'tied' | 'no-data';

export interface PairedResult {
  /** A − B on a 0..1 scale (null without shared cases). */
  diff: number | null;
  ci95: [number, number] | null;
  /** Shared cases used (both models have a score). */
  cases: number;
  tests: number;
  verdict: PairedVerdict;
}

const avg = (xs: readonly number[]) => xs.reduce((s, x) => s + x, 0) / xs.length;

/** Fewer shared cases than this is too little to call anyone ahead. */
export const MIN_PAIRED_CASES = 3;

export function pairedDifference(tests: readonly PairedTestInput[], opts: { iterations?: number; seed?: number } = {}): PairedResult {
  // Per test: the list of per-case differences on cases both models answered.
  const perTest: number[][] = [];
  for (const t of tests) {
    const d: number[] = [];
    for (const [caseId, as] of Object.entries(t.a)) {
      const bs = t.b[caseId];
      if (!as?.length || !bs?.length) continue;
      d.push(avg(as) - avg(bs));
    }
    if (d.length) perTest.push(d);
  }
  const cases = perTest.reduce((s, d) => s + d.length, 0);
  if (!perTest.length) return { diff: null, ci95: null, cases: 0, tests: 0, verdict: 'no-data' };
  const stat = (groups: readonly (readonly number[])[]) => avg(groups.map(avg));
  const diff = stat(perTest);
  const iterations = opts.iterations ?? 2000;
  const rng = createRng(opts.seed ?? 4242);
  const samples: number[] = new Array(iterations);
  for (let i = 0; i < iterations; i++) {
    let s = 0;
    for (const d of perTest) {
      let t = 0;
      for (let k = 0; k < d.length; k++) t += d[Math.floor(rng.next() * d.length)]!;
      s += t / d.length;
    }
    samples[i] = s / perTest.length;
  }
  samples.sort((x, y) => x - y);
  const ci95: [number, number] = [samples[Math.floor(0.025 * (iterations - 1))]!, samples[Math.ceil(0.975 * (iterations - 1))]!];
  let verdict: PairedVerdict = 'tied';
  if (cases >= MIN_PAIRED_CASES) {
    if (ci95[0] > 0) verdict = 'a-ahead';
    else if (ci95[1] < 0) verdict = 'b-ahead';
  }
  return { diff, ci95, cases, tests: perTest.length, verdict };
}

/** Plain words for a paired result, e.g. "Model A ahead by 12 points" or "Statistically tied (gap 3 points)". */
export function pairedWords(r: PairedResult, labelA: string, labelB: string): string {
  if (r.verdict === 'no-data' || r.diff === null) return 'No shared questions yet';
  const pts = Math.abs(Math.round(r.diff * 100));
  if (r.verdict === 'a-ahead') return `${labelA} ahead by ${pts} point${pts === 1 ? '' : 's'}`;
  if (r.verdict === 'b-ahead') return `${labelB} ahead by ${pts} point${pts === 1 ? '' : 's'}`;
  if (r.cases < MIN_PAIRED_CASES) return `Too few shared questions to call (${r.cases})`;
  return pts === 0 ? 'Statistically tied (no gap)' : `Statistically tied (gap ${pts} point${pts === 1 ? '' : 's'}, within the noise)`;
}
