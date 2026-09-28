/**
 * Client for "My budget" (/api/budget, docs/API.md → "Your own budget").
 * In mock mode (?mock=1) an in-memory month of spending answers, so the page can be demoed with no server.
 */
import { useEffect, useState } from 'react';
import { MOCK, request } from '../api.ts';
import { checkBudgetInput, monthBounds, summarize, localDate, type BudgetItem, type BudgetSettings, type BudgetStatus } from '../../../src/budget/budget.ts';
import { displayCurrency } from '../money.ts';

export type { BudgetItem, BudgetSettings, BudgetStatus };

export const budgetApi = {
  get: () => (MOCK ? Promise.resolve(mockStatus()) : request<BudgetStatus>('GET', '/api/budget')),
  save: (patch: Partial<Record<keyof BudgetSettings, number | boolean | null>>) => (MOCK ? mockSave(patch) : request<BudgetStatus>('PUT', '/api/budget', patch)),
};

/** The budget status, loaded once per screen (null while loading or when the server is too old to have it). */
export function useBudget(): { budget: BudgetStatus | null; reload: () => void } {
  const [budget, setBudget] = useState<BudgetStatus | null>(null);
  const [nonce, setNonce] = useState(0);
  useEffect(() => {
    let alive = true;
    budgetApi.get().then(
      (b) => alive && setBudget(b),
      () => alive && setBudget(null),
    );
    return () => {
      alive = false;
    };
  }, [nonce]);
  return { budget, reload: () => setNonce((n) => n + 1) };
}

// ───────────────────────────── Mock mode ─────────────────────────────

/** A plausible month for a UK creator: £50 a month (at £1 = $1.33), hard stop on, about three-quarters used. */
let mockSettings: BudgetSettings = { monthlyUsd: 66.5, hardStop: true, defaultRunUsd: 13.3, defaultPerAnswerUsd: 1.33 };

function mockItems(now: Date): BudgetItem[] {
  const b = monthBounds(now);
  const day = (d: number, h = 14) => new Date(b.start.getFullYear(), b.start.getMonth(), Math.min(d, now.getDate()), h, 5).toISOString();
  return [
    { kind: 'run', id: 'run-2026-09-24-agents', name: 'Agents Showdown · live', at: day(24, 20), spentUsd: 21.4, active: true },
    { kind: 'arena', id: 'arena-demo-chess', name: 'Chess Championship · September', at: day(22, 19), spentUsd: 9.41 },
    { kind: 'polish', name: 'Script polish (Claude Sonnet 5)', at: day(9, 11), spentUsd: 0.06 },
    { kind: 'run', id: 'run-2026-09-21-core', name: 'Core Gauntlet · September 2026', at: day(21, 10), spentUsd: 17.36 },
    { kind: 'grade', name: 'AI judges graded a pasted answer', at: day(5, 16), spentUsd: 0.04 },
    { kind: 'run', id: 'run-2026-09-18-quick', name: 'Quick check · new Nova config', at: day(18, 9), spentUsd: 3.84 },
    { kind: 'run', id: 'run-2026-09-23-manual', name: 'Orbit Chat by hand · social + honesty (judges only)', at: day(23, 18), spentUsd: 2.11 },
  ];
}

function mockStatus(now = new Date()): BudgetStatus {
  const b = monthBounds(now);
  const items = mockItems(now).sort((x, y) => y.at.localeCompare(x.at));
  const spent = Math.round(items.reduce((s, i) => s + i.spentUsd, 0) * 100) / 100;
  const days = new Map<string, number>();
  for (const i of items) days.set(localDate(new Date(i.at)), (days.get(localDate(new Date(i.at))) ?? 0) + i.spentUsd);
  return {
    settings: mockSettings,
    currency: displayCurrency(),
    month: { key: b.key, label: b.label, start: b.start.toISOString(), nextReset: b.nextReset.toISOString(), nextResetLabel: b.nextResetLabel },
    spentUsd: spent,
    committedUsd: mockSettings.monthlyUsd !== undefined ? 3.1 : 0,
    ...summarize(mockSettings, spent, mockSettings.monthlyUsd !== undefined ? 3.1 : 0),
    items,
    days: [...days.entries()].sort(([a], [z]) => a.localeCompare(z)).map(([date, spentUsd]) => ({ date, spentUsd })),
  };
}

async function mockSave(patch: unknown): Promise<BudgetStatus> {
  const next = checkBudgetInput(patch, mockSettings);
  if (typeof next === 'string') throw new Error(next);
  mockSettings = next;
  return mockStatus();
}
