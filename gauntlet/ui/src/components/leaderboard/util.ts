import type { LeaderboardRow } from '../../types.ts';

/** The random-baseline contestant is shown as a reference, not a competitor. */
export function isBaseline(row: Pick<LeaderboardRow, 'contestantId' | 'vendor' | 'label'>): boolean {
  return /(^|[-_.])(random|baseline)([-_.]|$)/i.test(row.contestantId) || /^baseline$/i.test(row.vendor ?? '') || /random baseline/i.test(row.label ?? '');
}

const SHORT: Record<string, string> = {
  reasoning: 'Logic',
  math: 'Math',
  coding: 'Code',
  instruction: 'Instruct',
  honesty: 'Honesty',
  'long-context': 'Long ctx',
  agentic: 'Agents',
  social: 'Social',
  visual: 'Visual',
  creative: 'Creative',
  extraction: 'Extract',
};

/** Compact category label for dense table headers and chart axes. */
export function shortCat(id: string, name?: string): string {
  if (SHORT[id]) return SHORT[id];
  const base = (name ?? id).split(/[\s&/]+/)[0] ?? id;
  return base.length > 9 ? `${base.slice(0, 8)}…` : base;
}

/** Olympic ordering: gold, then silver, then bronze. */
export function olympicCompare(a: { gold: number; silver: number; bronze: number }, b: { gold: number; silver: number; bronze: number }): number {
  return b.gold - a.gold || b.silver - a.silver || b.bronze - a.bronze;
}

export function errorRate(row: LeaderboardRow): number {
  return (row.reliability?.errorRate ?? 0) + (row.reliability?.refusalRate ?? 0);
}
