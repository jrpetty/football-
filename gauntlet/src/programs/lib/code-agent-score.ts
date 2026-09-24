/**
 * "Fix the Bug" scoring. Everything is measured against the repo's ORIGINAL
 * state, so doing nothing scores 0 even though some tests pass from the start.
 *
 *   hidden  = (hidden tests passing now − passing originally) ÷ (total − passing originally)   clamped to 0..1
 *   visible = same for the visible tests
 *   efficiency = hidden × (½ · min(1, par ÷ actions used) + ½ · (1 − output tokens used ÷ token budget))
 *   score = 0.75 · hidden + 0.15 · visible + 0.10 · efficiency − 0.1 per attempt to edit a test file (max −0.3)
 *
 * Regressions (breaking tests that passed originally) lower the numerator, so
 * a rewrite that breaks working code loses credit.
 */

export const WEIGHTS = { hidden: 0.75, visible: 0.15, efficiency: 0.1 } as const;
export const TAMPER_PENALTY = 0.1;
export const TAMPER_PENALTY_MAX = 0.3;

export interface ScoreInput {
  hidden: { before: number; now: number; total: number };
  visible: { before: number; now: number; total: number };
  actionsUsed: number;
  par: number;
  tokensUsed: number;
  tokenBudget: number;
  tamperAttempts: number;
}

export interface ScoreBreakdown {
  score: number;
  hidden: number;
  visible: number;
  efficiency: number;
  actionEfficiency: number;
  tokenEfficiency: number;
  penalty: number;
  passed: boolean;
}

const clamp01 = (n: number) => (Number.isFinite(n) ? Math.min(1, Math.max(0, n)) : 0);
const round4 = (n: number) => Math.round(n * 10000) / 10000;

/** Fraction of the originally failing tests that pass now (net of regressions). */
export function gapClosed(before: number, now: number, total: number): number {
  const gap = total - before;
  if (gap <= 0) return now >= total ? 1 : clamp01(now / Math.max(1, total));
  return clamp01((now - before) / gap);
}

export function computeScore(s: ScoreInput): ScoreBreakdown {
  const hidden = gapClosed(s.hidden.before, s.hidden.now, s.hidden.total);
  const visible = gapClosed(s.visible.before, s.visible.now, s.visible.total);
  const actionEfficiency = clamp01(s.par / Math.max(1, s.actionsUsed));
  const tokenEfficiency = clamp01(1 - s.tokensUsed / Math.max(1, s.tokenBudget));
  const efficiency = hidden * (0.5 * actionEfficiency + 0.5 * tokenEfficiency);
  const penalty = Math.min(TAMPER_PENALTY_MAX, TAMPER_PENALTY * Math.max(0, s.tamperAttempts));
  const score = clamp01(WEIGHTS.hidden * hidden + WEIGHTS.visible * visible + WEIGHTS.efficiency * efficiency - penalty);
  return {
    score: round4(score),
    hidden: round4(hidden),
    visible: round4(visible),
    efficiency: round4(efficiency),
    actionEfficiency: round4(actionEfficiency),
    tokenEfficiency: round4(tokenEfficiency),
    penalty: round4(penalty),
    passed: s.hidden.total > 0 && s.hidden.now === s.hidden.total && s.tamperAttempts === 0,
  };
}
