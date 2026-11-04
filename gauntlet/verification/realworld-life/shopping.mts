/**
 * Cook for Twelve (life.recipe-shopping): recipes, cupboard and shop generator + exact solver.
 *
 * Quantities are exact fractions (Q): each recipe line is converted to grams, millilitres or a count with the
 * conversion table printed in the prompt, scaled by guests ÷ servings, added up across recipes, and the cupboard is
 * taken off. Packs are whole, so the amount to buy is the next whole unit up. The cheapest basket is found by
 * trying every combination of pack counts (jointly for products linked by a multibuy offer).
 */
import { Q, Rng, money, pounds, ONE_ANSWER } from './common.mts';
import { basketPrice, type ShopOffer, type ShopProduct, type ShopWorld } from '../../src/scoring/verifiers/shopping.ts';

type Unit = 'g' | 'kg' | 'ml' | 'l' | 'cup' | 'tbsp' | 'tsp' | 'oz' | 'lb' | 'pint' | 'tin' | 'each';

interface Ingredient {
  key: string;
  label: string;
  base: 'g' | 'ml' | 'each';
  /** grams (or ml) per cup / tbsp / tsp for this ingredient, when recipes use them. */
  cup?: number;
  tbsp?: number;
  tsp?: number;
}

const ING: Record<string, Ingredient> = {
  flour: { key: 'flour', label: 'plain flour', base: 'g', cup: 125, tbsp: 8 },
  sugar: { key: 'sugar', label: 'caster sugar', base: 'g', cup: 200, tbsp: 12, tsp: 4 },
  butter: { key: 'butter', label: 'butter', base: 'g', cup: 225, tbsp: 14 },
  eggs: { key: 'eggs', label: 'eggs', base: 'each' },
  milk: { key: 'milk', label: 'milk', base: 'ml', cup: 240, tbsp: 15 },
  cream: { key: 'cream', label: 'double cream', base: 'ml', cup: 240, tbsp: 15 },
  cheddar: { key: 'cheddar', label: 'cheddar', base: 'g', cup: 100 },
  rice: { key: 'rice', label: 'long-grain rice', base: 'g', cup: 185 },
  onions: { key: 'onions', label: 'onions', base: 'each' },
  lemons: { key: 'lemons', label: 'lemons', base: 'each' },
  tomatoes: { key: 'tomatoes', label: 'chopped tomatoes', base: 'g' },
  coconut: { key: 'coconut', label: 'coconut milk', base: 'ml' },
  chickpeas: { key: 'chickpeas', label: 'chickpeas', base: 'g' },
  beans: { key: 'beans', label: 'kidney beans', base: 'g' },
  mince: { key: 'mince', label: 'beef mince', base: 'g' },
  lamb: { key: 'lamb', label: 'lamb mince', base: 'g' },
  chicken: { key: 'chicken', label: 'chicken thighs', base: 'g' },
  potatoes: { key: 'potatoes', label: 'potatoes', base: 'g' },
  carrots: { key: 'carrots', label: 'carrots', base: 'g' },
  lentils: { key: 'lentils', label: 'red lentils', base: 'g', cup: 190 },
  macaroni: { key: 'macaroni', label: 'macaroni', base: 'g' },
  oil: { key: 'oil', label: 'olive oil', base: 'ml', tbsp: 15, tsp: 5 },
};

/** Fixed conversions (printed in every prompt). */
const FIXED: Record<string, [number, number]> = { oz: [28, 1], lb: [454, 1], pint: [568, 1], tin: [400, 1], kg: [1000, 1], l: [1000, 1] };

interface Line {
  ing: string;
  /** Amount as a fraction [num, den]. */
  q: [number, number];
  unit: Unit;
  text: string;
}

interface Recipe {
  name: string;
  serves: number;
  lines: Line[];
}

function L(ing: string, q: number | [number, number], unit: Unit, text: string): Line {
  return { ing, q: typeof q === 'number' ? [q, 1] : q, unit, text };
}

const RECIPES: Record<string, Recipe> = {
  chilli: { name: 'Chilli con carne', serves: 4, lines: [L('mince', 500, 'g', '500 g beef mince'), L('onions', 2, 'each', '2 onions'), L('tomatoes', 2, 'tin', '2 tins chopped tomatoes'), L('beans', 1, 'tin', '1 tin kidney beans'), L('rice', 300, 'g', '300 g long-grain rice'), L('oil', 2, 'tbsp', '2 tbsp olive oil')] },
  curry: { name: 'Chickpea and chicken curry', serves: 6, lines: [L('chicken', 900, 'g', '900 g chicken thighs'), L('onions', 3, 'each', '3 onions'), L('coconut', 800, 'ml', '800 ml coconut milk'), L('chickpeas', 1, 'tin', '1 tin chickpeas'), L('tomatoes', 1, 'tin', '1 tin chopped tomatoes'), L('rice', 450, 'g', '450 g long-grain rice')] },
  drizzle: { name: 'Lemon drizzle cake (American recipe)', serves: 8, lines: [L('flour', [3, 2], 'cup', '1½ cups plain flour'), L('sugar', 1, 'cup', '1 cup caster sugar'), L('butter', [1, 2], 'cup', '½ cup butter'), L('eggs', 2, 'each', '2 eggs'), L('lemons', 2, 'each', '2 lemons'), L('milk', [1, 3], 'cup', '⅓ cup milk')] },
  yorkies: { name: 'Yorkshire puddings', serves: 6, lines: [L('flour', 140, 'g', '140 g plain flour'), L('eggs', 4, 'each', '4 eggs'), L('milk', 200, 'ml', '200 ml milk'), L('oil', 4, 'tbsp', '4 tbsp olive oil')] },
  shepherds: { name: 'Shepherd’s pie (Grandma’s imperial recipe)', serves: 4, lines: [L('lamb', 1, 'lb', '1 lb lamb mince'), L('potatoes', 2, 'lb', '2 lb potatoes'), L('onions', 1, 'each', '1 onion'), L('carrots', 8, 'oz', '8 oz carrots'), L('butter', 2, 'oz', '2 oz butter'), L('milk', [1, 4], 'pint', '¼ pint milk')] },
  dal: { name: 'Tarka dal', serves: 6, lines: [L('lentils', 300, 'g', '300 g red lentils'), L('tomatoes', 1, 'tin', '1 tin chopped tomatoes'), L('coconut', 400, 'ml', '400 ml coconut milk'), L('onions', 2, 'each', '2 onions'), L('butter', 30, 'g', '30 g butter')] },
  pancakes: { name: 'American pancakes', serves: 4, lines: [L('flour', [3, 2], 'cup', '1½ cups plain flour'), L('milk', [5, 4], 'cup', '1¼ cups milk'), L('eggs', 1, 'each', '1 egg'), L('butter', 3, 'tbsp', '3 tbsp butter, melted'), L('sugar', 1, 'tbsp', '1 tbsp caster sugar')] },
  sponge: { name: 'Victoria sponge', serves: 10, lines: [L('butter', 200, 'g', '200 g butter'), L('sugar', 200, 'g', '200 g caster sugar'), L('eggs', 4, 'each', '4 eggs'), L('flour', 200, 'g', '200 g plain flour'), L('milk', 2, 'tbsp', '2 tbsp milk'), L('cream', 150, 'ml', '150 ml double cream')] },
  jollof: { name: 'Jollof rice', serves: 6, lines: [L('rice', 400, 'g', '400 g long-grain rice'), L('tomatoes', 2, 'tin', '2 tins chopped tomatoes'), L('onions', 2, 'each', '2 onions'), L('chicken', 600, 'g', '600 g chicken thighs'), L('oil', 3, 'tbsp', '3 tbsp olive oil')] },
  maccheese: { name: 'Mac and cheese (American recipe)', serves: 4, lines: [L('macaroni', 8, 'oz', '8 oz macaroni'), L('milk', 2, 'cup', '2 cups milk'), L('cheddar', 2, 'cup', '2 cups grated cheddar'), L('butter', 3, 'tbsp', '3 tbsp butter'), L('flour', 3, 'tbsp', '3 tbsp plain flour')] },
};

/** Grams / ml / count of one recipe line, exactly. */
function lineAmount(l: Line): Q {
  const ing = ING[l.ing]!;
  const q = new Q(l.q[0], l.q[1]);
  if (l.unit === 'g' || l.unit === 'ml' || l.unit === 'each') return q;
  if (l.unit === 'cup' || l.unit === 'tbsp' || l.unit === 'tsp') {
    const per = ing[l.unit];
    if (per === undefined) throw new Error(`${l.ing} has no ${l.unit} conversion`);
    return q.mul(new Q(per));
  }
  const f = FIXED[l.unit];
  if (!f) throw new Error(`unit ${l.unit}`);
  return q.mul(new Q(f[0], f[1]));
}

interface Pack {
  ing: string;
  size: number;
  label: string;
  price: number;
}

/** Shop shelf: pack sizes per ingredient with base prices in pence (jittered per case). */
const SHELF: Pack[] = [
  { ing: 'flour', size: 500, label: 'Plain flour 500 g', price: 75 },
  { ing: 'flour', size: 1500, label: 'Plain flour 1.5 kg', price: 145 },
  { ing: 'sugar', size: 500, label: 'Caster sugar 500 g', price: 95 },
  { ing: 'sugar', size: 1000, label: 'Caster sugar 1 kg', price: 165 },
  { ing: 'butter', size: 250, label: 'Butter 250 g', price: 210 },
  { ing: 'butter', size: 500, label: 'Butter 500 g', price: 405 },
  { ing: 'eggs', size: 6, label: 'Free-range eggs x6', price: 175 },
  { ing: 'eggs', size: 12, label: 'Free-range eggs x12', price: 315 },
  { ing: 'eggs', size: 15, label: 'Free-range eggs x15', price: 399 },
  { ing: 'milk', size: 568, label: 'Milk 1 pint (568 ml)', price: 75 },
  { ing: 'milk', size: 1136, label: 'Milk 2 pints (1.136 l)', price: 125 },
  { ing: 'milk', size: 2272, label: 'Milk 4 pints (2.272 l)', price: 165 },
  { ing: 'cream', size: 300, label: 'Double cream 300 ml', price: 140 },
  { ing: 'cream', size: 600, label: 'Double cream 600 ml', price: 255 },
  { ing: 'cheddar', size: 400, label: 'Mature cheddar 400 g', price: 330 },
  { ing: 'cheddar', size: 800, label: 'Mature cheddar 800 g', price: 600 },
  { ing: 'rice', size: 500, label: 'Long-grain rice 500 g', price: 95 },
  { ing: 'rice', size: 1000, label: 'Long-grain rice 1 kg', price: 175 },
  { ing: 'onions', size: 1, label: 'Onion (loose, each)', price: 20 },
  { ing: 'onions', size: 3, label: 'Onions x3 net', price: 55 },
  { ing: 'lemons', size: 1, label: 'Lemon (loose, each)', price: 35 },
  { ing: 'lemons', size: 4, label: 'Lemons x4 pack', price: 110 },
  { ing: 'tomatoes', size: 400, label: 'Chopped tomatoes 400 g tin', price: 55 },
  { ing: 'coconut', size: 400, label: 'Coconut milk 400 ml tin', price: 130 },
  { ing: 'chickpeas', size: 400, label: 'Chickpeas 400 g tin', price: 65 },
  { ing: 'beans', size: 400, label: 'Kidney beans 400 g tin', price: 60 },
  { ing: 'mince', size: 500, label: 'Beef mince 500 g', price: 375 },
  { ing: 'mince', size: 750, label: 'Beef mince 750 g', price: 545 },
  { ing: 'lamb', size: 500, label: 'Lamb mince 500 g', price: 450 },
  { ing: 'chicken', size: 500, label: 'Chicken thighs 500 g', price: 325 },
  { ing: 'chicken', size: 1000, label: 'Chicken thighs 1 kg', price: 599 },
  { ing: 'potatoes', size: 2500, label: 'Potatoes 2.5 kg bag', price: 165 },
  { ing: 'potatoes', size: 1000, label: 'Potatoes 1 kg bag', price: 95 },
  { ing: 'carrots', size: 1000, label: 'Carrots 1 kg', price: 65 },
  { ing: 'lentils', size: 500, label: 'Red lentils 500 g', price: 135 },
  { ing: 'macaroni', size: 500, label: 'Macaroni 500 g', price: 85 },
  { ing: 'oil', size: 500, label: 'Olive oil 500 ml', price: 450 },
];

interface ShopSpec {
  id: string;
  difficulty: 'easy' | 'medium' | 'hard';
  seed: number;
  host: string;
  occasion: string;
  guests: number;
  recipes: string[];
  /** Cupboard: ingredient → [amount in base units, how it is described]. */
  cupboard: Array<[string, number, string]>;
  /** Offers: [label, ingredient keys or pack labels, qty, price pence]. */
  offers: Array<{ label: string; packs: string[]; qty: number; price: number }>;
  /** Extra lines (distractors, notes). */
  extra?: string[];
  /** A late change: the real guest count / dropped recipe is what the world uses. */
  late?: { text: string; guests?: number; drop?: string };
}

interface ShopCase {
  id: string;
  difficulty: 'easy' | 'medium' | 'hard';
  world: ShopWorld;
  prompt: string;
  notes: string;
  reference: Record<string, unknown>;
  optimum: number;
  optimalPlans: number;
  careless: Array<{ label: string; answer: Record<string, unknown> }>;
}

function needsFor(spec: ShopSpec, guests: number, recipes: string[], useCupboard = true): ShopWorld['needs'] {
  const total = new Map<string, Q>();
  for (const r of recipes) {
    const rec = RECIPES[r]!;
    const scale = new Q(guests, rec.serves);
    for (const l of rec.lines) total.set(l.ing, (total.get(l.ing) ?? new Q(0)).add(lineAmount(l).mul(scale)));
  }
  if (useCupboard) for (const [ing, amt] of spec.cupboard) if (total.has(ing)) total.set(ing, total.get(ing)!.sub(new Q(amt)));
  const out: ShopWorld['needs'] = [];
  for (const [ing, q] of total) {
    if (q.n <= 0n) continue;
    const ceil = (q.n + q.d - 1n) / q.d; // packs are whole units, so the need is the next whole g / ml / item
    out.push({ ingredient: ING[ing]!.label, unit: ING[ing]!.base, amount: Number(ceil) });
  }
  return out.sort((a, b) => a.ingredient.localeCompare(b.ingredient));
}

/** Every basket that covers the needs, cheapest first; exact (enumerates pack counts per linked group). */
export function solveShopping(w: ShopWorld): { best: Record<string, number>; optimum: number; optimalPlans: number } {
  // Groups: products connected through offers or a shared ingredient.
  const parent = new Map<string, string>(w.products.map((p) => [p.id, p.id]));
  const find = (x: string): string => (parent.get(x) === x ? x : find(parent.get(x)!));
  const join = (a: string, b: string) => parent.set(find(a), find(b));
  for (const o of w.offers) for (const id of o.products) join(o.products[0]!, id);
  for (const p of w.products) for (const q of w.products) if (p.ingredient === q.ingredient) join(p.id, q.id);
  const groups = new Map<string, ShopProduct[]>();
  for (const p of w.products) groups.set(find(p.id), [...(groups.get(find(p.id)) ?? []), p]);
  const best: Record<string, number> = {};
  let optimum = 0;
  let optimalPlans = 1;
  for (const members of groups.values()) {
    const needs = w.needs.filter((n) => members.some((p) => p.ingredient === n.ingredient));
    const offers = w.offers.filter((o) => o.products.some((id) => members.some((p) => p.id === id)));
    const extra = offers.reduce((s, o) => Math.max(s, o.qty), 0);
    const max = members.map((p) => {
      const n = needs.find((x) => x.ingredient === p.ingredient);
      return (n ? Math.ceil(n.amount / p.size) : 0) + extra;
    });
    const subWorld: ShopWorld = { needs, products: members, offers };
    let gBest: Record<string, number> | null = null;
    let gCost = Infinity;
    let gCount = 0;
    const q: Record<string, number> = {};
    const rec = (i: number) => {
      if (i === members.length) {
        for (const n of needs) {
          const have = members.filter((p) => p.ingredient === n.ingredient).reduce((s, p) => s + p.size * (q[p.id] ?? 0), 0);
          if (have < n.amount) return;
        }
        const c = basketPriceIndependent(subWorld, q);
        if (c < gCost) {
          gCost = c;
          gBest = { ...q };
          gCount = 1;
        } else if (c === gCost) gCount++;
        return;
      }
      for (let k = 0; k <= max[i]!; k++) {
        q[members[i]!.id] = k;
        rec(i + 1);
      }
      delete q[members[i]!.id];
    };
    rec(0);
    if (!gBest) throw new Error('cannot cover needs');
    Object.assign(best, gBest);
    optimum += gCost;
    optimalPlans *= gCount;
  }
  for (const k of Object.keys(best)) if (!best[k]) delete best[k];
  return { best, optimum, optimalPlans };
}

/** The till rule, written again from the prompt's wording (independent of the app's checker). */
function basketPriceIndependent(w: ShopWorld, q: Record<string, number>): number {
  let total = 0;
  const claimed = new Set<string>();
  for (const o of w.offers) {
    const packs = o.products.flatMap((id) => Array<number>(q[id] ?? 0).fill(w.products.find((p) => p.id === id)!.price));
    packs.sort((a, b) => b - a);
    let i = 0;
    for (; i + o.qty <= packs.length; i += o.qty) total += o.price;
    for (; i < packs.length; i++) total += packs[i]!;
    for (const id of o.products) claimed.add(id);
  }
  for (const p of w.products) if (!claimed.has(p.id)) total += (q[p.id] ?? 0) * p.price;
  return total;
}

function basketAnswer(q: Record<string, number>, w: ShopWorld): Record<string, unknown> {
  const lines = w.products.filter((p) => q[p.id]).map((p) => ({ item: p.id, qty: q[p.id]! }));
  return { basket: lines, total: pounds(basketPrice(w, q)) };
}

function fmtQty(n: number, unit: string): string {
  return unit === 'each' ? String(n) : `${n} ${unit}`;
}

const SPECS: ShopSpec[] = [
  {
    id: 's01', difficulty: 'easy', seed: 701, host: 'Amara', occasion: 'a family Sunday lunch', guests: 12, recipes: ['chilli', 'yorkies'],
    cupboard: [['oil', 250, 'about half a 500 ml bottle of olive oil (250 ml)']],
    offers: [],
  },
  {
    id: 's02', difficulty: 'easy', seed: 702, host: 'Jun', occasion: 'my flat-warming', guests: 12, recipes: ['curry', 'sponge'],
    cupboard: [['rice', 200, '200 g of rice'], ['eggs', 3, '3 eggs']],
    offers: [{ label: 'Coconut milk: 3 tins for £3.00', packs: ['Coconut milk 400 ml tin'], qty: 3, price: 300 }],
  },
  {
    id: 's03', difficulty: 'easy', seed: 703, host: 'Callum', occasion: 'a birthday tea', guests: 8, recipes: ['jollof', 'sponge'],
    cupboard: [['sugar', 100, '100 g of caster sugar'], ['butter', 125, 'half a 250 g block of butter']],
    offers: [{ label: 'Butter 250 g: 2 for £3.80', packs: ['Butter 250 g'], qty: 2, price: 380 }],
  },
  {
    id: 's04', difficulty: 'medium', seed: 704, host: 'Priya', occasion: 'Diwali dinner for the neighbours', guests: 12, recipes: ['dal', 'curry', 'drizzle'],
    cupboard: [['flour', 750, 'half a 1.5 kg bag of plain flour'], ['onions', 2, '2 onions'], ['milk', 568, 'a pint of milk']],
    offers: [
      { label: 'Any 3 tins of chopped tomatoes, chickpeas or kidney beans for £1.35', packs: ['Chopped tomatoes 400 g tin', 'Chickpeas 400 g tin', 'Kidney beans 400 g tin'], qty: 3, price: 135 },
      { label: 'Coconut milk: 3 tins for £3.00', packs: ['Coconut milk 400 ml tin'], qty: 3, price: 300 },
    ],
  },
  {
    id: 's05', difficulty: 'medium', seed: 705, host: 'Rhys', occasion: 'the rugby club supper', guests: 12, recipes: ['shepherds', 'pancakes'],
    cupboard: [['potatoes', 1000, 'a 1 kg bag of potatoes'], ['eggs', 2, '2 eggs'], ['sugar', 500, 'a full 500 g bag of caster sugar']],
    offers: [{ label: 'Milk 2 or 4 pints: any 2 for £2.00', packs: ['Milk 2 pints (1.136 l)', 'Milk 4 pints (2.272 l)'], qty: 2, price: 200 }],
  },
  {
    id: 's06', difficulty: 'medium', seed: 706, host: 'Lucia', occasion: 'a games night', guests: 10, recipes: ['maccheese', 'chilli', 'drizzle'],
    cupboard: [['butter', 250, 'a 250 g block of butter'], ['lemons', 1, 'one lemon'], ['rice', 500, 'a 500 g bag of rice']],
    offers: [
      { label: 'Cheddar 400 g: 2 for £5.50', packs: ['Mature cheddar 400 g'], qty: 2, price: 550 },
      { label: 'Any 3 tins of chopped tomatoes, chickpeas or kidney beans for £1.35', packs: ['Chopped tomatoes 400 g tin', 'Chickpeas 400 g tin', 'Kidney beans 400 g tin'], qty: 3, price: 135 },
    ],
  },
  {
    id: 's07', difficulty: 'hard', seed: 707, host: 'Kofi', occasion: 'my mum’s 60th', guests: 12, recipes: ['jollof', 'curry', 'dal', 'sponge'],
    cupboard: [['rice', 1000, 'a 1 kg bag of rice'], ['onions', 3, 'a net of 3 onions'], ['coconut', 400, 'one tin of coconut milk'], ['eggs', 6, 'half a dozen eggs']],
    offers: [
      { label: 'Any 3 tins of chopped tomatoes, chickpeas or kidney beans for £1.35', packs: ['Chopped tomatoes 400 g tin', 'Chickpeas 400 g tin', 'Kidney beans 400 g tin'], qty: 3, price: 135 },
      { label: 'Chicken thighs 1 kg: 2 for £10.00', packs: ['Chicken thighs 1 kg'], qty: 2, price: 1000 },
      { label: 'Coconut milk: 3 tins for £3.00', packs: ['Coconut milk 400 ml tin'], qty: 3, price: 300 },
    ],
    extra: ['My brother says the big packs are always the cheapest per kilo. He is not always right.'],
    late: { text: 'UPDATE: Auntie Grace just told me she is bringing a cake, so I am NOT making the Victoria sponge after all. Everything else stays the same.', drop: 'sponge' },
  },
  {
    id: 's08', difficulty: 'hard', seed: 708, host: 'Nadia', occasion: 'a charity bake sale lunch', guests: 15, recipes: ['maccheese', 'pancakes', 'drizzle', 'yorkies'],
    cupboard: [['flour', 500, 'a 500 g bag of plain flour'], ['milk', 1136, 'two pints of milk'], ['butter', 100, '100 g of butter'], ['eggs', 4, '4 eggs']],
    offers: [
      { label: 'Milk 2 or 4 pints: any 2 for £2.00', packs: ['Milk 2 pints (1.136 l)', 'Milk 4 pints (2.272 l)'], qty: 2, price: 200 },
      { label: 'Eggs x12 or x15: any 2 boxes for £5.00', packs: ['Free-range eggs x12', 'Free-range eggs x15'], qty: 2, price: 500 },
      { label: 'Butter 250 g: 2 for £3.80', packs: ['Butter 250 g'], qty: 2, price: 380 },
    ],
    late: { text: 'Change of numbers: three more volunteers signed up, so please plan for 18 people, not 15.', guests: 18 },
  },
  {
    id: 's09', difficulty: 'hard', seed: 709, host: 'Tomasz', occasion: 'the street party', guests: 20, recipes: ['chilli', 'curry', 'jollof', 'drizzle'],
    cupboard: [['oil', 500, 'a full 500 ml bottle of olive oil'], ['tomatoes', 800, 'two tins of chopped tomatoes'], ['onions', 4, '4 onions'], ['lemons', 2, '2 lemons']],
    offers: [
      { label: 'Any 3 tins of chopped tomatoes, chickpeas or kidney beans for £1.35', packs: ['Chopped tomatoes 400 g tin', 'Chickpeas 400 g tin', 'Kidney beans 400 g tin'], qty: 3, price: 135 },
      { label: 'Chicken thighs 1 kg: 2 for £10.00', packs: ['Chicken thighs 1 kg'], qty: 2, price: 1000 },
      { label: 'Beef mince 750 g: 2 for £9.00', packs: ['Beef mince 750 g'], qty: 2, price: 900 },
      { label: 'Onions x3 net: 2 for 90p', packs: ['Onions x3 net'], qty: 2, price: 90 },
    ],
    extra: ['Ignore the “serves 4–6” on the chilli card: the recipe below is written for 4.'],
    late: { text: 'Last thing: the two tins of tomatoes in the cupboard are past their date, so I am throwing them out. Don’t count them.' },
  },
  {
    id: 's10', difficulty: 'hard', seed: 710, host: 'Eilidh', occasion: 'a Burns-night-meets-Thanksgiving party', guests: 14, recipes: ['shepherds', 'maccheese', 'pancakes', 'sponge'],
    cupboard: [['potatoes', 2500, 'a 2.5 kg bag of potatoes'], ['cheddar', 200, 'about 200 g of cheddar'], ['milk', 568, 'a pint of milk'], ['flour', 1500, 'a 1.5 kg bag of plain flour']],
    offers: [
      { label: 'Milk 2 or 4 pints: any 2 for £2.00', packs: ['Milk 2 pints (1.136 l)', 'Milk 4 pints (2.272 l)'], qty: 2, price: 200 },
      { label: 'Cheddar 400 g: 2 for £5.50', packs: ['Mature cheddar 400 g'], qty: 2, price: 550 },
      { label: 'Butter 250 g: 2 for £3.80', packs: ['Butter 250 g'], qty: 2, price: 380 },
      { label: 'Eggs x12 or x15: any 2 boxes for £5.00', packs: ['Free-range eggs x12', 'Free-range eggs x15'], qty: 2, price: 500 },
    ],
    late: { text: 'Oops: the “1.5 kg bag of flour” in the cupboard is actually self-raising, and none of these recipes use self-raising flour. Don’t count it.' },
  },
];

function buildWorld(spec: ShopSpec, guests: number, recipes: string[], cupboard: ShopSpec['cupboard'], rng: Rng): { world: ShopWorld; shelf: Array<ShopProduct> } {
  const used = new Set(recipes.flatMap((r) => RECIPES[r]!.lines.map((l) => l.ing)));
  // Shelf: every pack of a used ingredient, plus the tins that share an offer with them; prices jittered by ±10%.
  const offerPacks = new Set(spec.offers.flatMap((o) => o.packs));
  const packs = SHELF.filter((p) => used.has(p.ing) || offerPacks.has(p.label));
  const products: ShopProduct[] = packs.map((p, i) => ({
    id: `P${String(i + 1).padStart(2, '0')}`,
    name: p.label,
    ingredient: ING[p.ing]!.label,
    size: p.size,
    price: Math.max(10, Math.round((p.price * (0.9 + rng.next() * 0.2)) / 5) * 5),
  }));
  const offers: ShopOffer[] = spec.offers.map((o, i) => ({ id: `M${i + 1}`, label: o.label, products: o.packs.map((l) => products.find((p) => p.name === l)!.id), qty: o.qty, price: o.price }));
  for (const o of offers) for (const id of o.products) {
    const p = products.find((x) => x.id === id)!;
    if (o.price >= o.qty * p.price) throw new Error(`${spec.id}: offer ${o.label} saves nothing on ${p.name}`);
  }
  return { world: { needs: needsFor({ ...spec, cupboard }, guests, recipes), products, offers }, shelf: products };
}

function promptFor(spec: ShopSpec, world: ShopWorld): string {
  const L: string[] = [];
  L.push(`Hi! I’m cooking for ${spec.occasion} and there will be ${spec.guests} of us, so every recipe needs to make exactly enough for ${spec.guests} people. I’m doing:`);
  for (const r of spec.recipes) {
    const rec = RECIPES[r]!;
    L.push('', `${rec.name} (serves ${rec.serves}):`);
    for (const l of rec.lines) L.push(`- ${l.text}`);
  }
  if (spec.extra?.length) L.push('', ...spec.extra);
  L.push('', 'Already in my cupboard (use it up before buying more):');
  for (const [, , text] of spec.cupboard) L.push(`- ${text}`);
  L.push('', 'Conversions to use (and only these): 1 oz = 28 g; 1 lb = 454 g; 1 pint = 568 ml; 1 tin = 400 g (or 400 ml for coconut milk).');
  const conv: string[] = [];
  for (const ing of Object.values(ING)) for (const u of ['cup', 'tbsp', 'tsp'] as const) if (ing[u] !== undefined) conv.push(`1 ${u} ${ing.label} = ${ing[u]} ${ing.base}`);
  L.push(`${conv.join('; ')}.`);
  L.push('', 'THE SHOP (price per pack)');
  L.push('ID | Product | Price');
  for (const p of world.products) L.push(`${p.id} | ${p.name} | ${money(p.price)}`);
  if (world.offers.length) {
    L.push('', 'OFFERS (applied automatically at the till: the qualifying packs are grouped most expensive first, each full group costs the offer price, any packs left over are charged normally; an offer can be used as many times as you like)');
    for (const o of world.offers) L.push(`- ${o.label} (counts: ${o.products.join(', ')})`);
  }
  L.push(
    '',
    'Rules: scale every ingredient exactly by (people ÷ servings) — no rounding until the end; add the same ingredient up across recipes; take off what is in the cupboard; then buy whole packs so I have at least enough of everything. A recipe’s “1 onion” scales like any other amount; you can’t buy part of a pack or part of an egg. Buying more than I need is fine if it is cheaper.',
  );
  if (spec.late) L.push('', spec.late.text);
  L.push(
    '',
    'What should I buy to spend as little as possible? Answer with the packs and how many of each, and the till total in pounds, in this JSON format:',
    '```json',
    '{"basket": [{"item": "<product ID>", "qty": 1}], "total": 0.00}',
    '```',
    ONE_ANSWER,
  );
  return L.join('\n');
}

export function buildShoppingCases(): ShopCase[] {
  return SPECS.map((spec) => {
    const rng = new Rng(spec.seed);
    const guests = spec.late?.guests ?? spec.guests;
    const recipes = spec.recipes.filter((r) => r !== spec.late?.drop);
    let cupboard = spec.cupboard;
    if (spec.id === 's09') cupboard = cupboard.filter(([ing]) => ing !== 'tomatoes');
    if (spec.id === 's10') cupboard = cupboard.filter(([ing]) => ing !== 'flour');
    const { world } = buildWorld(spec, guests, recipes, cupboard, rng);
    const sol = solveShopping(world);
    const reference = basketAnswer(sol.best, world);
    const careless: ShopCase['careless'] = [];
    // Careless 1: ignores the offers (cheapest basket at single prices).
    const noOffers = solveShopping({ ...world, offers: [] });
    careless.push({ label: 'ignores the multibuy offers', answer: basketAnswer(noOffers.best, world) });
    // Careless 2: forgets the cupboard.
    const noCup = solveShopping({ ...world, needs: needsFor({ ...spec, cupboard: [] }, guests, recipes, false) });
    careless.push({ label: 'forgets the cupboard', answer: basketAnswer(noCup.best, world) });
    // Careless 3: misses the late message (buys for the original plan).
    if (spec.late) {
      const orig = solveShopping({ ...world, needs: needsFor(spec, spec.guests, spec.recipes) });
      careless.push({ label: 'misses the late message', answer: basketAnswer(orig.best, world) });
    }
    const prompt = promptFor(spec, world);
    const needText = world.needs.map((n) => `${n.ingredient} ${fmtQty(n.amount, n.unit)}`).join(', ');
    return {
      id: spec.id,
      difficulty: spec.difficulty,
      world,
      prompt,
      reference,
      optimum: sol.optimum,
      optimalPlans: sol.optimalPlans,
      careless,
      notes: `[${spec.difficulty}] To buy (exact, after scaling, conversion, merging and the cupboard; rounded up to whole g/ml/items): ${needText}. Cheapest basket ${money(sol.optimum)} (every pack combination tried; ${sol.optimalPlans} optimal). One optimal basket: ${JSON.stringify(reference)}. Ignoring the offers the best basket costs ${money(basketPrice(world, noOffers.best))}.`,
    };
  });
}

if (import.meta.url === `file://${process.argv[1]}`) {
  for (const c of buildShoppingCases()) {
    console.log(c.id, c.notes);
    for (const k of c.careless) console.log('   careless', k.label, JSON.stringify(k.answer));
    if (process.argv.includes('--prompt')) console.log(c.prompt);
  }
}
