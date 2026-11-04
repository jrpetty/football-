/**
 * Pack for the Flight (life.packing-limits): checks a packing plan against the airline's bag limits and fees, the
 * liquids rule, the battery and sharp-item rules and the traveller's must-bring list, and prices it: bag fees +
 * overweight fees + what it costs to replace at the destination everything left at home.
 */
import type { PlanCheck, PlanVerifier, VerifierItem } from './types.ts';
import { field, gbp, normKey, readId, readPence } from './common.ts';

export interface PackItem {
  id: string;
  name: string;
  kg: number;
  litres: number;
  /** Outside size in cm (any order). */
  dims: [number, number, number];
  /** Cost (pence) of buying it at the destination if left at home; null = must bring. */
  replace: number | null;
  /** A liquid, gel or paste: its container size in ml. */
  liquidMl?: number;
  /** Lithium battery (power bank, spare battery): never in a hold bag. */
  battery?: boolean;
  /** Sharp item (scissors, knife, tools): never in the cabin. */
  sharp?: boolean;
  /** Can be worn onto the plane instead of packed. */
  wearable?: boolean;
  /** A medicine or baby milk: in the cabin it is exempt from the 100 ml limit and the 1-litre bag. */
  medical?: boolean;
}

export interface PackBag {
  id: 'personal' | 'cabin' | 'hold1' | 'hold2';
  label: string;
  /** Largest outside size in cm. */
  maxDims: [number, number, number];
  litres: number;
  /** Hard weight limit in kg (null: none). */
  maxKg: number | null;
  /** Fee in pence, charged if the bag is used. */
  fee: number;
  /** Hold bags: weight included in the fee, and the fee per started kg above it. */
  freeKg?: number;
  overPerKg?: number;
  hold: boolean;
}

export interface PackWorld {
  items: PackItem[];
  bags: PackBag[];
  /** Cabin liquids: largest container and the one clear bag's total. */
  liquidMaxMl: number;
  liquidBagMl: number;
  /** Personal item and cabin bag together at most this many kg (when set). */
  cabinTotalKg?: number;
}

export type PackPlace = PackBag['id'] | 'wear' | 'leave';

function sortedDims(d: number[]): number[] {
  return d.slice().sort((a, b) => b - a);
}

export function fitsBag(item: PackItem, bag: PackBag): boolean {
  const a = sortedDims(item.dims);
  const b = sortedDims(bag.maxDims);
  return a.every((x, i) => x <= b[i]!);
}

/** Bag fees + overweight fees for the bags' contents (pence). */
export function bagCost(bag: PackBag, kg: number): number {
  if (kg <= 0) return 0;
  const over = bag.freeKg !== undefined && kg > bag.freeKg + 1e-9 ? Math.ceil(kg - bag.freeKg - 1e-9) : 0;
  return bag.fee + over * (bag.overPerKg ?? 0);
}

/** Read the plan into item → place. Unlisted items stay at home. */
function readPlan(w: PackWorld, answer: Record<string, unknown>): { place: Map<string, PackPlace>; problems: string[] } {
  const ids = w.items.map((i) => i.id);
  const names = new Map(w.items.map((i) => [i.id, i.name]));
  const place = new Map<string, PackPlace>();
  const problems: string[] = [];
  const put = (raw: unknown, where: PackPlace) => {
    const list = Array.isArray(raw) ? raw : raw === undefined || raw === null || raw === '' ? [] : typeof raw === 'string' ? raw.split(/\s*[,;]\s*/).filter(Boolean) : [raw];
    for (const x of list) {
      if (typeof x === 'string' && /^\s*(none|nothing|-|n\/a)\s*$/i.test(x)) continue;
      const id = readId(x, ids, names);
      if (!id) {
        problems.push(`unknown item ${JSON.stringify(x).slice(0, 40)}`);
        continue;
      }
      if (place.has(id) && place.get(id) !== where) problems.push(`${id} is in two places`);
      else if (place.has(id)) problems.push(`${id} is listed twice`);
      place.set(id, where);
    }
  };
  const holdBoth = field(answer, 'hold', 'holdbags', 'checkedbags', 'checked');
  if (Array.isArray(holdBoth) && holdBoth.length > 0 && holdBoth.every((x) => Array.isArray(x))) {
    if (holdBoth.length > 2) problems.push('more than two hold bags');
    holdBoth.slice(0, 2).forEach((bag, i) => put(bag, i === 0 ? 'hold1' : 'hold2'));
  } else if (holdBoth !== undefined) put(holdBoth, 'hold1');
  for (const [k, v] of Object.entries(answer)) {
    const n = normKey(k);
    if (n === 'personal' || n === 'personalitem' || n === 'underseat' || n === 'underseatbag') put(v, 'personal');
    else if (n === 'cabin' || n === 'cabinbag' || n === 'carryon' || n === 'handluggage') put(v, 'cabin');
    else if (n === 'hold1' || n === 'holdbag1' || n === 'holda' || n === 'holdbaga') put(v, 'hold1');
    else if (n === 'hold2' || n === 'holdbag2' || n === 'holdb' || n === 'holdbagb') put(v, 'hold2');
    else if (n === 'wear' || n === 'wearing' || n === 'worn' || n === 'wearonplane') put(v, 'wear');
    else if (n === 'leave' || n === 'leaveathome' || n === 'leftathome' || n === 'leavebehind' || n === 'buythere' || n === 'notpacked') put(v, 'leave');
  }
  return { place, problems };
}

export function checkPacking(w: PackWorld, answer: Record<string, unknown>): PlanCheck {
  const items: VerifierItem[] = [];
  const stated = readPence(field(answer, 'total', 'totalcost', 'cost', 'totalfees'));
  const { place, problems } = readPlan(w, answer);
  if (problems.length) {
    items.push({ label: 'every item is named once, in one place', passed: false, hard: true, detail: problems.slice(0, 3).join('; ') });
    return { feasible: false, items, stated };
  }
  items.push({ label: 'every item is named once, in one place', passed: true, hard: true });
  const where = (it: PackItem): PackPlace => place.get(it.id) ?? 'leave';
  let cost = 0;
  // Must-bring items.
  const left = w.items.filter((it) => where(it) === 'leave');
  const missing = left.filter((it) => it.replace === null);
  items.push({ label: 'every must-bring item comes', passed: missing.length === 0, hard: true, detail: missing.length ? `left at home: ${missing.map((i) => i.name).join(', ')}` : undefined });
  for (const it of left) cost += it.replace ?? 0;
  // Wearing.
  const badWear = w.items.filter((it) => where(it) === 'wear' && !it.wearable);
  items.push({ label: 'only wearable things are worn', passed: badWear.length === 0, hard: true, detail: badWear.length ? badWear.map((i) => i.name).join(', ') : undefined });
  // Bags.
  for (const bag of w.bags) {
    const inside = w.items.filter((it) => where(it) === bag.id);
    if (!inside.length) continue;
    const kg = Math.round(inside.reduce((s, it) => s + it.kg, 0) * 1000) / 1000;
    const litres = Math.round(inside.reduce((s, it) => s + it.litres, 0) * 1000) / 1000;
    const tooBig = inside.filter((it) => !fitsBag(it, bag));
    items.push({ label: `${bag.label}: everything fits its size`, passed: tooBig.length === 0, hard: true, detail: tooBig.length ? `too long: ${tooBig.map((i) => i.name).join(', ')}` : undefined });
    items.push({ label: `${bag.label}: within ${bag.litres} litres`, passed: litres <= bag.litres + 1e-9, hard: true, detail: `${litres} litres packed` });
    if (bag.maxKg !== null) items.push({ label: `${bag.label}: within ${bag.maxKg} kg`, passed: kg <= bag.maxKg + 1e-9, hard: true, detail: `${kg} kg packed` });
    if (bag.hold) {
      const batt = inside.filter((it) => it.battery);
      items.push({ label: `${bag.label}: no spare lithium batteries in the hold`, passed: batt.length === 0, hard: true, detail: batt.length ? batt.map((i) => i.name).join(', ') : undefined });
    } else {
      const sharp = inside.filter((it) => it.sharp);
      items.push({ label: `${bag.label}: no sharp items in the cabin`, passed: sharp.length === 0, hard: true, detail: sharp.length ? sharp.map((i) => i.name).join(', ') : undefined });
    }
    cost += bagCost(bag, kg);
  }
  // Personal item and cabin bag together.
  if (w.cabinTotalKg !== undefined) {
    const kg = Math.round(w.items.filter((it) => where(it) === 'personal' || where(it) === 'cabin').reduce((s, it) => s + it.kg, 0) * 1000) / 1000;
    items.push({ label: `personal item and cabin bag together within ${w.cabinTotalKg} kg`, passed: kg <= w.cabinTotalKg + 1e-9, hard: true, detail: `${kg} kg in the cabin` });
  }
  // Liquids in the cabin (medicines and baby milk are exempt).
  const cabinLiquids = w.items.filter((it) => it.liquidMl !== undefined && !it.medical && ['personal', 'cabin', 'wear'].includes(where(it)));
  const big = cabinLiquids.filter((it) => it.liquidMl! > w.liquidMaxMl);
  const totalMl = cabinLiquids.reduce((s, it) => s + it.liquidMl!, 0);
  items.push({
    label: `cabin liquids: each at most ${w.liquidMaxMl} ml, all in one ${w.liquidBagMl / 1000}-litre bag`,
    passed: big.length === 0 && totalMl <= w.liquidBagMl,
    hard: true,
    detail: big.length ? `too big for the cabin: ${big.map((i) => i.name).join(', ')}` : `${totalMl} ml in the cabin`,
  });
  const feasible = items.every((i) => i.passed || !i.hard);
  return { feasible, value: cost, stated, items };
}

export const packing: PlanVerifier<PackWorld> = {
  kind: 'plan',
  name: 'packing',
  noun: 'packing plan',
  objective: 'min',
  answerKeys: ['personal', 'cabin', 'hold', 'hold1', 'wear', 'leave'],
  quotesTotal: true,
  marking: [
    'Every bag is checked against the airline’s rules: item sizes, litres, weight limits, no spare lithium batteries in the hold, no sharp items in the cabin, cabin liquids at most 100 ml each in one 1-litre bag (medicines and baby milk exempt), any combined cabin weight limit, only wearable things worn, and every must-bring item comes. Breaking any rule scores 0.',
    'The plan is priced: bag fees + overweight fees + the cost of replacing at the destination everything left at home. The cheapest possible plan (proven by a computer search of every packing) scores 100; a legal but dearer plan scores 50 × (cheapest ÷ its cost)², always under 50.',
    'The total quoted must be the real total: a missing or wrong total halves the score.',
  ],
  formatValue: gbp,
  check: checkPacking,
};
