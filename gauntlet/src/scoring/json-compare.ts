import type { ScoreBreakdownItem } from '../core/types.ts';

export interface JsonCompareOptions {
  unorderedArrays?: boolean;
  numberTolerance?: number;
  /** Accepted alternative spellings for string answers, e.g. { "color": "colour" } (case-insensitive). */
  aliases?: Record<string, string>;
  /**
   * Format-tolerant comparison (the real-world packs): keys match ignoring case, spaces, "_" and "-"; a number may be
   * written as a string with a currency sign or code, thousands separators or a trailing "%" ("£1,234.50"); strings
   * match ignoring case, spacing, "_"/"-" and a trailing full stop; a date-time's "T" equals a space and a trailing
   * "London" / "(London time)" is ignored; "none",
   * "n/a" and "-" count as null; a one-item list equals its item. Only presentation is forgiven, never content.
   */
  loose?: boolean;
  /**
   * Objects (by dotted path from the root, e.g. "non_compliant") whose keys are item ids, mapped to the number of
   * fields one item is worth. Ids match loosely ("Clause 8.2" = "8.2", "l03" = "L03"). A missing item loses its
   * fields as usual, and every extra item the answer adds costs that many fields too, so listing everything
   * scores no better than listing nothing.
   */
  sets?: Record<string, number>;
}

type Opts = Required<Omit<JsonCompareOptions, 'aliases' | 'sets'>> & Pick<JsonCompareOptions, 'aliases' | 'sets'>;

interface Tally {
  matched: number;
  total: number;
  items: ScoreBreakdownItem[];
}

function normStr(s: string): string {
  return s.trim().toLowerCase().replace(/\s+/g, ' ');
}

/** Key spelling for loose matching: case, spaces, "_" and "-" ignored. */
function normKey(k: string): string {
  return k.toLowerCase().replace(/[\s_\-]+/g, '');
}

/** Item-id spelling inside a set: also ignores a leading "clause"/"section"/"line"/"item" word, "no."/"#" and a trailing full stop. */
export function normSetId(k: string): string {
  return k
    .toLowerCase()
    .trim()
    .replace(/^(?:clause|cl\.?|section|sec\.?|line|item|id|ref)\s*/, '')
    .replace(/^(?:no\.?|number|#)\s*/, '')
    .replace(/[\s_]+/g, '')
    .replace(/\.+$/, '');
}

const LOOSE_NULL = new Set(['', 'none', 'null', 'n/a', 'na', '-', '—', 'nil']);

/** A number written as text: "£1,234.50", "GBP 1234.50", "-£35", "12.5%", "1 234". Null when it is not one plain number. */
export function looseNumber(s: string): number | null {
  let t = s.trim().replace(/[−–]/g, '-');
  t = t.replace(/^(-?)\s*(?:gbp|usd|eur|£|\$|€)\s*/i, '$1').replace(/\s*(?:gbp|usd|eur|%)$/i, '');
  t = t.replace(/(?<=\d)[,\s\u00a0\u2009](?=\d{3}(?!\d))/g, '');
  if (!/^-?\d+(?:\.\d+)?$/.test(t)) return null;
  return Number(t);
}

function looseStr(s: string): string {
  const t = s.trim().toLowerCase();
  const dt = /^(\d{4}-\d{2}-\d{2})[t\s]+(\d{2}:\d{2})(?::00)?(?:\s*\(?(?:london|uk)(?:\s+time)?\)?)?$/.exec(t);
  if (dt) return `${dt[1]} ${dt[2]}`;
  return t.replace(/[\s_\-]+/g, ' ').replace(/\.$/, '').trim();
}

function leafEqual(exp: unknown, act: unknown, tol: number, aliases?: Record<string, string>, loose = false): boolean {
  if (loose) {
    if (Array.isArray(act) && act.length === 1 && !Array.isArray(exp)) act = act[0];
    if (exp === null && typeof act === 'string' && LOOSE_NULL.has(act.trim().toLowerCase())) return true;
    if (typeof exp === 'number' && typeof act === 'string') {
      const n = looseNumber(act);
      return n !== null && Math.abs(n - exp) <= tol * Math.max(1, Math.abs(exp));
    }
    if (typeof exp === 'string' && (typeof act === 'string' || typeof act === 'number')) {
      let given = String(act);
      if (aliases) {
        const alias = Object.entries(aliases).find(([k]) => looseStr(k) === looseStr(given));
        if (alias) given = alias[1];
      }
      return looseStr(given) === looseStr(exp);
    }
  }
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
/** The answer's value for an expected key: the exact key, or (loose) the key spelled differently. */
function pick(obj: Record<string, unknown>, k: string, loose: boolean): unknown {
  if (Object.prototype.hasOwnProperty.call(obj, k) || !loose) return obj[k];
  const want = normKey(k);
  const hit = Object.keys(obj).find((a) => normKey(a) === want);
  return hit === undefined ? undefined : obj[hit];
}

function compareSet(exp: Record<string, unknown>, act: unknown, path: string, opts: Opts, out: Tally, absent: boolean, cost: number): void {
  const obj = !absent && act && typeof act === 'object' && !Array.isArray(act) ? (act as Record<string, unknown>) : {};
  const given = new Map<string, string>();
  for (const k of Object.keys(obj)) if (!given.has(normSetId(k))) given.set(normSetId(k), k);
  const wanted = new Set<string>();
  const keys = Object.keys(exp);
  for (const k of keys) {
    const id = normSetId(k);
    wanted.add(id);
    const ak = given.get(id);
    compare(exp[k], ak === undefined ? undefined : obj[ak], path ? `${path}.${k}` : k, opts, out, ak === undefined);
  }
  const extras = Object.keys(obj).filter((k) => !wanted.has(normSetId(k)));
  if (keys.length === 0) {
    out.total++;
    const ok = !absent && act !== undefined && act !== null && extras.length === 0;
    if (ok) out.matched++;
    out.items.push({ label: `${path} has no items`, passed: ok, detail: ok ? undefined : extras.length ? `got ${extras.length}` : 'missing' });
  }
  for (const k of extras) {
    out.total += cost;
    out.items.push({ label: `${path}: extra item ${JSON.stringify(k).slice(0, 40)} (not in the answer key)`, passed: false, detail: `costs ${cost} field${cost === 1 ? '' : 's'}` });
  }
}

function compare(exp: unknown, act: unknown, path: string, opts: Opts, out: Tally, absent = false): void {
  const setCost = opts.sets?.[path];
  if (setCost !== undefined && exp && typeof exp === 'object' && !Array.isArray(exp)) {
    compareSet(exp as Record<string, unknown>, act, path, opts, out, absent, setCost);
    return;
  }
  if (opts.loose && Array.isArray(exp) && act !== undefined && act !== null && !Array.isArray(act) && exp.length === 1) act = [act];
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
    const obj = act && typeof act === 'object' && !Array.isArray(act) ? (act as Record<string, unknown>) : {};
    const keys = Object.keys(exp);
    if (keys.length === 0) {
      out.total++;
      const ok = act !== null && typeof act === 'object' && !Array.isArray(act) && Object.keys(act as object).length === 0;
      if (ok) out.matched++;
      out.items.push({ label: `${path || '$'} is {}`, passed: ok });
      return;
    }
    const present = !absent && keys.some((k) => pick(obj, k, opts.loose) !== undefined || Object.prototype.hasOwnProperty.call(obj, k));
    for (const k of keys) compare((exp as Record<string, unknown>)[k], pick(obj, k, opts.loose), path ? `${path}.${k}` : k, opts, out, !present);
    return;
  }
  out.total++;
  const ok = !(absent && act === undefined) && leafEqual(exp, act, opts.numberTolerance, opts.aliases, opts.loose);
  if (ok) out.matched++;
  out.items.push({ label: path || '$', passed: ok, detail: ok ? undefined : `expected ${fmt(exp)}, got ${fmt(act)}` });
}

/** Field-level comparison: score = matched leaves / expected leaves. */
export function compareJson(expected: unknown, actual: unknown, options: JsonCompareOptions = {}): { score: number; items: ScoreBreakdownItem[]; matched: number; total: number } {
  const opts: Opts = { unorderedArrays: options.unorderedArrays ?? false, numberTolerance: options.numberTolerance ?? 1e-6, aliases: options.aliases, loose: options.loose ?? false, sets: options.sets };
  const tally: Tally = { matched: 0, total: 0, items: [] };
  compare(expected, actual, '', opts, tally);
  return { score: tally.total ? tally.matched / tally.total : 0, items: tally.items, matched: tally.matched, total: tally.total };
}
