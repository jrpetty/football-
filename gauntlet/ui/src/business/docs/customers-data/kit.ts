/**
 * Shared pure helpers of the customers, comms and data document pictures: reading the model's JSON answer the way
 * the real scorers read it, comparing one field the way the JSON scorer does, ids, dates and money in words.
 * No DOM, no React: imported by the pictures and by the unit tests (test/customers-data-docs.test.ts).
 */
import type { CaseVisualInput } from '../../../../../src/presenter/visuals/common.ts';
import { finalReply } from '../../../../../src/presenter/visuals/common.ts';
import { parseJsonLoose } from '../../../../../src/core/extract.ts';
import { compareJson, normSetId } from '../../../../../src/scoring/json-compare.ts';

export { finalReply, normSetId };

export type Json = null | boolean | number | string | Json[] | { [k: string]: Json };

/** Options of the JSON scorer that matter for one field (the rest only weigh points). */
export interface FieldOpts {
  numberTolerance?: number;
  aliases?: Record<string, string>;
}

/** The model's JSON answer, read exactly as the JSON scorer reads it (null: no reply or no JSON). */
export function replyJson(input: CaseVisualInput): Record<string, unknown> | null {
  const reply = finalReply(input);
  if (!reply.trim()) return null;
  const v = parseJsonLoose(reply, input.expected);
  return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null;
}

/** Whether the model's value counts as the key's value under the JSON scorer's lenient rules. */
export function sameValue(expected: unknown, got: unknown, opts: FieldOpts = {}): boolean {
  if (got === undefined) got = null;
  const r = compareJson({ v: expected }, { v: got }, { lenient: true, numberTolerance: opts.numberTolerance, aliases: opts.aliases });
  return r.total > 0 && r.matched === r.total;
}

const keyNorm = (k: string) => k.toLowerCase().replace(/[\s_\-]+/g, '');

/** A field of an object, its key matched ignoring case, spaces, "_" and "-" (as the lenient scorer does). */
export function pick(obj: unknown, ...names: string[]): unknown {
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return undefined;
  const want = new Set(names.map(keyNorm));
  for (const [k, v] of Object.entries(obj as Record<string, unknown>)) if (want.has(keyNorm(k))) return v;
  return undefined;
}

/** An id-keyed answer collection (object keyed by id, or a list of objects with an id field) as a map by normalised id. */
export function byId(v: unknown, idField = 'id'): Map<string, unknown> {
  const out = new Map<string, unknown>();
  if (Array.isArray(v)) {
    for (const x of v) {
      const id = pick(x, idField, 'id', 'clause', 'message', 'ticket');
      if (id !== undefined && id !== null) out.set(normSetId(String(id)), x);
      else if (typeof x === 'string') out.set(normSetId(x), null);
    }
  } else if (v && typeof v === 'object') {
    for (const [k, x] of Object.entries(v as Record<string, unknown>)) out.set(normSetId(k), x);
  }
  return out;
}

/** A value as a short readable text ("—" for nothing). */
export function show(v: unknown): string {
  if (v === undefined || v === null || v === '') return '—';
  if (typeof v === 'boolean') return v ? 'yes' : 'no';
  if (typeof v === 'number') return Number.isInteger(v) ? v.toLocaleString('en-GB') : String(Math.round(v * 100) / 100);
  if (typeof v === 'string') return v;
  return JSON.stringify(v);
}

// ───────────────────────────── dates and money ─────────────────────────────

const DOW = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "2026-03-05" → "Thu 5 Mar"; "2026-03-05 14:30" → "Thu 5 Mar 14:30"; anything else unchanged. */
export function niceDate(s: unknown, withYear = false): string {
  if (typeof s !== 'string') return show(s);
  const m = s.trim().match(/^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{1,2}:\d{2}))?/);
  if (!m) return s;
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
  if (Number.isNaN(d.getTime())) return s;
  return `${DOW[d.getUTCDay()]} ${d.getUTCDate()} ${MON[d.getUTCMonth()]}${withYear ? ` ${m[1]}` : ''}${m[4] ? ` ${m[4].padStart(5, '0')}` : ''}`;
}

/** £1,234.50 (pounds in); null/undefined → "—". */
export function money(v: unknown): string {
  const n = typeof v === 'number' ? v : typeof v === 'string' ? Number(v.replace(/[£,\s]/g, '')) : NaN;
  if (!Number.isFinite(n)) return show(v);
  return `${n < 0 ? '−' : ''}£${Math.abs(n).toLocaleString('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

/** £ from pence. */
export const pence = (p: number) => money(p / 100);

/** "1 of 3", plural nouns. */
export function plural(n: number, one: string, many = `${one}s`): string {
  return `${n.toLocaleString('en-GB')} ${n === 1 ? one : many}`;
}

/** Initials for an avatar ("Declan Kim" → "DK"). */
export function initials(name: string): string {
  const parts = name.replace(/[^\p{L}\s'-]/gu, ' ').split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? parts[parts.length - 1]![0] : '')).toUpperCase() || '?';
}

/** A stable hue (0–359) for a name, for avatars and van colours. */
export function hueOf(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
  return h % 360;
}

// ───────────────────────────── the verdict of one case ─────────────────────────────

export interface CaseVerdict {
  tone: 'pass' | 'fail' | 'partial' | 'none';
  mark: string;
  title: string;
  scoreText: string | null;
}

/** A plain verdict for a whole case: full marks, a share of the marks, or none. */
export function caseVerdict(score: number | null, status?: string): CaseVerdict {
  if (status === 'error' || status === 'cancelled' || status === 'skipped' || score === null) return { tone: 'none', mark: '–', title: status === 'error' ? 'No result (the call failed)' : 'Not scored', scoreText: null };
  const pct = Math.round(score * 100);
  if (score >= 0.9995) return { tone: 'pass', mark: '✓', title: 'Full marks', scoreText: '100 of 100' };
  if (score <= 0) return { tone: 'fail', mark: '✕', title: 'No marks', scoreText: '0 of 100' };
  return { tone: score >= 0.5 ? 'partial' : 'fail', mark: '◐', title: `${pct} of 100`, scoreText: `${pct}% of the marks` };
}
