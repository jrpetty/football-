/**
 * Shared helpers for the operations & workflow pack (work.stock-reorder, work.shift-rota, work.delivery-routes,
 * work.support-replies, work.board-report). The seeded random stream, money formatting and exact fractions are the
 * Everyday Life pack's own (reused, not copied); this file adds UK dates and the answer instruction.
 */
export { Rng, money, pounds, hhmm, mins, testJson, FIRST_NAMES } from '../realworld-life/common.mts';

const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** A UTC date from "2026-06-15". */
export function day(iso: string): Date {
  return new Date(`${iso}T00:00:00Z`);
}

export function addDays(d: Date, n: number): Date {
  return new Date(d.getTime() + n * 86400000);
}

/** "Mon 15 Jun" (UK style). */
export function ukDate(d: Date, withYear = false): string {
  return `${DAYS[d.getUTCDay()]} ${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]}${withYear ? ` ${d.getUTCFullYear()}` : ''}`;
}

/** "15 Jun" */
export function shortDate(d: Date): string {
  return `${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]}`;
}

export function isoDate(d: Date): string {
  return d.toISOString().slice(0, 10);
}

/** England and Wales bank holidays, 2026 and early 2027 (gov.uk). */
export const BANK_HOLIDAYS = new Set([
  '2026-01-01', '2026-04-03', '2026-04-06', '2026-05-04', '2026-05-25', '2026-08-31', '2026-12-25', '2026-12-28',
  '2027-01-01', '2027-03-26', '2027-03-29', '2027-05-03', '2027-05-31',
]);

/** Whether the Monday-to-Sunday week starting `monday` contains a bank holiday. */
export function weekHasBankHoliday(monday: Date): boolean {
  for (let i = 0; i < 7; i++) if (BANK_HOLIDAYS.has(isoDate(addDays(monday, i)))) return true;
  return false;
}

/** The anti-hedging sentence every prompt in this pack ends with. */
export const ONE_ANSWER =
  'Give exactly one answer as a single JSON block. Do not offer alternatives: a reply that contains two different answers, or a value that says "or", is marked wrong. You may explain your working before the JSON.';

export type Difficulty = 'easy' | 'medium' | 'hard';

/** A scripted wrong answer (for the spread report and the tests of the tests). */
export interface Careless {
  label: string;
  answer: Record<string, unknown>;
}
