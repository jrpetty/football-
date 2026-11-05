/**
 * Numbers for the Board (work.board-report) and What Do We Reorder? (work.stock-reorder).
 *
 * Board: the finance director's memo and the three exports read as spreadsheets; twelve KPIs, the model's against
 * the key's. Reorder: the item list as the shop has it (stock, case, MOQ, room), and the order replayed week by
 * week with the checker's own world (forecast, safety stock, deliveries) for the model's order and the cheapest one.
 */
import type { CaseVisualInput } from '../../../../../../src/presenter/visuals/common.ts';
import { orderCost, replaySku, type ReorderWorld } from '../../../../../../src/scoring/verifiers/reorder.ts';
import { isObj, keyOf, num, pick, readAnswer } from './common.ts';

// ───────────────────────────── Board ─────────────────────────────

export interface Grid {
  title: string;
  head: string[];
  rows: string[][];
}

export interface BoardQuestion {
  company: string;
  from: string;
  quarter: string;
  memo: string[];
  grids: Grid[];
}

export function boardQuestion(prompt: string): BoardQuestion | null {
  if (!/KPI DEFINITIONS/.test(prompt)) return null;
  const from = prompt.match(/^From:\s*(.+)$/m)?.[1] ?? '';
  const company = from.match(/, ([^)]+)\)/)?.[1] ?? '';
  const quarter = prompt.match(/\(quarter = ([^)]+)\)/)?.[1] ?? '';
  const memo = (prompt.split(/KPI DEFINITIONS[^\n]*\n/)[1]?.split(/\n\nEXPORT/)[0] ?? '').split('\n').filter((l) => /^\d+\.|^Rounding/.test(l));
  const grids: Grid[] = [];
  for (const m of prompt.matchAll(/\nEXPORT ([^:]+): ([A-Z ]+?) \(([^)]+)\)\n([\s\S]*?)(?=\n\n)/g)) {
    grids.push({ title: `Export ${m[1]}: ${m[2]!.toLowerCase()}`, head: m[3]!.split(/,\s*/), rows: m[4]!.split('\n').map((l) => l.split(/,\s*/)) });
  }
  return { company, from, quarter, memo, grids };
}

export const KPI_LABEL: Record<string, string> = {
  net_revenue: 'Net revenue',
  gross_sales: 'Gross sales',
  refund_rate_pct: 'Refund rate',
  gross_margin_pct: 'Gross margin',
  orders: 'Orders',
  average_order_value: 'Average order value',
  active_customers: 'Active customers',
  net_revenue_growth_pct: 'Revenue growth',
  average_fte: 'Average FTE',
  revenue_per_fte: 'Revenue per FTE',
  payroll_cost: 'Payroll cost',
  cash_conversion_pct: 'Cash conversion',
};

export function kpiText(k: string, v: number | null): string {
  if (v === null) return '—';
  if (/pct$/.test(k)) return `${v.toLocaleString('en-GB', { maximumFractionDigits: 1 })}%`;
  if (k === 'orders' || k === 'active_customers') return v.toLocaleString('en-GB');
  if (k === 'average_fte') return v.toLocaleString('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  if (k === 'revenue_per_fte') return `£${Math.round(v).toLocaleString('en-GB')}`;
  return `£${v.toLocaleString('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

export interface KpiRow {
  key: string;
  label: string;
  want: number | null;
  got: number | null;
  ok: boolean | null;
}

export function boardAnswer(input: CaseVisualInput): { answered: boolean; rows: KpiRow[] } {
  const key = keyOf(input) ?? {};
  const ra = input.replies.length ? readAnswer(input) : null;
  const ans = ra?.parsed;
  const answered = isObj(ans);
  const rows = Object.keys(key).map((k) => {
    const want = num(key[k]);
    const got = answered ? num(pick(ans, k)) : null;
    return { key: k, label: KPI_LABEL[k] ?? k, want, got, ok: !answered ? null : (ra!.verdict(k) ?? (got !== null && want !== null && Math.abs(got - want) <= 1e-9 * Math.max(1, Math.abs(want)))) };
  });
  return { answered, rows };
}

// ───────────────────────────── Reorder ─────────────────────────────

export interface ReorderQuestion {
  shop: string;
  today: string;
  suppliers: string[];
  items: Array<{ sku: string; name: string; supplier: string; caseSize: string; price: string; moq: string; max: string; stock: string; open: string }>;
  cash: string;
  history: { weeks: string[]; rows: Map<string, string[]> };
}

export function reorderQuestion(prompt: string): ReorderQuestion | null {
  if (!/\nITEMS \(MOQ/.test(prompt)) return null;
  const items = (prompt.split(/\nITEMS[^\n]*\n/)[1]?.split(/\n\n/)[0] ?? '')
    .split('\n')
    .slice(1)
    .map((l) => l.split('|').map((x) => x.trim()))
    .filter((c) => c.length >= 9)
    .map(([sku, name, supplier, caseSize, price, moq, max, stock, open]) => ({ sku: sku!, name: name!, supplier: supplier!, caseSize: caseSize!, price: price!, moq: moq!, max: max!, stock: stock!, open: open! }));
  const hist = (prompt.split(/\nSALES HISTORY[^\n]*\n/)[1]?.split(/\n\n/)[0] ?? '').split('\n');
  const weeks = (hist[0] ?? '').split('|').slice(1).map((x) => x.trim());
  const rows = new Map(hist.slice(1).map((l) => l.split('|').map((x) => x.trim())).map((c) => [c[0]!, c.slice(1)]));
  const suppliers = (prompt.split(/\nSUPPLIERS\n/)[1]?.split(/\n\n/)[0] ?? '').split('\n').filter((l) => l.startsWith('- ')).map((l) => l.slice(2));
  return {
    shop: prompt.match(/stock order for ([^?]+?)(?: in [A-Z][a-z]+)?\?/)?.[1] ?? prompt.match(/stock order for ([^?]+)\?/)?.[1] ?? '',
    today: prompt.match(/Today is ([^.]+)\./)?.[1] ?? '',
    suppliers,
    items,
    cash: prompt.match(/must not cost more than (£[\d,.]+)/)?.[1] ?? '',
    history: { weeks, rows },
  };
}

export interface ReorderRow {
  sku: string;
  name: string;
  supplier: string;
  caseSize: number;
  start: number;
  forecast: number;
  safety: number;
  max: number;
  best: number;
  got: number | null;
  /** Lowest projected stock (and when) with the model's order and with the best one. */
  lowGot: number | null;
  lowBest: number;
  unpackedGot: number | null;
  /** The model's quantity breaks a rule for this item. */
  broken: string | null;
}

export interface ReorderAnswer {
  answered: boolean;
  rows: ReorderRow[];
  bestTotal: number;
  modelTotal: number | null;
  /** What the model's order really costs (pence → pounds), priced as the prompt states. */
  modelCost: number | null;
  statedOk: boolean | null;
  checks: Array<{ label: string; passed: boolean; detail?: string }>;
}

export function reorderAnswer(input: CaseVisualInput): ReorderAnswer | null {
  const key = keyOf(input);
  const w = key && isObj(key.world) ? (key.world as unknown as ReorderWorld) : null;
  const ref = key && isObj(key.reference) ? (key.reference as { order: Record<string, number>; total: number }) : null;
  if (!w || !ref || !Array.isArray(w.skus)) return null;
  const ra = input.replies.length ? readAnswer(input) : null;
  const ans = ra?.parsed;
  const answered = isObj(ans) && pick(ans, 'order') !== undefined;
  const order = answered ? pick(ans, 'order') : undefined;
  const qtyOf = (id: string): number | null => {
    if (!answered) return null;
    const v = isObj(order) ? pick(order, id) : undefined;
    if (v === undefined) return 0;
    if (isObj(v)) return num(pick(v, 'units') ?? pick(v, 'qty')) ?? (num(pick(v, 'cases')) ?? 0) * (w.skus.find((s) => s.id === id)?.caseSize ?? 1);
    return num(v);
  };
  const rows: ReorderRow[] = w.skus.map((s) => {
    const best = ref.order[s.id] ?? 0;
    const got = qtyOf(s.id);
    const rb = replaySku(s, best);
    const rg = got === null ? null : replaySku(s, got);
    let broken: string | null = null;
    if (got !== null && rg) {
      if (got % s.caseSize !== 0) broken = 'not whole cases';
      else if (got > 0 && got < s.moq) broken = `under the minimum of ${s.moq}`;
      else if (rg.lowest < s.safety) broken = `falls to ${rg.lowest}, below safety stock ${s.safety}`;
      else if (got > 0 && rg.unpacked > s.max) broken = `${rg.unpacked} won’t fit (room for ${s.max})`;
    }
    return { sku: s.id, name: s.name, supplier: s.supplier, caseSize: s.caseSize, start: s.start, forecast: s.demand[0] ?? 0, safety: s.safety, max: s.max, best, got, lowGot: rg?.lowest ?? null, lowBest: rb.lowest, unpackedGot: rg?.unpacked ?? null, broken };
  });
  const qty = Object.fromEntries(rows.map((r) => [r.sku, r.got ?? 0]));
  const modelCost = answered ? orderCost(w, qty).total / 100 : null;
  const modelTotal = answered ? num(pick(ans, 'total')) : null;
  return {
    answered,
    rows,
    bestTotal: ref.total,
    modelTotal,
    modelCost,
    statedOk: answered ? modelTotal !== null && modelCost !== null && Math.abs(modelTotal - modelCost) < 0.005 : null,
    checks: (input.detail.items ?? []).map((i) => ({ label: i.label, passed: i.passed, detail: i.detail })),
  };
}
