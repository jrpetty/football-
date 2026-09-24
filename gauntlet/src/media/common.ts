/**
 * Shared helpers for the Studio: one set of number formatters (so every
 * number on screen and in the script is produced the same way and can be
 * traced back to data), contestant lookups and per-test aggregates.
 * Pure — no Node APIs — so it also runs in the browser (mock mode).
 */
import type { ContestantSnapshot } from '../core/types.ts';
import type { StudioInput, StudioResult, StudioTestInfo } from './types.ts';

// ─────────────────────────────── Formatters ───────────────────────────────

/** Score 0..1 as points out of 100: 0.974 → "97". */
export function pts(score: number): string {
  return String(Math.round(score * 100));
}

/** Gauntlet Index (already 0..100) with one decimal: 87.43 → "87.4". */
export function idx(index: number): string {
  return index.toFixed(1);
}

/** Money for narration: $0, $0.0043, $0.23, $12.34. */
export function money(usd: number): string {
  if (usd === 0) return '$0';
  if (usd < 0.01) return `$${Number(usd.toPrecision(2)).toString()}`;
  return `$${usd.toFixed(2)}`;
}

/** Seconds from milliseconds: 1234 → "1.2 s", 23456 → "23 s". */
export function secs(ms: number): string {
  const s = ms / 1000;
  return s < 10 ? `${s.toFixed(1)} s` : `${Math.round(s)} s`;
}

/** A multiplier: 12.4 → "12×", 2.46 → "2.5×". */
export function times(x: number): string {
  return x >= 10 ? `${Math.round(x)}×` : `${x.toFixed(1)}×`;
}

/** Price per million tokens: 2.5 → "$2.50", 0.3 → "$0.30", 10 → "$10". */
export function perM(usd: number): string {
  return Number.isInteger(usd) ? `$${usd}` : `$${usd.toFixed(2)}`;
}

const ORD = ['first', 'second', 'third', 'fourth', 'fifth', 'sixth', 'seventh', 'eighth', 'ninth', 'tenth', 'eleventh', 'twelfth'];
/** Ordinal as a word (no digits): 1 → "first". */
export function ordinal(n: number): string {
  return ORD[n - 1] ?? `number ${n}`;
}

const WORDS = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten', 'eleven', 'twelve'];
/** Small counts as words, larger ones as digits. */
export function count(n: number): string {
  return WORDS[n] ?? String(n);
}

/** Every number token in a text ("1,234.5" → "1234.5"). Used to check that nothing is invented. */
export function numberTokens(text: string): string[] {
  return [...text.matchAll(/\d+(?:[.,]\d+)*/g)].map((m) => m[0].replace(/,(?=\d{3}\b)/g, ''));
}

// ─────────────────────────────── Contestants ───────────────────────────────

/** The random-baseline contestant is a reference, not a competitor (same rule as the UI). */
export function isBaselineId(c: { id: string; vendor?: string; label?: string }): boolean {
  return /(^|[-_.])(random|baseline)([-_.]|$)/i.test(c.id) || /^baseline$/i.test(c.vendor ?? '') || /random baseline/i.test(c.label ?? '');
}

export interface Ctx {
  input: StudioInput;
  runId: string;
  byId: Map<string, ContestantSnapshot>;
  tests: StudioTestInfo[];
  testById: Map<string, StudioTestInfo>;
  manual: Set<string>;
  baseline: Set<string>;
  /** Scored results (score !== null), grouped by test then contestant. */
  scored: Map<string, Map<string, StudioResult[]>>;
  /** Mean score 0..1 per test per contestant. */
  mean: Map<string, Map<string, number>>;
  /** Contestant spend per test (USD, contestant calls only). */
  spend: Map<string, Map<string, number>>;
}

export function buildCtx(input: StudioInput): Ctx {
  const m = input.manifest;
  const byId = new Map(m.contestants.map((c) => [c.id, c]));
  const manual = new Set(input.manualIds ?? []);
  for (const r of input.leaderboard?.rows ?? []) if (r.manual) manual.add(r.contestantId);
  const baseline = new Set(m.contestants.filter((c) => isBaselineId(c)).map((c) => c.id));
  const infoById = new Map(input.tests.map((t) => [t.id, t]));
  const tests: StudioTestInfo[] = m.tests.map((s) => infoById.get(s.id) ?? { id: s.id, name: s.name, category: s.category, kind: s.kind });
  const testById = new Map(tests.map((t) => [t.id, t]));
  const scored = new Map<string, Map<string, StudioResult[]>>();
  const spend = new Map<string, Map<string, number>>();
  for (const r of input.results) {
    if (!byId.has(r.contestantId) || !testById.has(r.testId)) continue;
    const sp = spend.get(r.testId) ?? new Map<string, number>();
    sp.set(r.contestantId, (sp.get(r.contestantId) ?? 0) + (r.metrics?.costUsd ?? 0));
    spend.set(r.testId, sp);
    if (typeof r.score !== 'number') continue;
    const g = scored.get(r.testId) ?? new Map<string, StudioResult[]>();
    const list = g.get(r.contestantId) ?? [];
    list.push(r);
    g.set(r.contestantId, list);
    scored.set(r.testId, g);
  }
  const mean = new Map<string, Map<string, number>>();
  for (const [testId, g] of scored) {
    const mm = new Map<string, number>();
    for (const [cid, list] of g) {
      // The leaderboard aggregate is the official number; fall back to our own mean.
      const lb = input.leaderboard?.rows.find((r) => r.contestantId === cid)?.tests?.[testId]?.score;
      mm.set(cid, typeof lb === 'number' ? lb : list.reduce((s, r) => s + (r.score as number), 0) / list.length);
    }
    mean.set(testId, mm);
  }
  return { input, runId: m.id, byId, tests, testById, manual, baseline, scored, mean, spend };
}

export function label(ctx: Ctx, id: string): string {
  return ctx.byId.get(id)?.label ?? id;
}

export function testName(ctx: Ctx, id: string): string {
  return ctx.testById.get(id)?.name ?? id;
}

/** Contestants ranked on one test (best first), with their mean score 0..1. */
export function rankedOnTest(ctx: Ctx, testId: string, opts: { includeBaseline?: boolean } = {}): Array<{ id: string; score: number }> {
  const mm = ctx.mean.get(testId);
  if (!mm) return [];
  return [...mm.entries()]
    .filter(([id]) => opts.includeBaseline || !ctx.baseline.has(id))
    .map(([id, score]) => ({ id, score }))
    .sort((a, b) => b.score - a.score || a.id.localeCompare(b.id));
}

/** Blended list price (USD per 1M tokens, 3:1 input:output) — a size proxy when no spend was measured. */
export function listPrice(c: ContestantSnapshot | undefined): number {
  if (!c) return 0;
  return (3 * (c.pricing?.inputPerM ?? 0) + (c.pricing?.outputPerM ?? 0)) / 4;
}

/** Final standings from the leaderboard (competitors only, best first). */
export function standings(ctx: Ctx): Array<{ id: string; index: number; costUsd: number }> {
  return (ctx.input.leaderboard?.rows ?? [])
    .filter((r) => !ctx.baseline.has(r.contestantId) && typeof r.index === 'number')
    .sort((a, b) => (a.rank ?? 99) - (b.rank ?? 99) || (b.index as number) - (a.index as number))
    .map((r) => ({ id: r.contestantId, index: r.index as number, costUsd: r.totals?.costUsd ?? 0 }));
}

/** Dashboard deep link to one case (opens the result inspector, optionally at a replay step). */
export function caseLink(runId: string, r: { testId: string; contestantId: string; key?: string }, step?: number): string {
  const q = new URLSearchParams({ tab: 'matrix', test: r.testId, c: r.contestantId });
  if (r.key) q.set('key', r.key);
  if (step !== undefined) q.set('step', String(step));
  return `#/runs/${encodeURIComponent(runId)}?${q.toString()}`;
}

export function presenterLink(runId: string, slide?: number): string {
  return `#/present/${encodeURIComponent(runId)}${slide ? `?s=${slide}` : ''}`;
}

export function clamp(x: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, x));
}

export function round1(x: number): number {
  return Math.round(x * 10) / 10;
}
