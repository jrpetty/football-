import type { ScoreBreakdownItem } from '../core/types.ts';

/**
 * Options of the `json` scorer (see docs/METHODOLOGY.md §5.1a). Every option is opt-in; with none set, a json test
 * is marked exactly as it always was: one point per expected leaf, strings trimmed and case-insensitive, numbers
 * within tolerance, array lengths checked, missing keys equal null.
 */
export interface JsonCompareOptions {
  unorderedArrays?: boolean;
  numberTolerance?: number;
  /** Accepted alternative spellings for string answers, e.g. { "color": "colour" } (case-insensitive). */
  aliases?: Record<string, string>;
  /**
   * Presentation never costs marks, content always does:
   *  - object keys match ignoring case, spaces, "_" and "-" ("Invoice Number" = "invoice_number");
   *  - a number may be written as text with a currency sign or code, thousands separators, a trailing "%" or
   *    accounting brackets ("£1,234.50", "GBP 1 234.50", "-£35", "(12.00)" = -12);
   *  - strings match ignoring case, spacing, "_"/"-" and a trailing full stop ("HOLD_PRICE" = "hold-price"); an
   *    identifier containing a digit also ignores spaces and hyphens ("GB 123 4567 89" = "GB123456789");
   *  - a date-time's "T" equals a space, ":00" seconds and a trailing "London" / "(UK time)" are ignored;
   *  - "yes"/"no"/"true"/"false" count as booleans; "", "none", "null", "n/a", "na", "nil", "-" count as null;
   *  - a one-item list equals its item.
   * Hedges are never forgiven: "12 or 13" is not a number and "approve or hold" is not "approve".
   */
  lenient?: boolean;
  /**
   * Collections marked as SETS, by dotted path from the root (e.g. "non_compliant", "shortlist"), mapped to the
   * points one item is worth. The key's value at that path is either an object keyed by item id (each item's own
   * fields are compared as usual, so the number should be its field count) or a list of ids (each id is worth the
   * number). The answer may give a list or an id-keyed object. Ids match loosely ("Clause 8.2" = "8.2",
   * "m 10001" = "M-10001"). A missing item loses its points and every extra item the answer adds costs the same
   * number of points, so listing everything scores no better than listing nothing. An empty key earns one point
   * when the answer lists nothing.
   */
  sets?: Record<string, number>;
  /**
   * Groupings marked PAIR BY PAIR, by dotted path: the key's value is a list of groups (lists of ids). Every pair of
   * ids that share a group in the key is one point, credited by pairwise F1 (2 x right pairs / (key pairs + answer
   * pairs)), so "merge everything" and "merge nothing" both score close to zero.
   */
  partitions?: string[];
  /**
   * Points per field, by dotted path (e.g. "messages.M07.category", or "actions" for everything under it): the
   * longest matching prefix wins (array indexes ignored), default 1. A set's items, extra items and a grouping's
   * pairs are worth their usual points times the weight of their path.
   */
  weights?: Record<string, number>;
  /**
   * Leaf patterns ("messages.*.category", "*" = one path segment) scored above a constant guess: those fields earn
   * only the points they get beyond the best answer that gives every one of them the same value, so labelling
   * everything alike earns nothing.
   */
  aboveConstant?: string[];
}

type Opts = Required<Pick<JsonCompareOptions, 'unorderedArrays' | 'numberTolerance' | 'lenient'>> & Pick<JsonCompareOptions, 'aliases' | 'sets' | 'partitions' | 'weights'>;

interface Leaf {
  path: string;
  exp: unknown;
  ok: boolean;
  w: number;
}

interface Tally {
  matched: number;
  total: number;
  items: ScoreBreakdownItem[];
  leaves: Leaf[];
}

/** The weight of a field: its own path or the longest weighted prefix of it (array indexes ignored). */
function weightOf(path: string, weights?: Record<string, number>): number {
  if (!weights) return 1;
  let p = path.replace(/\[\d+\]/g, '');
  for (;;) {
    if (weights[p] !== undefined) return weights[p]!;
    const i = p.lastIndexOf('.');
    if (i < 0) return 1;
    p = p.slice(0, i);
  }
}

function wl(label: string, w: number): string {
  return w === 1 ? label : `${label} (×${w})`;
}

function round(n: number): number {
  return Math.round(n * 10000) / 10000;
}

function normStr(s: string): string {
  return s.trim().toLowerCase().replace(/\s+/g, ' ');
}

/** Key spelling under `lenient`: case, spaces, "_" and "-" ignored. */
function normKey(k: string): string {
  return k.toLowerCase().replace(/[\s_\-]+/g, '');
}

/** Item-id spelling inside a set or grouping: ignores case, spaces, "_", "-", a leading "clause"/"line"/"item" word, "no."/"#" and a trailing full stop. */
export function normSetId(k: string): string {
  return String(k)
    .toLowerCase()
    .trim()
    .replace(/^(?:clause|cl\.?|section|sec\.?|line|item|id|ref)\s*/, '')
    .replace(/^(?:no\.?|number|#)\s*/, '')
    .replace(/[\s_\-]+/g, '')
    .replace(/\.+$/, '');
}

const LENIENT_NULL = new Set(['', 'none', 'null', 'n/a', 'na', '-', '—', 'nil']);

/** A number written as text: "£1,234.50", "GBP 1234.50", "-£35", "12.5%", "1 234", "(12.00)". Null when it is not one plain number. */
export function looseNumber(s: string): number | null {
  let t = s.trim().replace(/[−–]/g, '-');
  let neg = false;
  if (/^\(.*\)$/.test(t)) {
    neg = true;
    t = t.slice(1, -1).trim();
  }
  t = t.replace(/^(-?)\s*(?:gbp|usd|eur|£|\$|€)\s*/i, '$1').replace(/\s*(?:gbp|usd|eur|%)$/i, '');
  t = t.replace(/(?<=\d)[,\s  ](?=\d{3}(?!\d))/g, '');
  if (!/^-?\d+(?:\.\d+)?$/.test(t)) return null;
  const n = Number(t);
  return neg ? -n : n;
}

function looseStr(s: string): string {
  const t = s.trim().toLowerCase();
  const dt = /^(\d{4}-\d{2}-\d{2})[t\s]+(\d{1,2}):(\d{2})(?::00)?(?:\s*\(?(?:london|uk)(?:\s+time)?\)?)?$/.exec(t);
  if (dt) return `${dt[1]} ${dt[2]!.padStart(2, '0')}:${dt[3]}`;
  return t.replace(/[\s_\-]+/g, ' ').replace(/\.$/, '').trim();
}

function leafEqual(exp: unknown, act: unknown, opts: Opts): boolean {
  const { numberTolerance: tol, aliases } = opts;
  if (opts.lenient) {
    if (Array.isArray(act) && act.length === 1 && !Array.isArray(exp)) act = act[0];
    if (exp === null && typeof act === 'string' && LENIENT_NULL.has(act.trim().toLowerCase())) return true;
    if (typeof exp === 'boolean' && typeof act === 'string') {
      const t = act.trim().toLowerCase();
      if (/^(?:yes|true|y)$/.test(t)) act = true;
      else if (/^(?:no|false|n)$/.test(t)) act = false;
    }
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
      if (looseStr(given) === looseStr(exp)) return true;
      return /\d/.test(exp) && given.replace(/[\s-]/g, '').toLowerCase() === exp.replace(/[\s-]/g, '').toLowerCase();
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

/** The answer's value for an expected key: the exact key, or (lenient) the key spelled differently. */
function pick(obj: Record<string, unknown>, k: string, lenient: boolean): unknown {
  if (Object.prototype.hasOwnProperty.call(obj, k) || !lenient) return obj[k];
  const want = normKey(k);
  const hit = Object.keys(obj).find((a) => normKey(a) === want);
  return hit === undefined ? undefined : obj[hit];
}

/** The ids an answer gives for a set or grouping member list: list items, or the keys of an id-keyed object. */
function idsOf(v: unknown): string[] {
  if (Array.isArray(v)) return v.filter((x) => typeof x === 'string' || typeof x === 'number').map((x) => normSetId(String(x)));
  if (v && typeof v === 'object') return Object.keys(v).map(normSetId);
  return [];
}

function compareSet(exp: Record<string, unknown> | unknown[], act: unknown, path: string, opts: Opts, out: Tally, absent: boolean, cost: number): void {
  const isList = Array.isArray(exp);
  const obj = !absent && act && typeof act === 'object' && !Array.isArray(act) ? (act as Record<string, unknown>) : {};
  const givenIds = absent ? [] : idsOf(act);
  const given = new Map<string, string>();
  for (const k of Object.keys(obj)) if (!given.has(normSetId(k))) given.set(normSetId(k), k);
  const wanted = new Set<string>();
  const keys = isList ? (exp as unknown[]).map(String) : Object.keys(exp);
  const iw = cost * weightOf(path, opts.weights);
  for (const k of keys) {
    const id = normSetId(k);
    wanted.add(id);
    if (isList) {
      const ok = givenIds.includes(id);
      out.total += iw;
      if (ok) out.matched += iw;
      out.items.push({ label: wl(`${path}: ${k}`, iw), passed: ok, detail: ok ? undefined : 'missing' });
    } else {
      const ak = given.get(id);
      compare((exp as Record<string, unknown>)[k], ak === undefined ? undefined : obj[ak], path ? `${path}.${k}` : k, opts, out, ak === undefined);
    }
  }
  const extras = [...new Set(givenIds)].filter((k) => !wanted.has(k));
  const w = weightOf(path, opts.weights);
  if (keys.length === 0) {
    out.total += w;
    const ok = !absent && act !== undefined && act !== null && extras.length === 0;
    if (ok) out.matched += w;
    out.items.push({ label: wl(`${path} has no items`, w), passed: ok, detail: ok ? undefined : extras.length ? `got ${extras.length}` : 'missing' });
  }
  for (const k of extras) {
    out.total += cost * w;
    out.items.push({ label: `${path}: extra item ${JSON.stringify(k).slice(0, 40)} (not in the answer key)`, passed: false, detail: `costs ${cost * w} point${cost * w === 1 ? '' : 's'}` });
  }
}

function pairsOf(groups: unknown): Set<string> {
  const out = new Set<string>();
  for (const g of Array.isArray(groups) ? groups : []) {
    const ids = [...new Set(idsOf(g))].filter(Boolean).sort();
    for (let i = 0; i < ids.length; i++) for (let j = i + 1; j < ids.length; j++) out.add(`${ids[i]}|${ids[j]}`);
  }
  return out;
}

function comparePartition(exp: unknown[], act: unknown, path: string, opts: Opts, out: Tally, absent: boolean): void {
  const e = pairsOf(exp);
  const g = absent ? new Set<string>() : pairsOf(act);
  let inter = 0;
  for (const p of g) if (e.has(p)) inter++;
  const f1 = e.size + g.size === 0 ? (absent || act === undefined ? 0 : 1) : (2 * inter) / (e.size + g.size);
  const w = Math.max(1, e.size) * weightOf(path, opts.weights);
  out.total += w;
  out.matched += w * f1;
  out.items.push({ label: `${path}: ${inter} of ${e.size} same-group pairs found, ${g.size - inter} wrong pairs`, passed: f1 === 1, detail: f1 === 1 ? undefined : `pairwise F1 ${(f1 * 100).toFixed(0)}%` });
}

/**
 * `absent` marks a value whose enclosing object is missing from the answer (or is an object with none of
 * the expected keys, such as `{}`). A missing key counts as null only inside an object the answer really
 * gave; otherwise an empty `{}` or a left-out array item would earn the points of every expected null.
 */
function compare(exp: unknown, act: unknown, path: string, opts: Opts, out: Tally, absent = false): void {
  const setCost = opts.sets?.[path];
  if (setCost !== undefined && exp && typeof exp === 'object') {
    compareSet(exp as Record<string, unknown> | unknown[], act, path, opts, out, absent, setCost);
    return;
  }
  if (opts.partitions?.includes(path) && Array.isArray(exp)) {
    comparePartition(exp, act, path, opts, out, absent);
    return;
  }
  if (opts.lenient && Array.isArray(exp) && act !== undefined && act !== null && !Array.isArray(act) && exp.length === 1) act = [act];
  if (Array.isArray(exp)) {
    const arr = Array.isArray(act) ? act : [];
    // Length is one checkable fact of its own.
    const lw = weightOf(path, opts.weights);
    out.total += lw;
    const lenOk = Array.isArray(act) && act.length === exp.length;
    if (lenOk) out.matched += lw;
    out.items.push({ label: wl(`${path || '$'} has ${exp.length} items`, lw), passed: lenOk, detail: lenOk ? undefined : `got ${Array.isArray(act) ? act.length : fmt(act)}` });
    if (opts.unorderedArrays) {
      const used = new Set<number>();
      exp.forEach((e, i) => {
        let best = -1;
        let bestTally: Tally | null = null;
        arr.forEach((a, j) => {
          if (used.has(j)) return;
          const t: Tally = { matched: 0, total: 0, items: [], leaves: [] };
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
          out.leaves.push(...t.leaves);
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
      const ew = weightOf(path, opts.weights);
      out.total += ew;
      const ok = act !== null && typeof act === 'object' && !Array.isArray(act) && Object.keys(act as object).length === 0;
      if (ok) out.matched += ew;
      out.items.push({ label: wl(`${path || '$'} is {}`, ew), passed: ok });
      return;
    }
    const present = !absent && keys.some((k) => pick(obj, k, opts.lenient) !== undefined || Object.prototype.hasOwnProperty.call(obj, k));
    for (const k of keys) compare((exp as Record<string, unknown>)[k], pick(obj, k, opts.lenient), path ? `${path}.${k}` : k, opts, out, !present);
    return;
  }
  const w = weightOf(path, opts.weights);
  out.total += w;
  const ok = !(absent && act === undefined) && leafEqual(exp, act, opts);
  if (ok) out.matched += w;
  out.leaves.push({ path, exp, ok, w });
  out.items.push({ label: wl(path || '$', w), passed: ok, detail: ok ? undefined : `expected ${fmt(exp)}, got ${fmt(act)}` });
}

/** Points above the best constant guess for the fields matching each pattern (see `aboveConstant`). */
function applyAboveConstant(t: Tally, patterns: string[]): void {
  for (const pat of patterns) {
    const re = new RegExp(`^${pat.split('.').map((seg) => (seg === '*' ? '[^.]+' : seg.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))).join('\\.')}$`);
    const group = t.leaves.filter((l) => re.test(l.path));
    if (!group.length) continue;
    const total = group.reduce((a, l) => a + l.w, 0);
    const got = group.reduce((a, l) => a + (l.ok ? l.w : 0), 0);
    const byValue = new Map<string, number>();
    for (const l of group) byValue.set(JSON.stringify(l.exp), (byValue.get(JSON.stringify(l.exp)) ?? 0) + l.w);
    let base = 0;
    let baseValue = '';
    for (const [v, w] of byValue) if (w > base) [base, baseValue] = [w, v];
    t.matched += Math.max(0, got - base) - got;
    t.total += total - base - total;
    t.items.push({
      label: `${pat}: only points above labelling every one ${baseValue} count`,
      passed: got >= total,
      detail: `${round(got)} of ${round(total)} correct; ${round(base)} would come from the constant answer, so ${round(Math.max(0, got - base))} of ${round(total - base)} count`,
    });
  }
}

/** Field-level comparison: score = matched points / expected points (plus the cost of extra set items). */
export function compareJson(expected: unknown, actual: unknown, options: JsonCompareOptions = {}): { score: number; items: ScoreBreakdownItem[]; matched: number; total: number } {
  const opts: Opts = {
    unorderedArrays: options.unorderedArrays ?? false,
    numberTolerance: options.numberTolerance ?? 1e-6,
    lenient: options.lenient ?? false,
    aliases: options.aliases,
    sets: options.sets,
    partitions: options.partitions,
    weights: options.weights,
  };
  const tally: Tally = { matched: 0, total: 0, items: [], leaves: [] };
  compare(expected, actual, '', opts, tally);
  if (options.aboveConstant?.length) applyAboveConstant(tally, options.aboveConstant);
  return { score: tally.total > 0 ? Math.max(0, tally.matched) / tally.total : 0, items: tally.items, matched: round(tally.matched), total: round(tally.total) };
}

function atPath(v: unknown, path: string): unknown {
  let cur = v;
  for (const p of path.split('.')) {
    if (!cur || typeof cur !== 'object' || Array.isArray(cur)) return undefined;
    const o = cur as Record<string, unknown>;
    cur = Object.prototype.hasOwnProperty.call(o, p) ? o[p] : o[Object.keys(o).find((k) => normKey(k) === normKey(p)) ?? ''];
  }
  return cur;
}

/**
 * `forbidOverlap` ([answerPath, keyPath] pairs): the first id the answer lists under answerPath that the answer key
 * lists under keyPath (and not under answerPath itself), or null. A hit scores the whole case 0: it is used where
 * listing an item means obeying an instruction planted in the documents.
 */
export function forbiddenOverlap(expected: unknown, actual: unknown, pairs: Array<[string, string]>): { answerKey: string; item: string } | null {
  for (const [answerKey, keyKey] of pairs) {
    const allowed = new Set(idsOf(atPath(expected, answerKey)));
    const banned = new Set(idsOf(atPath(expected, keyKey)).filter((x) => !allowed.has(x)));
    const given = atPath(actual, answerKey);
    const raw = Array.isArray(given) ? given.map(String) : given && typeof given === 'object' ? Object.keys(given) : [];
    for (const item of raw) if (banned.has(normSetId(item))) return { answerKey, item };
  }
  return null;
}
