/**
 * What Do We Reorder? (work.stock-reorder): checks a purchase order against the stock plan. For every item the
 * checker replays the coming weeks (stock at the start of week 1, plus deliveries on their Mondays, minus each
 * week's forecast, all worked out by the generator from the prompt's stated rules and stored in the world) and
 * checks the safety stock from the week this order arrives until the week before that supplier's next delivery,
 * the storage limit on the day it arrives, whole cases, minimum order quantities, each supplier's minimum order
 * and the cash limit. The order is priced as the prompt states: case prices (with any bulk price), plus each
 * supplier's delivery charge when its goods come to less than its free-delivery threshold.
 */
import type { PlanCheck, PlanVerifier, VerifierItem } from './types.ts';
import { field, gbp, readId, readNumber, readPence } from './common.ts';

export interface ReorderSku {
  id: string;
  name: string;
  supplier: string;
  /** Units per case. */
  caseSize: number;
  /** Pence per case. */
  casePrice: number;
  /** Bulk price: `breakPrice` pence per case for an order of `breakCases` cases or more. */
  breakCases?: number;
  breakPrice?: number;
  /** Minimum order quantity in units (if the item is ordered at all). */
  moq: number;
  /** Most units the store can hold just after a delivery is unpacked. */
  max: number;
  /** Units in stock at the start of week 1. */
  start: number;
  /** Other open orders: units arriving on the Monday of `week`. */
  arrivals: Array<{ week: number; qty: number }>;
  /** Forecast demand per week (index 0 = week 1), as the prompt's rules give it. */
  demand: number[];
  /** Safety stock in units. */
  safety: number;
  /** The week this order arrives (its Monday). */
  arrive: number;
  /** The last week the order must cover (the week before this supplier's next delivery). */
  until: number;
}

export interface ReorderSupplier {
  id: string;
  name: string;
  /** Delivery charge in pence when the goods total is above 0 and below `freeOver`. */
  charge: number;
  freeOver: number;
  /** Smallest goods total (pence) the supplier accepts; 0 = none. */
  minOrder: number;
}

export interface ReorderWorld {
  skus: ReorderSku[];
  suppliers: ReorderSupplier[];
  /** Most the whole order may cost, in pence (goods plus delivery charges). */
  cash: number;
}

/** Pence for `units` of an item (a whole number of cases), with the bulk price applied as stated. */
export function goodsCost(s: ReorderSku, units: number): number {
  const cases = units / s.caseSize;
  const per = s.breakCases !== undefined && s.breakPrice !== undefined && cases >= s.breakCases ? s.breakPrice : s.casePrice;
  return cases * per;
}

/** Goods per supplier and the charges, for a quantity per item. */
export function orderCost(w: ReorderWorld, qty: Record<string, number>): { goods: Record<string, number>; charges: Record<string, number>; total: number } {
  const goods: Record<string, number> = {};
  const charges: Record<string, number> = {};
  for (const sup of w.suppliers) goods[sup.id] = 0;
  for (const s of w.skus) goods[s.supplier] = (goods[s.supplier] ?? 0) + goodsCost(s, qty[s.id] ?? 0);
  let total = 0;
  for (const sup of w.suppliers) {
    const g = goods[sup.id] ?? 0;
    charges[sup.id] = g > 0 && g < sup.freeOver ? sup.charge : 0;
    total += g + charges[sup.id]!;
  }
  return { goods, charges, total };
}

/** Replays one item's weeks with this order: the lowest stock (and its week) from arrival to cover end, and the stock just after this order is unpacked. */
export function replaySku(s: ReorderSku, q: number): { lowest: number; lowestWeek: number; unpacked: number; preArrivalLow: number } {
  let stock = s.start;
  let lowest = Infinity;
  let lowestWeek = s.arrive;
  let unpacked = 0;
  let preArrivalLow = Infinity;
  for (let wk = 1; wk <= s.until; wk++) {
    for (const a of s.arrivals) if (a.week === wk) stock += a.qty;
    if (wk === s.arrive) {
      stock += q;
      unpacked = stock;
    }
    stock -= s.demand[wk - 1] ?? 0;
    if (wk >= s.arrive && stock < lowest) {
      lowest = stock;
      lowestWeek = wk;
    }
    if (wk < s.arrive) preArrivalLow = Math.min(preArrivalLow, stock);
  }
  return { lowest, lowestWeek, unpacked, preArrivalLow };
}

/** Reads the order: {"SKU": units}, {"SKU": {"units": n}} / {"cases": n}, or a list of {sku, units|qty|cases}. */
function readOrder(w: ReorderWorld, raw: unknown): { qty: Record<string, number>; bad?: string } {
  const ids = w.skus.map((s) => s.id);
  const names = new Map(w.skus.map((s) => [s.id, s.name]));
  const qty: Record<string, number> = {};
  const lines: Array<[unknown, unknown]> = Array.isArray(raw)
    ? raw.map((l) => [field(l, 'sku', 'item', 'id', 'code', 'product') ?? l, l])
    : raw && typeof raw === 'object'
      ? Object.entries(raw as Record<string, unknown>)
      : [];
  if (!lines.length && !(raw && typeof raw === 'object')) return { qty, bad: 'no order given' };
  for (const [k, v] of lines) {
    const id = readId(k, ids, names);
    if (!id) return { qty, bad: `"${String(typeof k === 'object' ? JSON.stringify(k) : k).slice(0, 40)}" is not one of the listed items` };
    const sku = w.skus.find((s) => s.id === id)!;
    let units: number | null = null;
    if (v && typeof v === 'object' && !Array.isArray(v)) {
      const u = field(v, 'units', 'qty', 'quantity', 'order', 'orderunits', 'unitsordered');
      const c = field(v, 'cases', 'casesordered', 'packs');
      if (u !== undefined) units = readNumber(u);
      else if (c !== undefined) {
        const n = readNumber(c);
        units = n === null ? null : n * sku.caseSize;
      }
    } else units = readNumber(v);
    if (units === null || !Number.isInteger(units) || units < 0) return { qty, bad: `${id}: ${JSON.stringify(v).slice(0, 40)} is not a whole number of units` };
    qty[id] = (qty[id] ?? 0) + units;
  }
  return { qty };
}

export function checkReorder(w: ReorderWorld, answer: Record<string, unknown>): PlanCheck {
  const items: VerifierItem[] = [];
  const stated = readPence(field(answer, 'total', 'totalcost', 'cost', 'ordertotal', 'totalspend'));
  const raw = field(answer, 'order', 'orders', 'reorder', 'purchaseorder', 'quantities');
  if (raw === undefined || raw === null) {
    items.push({ label: 'the order lists a quantity per item', passed: false, hard: true, detail: 'no order given' });
    return { feasible: false, items, stated };
  }
  const { qty, bad } = readOrder(w, raw);
  items.push({ label: 'every line is a listed item and a whole number of units', passed: !bad, hard: true, detail: bad });
  if (bad) return { feasible: false, items, stated };

  const fail = (xs: string[]) => (xs.length ? xs.slice(0, 4).join('; ') + (xs.length > 4 ? ` (+${xs.length - 4} more)` : '') : undefined);
  const notCases: string[] = [];
  const underMoq: string[] = [];
  const short: string[] = [];
  const over: string[] = [];
  for (const s of w.skus) {
    const q = qty[s.id] ?? 0;
    if (q % s.caseSize !== 0) notCases.push(`${s.id}: ${q} is not a whole number of cases of ${s.caseSize}`);
    if (q > 0 && q < s.moq) underMoq.push(`${s.id}: ${q} is under the minimum of ${s.moq}`);
    const r = replaySku(s, q);
    if (r.lowest < s.safety) short.push(`${s.id}: falls to ${r.lowest} at the end of week ${r.lowestWeek} (safety stock ${s.safety})`);
    if (q > 0 && r.unpacked > s.max) over.push(`${s.id}: ${r.unpacked} after unpacking (room for ${s.max})`);
  }
  items.push({ label: 'whole cases only', passed: !notCases.length, hard: true, detail: fail(notCases) });
  items.push({ label: 'minimum order quantities respected', passed: !underMoq.length, hard: true, detail: fail(underMoq) });
  items.push({ label: 'no item falls below its safety stock before the next delivery', passed: !short.length, hard: true, detail: fail(short) });
  items.push({ label: 'nothing more than the store can hold', passed: !over.length, hard: true, detail: fail(over) });

  const cost = orderCost(w, qty);
  const belowMin = w.suppliers.filter((s) => s.minOrder > 0 && (cost.goods[s.id] ?? 0) > 0 && (cost.goods[s.id] ?? 0) < s.minOrder).map((s) => `${s.name}: goods ${gbp(cost.goods[s.id]!)} under the ${gbp(s.minOrder)} minimum`);
  if (w.suppliers.some((s) => s.minOrder > 0)) items.push({ label: 'every supplier’s minimum order value met', passed: !belowMin.length, hard: true, detail: fail(belowMin) });
  items.push({ label: `within the cash limit of ${gbp(w.cash)}`, passed: cost.total <= w.cash, hard: true, detail: `order costs ${gbp(cost.total)}` });
  const feasible = items.every((i) => i.passed || !i.hard);
  return { feasible, value: cost.total, stated, items };
}

export const reorder: PlanVerifier<ReorderWorld> = {
  kind: 'plan',
  name: 'reorder',
  noun: 'order',
  objective: 'min',
  answerKeys: ['order', 'reorder', 'purchaseorder'],
  quotesTotal: true,
  marking: [
    'Every item’s stock is replayed week by week with the order: today’s stock, plus open orders and this order on the Mondays they arrive, minus each week’s forecast worked out by the prompt’s rules. From the week this order arrives until the week before that supplier’s next delivery it must never fall below the safety stock, and it must fit in the store when unpacked.',
    'Whole cases only, every minimum order quantity and supplier minimum met, and the whole order within the cash limit. Any broken rule scores 0 (the shop would run out, or the order would be refused).',
    'The order is priced with the case prices, bulk prices and delivery charges as stated. The cheapest order that keeps every rule (found by an exact search over every case count) scores 100; a workable but dearer order scores 50 × (cheapest ÷ its cost)², always under 50. The total must be quoted: a missing or wrong total halves the score.',
  ],
  formatValue: gbp,
  check: checkReorder,
};
