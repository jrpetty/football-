import type { ScoreBreakdownItem } from '../core/types.ts';

export interface JsonCompareOptions {
  unorderedArrays?: boolean;
  numberTolerance?: number;
}

interface Tally {
  matched: number;
  total: number;
  items: ScoreBreakdownItem[];
}

function normStr(s: string): string {
  return s.trim().toLowerCase().replace(/\s+/g, ' ');
}

function leafEqual(exp: unknown, act: unknown, tol: number): boolean {
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

function compare(exp: unknown, act: unknown, path: string, opts: Required<JsonCompareOptions>, out: Tally): void {
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
        } else compare(e, undefined, `${path}[${i}]`, opts, out);
      });
    } else {
      exp.forEach((e, i) => compare(e, arr[i], `${path}[${i}]`, opts, out));
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
    for (const k of keys) compare((exp as Record<string, unknown>)[k], obj[k], path ? `${path}.${k}` : k, opts, out);
    return;
  }
  out.total++;
  const ok = leafEqual(exp, act, opts.numberTolerance);
  if (ok) out.matched++;
  out.items.push({ label: path || '$', passed: ok, detail: ok ? undefined : `expected ${fmt(exp)}, got ${fmt(act)}` });
}

/** Field-level comparison: score = matched leaves / expected leaves. */
export function compareJson(expected: unknown, actual: unknown, options: JsonCompareOptions = {}): { score: number; items: ScoreBreakdownItem[]; matched: number; total: number } {
  const opts: Required<JsonCompareOptions> = { unorderedArrays: options.unorderedArrays ?? false, numberTolerance: options.numberTolerance ?? 1e-6 };
  const tally: Tally = { matched: 0, total: 0, items: [] };
  compare(expected, actual, '', opts, tally);
  return { score: tally.total ? tally.matched / tally.total : 0, items: tally.items, matched: tally.matched, total: tally.total };
}
