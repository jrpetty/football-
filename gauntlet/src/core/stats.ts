import { createRng } from './rng.ts';

export function mean(values: readonly number[]): number | null {
  if (values.length === 0) return null;
  let s = 0;
  for (const v of values) s += v;
  return s / values.length;
}

export function median(values: readonly number[]): number | null {
  if (values.length === 0) return null;
  const sorted = values.slice().sort((a, b) => a - b);
  const mid = sorted.length >> 1;
  return sorted.length % 2 ? sorted[mid]! : (sorted[mid - 1]! + sorted[mid]!) / 2;
}

export function stdDev(values: readonly number[]): number | null {
  if (values.length < 2) return values.length === 1 ? 0 : null;
  const m = mean(values)!;
  let s = 0;
  for (const v of values) s += (v - m) ** 2;
  return Math.sqrt(s / (values.length - 1));
}

/**
 * Percentile bootstrap 95% CI of a statistic over clusters.
 * `clusters` groups repeat scores of the same case so repeats are resampled
 * together (cases are the independent unit, not individual calls).
 */
export function bootstrapCi(
  clusters: readonly (readonly number[])[],
  statistic: (sample: readonly (readonly number[])[]) => number,
  iterations = 2000,
  seed = 1234,
): [number, number] | null {
  if (clusters.length === 0) return null;
  if (clusters.length === 1) {
    const v = statistic(clusters);
    return [v, v];
  }
  const rng = createRng(seed);
  const stats: number[] = [];
  const sample: (readonly number[])[] = new Array(clusters.length);
  for (let i = 0; i < iterations; i++) {
    for (let j = 0; j < clusters.length; j++) sample[j] = clusters[Math.floor(rng.next() * clusters.length)]!;
    stats.push(statistic(sample));
  }
  stats.sort((a, b) => a - b);
  const lo = stats[Math.floor(0.025 * (iterations - 1))]!;
  const hi = stats[Math.ceil(0.975 * (iterations - 1))]!;
  return [lo, hi];
}

/** Mean of cluster means (each case weighted equally regardless of repeat count). */
export function clusterMean(clusters: readonly (readonly number[])[]): number {
  let s = 0;
  for (const c of clusters) s += mean(c) ?? 0;
  return clusters.length ? s / clusters.length : 0;
}
