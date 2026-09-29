/**
 * Spend tracking for "My budget": how much was spent this calendar month, read from what is already stored.
 *
 *  - runs:        data/runs/<id>/results.jsonl, every answer attempt (a replayed or re-scored answer is counted once
 *                 per attempt), plus spend a spend limit stopped half-way (manifest.unrecordedCostUsd)
 *  - tournaments: data/arena/<id>/games.jsonl, every game played, judges included
 *  - one-off paid calls outside runs (AI judges grading a pasted answer, Video Studio script polish):
 *                 data/budget/spend-log.jsonl, written by recordSpend()
 *
 * Money is counted in the month it was spent (each answer's and game's start time), so a run started in September
 * and resumed in October counts its October spending in October. Manual (copy & paste) and Random Baseline
 * contestants cost nothing; the AI judges grading them still count. Read-only apart from recordSpend().
 */
import { appendFileSync, existsSync, mkdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { DATA_DIR, RUNS_DIR } from '../core/paths.ts';
import { loadProviders, loadSettings } from '../core/config.ts';
import { normalizeCurrency } from '../core/currency.ts';
import type { RunManifest } from '../core/types.ts';
import type { TournamentManifest } from '../arena/types.ts';
import { listRunIds, readManifest } from '../engine/store.ts';
import { ARENA_DIR, listTournamentIds, readTournament } from '../arena/store.ts';
import { gateLimit, inMonth, localDate, monthBounds, normalizeBudget, summarize, type BudgetGate, type BudgetItem, type BudgetSettings, type BudgetStatus } from './budget.ts';

export const BUDGET_DIR = join(DATA_DIR, 'budget');
export const SPEND_LOG = join(BUDGET_DIR, 'spend-log.jsonl');

interface SpendLine {
  at: string;
  usd: number;
}

/** Remember a paid call made outside a run or tournament (e.g. judges grading a pasted answer). Never throws. */
export function recordSpend(entry: { kind: BudgetItem['kind']; name: string; costUsd: number; at?: string }): void {
  if (!(entry.costUsd > 0)) return;
  try {
    mkdirSync(BUDGET_DIR, { recursive: true });
    appendFileSync(SPEND_LOG, JSON.stringify({ at: entry.at ?? new Date().toISOString(), kind: entry.kind, name: entry.name, costUsd: Math.round(entry.costUsd * 1e8) / 1e8 }) + '\n');
  } catch {
    // Spend tracking must never break the feature that spent the money.
  }
}

function jsonLines<T>(file: string): T[] {
  if (!existsSync(file)) return [];
  const out: T[] = [];
  for (const line of readFileSync(file, 'utf8').split('\n')) {
    if (!line.trim()) continue;
    try {
      out.push(JSON.parse(line) as T);
    } catch {
      // A torn final line after a crash is ignored.
    }
  }
  return out;
}

/** Provider ids whose contestants cost nothing: Manual (copy & paste) and the Random Baseline. */
function freeProviders(): Set<string> {
  try {
    return new Set(loadProviders().filter((p) => p.type === 'manual' || p.type === 'mock').map((p) => p.id));
  } catch {
    return new Set(['manual', 'baseline']);
  }
}

/** Every spend of a run, dated. One line per answer attempt: re-scored copies of the same attempt count once. */
export function runSpendLines(m: RunManifest, free = freeProviders()): SpendLine[] {
  const freeContestants = new Set(m.contestants.filter((c) => free.has(c.provider)).map((c) => c.id));
  type Line = { key: string; contestantId: string; startedAt?: string; metrics?: { costUsd?: number; judgeCostUsd?: number }; manualOrigin?: unknown };
  const attempts = new Map<string, Line>();
  // A copy & paste result moved to a named model (manualOrigin) is the same attempt as its original: count it once.
  for (const r of jsonLines<Line>(join(RUNS_DIR, m.id, 'results.jsonl'))) if (!r.manualOrigin) attempts.set(`${r.key}@${r.startedAt ?? ''}`, r);
  const out: SpendLine[] = [];
  for (const r of attempts.values()) {
    const usd = (freeContestants.has(r.contestantId) ? 0 : (r.metrics?.costUsd ?? 0)) + (r.metrics?.judgeCostUsd ?? 0);
    if (usd > 0) out.push({ at: r.startedAt ?? m.startedAt ?? m.createdAt, usd });
  }
  if (m.unrecordedCostUsd) out.push({ at: m.startedAt ?? m.createdAt, usd: m.unrecordedCostUsd });
  return out;
}

/** Every spend of a tournament, dated (each game line as played; human-verdict amendments cost nothing). */
export function tournamentSpendLines(m: TournamentManifest): SpendLine[] {
  const free = new Set(m.entrants.filter((e) => e.manual || e.baseline).map((e) => e.id));
  type Line = { players?: [string, string]; startedAt?: string; amends?: boolean; metrics?: Array<{ costUsd?: number }>; judging?: { costUsd?: number } };
  const out: SpendLine[] = [];
  for (const g of jsonLines<Line>(join(ARENA_DIR, m.id, 'games.jsonl'))) {
    if (g.amends) continue;
    const side = (i: 0 | 1) => (g.players && free.has(g.players[i]) ? 0 : (g.metrics?.[i]?.costUsd ?? 0));
    const usd = side(0) + side(1) + (g.judging?.costUsd ?? 0);
    if (usd > 0) out.push({ at: g.startedAt ?? m.startedAt ?? m.createdAt, usd });
  }
  return out;
}

const sum = (lines: SpendLine[]) => lines.reduce((s, l) => s + l.usd, 0);
const r6 = (x: number) => Math.round(x * 1e6) / 1e6;

/** Everything a run has spent so far (all months): its whole-run limit covers all of it. */
export function runSpentUsd(runId: string): number {
  const m = readManifest(runId);
  return m ? r6(sum(runSpendLines(m))) : 0;
}

export function tournamentSpentUsd(id: string): number {
  const m = readTournament(id);
  return m ? r6(sum(tournamentSpendLines(m))) : 0;
}

/** A job that finished before the month began cannot have spent anything in it (resuming clears finishedAt). */
function mayHaveSpent(m: { status: string; finishedAt?: string; createdAt: string }, start: Date, nextReset: Date): boolean {
  if (Date.parse(m.createdAt) >= nextReset.getTime()) return false;
  if (m.status === 'running' || m.status === 'queued' || !m.finishedAt) return true;
  return Date.parse(m.finishedAt) >= start.getTime();
}

export interface MonthSpend {
  spentUsd: number;
  committedUsd: number;
  items: BudgetItem[];
  days: Array<{ date: string; spentUsd: number }>;
}

/** This month's spend, per job and per day, plus what running jobs may still spend under their limits. */
export function monthSpend(now: Date = new Date()): MonthSpend {
  const b = monthBounds(now);
  const items: BudgetItem[] = [];
  const days = new Map<string, number>();
  let committed = 0;
  const add = (lines: SpendLine[], item: Omit<BudgetItem, 'spentUsd' | 'at'>, fallbackAt: string) => {
    const mine = lines.filter((l) => inMonth(l.at, b));
    for (const l of mine) {
      const d = localDate(new Date(l.at));
      days.set(d, (days.get(d) ?? 0) + l.usd);
    }
    const spent = sum(mine);
    if (spent > 0 || item.active) items.push({ ...item, at: mine.map((l) => l.at).sort()[0] ?? fallbackAt, spentUsd: r6(spent) });
  };
  const free = freeProviders();
  for (const id of listRunIds()) {
    let m: RunManifest | null;
    try {
      m = readManifest(id);
    } catch {
      continue;
    }
    if (!m) continue;
    const running = m.status === 'running' || m.status === 'queued';
    if (!running && !mayHaveSpent(m, b.start, b.nextReset)) continue;
    const lines = runSpendLines(m, free);
    if (running && m.settings.maxCostUsd !== undefined) committed += Math.max(0, m.settings.maxCostUsd - sum(lines));
    add(lines, { kind: 'run', id, name: m.name, ...(running ? { active: true } : {}) }, m.startedAt ?? m.createdAt);
  }
  for (const id of listTournamentIds()) {
    let m: TournamentManifest | null;
    try {
      m = readTournament(id);
    } catch {
      continue;
    }
    if (!m) continue;
    const running = m.status === 'running' || m.status === 'queued';
    if (!running && !mayHaveSpent(m, b.start, b.nextReset)) continue;
    const lines = tournamentSpendLines(m);
    if (running && m.settings.maxCostUsd !== undefined) committed += Math.max(0, m.settings.maxCostUsd - sum(lines));
    add(lines, { kind: 'arena', id, name: m.name, ...(running ? { active: true } : {}) }, m.startedAt ?? m.createdAt);
  }
  for (const e of jsonLines<{ at: string; kind: BudgetItem['kind']; name: string; costUsd: number }>(SPEND_LOG)) {
    if (!(e.costUsd > 0) || !inMonth(e.at, b)) continue;
    const d = localDate(new Date(e.at));
    days.set(d, (days.get(d) ?? 0) + e.costUsd);
    items.push({ kind: e.kind, name: e.name, at: e.at, spentUsd: r6(e.costUsd) });
  }
  items.sort((x, y) => y.at.localeCompare(x.at));
  return {
    spentUsd: r6(items.reduce((s, i) => s + i.spentUsd, 0)),
    committedUsd: r6(committed),
    items,
    days: [...days.entries()].sort(([a], [z]) => a.localeCompare(z)).map(([date, usd]) => ({ date, spentUsd: r6(usd) })),
  };
}

export function loadBudget(): BudgetSettings {
  return normalizeBudget((loadSettings() as { budget?: unknown }).budget);
}

/** GET /api/budget: settings, this month's spend, what is left, and the breakdown. */
export function budgetStatus(now: Date = new Date(), settings: BudgetSettings = loadBudget()): BudgetStatus {
  const b = monthBounds(now);
  const spend = monthSpend(now);
  return {
    settings,
    currency: normalizeCurrency(loadSettings().currency),
    month: { key: b.key, label: b.label, start: b.start.toISOString(), nextReset: b.nextReset.toISOString(), nextResetLabel: b.nextResetLabel },
    spentUsd: spend.spentUsd,
    committedUsd: spend.committedUsd,
    ...summarize(settings, spend.spentUsd, spend.committedUsd),
    items: spend.items.slice(0, 50),
    days: spend.days,
  };
}

/** The monthly budget applied to a run or tournament about to start. Throws BudgetBlockedError under the hard stop. */
export function gateStart(requestedCapUsd: number | undefined, what: 'run' | 'tournament', now: Date = new Date()): BudgetGate {
  const settings = loadBudget();
  if (settings.monthlyUsd === undefined) return { capUsd: requestedCapUsd, clamped: false };
  return gateLimit(requestedCapUsd, budgetStatus(now, settings), normalizeCurrency(loadSettings().currency), 0, what);
}

/** The monthly budget applied to a run or tournament being resumed (its limit covers what it already spent). */
export function gateResume(requestedCapUsd: number | undefined, alreadySpentUsd: number, what: 'run' | 'tournament', now: Date = new Date()): BudgetGate {
  const settings = loadBudget();
  if (settings.monthlyUsd === undefined) return { capUsd: requestedCapUsd, clamped: false };
  return gateLimit(requestedCapUsd, budgetStatus(now, settings), normalizeCurrency(loadSettings().currency), alreadySpentUsd, what, true);
}
