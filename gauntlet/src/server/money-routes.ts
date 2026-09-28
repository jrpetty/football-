import type { IncomingMessage, ServerResponse } from 'node:http';
import { join } from 'node:path';
import { readFileSync } from 'node:fs';
import { CONFIG_DIR } from '../core/paths.ts';
import { loadSettings, writeJsonAtomic } from '../core/config.ts';
import { isCurrencyCode, normalizeCurrency, type CurrencySettings } from '../core/currency.ts';

/**
 * Display currency (GBP by default).
 *  GET /api/settings/currency                          the current currency and exchange rate
 *  PUT /api/settings/currency  {code, usdPerUnit}      change them (saved in config/settings.json)
 * The rate is whatever the owner types: nothing is fetched from the internet. Writes only from this computer.
 */

type Handler = (ctx: { req: IncomingMessage; res: ServerResponse; params: Record<string, string>; query: URLSearchParams; body: () => Promise<unknown> }) => unknown | Promise<unknown>;

interface Deps {
  route: (method: string, path: string, handler: Handler) => void;
  httpError: (status: number, message: string) => Error;
}

function isLocal(req: IncomingMessage): boolean {
  const a = req.socket.remoteAddress ?? '';
  return a === '127.0.0.1' || a === '::1' || a === '::ffff:127.0.0.1';
}

/** Validate a currency change; returns the normalised setting or a plain-English error. */
export function checkCurrencyInput(b: unknown, today = new Date().toISOString().slice(0, 10)): CurrencySettings | string {
  const x = (b ?? {}) as { code?: unknown; usdPerUnit?: unknown };
  if (!isCurrencyCode(x.code)) return 'code must be GBP, USD or EUR';
  if (x.code === 'USD') return { code: 'USD', usdPerUnit: 1, rateDate: today };
  if (!(typeof x.usdPerUnit === 'number' && x.usdPerUnit > 0.01 && x.usdPerUnit < 100)) return 'usdPerUnit must be a number such as 1.33 (US dollars per one pound)';
  return normalizeCurrency({ code: x.code, usdPerUnit: Math.round(x.usdPerUnit * 10000) / 10000, rateDate: today });
}

export function registerMoneyRoutes({ route, httpError }: Deps): void {
  route('GET', '/api/settings/currency', () => loadSettings().currency);
  route('PUT', '/api/settings/currency', async ({ req, body }) => {
    if (!isLocal(req)) throw httpError(403, 'The currency can only be changed from this computer');
    const next = checkCurrencyInput(await body());
    if (typeof next === 'string') throw httpError(400, next);
    // Only the currency key changes; every other setting is written back exactly as stored.
    const file = join(CONFIG_DIR, 'settings.json');
    const stored = JSON.parse(readFileSync(file, 'utf8')) as Record<string, unknown>;
    writeJsonAtomic(file, { ...stored, currency: next });
    return next;
  });
}
