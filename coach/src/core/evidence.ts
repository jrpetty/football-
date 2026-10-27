// Evidence scoring. The question asked of every episode type is narrow: "did
// one visible response go wrong noticeably more often than another?" The
// answer is graded, and the grade — not just the raw rate — decides what the
// coach is allowed to say. Everything here is association, never causation.

import type { EpisodeSpec } from './profile/schema.ts'
import { NO_RESPONSE, type EvidenceGrade, type Insight, type OutcomeCounts } from './types.ts'

export const GRADE_ORDER: Record<EvidenceGrade, number> = { insufficient: 0, weak: 1, moderate: 2, strong: 3 }

export function gradeAtLeast(g: EvidenceGrade, min: EvidenceGrade): boolean {
  return GRADE_ORDER[g] >= GRADE_ORDER[min]
}

/** Wilson score interval for k successes in n trials. */
export function wilson(k: number, n: number, z = 1.96): { lo: number; hi: number } {
  if (n <= 0) return { lo: 0, hi: 1 }
  const p = k / n
  const z2 = z * z
  const denom = 1 + z2 / n
  const centre = (p + z2 / (2 * n)) / denom
  const half = (z * Math.sqrt((p * (1 - p)) / n + z2 / (4 * n * n))) / denom
  return { lo: Math.max(0, centre - half), hi: Math.min(1, centre + half) }
}

/** Standard normal CDF (Abramowitz–Stegun 7.1.26 erf approximation, |error| < 1.5e-7). */
export function normalCdf(x: number): number {
  const t = 1 / (1 + 0.3275911 * (Math.abs(x) / Math.SQRT2))
  const poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
  const erf = 1 - poly * Math.exp(-(x * x) / 2)
  return x >= 0 ? (1 + erf) / 2 : (1 - erf) / 2
}

/**
 * P(rate_a > rate_b) for negative-outcome rates under uniform Beta(1,1)
 * priors, via a normal approximation of the posterior difference.
 */
export function probabilityWorse(a: OutcomeCounts, b: OutcomeCounts): number {
  const post = (c: OutcomeCounts) => {
    const al = c.negative + 1
    const be = c.n - c.negative + 1
    const s = al + be
    return { mean: al / s, variance: (al * be) / (s * s * (s + 1)) }
  }
  const pa = post(a)
  const pb = post(b)
  return normalCdf((pa.mean - pb.mean) / Math.sqrt(pa.variance + pb.variance))
}

export function negativeRate(c: OutcomeCounts): number {
  return c.n ? c.negative / c.n : 0
}

/**
 * Grade thresholds. Conservative on purpose: silence is cheaper than a wrong
 * claim. Beyond the posterior probability, each grade needs a minimum number
 * of cases on *both* sides and a minimum number of bad outcomes on the worse
 * side, so one unlucky elimination can never become a "pattern". Moderate
 * and strong also need the two rates' Wilson intervals (80% / 90%) not to
 * overlap, which is far stricter than the posterior alone at small n.
 */
export function grade(worse: OutcomeCounts, better: OutcomeCounts, probability: number): EvidenceGrade {
  const diff = negativeRate(worse) - negativeRate(better)
  const minN = Math.min(worse.n, better.n)
  const k = worse.negative
  const separated = (z: number) => wilson(worse.negative, worse.n, z).lo > wilson(better.negative, better.n, z).hi
  if (probability >= 0.95 && minN >= 6 && k >= 3 && diff >= 0.25 && separated(1.645)) return 'strong'
  if (probability >= 0.9 && minN >= 4 && k >= 2 && diff >= 0.2 && separated(1.2816)) return 'moderate'
  if (probability >= 0.75 && minN >= 2 && k >= 1 && diff > 0.1) return 'weak'
  return 'insufficient'
}

/**
 * The single most supported comparison between two responses of one episode
 * type, or null when fewer than two responses have been seen at least twice.
 */
export function evaluateEpisode(
  spec: EpisodeSpec,
  counts: ReadonlyMap<string, OutcomeCounts>,
  usesHistory: boolean,
): Insight | null {
  const keys = [...spec.responses.map((r) => r.id), NO_RESPONSE].filter((k) => (counts.get(k)?.n ?? 0) >= 2)
  let best: Insight | null = null
  for (const a of keys) {
    for (const b of keys) {
      if (a === b) continue
      const ca = counts.get(a)!
      const cb = counts.get(b)!
      if (negativeRate(ca) <= negativeRate(cb)) continue
      const probability = probabilityWorse(ca, cb)
      const g = grade(ca, cb, probability)
      const candidate: Insight = {
        key: `${spec.id}:${a}>${b}`,
        episodeId: spec.id,
        worse: { response: a, counts: { ...ca } },
        better: { response: b, counts: { ...cb } },
        grade: g,
        probability,
        usesHistory,
      }
      if (
        !best ||
        GRADE_ORDER[g] > GRADE_ORDER[best.grade] ||
        (GRADE_ORDER[g] === GRADE_ORDER[best.grade] && probability > best.probability)
      ) {
        best = candidate
      }
    }
  }
  return best
}
