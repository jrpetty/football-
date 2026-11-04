/**
 * Pack for the Flight (life.packing-limits): trip generator + exact solver.
 *
 * The solver is an exhaustive branch-and-bound over every place for every item (each bag, worn, or left at home),
 * written from the rules as stated in the prompt (independently of the app's checker). The two hold bags are
 * interchangeable, so the second is only opened after the first. The cheapest legal plan is the proven optimum.
 */
import { Rng, money, pounds, ONE_ANSWER } from './common.mts';
import type { PackBag, PackItem, PackPlace, PackWorld } from '../../src/scoring/verifiers/packing.ts';

type Cat = Omit<PackItem, 'id' | 'replace'> & { replace: number | null; key: string };

const C = (key: string, name: string, kg: number, litres: number, dims: [number, number, number], replace: number | null, extra: Partial<PackItem> = {}): Cat => ({ key, name, kg, litres, dims, replace, ...extra });

const CATALOGUE: Cat[] = [
  C('passport', 'Passport', 0.05, 0.1, [13, 9, 1], null),
  C('phone', 'Phone', 0.2, 0.1, [15, 7, 1], null),
  C('meds', 'Prescription medicine', 0.2, 0.4, [12, 8, 6], null),
  C('clothes', 'Clothes for the trip (rolled)', 3.4, 16, [40, 30, 14], 18000),
  C('clothes-light', 'Summer clothes (rolled)', 2.2, 11, [35, 25, 12], 12000),
  C('outfit', 'Wedding outfit in a folding garment cover', 1.6, 9, [52, 38, 6], null),
  C('suitbag', 'Suit in a folding garment bag', 1.9, 10, [52, 38, 8], 25000),
  C('shoes', 'Smart shoes', 1.1, 6, [32, 22, 12], 6500),
  C('trainers', 'Trainers', 0.9, 7, [32, 22, 13], 6000, { wearable: true }),
  C('boots', 'Walking boots', 1.6, 9, [33, 25, 18], 9000, { wearable: true }),
  C('coat', 'Winter coat', 1.8, 14, [50, 40, 15], 12000, { wearable: true }),
  C('jacket', 'Waterproof jacket', 0.6, 5, [30, 25, 8], 5500, { wearable: true }),
  C('laptop', 'Laptop', 1.6, 2.5, [36, 25, 2], null),
  C('charger', 'Laptop charger', 0.4, 0.6, [15, 8, 4], 6500),
  C('powerbank', 'Power bank (spare lithium battery)', 0.35, 0.3, [15, 7, 3], 3000, { battery: true }),
  C('camera', 'Camera and lens', 1.3, 3, [20, 15, 12], null),
  C('tripod', 'Camera tripod (folded)', 1.5, 2.5, [62, 10, 10], 8000),
  C('book', 'Paperback book', 0.35, 0.6, [20, 13, 3], 900),
  C('kindle', 'E-reader', 0.2, 0.2, [16, 11, 1], 9000),
  C('hairdryer', 'Hairdryer', 0.7, 3, [25, 22, 9], 2500),
  C('straighteners', 'Hair straighteners', 0.4, 1, [30, 5, 4], 4000),
  C('washbag', 'Washbag (toothbrush, razor, dry items)', 0.6, 2.5, [24, 14, 10], 2000),
  C('toothpaste', 'Toothpaste 75 ml', 0.1, 0.1, [16, 4, 3], 300, { liquidMl: 75 }),
  C('shampoo', 'Shampoo 250 ml', 0.3, 0.3, [18, 7, 4], 450, { liquidMl: 250 }),
  C('shampoo-mini', 'Travel shampoo 100 ml', 0.12, 0.12, [12, 5, 3], 350, { liquidMl: 100 }),
  C('suncream', 'Sun cream 200 ml', 0.25, 0.25, [16, 6, 4], 1200, { liquidMl: 200 }),
  C('suncream-mini', 'Sun cream 50 ml', 0.07, 0.07, [10, 4, 3], 600, { liquidMl: 50 }),
  C('perfume', 'Perfume 50 ml', 0.2, 0.15, [10, 6, 4], 6500, { liquidMl: 50 }),
  C('lenses', 'Contact lens solution 360 ml', 0.4, 0.4, [19, 7, 5], 900, { liquidMl: 360 }),
  C('lenses-mini', 'Contact lens solution 100 ml', 0.12, 0.12, [12, 5, 4], 450, { liquidMl: 100 }),
  C('moisturiser', 'Moisturiser 100 ml', 0.15, 0.12, [9, 6, 5], 1800, { liquidMl: 100 }),
  C('whisky', 'Bottle of single malt (gift, 700 ml)', 1.3, 1.2, [32, 9, 9], 4500, { liquidMl: 700 }),
  C('gin', 'Bottle of gin (gift, 700 ml)', 1.2, 1.1, [30, 9, 9], 3200, { liquidMl: 700 }),
  C('jam', 'Jar of homemade jam for my host (300 ml)', 0.5, 0.4, [10, 9, 9], 400, { liquidMl: 300 }),
  C('scissors', 'Nail scissors (blades over 6 cm)', 0.05, 0.05, [12, 5, 1], 500, { sharp: true }),
  C('multitool', 'Pocket multitool with a knife', 0.2, 0.1, [11, 4, 3], 4500, { sharp: true }),
  C('snorkel', 'Snorkel and mask', 0.8, 4, [40, 20, 12], 3500),
  C('towel', 'Beach towel', 0.9, 6, [40, 30, 8], 1500),
  C('gifts', 'Wrapped presents for the family', 2.2, 10, [40, 30, 15], 8000),
  C('boardgame', 'Board game', 1.1, 4.5, [30, 30, 7], 3500),
  C('babyclothes', 'Baby clothes and nappies', 2.5, 15, [40, 30, 15], 6000),
  C('formula', 'Baby formula (powder tin)', 0.9, 1.6, [13, 13, 18], 1400),
  C('babymilk', 'Ready-made baby milk 200 ml', 0.22, 0.2, [12, 6, 6], 250, { liquidMl: 200, medical: true }),
  C('pillow', 'Travel pillow', 0.3, 4, [30, 28, 10], 1500, { wearable: true }),
  C('skisuit', 'Ski jacket and trousers', 2.6, 18, [50, 40, 15], 22000, { wearable: true }),
  C('skiboots', 'Ski boots', 3.6, 16, [42, 32, 28], 8000),
  C('goggles', 'Ski goggles and helmet', 1.0, 9, [30, 25, 20], 9000),
  C('thermals', 'Thermals and ski socks', 1.0, 5, [30, 25, 8], 7000),
  C('docs', 'Conference badge and printed papers', 0.4, 0.8, [31, 22, 2], null),
  C('heels', 'High heels', 0.8, 5, [26, 20, 10], 7000),
  C('dress', 'Bridesmaid dress', 0.9, 6, [45, 35, 6], null),
  C('speaker', 'Bluetooth speaker', 0.6, 1.2, [20, 8, 8], 4000),
  C('tent', 'Festival tent', 2.4, 14, [58, 18, 18], 6000),
  C('sleepingbag', 'Sleeping bag', 1.4, 12, [40, 25, 25], 4500),
  C('wellies', 'Wellies', 1.8, 12, [40, 30, 12], 2500, { wearable: true }),
  C('eyedrops', 'Prescription eye drops 15 ml', 0.03, 0.03, [7, 3, 3], null, { liquidMl: 15, medical: true }),
  C('antibiotic', 'Liquid antibiotics 250 ml (prescribed)', 0.35, 0.3, [14, 7, 7], null, { liquidMl: 250, medical: true }),
  C('hoodie', 'Hoodie', 0.7, 5, [35, 30, 8], 3500, { wearable: true }),
  C('jeans', 'Spare jeans', 0.8, 4, [35, 30, 5], 4500),
  C('cables', 'Phone charger and cables', 0.2, 0.4, [12, 8, 4], 2000),
  C('headphones', 'Noise-cancelling headphones', 0.3, 1.5, [20, 18, 6], 15000),
  C('tablet', 'Tablet', 0.5, 0.6, [25, 17, 1], 30000),
  C('umbrella', 'Folding umbrella', 0.4, 0.8, [30, 6, 6], 800),
  C('adapter', 'Travel adapter', 0.15, 0.2, [7, 6, 5], 1200),
  C('swimwear', 'Swimwear', 0.3, 1.5, [25, 20, 4], 3000),
  C('flipflops', 'Flip-flops', 0.3, 2, [28, 11, 4], 1000, { wearable: true }),
  C('shortbread', 'Tin of shortbread (gift)', 0.8, 2, [20, 20, 6], 1200),
  C('teabags', 'Box of 240 tea bags for my aunt', 0.75, 3, [22, 14, 12], 900),
  C('marmite', 'Jar of Marmite 250 g (paste)', 0.4, 0.3, [11, 8, 8], 600, { liquidMl: 250 }),
  C('firstaid', 'First-aid kit', 0.4, 1.5, [20, 13, 6], 1500),
  C('shaver', 'Electric shaver', 0.3, 0.6, [16, 7, 5], 6000),
  C('curling', 'Curling tongs', 0.5, 1, [32, 5, 5], 3500),
  C('yogamat', 'Yoga mat (rolled)', 1.2, 8, [61, 12, 12], 2500),
  C('babycarrier', 'Baby carrier', 0.9, 6, [40, 30, 10], 7000, { wearable: true }),
  C('toys', 'Bag of toys and picture books', 1.5, 7, [35, 25, 12], 3000),
  C('kneebrace', 'Knee brace', 0.4, 1.5, [30, 15, 6], 4500, { wearable: true }),
  C('gloves', 'Ski gloves and neck warmer', 0.4, 2, [28, 15, 6], 4000, { wearable: true }),
  C('suncream-face', 'Face sun cream 40 ml', 0.05, 0.05, [9, 4, 2], 1600, { liquidMl: 40 }),
  C('handcream', 'Hand cream 75 ml', 0.09, 0.09, [12, 5, 3], 800, { liquidMl: 75 }),
  C('deodorant', 'Spray deodorant 150 ml', 0.18, 0.2, [16, 5, 5], 450, { liquidMl: 150 }),
];

interface PackSpec {
  id: string;
  difficulty: 'easy' | 'medium' | 'hard';
  seed: number;
  intro: string;
  items: string[];
  /** Overrides on catalogue items: replacement price changes etc. */
  tweaks?: Record<string, Partial<PackItem>>;
  airline: { cabinFee: number; holdFee: number; hold2Fee: number; overPerKg: number; cabinKg: number; holdFreeKg?: number; personalLitres?: number; cabinTotalKg?: number };
  late?: { text: string; tweaks?: Record<string, Partial<PackItem>>; airline?: Partial<PackSpec['airline']> };
}

const SPECS: PackSpec[] = [
  {
    id: 'p01', difficulty: 'easy', seed: 801,
    intro: 'Weekend in Lisbon, Friday to Sunday, flying from Manchester. Just me, sightseeing and a nice dinner.',
    items: ['passport', 'phone', 'clothes-light', 'shoes', 'washbag', 'toothpaste', 'shampoo', 'suncream-mini', 'book', 'powerbank'],
    airline: { cabinFee: 3600, holdFee: 4800, hold2Fee: 5500, overPerKg: 1200, cabinKg: 10 },
  },
  {
    id: 'p02', difficulty: 'easy', seed: 802,
    intro: 'Four nights in Dublin for a work conference, flying from Bristol.',
    items: ['passport', 'phone', 'laptop', 'charger', 'docs', 'clothes', 'suitbag', 'shoes', 'washbag', 'toothpaste', 'lenses', 'scissors'],
    airline: { cabinFee: 2800, holdFee: 4200, hold2Fee: 5200, overPerKg: 1100, cabinKg: 10 },
  },
  {
    id: 'p03', difficulty: 'easy', seed: 803,
    intro: 'A week of beach in Mallorca from Gatwick. I’ll mostly live in shorts.',
    items: ['passport', 'phone', 'clothes-light', 'trainers', 'washbag', 'toothpaste', 'suncream', 'shampoo-mini', 'towel', 'snorkel', 'kindle', 'powerbank'],
    airline: { cabinFee: 4500, holdFee: 3900, hold2Fee: 4900, overPerKg: 1000, cabinKg: 10 },
  },
  {
    id: 'p04', difficulty: 'medium', seed: 804,
    intro: 'My cousin’s wedding in Kraków, flying from Luton. I’m doing the readings, so the outfit has to come.',
    items: ['passport', 'phone', 'meds', 'outfit', 'shoes', 'clothes-light', 'washbag', 'toothpaste', 'perfume', 'moisturiser', 'shampoo', 'gin', 'gifts', 'hairdryer', 'powerbank', 'pillow', 'curling', 'deodorant', 'cables', 'shortbread'],
    airline: { cabinFee: 3000, holdFee: 11900, hold2Fee: 12900, overPerKg: 1200, cabinKg: 10 },
  },
  {
    id: 'p05', difficulty: 'medium', seed: 805,
    intro: 'Hiking in Norway for five days from Edinburgh. Some of this kit is expensive to replace out there.',
    items: ['passport', 'phone', 'boots', 'jacket', 'clothes', 'washbag', 'toothpaste', 'suncream-mini', 'lenses', 'lenses-mini', 'multitool', 'camera', 'tripod', 'powerbank', 'book', 'hoodie', 'firstaid', 'kneebrace', 'handcream', 'cables'],
    airline: { cabinFee: 2600, holdFee: 13900, hold2Fee: 14900, overPerKg: 1500, cabinKg: 8 },
  },
  {
    id: 'p06', difficulty: 'medium', seed: 806,
    intro: 'Christmas with family in Toronto from Heathrow. Lots of presents.',
    items: ['passport', 'phone', 'meds', 'coat', 'clothes', 'trainers', 'washbag', 'toothpaste', 'shampoo-mini', 'gifts', 'boardgame', 'whisky', 'jam', 'kindle', 'charger', 'powerbank', 'shortbread', 'teabags', 'marmite', 'jeans'],
    airline: { cabinFee: 0, holdFee: 7500, hold2Fee: 8500, overPerKg: 1800, cabinKg: 10 },
  },
  {
    id: 'p07', difficulty: 'hard', seed: 807,
    intro: 'Ski week in Geneva from Birmingham. Ski hire is booked, but not boots or clothing.',
    items: ['passport', 'phone', 'meds', 'eyedrops', 'skisuit', 'skiboots', 'goggles', 'gloves', 'thermals', 'clothes', 'jeans', 'boots', 'coat', 'hoodie', 'washbag', 'toothpaste', 'suncream-mini', 'suncream-face', 'moisturiser', 'handcream', 'lenses', 'camera', 'laptop', 'charger', 'cables', 'powerbank', 'book', 'gin', 'boardgame', 'headphones'],
    airline: { cabinFee: 2500, holdFee: 14900, hold2Fee: 15900, overPerKg: 1300, cabinKg: 10, holdFreeKg: 20, cabinTotalKg: 14 },
    late: { text: 'UPDATE: the airline has just emailed new rules from this month: the cabin bag limit is 8 kg, not 10 kg, and each hold bag now includes only 15 kg (not 20 kg) before the overweight fee. Also, the ski hire shop says boots are £40 for the week, so replacing my ski boots there would only cost £40.', airline: { cabinKg: 8, holdFreeKg: 15 }, tweaks: { skiboots: { replace: 4000 } } },
  },
  {
    id: 'p08', difficulty: 'hard', seed: 808,
    intro: 'I’m a bridesmaid in Malaga and flying from Newcastle with the bride’s present. The dress and my medicine absolutely have to come.',
    items: ['passport', 'phone', 'meds', 'antibiotic', 'dress', 'heels', 'clothes-light', 'trainers', 'flipflops', 'jacket', 'swimwear', 'washbag', 'toothpaste', 'shampoo', 'shampoo-mini', 'suncream', 'suncream-mini', 'perfume', 'moisturiser', 'deodorant', 'straighteners', 'hairdryer', 'curling', 'gifts', 'whisky', 'speaker', 'powerbank', 'scissors', 'towel', 'snorkel', 'adapter', 'headphones'],
    airline: { cabinFee: 2800, holdFee: 7400, hold2Fee: 8400, overPerKg: 1200, cabinKg: 10, holdFreeKg: 15, cabinTotalKg: 13 },
    late: { text: 'Good news from the bride: the villa has a hairdryer and straighteners we can all use, so they are worth nothing to me there (I would not need to buy them). And her mum is driving out, so she will take the wrapped presents and the bottle of whisky in the car: they do not need to come with me and cost me nothing to leave behind.', tweaks: { hairdryer: { replace: 0 }, straighteners: { replace: 0 }, gifts: { replace: 0 }, whisky: { replace: 0 } } },
  },
  {
    id: 'p09', difficulty: 'hard', seed: 809,
    intro: 'Taking our baby to see her grandparents in Lagos, flying from Heathrow. I’m carrying everything.',
    items: ['passport', 'phone', 'meds', 'babyclothes', 'formula', 'babymilk', 'babycarrier', 'toys', 'clothes', 'trainers', 'coat', 'washbag', 'toothpaste', 'moisturiser', 'lenses', 'gifts', 'boardgame', 'whisky', 'gin', 'perfume', 'shortbread', 'teabags', 'kindle', 'tablet', 'powerbank', 'charger', 'cables', 'pillow', 'jacket', 'firstaid'],
    tweaks: { gifts: { replace: null, name: 'Wrapped presents for the family (they must come)' } },
    late: { text: 'Two updates. First, the airline says that on this route the cabin bag limit is 10 kg, not 7 kg (the 12 kg limit for the personal item and cabin bag together stays). Second, my sister is visiting next week and will take the toys and picture books over for us, so they cost nothing to leave behind.', airline: { cabinKg: 10 }, tweaks: { toys: { replace: 0 } } },
    airline: { cabinFee: 0, holdFee: 13500, hold2Fee: 14500, overPerKg: 2000, cabinKg: 7, holdFreeKg: 20, cabinTotalKg: 12 },
  },
  {
    id: 'p10', difficulty: 'hard', seed: 810,
    intro: 'Glastonbury? No — a music festival near Barcelona, flying from Stansted, camping for four nights.',
    items: ['passport', 'phone', 'tent', 'sleepingbag', 'wellies', 'jacket', 'hoodie', 'clothes-light', 'trainers', 'flipflops', 'swimwear', 'washbag', 'toothpaste', 'suncream', 'suncream-mini', 'suncream-face', 'shampoo-mini', 'deodorant', 'speaker', 'powerbank', 'cables', 'multitool', 'towel', 'camera', 'pillow', 'book', 'gin', 'umbrella', 'headphones', 'yogamat'],
    tweaks: { camera: { replace: 25000, name: 'Camera and lens (nice to have)' } },
    airline: { cabinFee: 2900, holdFee: 12900, hold2Fee: 13900, overPerKg: 1400, cabinKg: 10, holdFreeKg: 15, cabinTotalKg: 14 },
    late: { text: 'Correction from the festival site: they now rent out tents AND sleeping bags, £25 each for the whole festival, so replacing either there would only cost £25, not the prices on my list. And the airline has just put the first hold bag up to £99.00 (the second to £109.00).', tweaks: { tent: { replace: 2500 }, sleepingbag: { replace: 2500 } }, airline: { holdFee: 9900, hold2Fee: 10900 } },
  },
];

function makeWorld(spec: PackSpec, useLate: boolean): PackWorld {
  const rng = new Rng(spec.seed);
  const airline = { ...spec.airline, ...(useLate ? spec.late?.airline ?? {} : {}) };
  const tweaks = { ...(spec.tweaks ?? {}), ...(useLate ? spec.late?.tweaks ?? {} : {}) };
  const items: PackItem[] = spec.items.map((key, i) => {
    const c = CATALOGUE.find((x) => x.key === key);
    if (!c) throw new Error(`no item ${key}`);
    const { key: _k, ...rest } = c;
    // Replacement prices jitter by ±10% (to the nearest 50p) so every case has its own numbers.
    const replace = rest.replace === null ? null : Math.round((rest.replace * (0.9 + rng.next() * 0.2)) / 50) * 50;
    return { id: `I${String(i + 1).padStart(2, '0')}`, ...rest, replace, ...(tweaks[key] ?? {}) } as PackItem;
  });
  const bags: PackBag[] = [
    { id: 'personal', label: 'Personal item (under the seat)', maxDims: [40, 30, 20], litres: airline.personalLitres ?? 20, maxKg: null, fee: 0, hold: false },
    { id: 'cabin', label: 'Cabin bag', maxDims: [55, 40, 20], litres: 40, maxKg: airline.cabinKg, fee: airline.cabinFee, hold: false },
    { id: 'hold1', label: 'Hold bag 1', maxDims: [75, 50, 30], litres: 95, maxKg: 32, fee: airline.holdFee, freeKg: airline.holdFreeKg ?? 23, overPerKg: airline.overPerKg, hold: true },
    { id: 'hold2', label: 'Hold bag 2', maxDims: [75, 50, 30], litres: 95, maxKg: 32, fee: airline.hold2Fee, freeKg: airline.holdFreeKg ?? 23, overPerKg: airline.overPerKg, hold: true },
  ];
  return { items, bags, liquidMaxMl: 100, liquidBagMl: 1000, ...(airline.cabinTotalKg !== undefined ? { cabinTotalKg: airline.cabinTotalKg } : {}) };
}

/**
 * Exact optimum. For every set of paid bags (none, cabin, hold 1, cabin + hold 1, two hold bags, all three; the hold
 * bags are interchangeable), a branch-and-bound places every item (largest first) in an open bag, on the body or at
 * home. Bound: fees + overweight so far + replacements so far + the cheapest way (fractionally) to free the litres
 * the remaining items cannot fit, so no branch that could still win is ever cut. Returns the cheapest legal plan.
 */
export function solvePacking(w: PackWorld): { plan: Record<string, PackPlace>; optimum: number } {
  const bagOf = (id: string) => w.bags.find((b) => b.id === id)!;
  const sets: string[][] = [[], ['cabin'], ['hold1'], ['cabin', 'hold1'], ['hold1', 'hold2'], ['cabin', 'hold1', 'hold2']];
  const sd = (d: number[]) => d.slice().sort((x, y) => y - x);
  const fits = (it: PackItem, b: PackBag) => {
    const a = sd(it.dims);
    const c = sd(b.maxDims);
    if (a.some((x, k) => x > c[k]!)) return false;
    if (b.hold && it.battery) return false;
    if (!b.hold && it.sharp) return false;
    if (!b.hold && it.liquidMl !== undefined && !it.medical && it.liquidMl > w.liquidMaxMl) return false;
    if (b.maxKg !== null && it.kg > b.maxKg) return false;
    return true;
  };
  let best = Number.POSITIVE_INFINITY;
  let bestPlan: Record<string, PackPlace> = {};
  const order = w.items.slice().sort((a, b) => b.litres - a.litres || b.kg - a.kg || a.id.localeCompare(b.id));
  for (const set of sets) {
    const open = ['personal', ...set].map(bagOf);
    const fee = set.reduce((s, id) => s + bagOf(id).fee, 0);
    if (fee >= best) continue;
    const kg: Record<string, number> = {};
    const litres: Record<string, number> = {};
    const count: Record<string, number> = {};
    for (const b of open) {
      kg[b.id] = 0;
      litres[b.id] = 0;
      count[b.id] = 0;
    }
    let cabinMl = 0;
    const assign: Record<string, PackPlace> = {};
    const over = () => open.reduce((s, b) => s + (b.freeKg !== undefined && kg[b.id]! > b.freeKg + 1e-9 ? Math.ceil(kg[b.id]! - b.freeKg - 1e-9) * (b.overPerKg ?? 0) : 0), 0);
    const bound = (i: number, left: number): number => {
      let lb = fee + left + over();
      let cap = open.reduce((s, b) => s + (b.litres - litres[b.id]!), 0);
      const cand: PackItem[] = [];
      for (let k = i; k < order.length; k++) {
        const it = order[k]!;
        if (it.wearable) continue;
        if (!open.some((b) => fits(it, b))) {
          if (it.replace === null) return Number.POSITIVE_INFINITY;
          lb += it.replace;
          continue;
        }
        cand.push(it);
      }
      let need = cand.reduce((s, it) => s + it.litres, 0) - cap;
      if (need > 1e-9) {
        const leavable = cand.filter((it) => it.replace !== null).sort((a, b) => a.replace! / a.litres - b.replace! / b.litres);
        for (const it of leavable) {
          if (need <= 1e-9) break;
          const take = Math.min(it.litres, need);
          lb += (it.replace! * take) / it.litres;
          need -= take;
        }
        if (need > 1e-9) return Number.POSITIVE_INFINITY;
      }
      void cap;
      return lb;
    };
    const rec = (i: number, left: number) => {
      if (bound(i, left) >= best - 1e-9) return;
      if (i === order.length) {
        // Actual cost: only bags with something in them are paid for.
        let c = left;
        for (const b of open) if (count[b.id]) c += b.fee;
        c += over();
        if (c < best) {
          best = c;
          bestPlan = { ...assign };
        }
        return;
      }
      const it = order[i]!;
      for (const b of open) {
        if (b.id === 'hold2' && !count.hold1) continue;
        if (!fits(it, b)) continue;
        if (litres[b.id]! + it.litres > b.litres + 1e-9) continue;
        if (b.maxKg !== null && kg[b.id]! + it.kg > b.maxKg + 1e-9) continue;
        if (!b.hold && w.cabinTotalKg !== undefined && (kg.personal ?? 0) + (kg.cabin ?? 0) + it.kg > w.cabinTotalKg + 1e-9) continue;
        const liq = !b.hold && it.liquidMl !== undefined && !it.medical;
        if (liq && cabinMl + it.liquidMl! > w.liquidBagMl) continue;
        kg[b.id]! += it.kg;
        litres[b.id]! += it.litres;
        count[b.id]!++;
        if (liq) cabinMl += it.liquidMl!;
        assign[it.id] = b.id;
        rec(i + 1, left);
        delete assign[it.id];
        if (liq) cabinMl -= it.liquidMl!;
        count[b.id]!--;
        litres[b.id]! -= it.litres;
        kg[b.id]! -= it.kg;
      }
      if (it.wearable) {
        assign[it.id] = 'wear';
        rec(i + 1, left);
        delete assign[it.id];
      }
      if (it.replace !== null) {
        assign[it.id] = 'leave';
        rec(i + 1, left + it.replace);
        delete assign[it.id];
      }
    };
    rec(0, 0);
  }
  if (!Number.isFinite(best)) throw new Error('no legal plan');
  return { plan: bestPlan, optimum: best };
}

export function packingAnswer(w: PackWorld, plan: Record<string, PackPlace>, total: number): Record<string, unknown> {
  const of = (p: PackPlace) => w.items.filter((it) => (plan[it.id] ?? 'leave') === p).map((it) => it.id);
  const hold = [of('hold1'), of('hold2')].filter((b) => b.length);
  return { personal: of('personal'), cabin: of('cabin'), hold, wear: of('wear'), leave: of('leave'), total: pounds(total) };
}

function kgText(n: number): string {
  return `${Math.round(n * 100) / 100} kg`;
}

function promptFor(spec: PackSpec, shown: PackWorld): string {
  const a = { ...spec.airline };
  const L: string[] = [spec.intro, '', 'Here is everything I’d like to take. "Replace" is what it would cost me to buy it there if I leave it at home; MUST means it has to come with me.', ''];
  L.push('ID | Item | Weight | Volume | Size (cm) | Replace | Notes');
  for (const it of shown.items) {
    const notes = [it.liquidMl !== undefined ? `liquid, ${it.liquidMl} ml container${it.medical ? ' (medicine/baby milk)' : ''}` : '', it.battery ? 'spare lithium battery' : '', it.sharp ? 'sharp' : '', it.wearable ? 'can be worn' : ''].filter(Boolean).join('; ');
    L.push(`${it.id} | ${it.name} | ${kgText(it.kg)} | ${it.litres} l | ${it.dims.join(' × ')} | ${it.replace === null ? 'MUST' : money(it.replace)} | ${notes || '-'}`);
  }
  L.push('', 'AIRLINE RULES (fees are for the whole return trip; the same things come home with me)');
  L.push('- Personal item under the seat: free. Up to 40 × 30 × 20 cm, 20 litres; no weight limit.');
  L.push(`- Cabin bag: ${a.cabinFee ? money(a.cabinFee) : 'included in my ticket (free)'}. Up to 55 × 40 × 20 cm, 40 litres, ${a.cabinKg} kg.`);
  L.push(`- Hold bags (at most 2): first ${money(a.holdFee)}, second ${money(a.hold2Fee)}. Each up to 75 × 50 × 30 cm, 95 litres and 32 kg. Each includes ${a.holdFreeKg ?? 23} kg; every kg or part of a kg over that costs ${money(a.overPerKg)}.`);
  L.push('- An item fits a bag only if its size fits inside the bag’s size (in any orientation: compare longest with longest, and so on). The volumes of the items in a bag add up and must not exceed its litres; their weights add up too.');
  L.push('- Liquids, gels and pastes in the cabin (personal item or cabin bag): each container at most 100 ml, and all of them together must fit in one 1-litre bag (at most 1,000 ml in total). Any size is fine in a hold bag.');
  if (shown.items.some((it) => it.medical)) L.push('- Exception: medicines and baby milk (marked "medicine/baby milk") may go in the cabin in any size, outside the 1-litre bag; they do not count towards the 1,000 ml. (In the hold, any liquid is fine anyway.)');
  if (shown.cabinTotalKg !== undefined) L.push(`- On this airline the personal item and the cabin bag together may weigh at most ${shown.cabinTotalKg} kg (as well as the cabin bag’s own limit).`);
  L.push('- Spare lithium batteries (power banks) must travel in the cabin, never in a hold bag. Sharp items (scissors with blades over 6 cm, knives, multitools) must go in a hold bag, never in the cabin.');
  L.push('- Things marked "can be worn" can be worn onto the plane instead (they then count towards no bag). Nothing else can be worn.');
  L.push('- You only pay for a bag if you put something in it. The total cost = bag fees + overweight fees + the "Replace" price of everything left at home.');
  if (spec.late) L.push('', spec.late.text);
  L.push(
    '',
    'Pack for the lowest total cost while keeping every rule. Answer with where each item goes, using the item IDs, in this JSON format (each hold bag is its own list; anything not listed is left at home):',
    '```json',
    '{"personal": ["<ID>"], "cabin": ["<ID>"], "hold": [["<ID>"]], "wear": ["<ID>"], "leave": ["<ID>"], "total": 0.00}',
    '```',
    ONE_ANSWER,
  );
  return L.join('\n');
}

export interface PackCase {
  id: string;
  difficulty: 'easy' | 'medium' | 'hard';
  world: PackWorld;
  prompt: string;
  notes: string;
  reference: Record<string, unknown>;
  optimum: number;
  careless: Array<{ label: string; answer: Record<string, unknown> }>;
}

export function buildPackingCases(): PackCase[] {
  return SPECS.map((spec) => {
    const world = makeWorld(spec, true);
    const shown = makeWorld(spec, false);
    const sol = solvePacking(world);
    const reference = packingAnswer(world, sol.plan, sol.optimum);
    const careless: PackCase['careless'] = [];
    // Careless 1: "just take everything": a cabin bag and as many hold bags as needed, first-fit, heaviest first.
    careless.push({ label: 'takes everything in the first bag it fits (no optimising)', answer: firstFit(world) });
    // Careless 2: misses the late message.
    if (spec.late) {
      const s2 = solvePacking(shown);
      careless.push({ label: 'misses the late message', answer: packingAnswer(world, s2.plan, s2.optimum) });
    }
    return {
      id: spec.id,
      difficulty: spec.difficulty,
      world,
      prompt: promptFor(spec, shown),
      reference,
      optimum: sol.optimum,
      careless,
      notes: `[${spec.difficulty}] Cheapest legal plan ${money(sol.optimum)} (exhaustive branch-and-bound over every place for every item). One optimal plan: ${JSON.stringify(reference)}.`,
    };
  });
}

/** A naive packer: every item into the first bag (personal, cabin, hold 1, hold 2) where it is allowed, heaviest first; worn if it can't go anywhere. */
function firstFit(w: PackWorld): Record<string, unknown> {
  const order = w.items.slice().sort((a, b) => b.kg - a.kg);
  const kg: Record<string, number> = { personal: 0, cabin: 0, hold1: 0, hold2: 0 };
  const litres: Record<string, number> = { personal: 0, cabin: 0, hold1: 0, hold2: 0 };
  let ml = 0;
  const plan: Record<string, PackPlace> = {};
  for (const it of order) {
    let done = false;
    for (const b of w.bags) {
      const sd = it.dims.slice().sort((x, y) => y - x);
      const bd = b.maxDims.slice().sort((x, y) => y - x);
      if (sd.some((x, k) => x > bd[k]!)) continue;
      if (litres[b.id]! + it.litres > b.litres || (b.maxKg !== null && kg[b.id]! + it.kg > Math.min(b.maxKg, b.freeKg ?? b.maxKg))) continue;
      if ((b.hold && it.battery) || (!b.hold && it.sharp)) continue;
      if (!b.hold && it.liquidMl !== undefined && !it.medical && (it.liquidMl > w.liquidMaxMl || ml + it.liquidMl > w.liquidBagMl)) continue;
      if (!b.hold && w.cabinTotalKg !== undefined && kg.personal! + kg.cabin! + it.kg > w.cabinTotalKg) continue;
      kg[b.id]! += it.kg;
      litres[b.id]! += it.litres;
      if (!b.hold && it.liquidMl !== undefined && !it.medical) ml += it.liquidMl;
      plan[it.id] = b.id;
      done = true;
      break;
    }
    if (!done) plan[it.id] = it.wearable ? 'wear' : 'leave';
  }
  // Priced with the real rules so the quoted total is honest.
  let total = 0;
  for (const b of w.bags) {
    const inside = w.items.filter((it) => plan[it.id] === b.id);
    if (!inside.length) continue;
    const k = inside.reduce((s, it) => s + it.kg, 0);
    total += b.fee + (b.freeKg !== undefined && k > b.freeKg ? Math.ceil(k - b.freeKg - 1e-9) * (b.overPerKg ?? 0) : 0);
  }
  for (const it of w.items) if (plan[it.id] === 'leave') total += it.replace ?? 0;
  return packingAnswer(w, plan, total);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  for (const c of buildPackingCases()) {
    console.log(c.id, c.notes);
    for (const k of c.careless) console.log('   careless', k.label, JSON.stringify(k.answer));
    if (process.argv.includes('--prompt')) console.log(c.prompt);
  }
}
