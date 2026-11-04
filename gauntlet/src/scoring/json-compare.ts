import type { ScoreBreakdownItem } from '../core/types.ts';

export interface JsonCompareOptions {
  unorderedArrays?: boolean;
  numberTolerance?: number;
  /** Accepted alternative spellings for string answers, e.g. { "color": "colour" } (case-insensitive). */
  aliases?: Record<string, string>;
  /**
   * Presentation never costs marks (content mistakes only): object keys match ignoring case, spaces, hyphens and
   * underscores; a numeric string with a currency sign, code or thousands separators ("£1,234.50", "1 234.50 GBP")
   * counts as its number; an identifier containing a digit matches ignoring spaces and hyphens ("GB 123 4567 89"); "yes"/"no"/"true"/"false" count as booleans; "", "n/a", "none" and "null" count as null.
   */
  lenient?: boolean;
}

type Opts = Required<Omit<JsonCompareOptions, 'aliases' | 'lenient'>> & Pick<JsonCompareOptions, 'aliases' | 'lenient'>;

interface Tally {
  matched: number;
  total: number;
  items: ScoreBreakdownItem[];
}

function normStr(s: string): string {
  return s.trim().toLowerCase().replace(/\s+/g, ' ');
}

/** Lenient reading of a presented value as the type the key expects (see `JsonCompareOptions.lenient`). */
function lenientValue(exp: unknown, act: unknown): unknown {
  if (typeof act !== 'string') return act;
  const t = act.trim();
  if (exp === null) return /^(?:n\/?a|none|null|-|—)?$/i.test(t) ? null : act;
  if (typeof exp === 'boolean') return /^(?:yes|true|y)$/i.test(t) ? true : /^(?:no|false|n)$/i.test(t) ? false : act;
  // Identifiers (anything with a digit: VAT numbers, sort codes, order numbers): spaces and hyphens are presentation.
  if (typeof exp === 'string' && /\d/.test(exp) && t.replace(/[\s-]/g, '').toLowerCase() === exp.replace(/[\s-]/g, '').toLowerCase()) return exp;
  if (typeof exp === 'number') {
    const m = /^(?:GBP|USD|EUR)?\s*([-−]?)\s*[£$€]?\s*([-−]?)(\d{1,3}(?:[, ]\d{3})+|\d+)(\.\d+)?\s*(?:GBP|USD|EUR)?$/i.exec(t);
    if (m && !(m[1] && m[2])) return Number(`${m[1] || m[2] ? '-' : ''}${m[3]!.replace(/[, ]/g, '')}${m[4] ?? ''}`);
  }
  return act;
}

function keyNorm(k: string): string {
  return k.toLowerCase().replace(/[\s_-]+/g, '');
}

function leafEqual(exp: unknown, act: unknown, tol: number, aliases?: Record<string, string>): boolean {
  if (typeof exp === 'string' && typeof act === 'string' && aliases) {
    const given = normStr(act);
    const alias = Object.entries(aliases).find(([k]) => normStr(k) === given);
    if (alias) act = alias[1];
  }
  if (exp === null) return act === null || act === undefined || act === '';
  if (typeof exp === 'number') {
    const a = typeof act === 'string' && act.trim() !== '' && Number.isFinite(Number(act)) ? Number(act) : act;
    return typeof a === 'number' && Math.abs(a - exp) <= tol * Math.max(1, Math.abs(exp));
  }
  if (typeof exp === 'string') return typeof act === 'string' ? normStr(act) === normStr(exp) : typeof act === 'number' && normStr(String(act)) === normStr(exp);
  if (typeof exp === 'boolean') return act === exp;
  return false;
}

function fmt(v: unknown): string {
  const s = JSON.stringify(v);
  return s === undefined ? 'missing' : s.length > 60 ? s.slice(0, 57) + '…' : s;
}

/**
 * `absent` marks a value whose enclosing object is missing from the answer (or is an object with none of
 * the expected keys, such as `{}`). A missing key counts as null only inside an object the answer really
 * gave; otherwise an empty `{}` or a left-out array item would earn the points of every expected null.
 */
function compare(exp: unknown, act: unknown, path: string, opts: Opts, out: Tally, absent = false): void {
  if (Array.isArray(exp)) {
    const arr = Array.isArray(act) ? act : [];
    // Length is one checkable fact of its own.
    out.total++;
    const lenOk = Array.isArray(act) && act.length === exp.length;
    if (lenOk) out.matched++;
    out.items.push({ label: `${path || '$'} has ${exp.length} items`, passed: lenOk, detail: lenOk ? undefined : `got ${Array.isArray(act) ? act.length : fmt(act)}` });
    if (opts.unorderedArrays) {
      const used = new Set<number>();
      exp.forEach((e, i) => {
        let best = -1;
        let bestTally: Tally | null = null;
        arr.forEach((a, j) => {
          if (used.has(j)) return;
          const t: Tally = { matched: 0, total: 0, items: [] };
          compare(e, a, `${path}[${i}]`, opts, t);
          if (!bestTally || t.matched > bestTally.matched) {
            bestTally = t;
            best = j;
          }
        });
        if (best >= 0 && bestTally) {
          used.add(best);
          const t = bestTally as Tally;
          out.matched += t.matched;
          out.total += t.total;
          out.items.push(...t.items);
        } else compare(e, undefined, `${path}[${i}]`, opts, out, true);
      });
    } else {
      exp.forEach((e, i) => compare(e, arr[i], `${path}[${i}]`, opts, out, absent || i >= arr.length));
    }
    return;
  }
  if (exp && typeof exp === 'object') {
    let obj = act && typeof act === 'object' && !Array.isArray(act) ? (act as Record<string, unknown>) : {};
    const keys = Object.keys(exp);
    if (opts.lenient) {
      // Keys written as "Invoice Number" or "invoiceNumber" still find "invoice_number".
      const remapped: Record<string, unknown> = { ...obj };
      for (const k of keys) {
        if (Object.prototype.hasOwnProperty.call(remapped, k)) continue;
        const hit = Object.keys(obj).find((g) => keyNorm(g) === keyNorm(k));
        if (hit !== undefined) remapped[k] = obj[hit];
      }
      obj = remapped;
    }
    if (keys.length === 0) {
      out.total++;
      const ok = act !== null && typeof act === 'object' && !Array.isArray(act) && Object.keys(act as object).length === 0;
      if (ok) out.matched++;
      out.items.push({ label: `${path || '$'} is {}`, passed: ok });
      return;
    }
    const present = !absent && keys.some((k) => Object.prototype.hasOwnProperty.call(obj, k));
    for (const k of keys) compare((exp as Record<string, unknown>)[k], obj[k], path ? `${path}.${k}` : k, opts, out, !present);
    return;
  }
  out.total++;
  const ok = !(absent && act === undefined) && leafEqual(exp, opts.lenient ? lenientValue(exp, act) : act, opts.numberTolerance, opts.aliases);
  if (ok) out.matched++;
  out.items.push({ label: path || '$', passed: ok, detail: ok ? undefined : `expected ${fmt(exp)}, got ${fmt(act)}` });
}

/** Field-level comparison: score = matched leaves / expected leaves. */
export function compareJson(expected: unknown, actual: unknown, options: JsonCompareOptions = {}): { score: number; items: ScoreBreakdownItem[]; matched: number; total: number } {
  const opts: Opts = { unorderedArrays: options.unorderedArrays ?? false, numberTolerance: options.numberTolerance ?? 1e-6, aliases: options.aliases, lenient: options.lenient };
  const tally: Tally = { matched: 0, total: 0, items: [] };
  compare(expected, actual, '', opts, tally);
  return { score: tally.total ? tally.matched / tally.total : 0, items: tally.items, matched: tally.matched, total: tally.total };
}
