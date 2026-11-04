/**
 * Cook for Twelve (life.recipe-shopping): checks a shopping basket against what the meal needs (after scaling,
 * unit conversion, merging and subtracting the cupboard; worked out by the generator and stored in the world) and
 * prices it with the shop's multibuy offers applied the way the prompt states.
 */
import type { PlanCheck, PlanVerifier, VerifierItem } from './types.ts';
import { field, gbp, readId, readNumber, readPence } from './common.ts';

export interface ShopProduct {
  id: string;
  name: string;
  ingredient: string;
  /** Pack size in the ingredient's unit (g, ml or each). */
  size: number;
  /** Pence. */
  price: number;
}

export interface ShopOffer {
  id: string;
  label: string;
  /** Products that count towards the offer. */
  products: string[];
  /** Any `qty` of them for `price` pence. */
  qty: number;
  price: number;
  /** At most this many deals per customer (further packs at their own price). */
  limit?: number;
}

export interface ShopWorld {
  /** What must be bought, per ingredient, in its unit (already net of the cupboard). */
  needs: Array<{ ingredient: string; unit: string; amount: number }>;
  products: ShopProduct[];
  offers: ShopOffer[];
}

/**
 * Till price of a basket: every product at its price, except that each offer groups qualifying packs, most expensive
 * first, into groups of `qty`, and each full group costs the offer price instead.
 */
export function basketPrice(w: ShopWorld, qty: Record<string, number>): number {
  let total = 0;
  const inOffer = new Set(w.offers.flatMap((o) => o.products));
  for (const p of w.products) if (!inOffer.has(p.id)) total += p.price * (qty[p.id] ?? 0);
  for (const o of w.offers) {
    const units: number[] = [];
    for (const id of o.products) {
      const p = w.products.find((x) => x.id === id)!;
      for (let i = 0; i < (qty[id] ?? 0); i++) units.push(p.price);
    }
    units.sort((a, b) => b - a);
    const groups = Math.min(Math.floor(units.length / o.qty), o.limit ?? Number.POSITIVE_INFINITY);
    total += groups * o.price + units.slice(groups * o.qty).reduce((s, x) => s + x, 0);
  }
  return total;
}

function fmtAmount(n: number, unit: string): string {
  const r = Math.round(n * 100) / 100;
  if (unit === 'g' && r >= 1000) return `${Math.round(r) / 1000} kg`;
  if (unit === 'ml' && r >= 1000) return `${Math.round(r) / 1000} litres`;
  return unit === 'each' ? `${r}` : `${r} ${unit}`;
}

export function checkShopping(w: ShopWorld, answer: Record<string, unknown>): PlanCheck {
  const items: VerifierItem[] = [];
  const stated = readPence(field(answer, 'total', 'totalcost', 'cost', 'totalprice'));
  const raw = field(answer, 'basket', 'shopping', 'shoppinglist', 'items', 'buy');
  const ids = w.products.map((p) => p.id);
  const names = new Map(w.products.map((p) => [p.id, p.name]));
  const qty: Record<string, number> = {};
  const list: unknown[] = Array.isArray(raw)
    ? raw
    : raw && typeof raw === 'object'
      ? Object.entries(raw as Record<string, unknown>).map(([k, v]) => ({ item: k, qty: v }))
      : [];
  if (!list.length) {
    items.push({ label: 'the basket lists what to buy', passed: false, hard: true, detail: 'no basket given' });
    return { feasible: false, items, stated };
  }
  for (const line of list) {
    const id = readId(field(line, 'item', 'product', 'id', 'name', 'code') ?? line, ids, names);
    const qRaw = field(line, 'qty', 'quantity', 'packs', 'count', 'number');
    const q = qRaw === undefined && typeof line === 'string' ? 1 : readNumber(qRaw);
    if (!id || q === null || !Number.isInteger(q) || q < 0) {
      items.push({ label: 'every basket line is a listed product and a whole number of packs', passed: false, hard: true, detail: JSON.stringify(line).slice(0, 60) });
      return { feasible: false, items, stated };
    }
    qty[id] = (qty[id] ?? 0) + q;
  }
  items.push({ label: 'every basket line is a listed product and a whole number of packs', passed: true, hard: true });
  for (const n of w.needs) {
    const have = w.products.filter((p) => p.ingredient === n.ingredient).reduce((s, p) => s + p.size * (qty[p.id] ?? 0), 0);
    const ok = have + 1e-9 >= n.amount;
    items.push({ label: `enough ${n.ingredient}`, passed: ok, hard: true, detail: `buys ${fmtAmount(have, n.unit)}, needs ${fmtAmount(n.amount, n.unit)}` });
  }
  const feasible = items.every((i) => i.passed || !i.hard);
  return { feasible, value: basketPrice(w, qty), stated, items };
}

export const shopping: PlanVerifier<ShopWorld> = {
  kind: 'plan',
  name: 'shopping',
  noun: 'shopping list',
  objective: 'min',
  answerKeys: ['basket', 'shoppinglist', 'shopping'],
  quotesTotal: true,
  marking: [
    'The basket must be listed products in whole packs, and must give at least the amount of every ingredient the meal still needs after scaling every recipe, converting the units, adding them up and taking off what is already in the cupboard. Running short of anything scores 0.',
    'The basket is priced at the till with the multibuy offers applied as stated (most expensive qualifying packs grouped first, within any per-customer limit). The cheapest basket that covers everything (found by trying every combination of packs) scores 100; one that covers everything but costs more scores 50 × (cheapest ÷ its cost)², always under 50.',
    'The total quoted must be the real till total: a missing or wrong total halves the score.',
  ],
  formatValue: gbp,
  check: checkShopping,
};
