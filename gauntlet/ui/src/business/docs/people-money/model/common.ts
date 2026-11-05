/**
 * Shared helpers of the people-and-money document visuals: reading the model's JSON answer the way the json
 * scorer reads it, the scorer's own verdict for one field, loose key and id lookups, and money / date wording.
 * Pure functions (no React, no DOM), shared by the UI and test/people-money-docs.test.ts.
 */
import { parseJsonLoose } from '../../../../../../src/core/extract.ts';
import { looseNumber, normSetId } from '../../../../../../src/scoring/json-compare.ts';
import { finalReply, type CaseVisualInput } from '../../../../../../src/presenter/visuals/common.ts';

export type Json = null | boolean | number | string | Json[] | { [k: string]: Json };
export type Obj = Record<string, unknown>;

export const isObj = (v: unknown): v is Obj => !!v && typeof v === 'object' && !Array.isArray(v);

/** Key spelling the lenient scorer ignores: case, spaces, "_" and "-". */
export const normKey = (k: string) => k.toLowerCase().replace(/[\s_\-]+/g, '');

/** obj[key], or the key spelled differently (as the lenient scorer reads it). */
export function pick(obj: unknown, key: string): unknown {
  if (!isObj(obj)) return undefined;
  if (Object.prototype.hasOwnProperty.call(obj, key)) return obj[key];
  const want = normKey(key);
  const hit = Object.keys(obj).find((k) => normKey(k) === want);
  return hit === undefined ? undefined : obj[hit];
}

/** obj[id] for an item id inside a set ("L07" = "line 7"? no: "L07" = "l07" = "L-07"). */
export function pickId(obj: unknown, id: string): unknown {
  if (!isObj(obj)) return undefined;
  if (Object.prototype.hasOwnProperty.call(obj, id)) return obj[id];
  const want = normSetId(id);
  const hit = Object.keys(obj).find((k) => normSetId(k) === want);
  return hit === undefined ? undefined : obj[hit];
}

/** The ids an answer lists: list items, or the keys of an id-keyed object (normalised like the scorer). */
export function idsOf(v: unknown): string[] {
  if (Array.isArray(v)) return v.filter((x) => typeof x === 'string' || typeof x === 'number').map((x) => normSetId(String(x)));
  if (isObj(v)) return Object.keys(v).map(normSetId);
  if (typeof v === 'string' && v.trim()) return [normSetId(v)];
  return [];
}

export const sameId = (a: string, b: string) => normSetId(a) === normSetId(b);

/** A number the way the lenient scorer reads it ("£1,234.50" = 1234.5), else null. */
export function num(v: unknown): number | null {
  if (typeof v === 'number' && Number.isFinite(v)) return v;
  if (typeof v === 'string') return looseNumber(v);
  return null;
}

const LENIENT_NULL = new Set(['', 'none', 'null', 'n/a', 'na', '-', '—', 'nil']);
const looseStr = (s: string) => s.trim().toLowerCase().replace(/[\s_\-]+/g, ' ').replace(/\.$/, '').trim();

/** The lenient scorer's equality for one leaf (numbers to a hair, strings ignoring case and spacing). */
export function looseEq(exp: unknown, got: unknown, aliases?: Record<string, string>): boolean {
  if (got === undefined) return false;
  if (Array.isArray(got) && got.length === 1 && !Array.isArray(exp)) got = got[0];
  if (exp === null) return got === null || (typeof got === 'string' && LENIENT_NULL.has(got.trim().toLowerCase()));
  if (typeof exp === 'boolean') {
    if (typeof got === 'string') {
      const t = got.trim().toLowerCase();
      if (/^(?:yes|true|y)$/.test(t)) return exp === true;
      if (/^(?:no|false|n)$/.test(t)) return exp === false;
    }
    return got === exp;
  }
  if (typeof exp === 'number') {
    const n = num(got);
    return n !== null && Math.abs(n - exp) <= 1e-6 * Math.max(1, Math.abs(exp));
  }
  if (typeof exp === 'string') {
    if (typeof got !== 'string' && typeof got !== 'number') return false;
    let g = String(got);
    if (aliases) {
      const a = Object.entries(aliases).find(([k]) => looseStr(k) === looseStr(g));
      if (a) g = a[1];
    }
    if (looseStr(g) === looseStr(exp)) return true;
    return /\d/.test(exp) && g.replace(/[\s-]/g, '').toLowerCase() === exp.replace(/[\s-]/g, '').toLowerCase();
  }
  return JSON.stringify(exp) === JSON.stringify(got);
}

export interface ReadAnswer {
  /** The JSON the scorer read (undefined: none could be read). */
  parsed: unknown;
  /** The scorer's own verdict for a field path ("documents.DOC-1.outcome", "shortlist: C06"), or null when not recorded. */
  verdict: (path: string) => boolean | null;
  /** Points scored, as the scorer printed it, when recorded. */
  score: number | null;
}

/** The model's answer as the json scorer read it, with the scorer's per-field verdicts (scoreDetail.items). */
export function readAnswer(input: CaseVisualInput): ReadAnswer {
  const reply = finalReply(input);
  let parsed: unknown = undefined;
  if (reply.trim()) {
    try {
      parsed = parseJsonLoose(reply, input.expected);
    } catch {
      parsed = undefined;
    }
  }
  if (parsed === undefined && typeof input.detail.extracted === 'string') {
    try {
      parsed = JSON.parse(input.detail.extracted);
    } catch {
      parsed = undefined;
    }
  }
  const map = new Map<string, boolean>();
  for (const it of input.detail.items ?? []) map.set(it.label.replace(/\s\(×[\d.]+\)$/, ''), it.passed);
  return { parsed, verdict: (p) => map.get(p) ?? null, score: input.score };
}

/** The answer key as an object (or null). */
export function keyOf(input: CaseVisualInput): Obj | null {
  const e = input.expected ?? input.detail.expected;
  return isObj(e) ? e : null;
}

// ───────────────────────────── wording ─────────────────────────────

/** "£1,234.56" (negative: "−£12.00"). */
export function gbp(n: number | null | undefined, pence = true): string {
  if (n === null || n === undefined || !Number.isFinite(n)) return '—';
  const s = Math.abs(n).toLocaleString('en-GB', { minimumFractionDigits: pence ? 2 : 0, maximumFractionDigits: pence ? 2 : 0 });
  return `${n < 0 ? '−' : ''}£${s}`;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "2026-11-30" → "30 Nov 2026" (anything else as given). */
export function niceDate(s: unknown): string {
  if (typeof s !== 'string') return s === null || s === undefined ? '—' : String(s);
  const m = s.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  return m ? `${Number(m[3])} ${MONTHS[Number(m[2]) - 1]} ${m[1]}` : s;
}

/** A value as a person would read it in a sentence. */
export function shown(v: unknown): string {
  if (v === undefined) return 'nothing';
  if (v === null) return 'none';
  if (typeof v === 'boolean') return v ? 'yes' : 'no';
  if (typeof v === 'number') return Number.isInteger(v) ? v.toLocaleString('en-GB') : String(v);
  if (typeof v === 'string') return v;
  return JSON.stringify(v);
}

export const plural = (n: number, one: string, many = `${one}s`) => `${n.toLocaleString('en-GB')} ${n === 1 ? one : many}`;

/** "A, B and C". */
export function listText(xs: string[]): string {
  if (xs.length <= 1) return xs[0] ?? '';
  return `${xs.slice(0, -1).join(', ')} and ${xs[xs.length - 1]}`;
}

/** The prompt text between `<<<` and `>>>` after a heading line containing `label` (or null). */
export function fenced(prompt: string, label: RegExp): string | null {
  const m = prompt.match(new RegExp(`${label.source}[^\\n]*\\n<<<\\n([\\s\\S]*?)\\n>>>`, label.flags));
  return m ? m[1]! : null;
}
