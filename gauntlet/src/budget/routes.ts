import type { IncomingMessage, ServerResponse } from 'node:http';
import { updateSettings } from '../core/config.ts';
import { checkBudgetInput, type BudgetSettings } from './budget.ts';
import { budgetStatus, loadBudget } from './spend.ts';

/**
 * "My budget".
 *  GET /api/budget                     settings, spent this month, what is left, next reset, recent spending, per-day totals
 *  PUT /api/budget  {defaultRunUsd?, defaultPerAnswerUsd?, monthlyUsd?, hardStop?}
 *                                      change the settings (a number sets, null clears, a missing field keeps); amounts in USD
 * Writes are only accepted from this computer (loopback), like the API keys routes.
 */

type Handler = (ctx: { req: IncomingMessage; res: ServerResponse; params: Record<string, string>; query: URLSearchParams; body: () => Promise<unknown> }) => unknown | Promise<unknown>;

interface Deps {
  route: (method: string, path: string, handler: Handler) => void;
  httpError: (status: number, message: string) => Error;
}

export function isLocal(req: IncomingMessage): boolean {
  const a = req.socket.remoteAddress ?? '';
  return a === '127.0.0.1' || a === '::1' || a === '::ffff:127.0.0.1';
}

/** Save the budget into the owner's settings file (kept when Gauntlet is updated), keeping every other setting as stored. */
export function saveBudget(next: BudgetSettings): void {
  updateSettings({ budget: next });
}

export function registerBudgetRoutes({ route, httpError }: Deps): void {
  route('GET', '/api/budget', () => budgetStatus());
  route('PUT', '/api/budget', async ({ req, body }) => {
    if (!isLocal(req)) throw httpError(403, 'Your budget can only be changed from the computer running Gauntlet.');
    const next = checkBudgetInput(await body(), loadBudget());
    if (typeof next === 'string') throw httpError(400, next);
    saveBudget(next);
    return budgetStatus();
  });
}
