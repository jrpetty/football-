/**
 * Display currency. Every cost is measured and stored in US dollars (the unit providers bill in); a display currency
 * only changes how amounts are shown and how spend limits are typed in. The exchange rate is a plain number in
 * config/settings.json that the owner can edit: nothing is ever fetched from the internet behind their back.
 *
 * Shared by the server and the UI (no Node imports).
 */

export type CurrencyCode = 'GBP' | 'USD' | 'EUR';

export interface CurrencySettings {
  /** The currency amounts are shown in and limits are typed in. */
  code: CurrencyCode;
  /** US dollars per one unit of the display currency, e.g. 1.33 means £1 = $1.33. Ignored for USD. */
  usdPerUnit: number;
  /** When the rate was last set (ISO date), shown next to it. */
  rateDate?: string;
}

export const CURRENCIES: Record<CurrencyCode, { symbol: string; name: string; defaultUsdPerUnit: number }> = {
  GBP: { symbol: '£', name: 'British pounds', defaultUsdPerUnit: 1.33 },
  USD: { symbol: '$', name: 'US dollars', defaultUsdPerUnit: 1 },
  EUR: { symbol: '€', name: 'Euros', defaultUsdPerUnit: 1.17 },
};

/** The owner is in the UK: pounds by default, at the GBP/USD rate of late September 2026 (about 1.33). */
export const DEFAULT_CURRENCY: CurrencySettings = { code: 'GBP', usdPerUnit: 1.33, rateDate: '2026-09-28' };

export function isCurrencyCode(x: unknown): x is CurrencyCode {
  return x === 'GBP' || x === 'USD' || x === 'EUR';
}

/** A usable currency setting from whatever is stored (bad or missing values fall back to the defaults). */
export function normalizeCurrency(c: Partial<CurrencySettings> | null | undefined): CurrencySettings {
  const code = isCurrencyCode(c?.code) ? c!.code : DEFAULT_CURRENCY.code;
  if (code === 'USD') return { code, usdPerUnit: 1, ...(c?.rateDate ? { rateDate: c.rateDate } : {}) };
  const rate = typeof c?.usdPerUnit === 'number' && Number.isFinite(c.usdPerUnit) && c.usdPerUnit > 0.01 && c.usdPerUnit < 100 ? c.usdPerUnit : CURRENCIES[code].defaultUsdPerUnit;
  return { code, usdPerUnit: rate, ...(c?.rateDate ? { rateDate: c.rateDate } : {}) };
}

/** Display-currency amount → US dollars (what the engine stores and caps in). */
export function toUsd(amount: number, c: CurrencySettings): number {
  return c.code === 'USD' ? amount : amount * c.usdPerUnit;
}

/** US dollars → display-currency amount. */
export function fromUsd(usd: number, c: CurrencySettings): number {
  return c.code === 'USD' ? usd : usd / c.usdPerUnit;
}

/** `£1.20`, `£0.052`, `£1,234`: the same shape as the dollar formatter, in the display currency. */
export function formatMoney(usd: number, c: CurrencySettings): string {
  const sym = CURRENCIES[c.code].symbol;
  const amount = fromUsd(usd, c);
  if (amount === 0) return `${sym}0.00`;
  const sign = amount < 0 ? '-' : '';
  const v = Math.abs(amount);
  if (v >= 1000) return `${sign}${sym}${Math.round(v).toLocaleString('en-US')}`;
  if (v >= 0.1) return `${sign}${sym}${v.toFixed(2)}`;
  if (v < 0.000001) return `${sign}<${sym}0.000001`;
  const digits = Math.min(6, Math.max(2, 1 - Math.floor(Math.log10(v))));
  return `${sign}${sym}${v.toFixed(digits)}`;
}

/** The rate line shown next to money inputs: "£1 = $1.33". Empty for USD. */
export function rateLabel(c: CurrencySettings): string {
  return c.code === 'USD' ? '' : `${CURRENCIES[c.code].symbol}1 = $${c.usdPerUnit.toFixed(2)}`;
}
