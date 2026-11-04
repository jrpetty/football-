/**
 * Exact multi-part answers (life.tariff-chooser, life.loan-overpayment): every field of the answer is compared with
 * the solver's value, one check per field, so a model that gets four of five numbers right scores 80. Reading is
 * format-tolerant (key case and punctuation, "£1,234.56" or 1234.56, "Aug 2039" / "2039-08" / "08/2039", a
 * choice by its ID or its name); a value offering two alternatives is wrong.
 */
import type { AnswerVerifier, VerifierItem } from './types.ts';
import { field, gbp, hedgedText, readId, readNumber, readPence } from './common.ts';

export interface FieldSpec {
  /** Path of the field in the answer, e.g. "totals.A" or "clearedMonth". */
  path: string;
  /** Other accepted spellings of the last path part. */
  aliases?: string[];
  label: string;
  kind: 'money' | 'int' | 'month' | 'choice';
  /** money: pence; int: whole number; month: "YYYY-MM"; choice: an ID from `choices`. */
  value: number | string;
  /** choice: ID → display name. */
  choices?: Record<string, string>;
}

export interface FieldsWorld {
  fields: FieldSpec[];
}

const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];

/** "2039-08", "Aug 2039", "August 2039", "08/2039", "8/2039" → "2039-08". */
export function readMonth(v: unknown): string | null {
  if (typeof v !== 'string' || hedgedText(v)) return null;
  const t = v.trim().toLowerCase();
  let m = /^(\d{4})[-/.](\d{1,2})(?:[-/.]\d{1,2})?$/.exec(t);
  if (m) return Number(m[2]) >= 1 && Number(m[2]) <= 12 ? `${m[1]}-${m[2]!.padStart(2, '0')}` : null;
  m = /^(\d{1,2})[-/.](\d{4})$/.exec(t);
  if (m) return Number(m[1]) >= 1 && Number(m[1]) <= 12 ? `${m[2]}-${m[1]!.padStart(2, '0')}` : null;
  m = /^(?:\d{1,2}(?:st|nd|rd|th)?\s+)?([a-z]{3,9})\.?,?\s+(\d{4})$/.exec(t);
  if (m) {
    const i = MONTHS.indexOf(m[1]!.slice(0, 3));
    if (i >= 0 && (m[1]!.length === 3 || 'january february march april may june july august september october november december'.split(' ')[i]!.startsWith(m[1]!))) return `${m[2]}-${String(i + 1).padStart(2, '0')}`;
  }
  return null;
}

function monthText(ym: string): string {
  const [y, mo] = ym.split('-');
  return `${MONTHS[Number(mo) - 1]![0]!.toUpperCase()}${MONTHS[Number(mo) - 1]!.slice(1)} ${y}`;
}

/** Walk a dotted path with format-tolerant keys. */
function at(answer: unknown, path: string, aliases: string[] = []): unknown {
  const parts = path.split('.');
  let cur: unknown = answer;
  for (let i = 0; i < parts.length; i++) {
    const names = i === parts.length - 1 ? [parts[i]!, ...aliases] : [parts[i]!];
    cur = field(cur, ...names);
    if (cur === undefined) return undefined;
  }
  return cur;
}

export function checkFields(w: FieldsWorld, answer: Record<string, unknown>): { items: VerifierItem[] } {
  const items: VerifierItem[] = [];
  for (const f of w.fields) {
    const got = at(answer, f.path, f.aliases);
    let ok = false;
    let shown = got === undefined ? 'missing' : JSON.stringify(got).slice(0, 40);
    switch (f.kind) {
      case 'money': {
        const p = readPence(got);
        ok = p !== null && p === f.value;
        break;
      }
      case 'int': {
        const n = readNumber(got);
        ok = n !== null && Number.isInteger(n) && n === f.value;
        break;
      }
      case 'month': {
        const m = readMonth(got);
        ok = m !== null && m === f.value;
        break;
      }
      case 'choice': {
        const ids = Object.keys(f.choices ?? {});
        const names = new Map(Object.entries(f.choices ?? {}));
        const id = typeof got === 'string' && hedgedText(got) ? null : readId(got, ids, names);
        ok = id === f.value;
        if (id) shown = id;
        break;
      }
    }
    const want = f.kind === 'money' ? gbp(f.value as number) : f.kind === 'month' ? monthText(f.value as string) : f.kind === 'choice' ? `${f.value} (${f.choices?.[f.value as string] ?? ''})` : String(f.value);
    items.push({ label: f.label, passed: ok, detail: ok ? undefined : `expected ${want}, got ${shown}` });
  }
  return { items };
}

export const fields: AnswerVerifier<FieldsWorld> = {
  kind: 'answer',
  name: 'fields',
  noun: 'answer',
  answerKeys: ['totals', 'cheapest', 'clearedmonth', 'months', 'totalinterest', 'interestsaved', 'answers', 'options'],
  marking: [
    'Every number asked for is checked on its own against the exact value worked out by the answer-key program, to the penny (or the exact month or whole number). The score is the share of numbers right, so each one is worth the same.',
    'Presentation does not matter: "£1,234.56" and 1234.56 are the same, a month can be written "Aug 2039" or "2039-08", and an option can be named by its letter or its name. A value that offers two alternatives is wrong.',
  ],
  check: checkFields,
};

