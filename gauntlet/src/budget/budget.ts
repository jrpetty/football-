/**
 * "My budget": the owner's saved spending settings and the monthly budget maths. Pure functions shared by the
 * server and the UI (no Node imports).
 *
 * Stored in config/settings.json under an optional `budget` key, in US dollars like every other amount:
 *   defaultRunUsd        the whole-run limit pre-selected in New Run and new Arena tournaments (still changeable per run)
 *   defaultPerAnswerUsd  the per-answer limit pre-selected in New Run
 *   monthlyUsd           a monthly budget, e.g. £50 a month (calendar month, the owner's local time)
 *   hardStop             when on, nothing may start once the month's budget is used up, and a starting run's limit is
 *                        lowered to what is left; when off, the budget only gives warnings
 */
import { formatMoney, type CurrencySettings } from '../core/currency.ts';

export interface BudgetSettings {
  defaultRunUsd?: number;
  defaultPerAnswerUsd?: number;
  monthlyUsd?: number;
  hardStop?: boolean;
}

/** One entry of the "recent spending" list: a run, a tournament or a paid one-off call (judging a pasted answer, script polish). */
export interface BudgetItem {
  kind: 'run' | 'arena' | 'grade' | 'polish' | 'other';
  id?: string;
  name: string;
  /** When its spending this month started (ISO). */
  at: string;
  spentUsd: number;
  active?: boolean;
}

export interface BudgetStatus {
  settings: BudgetSettings;
  currency: CurrencySettings;
  month: { key: string; label: string; start: string; nextReset: string; nextResetLabel: string };
  /** Recorded spend this month (contestants + judges; Manual and Random contestants cost 0). */
  spentUsd: number;
  /** Money still reserved by runs and tournaments that are running now (their limit minus what they have spent). */
  committedUsd: number;
  /** monthly − spent (null without a monthly budget); can be negative. */
  remainingUsd: number | null;
  /** remaining − committed, never below 0 (null without a monthly budget): the most a new run may spend under the hard stop. */
  availableUsd: number | null;
  /** spent / monthly (null without a monthly budget). */
  fraction: number | null;
  tone: 'none' | 'ok' | 'warn' | 'over';
  /** True when the hard stop is on and nothing is left: runs and tournaments cannot start. */
  blocked: boolean;
  items: BudgetItem[];
  days: Array<{ date: string; spentUsd: number }>;
}

/** Below this (one cent) the month's budget counts as used up. */
export const MIN_BUDGET_LEFT_USD = 0.01;
/** Amounts above this are refused as typos. */
export const MAX_BUDGET_USD = 100_000;

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

const pad = (n: number) => String(n).padStart(2, '0');

/** Local calendar date "2026-09-28" (not UTC: a late-evening run in the UK belongs to the owner's day). */
export function localDate(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** The calendar month containing `now`, in the local time of the computer running Gauntlet. */
export function monthBounds(now: Date = new Date()): { key: string; label: string; start: Date; nextReset: Date; nextResetLabel: string } {
  const start = new Date(now.getFullYear(), now.getMonth(), 1, 0, 0, 0, 0);
  const nextReset = new Date(now.getFullYear(), now.getMonth() + 1, 1, 0, 0, 0, 0);
  return {
    key: `${start.getFullYear()}-${pad(start.getMonth() + 1)}`,
    label: `${MONTHS[start.getMonth()]} ${start.getFullYear()}`,
    start,
    nextReset,
    nextResetLabel: `1 ${MONTHS[nextReset.getMonth()]} ${nextReset.getFullYear()}`,
  };
}

/** True when an ISO timestamp falls in [start, nextReset). */
export function inMonth(iso: string | undefined, b: { start: Date; nextReset: Date }): boolean {
  if (!iso) return false;
  const t = Date.parse(iso);
  return Number.isFinite(t) && t >= b.start.getTime() && t < b.nextReset.getTime();
}

/** Meter colour: green, amber from 80 %, red from 100 %. */
export function meterTone(fraction: number | null): BudgetStatus['tone'] {
  if (fraction === null) return 'none';
  if (fraction >= 1) return 'over';
  if (fraction >= 0.8) return 'warn';
  return 'ok';
}

/** Money for budget messages: "£50" for whole amounts, "£12.40" otherwise. */
export function budgetMoney(usd: number, c: CurrencySettings): string {
  return formatMoney(usd, c).replace(/(\d)\.00$/, '$1');
}

const round = (x: number) => Math.round(x * 1e6) / 1e6;
/** Round money down to a whole cent (a clamped limit must never exceed what is left). */
export const floorCents = (usd: number) => Math.floor(usd * 100 + 1e-9) / 100;

/** A usable budget setting from whatever is stored (bad values are dropped, never fatal). */
export function normalizeBudget(b: unknown): BudgetSettings {
  const x = (b && typeof b === 'object' ? b : {}) as Record<string, unknown>;
  const amt = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) && v > 0 && v <= MAX_BUDGET_USD ? round(v) : undefined);
  const out: BudgetSettings = {};
  const run = amt(x.defaultRunUsd);
  const per = amt(x.defaultPerAnswerUsd);
  const month = amt(x.monthlyUsd);
  if (run !== undefined) out.defaultRunUsd = run;
  if (per !== undefined) out.defaultPerAnswerUsd = per;
  if (month !== undefined) out.monthlyUsd = month;
  if (x.hardStop === true) out.hardStop = true;
  return out;
}

/**
 * Validate a budget change (PUT /api/budget, `budget set`). Every field is optional: a number sets it, null clears it,
 * a missing field keeps the current value. Returns the new settings or a plain-English error.
 */
export function checkBudgetInput(body: unknown, current: BudgetSettings = {}): BudgetSettings | string {
  if (!body || typeof body !== 'object' || Array.isArray(body)) return 'Send an object such as {"monthlyUsd": 66.5, "hardStop": true}';
  const b = body as Record<string, unknown>;
  const known = ['defaultRunUsd', 'defaultPerAnswerUsd', 'monthlyUsd', 'hardStop'];
  const unknown = Object.keys(b).filter((k) => !known.includes(k));
  if (unknown.length) return `Unknown budget setting: ${unknown.join(', ')} (use ${known.join(', ')})`;
  const next: BudgetSettings = { ...normalizeBudget(current) };
  const names: Record<string, string> = { defaultRunUsd: 'The default run limit', defaultPerAnswerUsd: 'The default per-answer limit', monthlyUsd: 'The monthly budget' };
  for (const k of ['defaultRunUsd', 'defaultPerAnswerUsd', 'monthlyUsd'] as const) {
    if (!(k in b)) continue;
    const v = b[k];
    if (v === null) delete next[k];
    else if (typeof v !== 'number' || !Number.isFinite(v) || v <= 0) return `${names[k]} must be an amount above zero (or null for none)`;
    else if (v > MAX_BUDGET_USD) return `${names[k]} looks like a typo: it is over $${MAX_BUDGET_USD.toLocaleString('en-US')}`;
    else next[k] = round(v);
  }
  if ('hardStop' in b) {
    if (typeof b.hardStop !== 'boolean') return 'hardStop must be true or false';
    if (b.hardStop) next.hardStop = true;
    else delete next.hardStop;
  }
  if (next.defaultPerAnswerUsd !== undefined && next.defaultRunUsd !== undefined && next.defaultPerAnswerUsd > next.defaultRunUsd) return 'The default per-answer limit cannot be more than the default run limit';
  if (next.hardStop && next.monthlyUsd === undefined) return 'The hard stop needs a monthly budget: set one first';
  return next;
}

/** The status numbers from the settings and the month's spend. */
export function summarize(settings: BudgetSettings, spentUsd: number, committedUsd: number): Pick<BudgetStatus, 'remainingUsd' | 'availableUsd' | 'fraction' | 'tone' | 'blocked'> {
  const m = settings.monthlyUsd;
  if (m === undefined) return { remainingUsd: null, availableUsd: null, fraction: null, tone: 'none', blocked: false };
  const remainingUsd = round(m - spentUsd);
  const availableUsd = Math.max(0, round(remainingUsd - committedUsd));
  const fraction = spentUsd / m;
  return { remainingUsd, availableUsd, fraction, tone: meterTone(fraction), blocked: !!settings.hardStop && availableUsd < MIN_BUDGET_LEFT_USD };
}

/** Thrown when the hard stop refuses to start or resume a run or tournament. */
export class BudgetBlockedError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'BudgetBlockedError';
  }
}

export function isBudgetBlockedError(err: unknown): boolean {
  return err instanceof BudgetBlockedError || (err as Error | null)?.name === 'BudgetBlockedError';
}

export interface BudgetGate {
  /** The whole-run limit to use (USD); undefined = no limit. */
  capUsd: number | undefined;
  /** True when the monthly budget lowered (or introduced) the limit. */
  clamped: boolean;
  /** Shown to the owner, e.g. "Limited to £12.40: what's left of your £50 monthly budget". */
  message?: string;
}

/**
 * Apply the monthly budget to a run or tournament that is about to start (alreadySpentUsd = 0) or resume
 * (alreadySpentUsd = everything it has spent so far, because its limit covers its whole life).
 *  - no monthly budget: the requested limit, untouched
 *  - hard stop off: untouched, with a warning when it could go over
 *  - hard stop on: refused when nothing is left; otherwise the limit becomes at most what is left
 */
export function gateLimit(requestedCapUsd: number | undefined, status: Pick<BudgetStatus, 'settings' | 'availableUsd' | 'remainingUsd' | 'spentUsd' | 'month'>, currency: CurrencySettings, alreadySpentUsd = 0, what = 'run', resuming = alreadySpentUsd > 0): BudgetGate {
  const s = status.settings;
  if (s.monthlyUsd === undefined || status.availableUsd === null) return { capUsd: requestedCapUsd, clamped: false };
  const fmt = (usd: number) => budgetMoney(usd, currency);
  const avail = status.availableUsd;
  const monthly = fmt(s.monthlyUsd);
  if (!s.hardStop) {
    if (avail < MIN_BUDGET_LEFT_USD) return { capUsd: requestedCapUsd, clamped: false, message: `Warning: your ${monthly} monthly budget is used up (${fmt(status.spentUsd)} spent this month). The hard stop is off, so the ${what} starts anyway.` };
    if (requestedCapUsd === undefined || requestedCapUsd - alreadySpentUsd > avail) return { capUsd: requestedCapUsd, clamped: false, message: `Warning: this ${what} may spend more than the ${fmt(avail)} left of your ${monthly} monthly budget.` };
    return { capUsd: requestedCapUsd, clamped: false };
  }
  if (avail < MIN_BUDGET_LEFT_USD)
    throw new BudgetBlockedError(`Your ${monthly} monthly budget is used up (${fmt(status.spentUsd)} spent in ${status.month.label}), so the hard stop will not let this ${what} ${resuming ? 'resume' : 'start'}. It resets on ${status.month.nextResetLabel}. To go ahead now, raise the monthly budget or turn off the hard stop on the Budget page.`);
  const most = floorCents(alreadySpentUsd + avail);
  if (requestedCapUsd === undefined || requestedCapUsd > most) {
    return { capUsd: most, clamped: true, message: `Limited to ${fmt(most - alreadySpentUsd)}: what's left of your ${monthly} monthly budget.` };
  }
  return { capUsd: requestedCapUsd, clamped: false };
}

/** "This month: £12.40 of £50 spent · this run up to £8.20" (the compact New Run / Arena line). */
export function budgetLine(status: Pick<BudgetStatus, 'settings' | 'spentUsd' | 'availableUsd'>, currency: CurrencySettings, runCapUsd: number | null | undefined, what = 'run'): string {
  const fmt = (usd: number) => budgetMoney(usd, currency);
  const m = status.settings.monthlyUsd;
  const head = m === undefined ? `This month: ${fmt(status.spentUsd)} spent` : `This month: ${fmt(status.spentUsd)} of ${fmt(m)} spent`;
  if (status.settings.hardStop && status.availableUsd !== null && status.availableUsd < MIN_BUDGET_LEFT_USD) return `${head} · used up, so the hard stop blocks this ${what}`;
  let cap = runCapUsd ?? undefined;
  if (status.settings.hardStop && status.availableUsd !== null) cap = cap === undefined ? floorCents(status.availableUsd) : Math.min(cap, floorCents(status.availableUsd));
  return `${head} · this ${what} ${cap === undefined ? 'has no limit' : `up to ${fmt(cap)}`}`;
}
