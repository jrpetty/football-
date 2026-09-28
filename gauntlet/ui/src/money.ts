/**
 * The display currency for the whole app (GBP by default for this owner). Costs arrive from the server in US dollars;
 * fmtCost (format.ts) converts them with the rate set here. It is set once from /api/meta when the app loads (and the
 * page reloads after the owner changes it), so every screen agrees.
 */
import { CURRENCIES, DEFAULT_CURRENCY, formatMoney, fromUsd, normalizeCurrency, rateLabel, toUsd, type CurrencySettings } from '../../src/core/currency.ts';

let current: CurrencySettings = DEFAULT_CURRENCY;

export function setDisplayCurrency(c: Partial<CurrencySettings> | null | undefined): void {
  current = normalizeCurrency(c ?? undefined);
}

export function displayCurrency(): CurrencySettings {
  return current;
}

export const currencySymbol = (): string => CURRENCIES[current.code].symbol;

/** USD → the display amount as text, e.g. "£3.20". */
export const money = (usd: number): string => formatMoney(usd, current);

/** A typed display-currency amount → USD for the API. */
export const displayToUsd = (amount: number): number => toUsd(amount, current);

/** USD → display-currency number (for input fields). */
export const usdToDisplay = (usd: number): number => fromUsd(usd, current);

/** "£1 = $1.33" (empty for USD). */
export const currentRateLabel = (): string => rateLabel(current);
