// work.delivery-note: "Check the Delivery" (Business Ladder, Operations, Level 1).
// A purchase order and the supplier's delivery note (plus our goods-in check) per case. For every PO line the model
// reports what arrived in single units: received, damaged, short, over, and any substitute. The delivery is
// generated as single-unit events and rendered in the units each document uses, so the key is exact.
import { rngFor, person, D, addDays, ukNum, ukShort, HEDGE_RULE, assert } from './lib.mjs';

const CAT = [
  ['OAT-1L', 'Oat milk 1L', 6],
  ['TOM-400', 'Chopped tomatoes 400g tin', 12],
  ['CUP-12', 'Paper cups 12oz (sleeve of 50)', 20],
  ['NAP-2P', 'Napkins 2-ply (pack of 100)', 10],
  ['COF-1K', 'Espresso beans 1kg', 6],
  ['SYR-VAN', 'Vanilla syrup 1L', 6],
  ['LID-12', 'Sip lids 12oz (sleeve of 50)', 20],
  ['BAG-KR', 'Kraft carrier bags (pack of 250)', 4],
  ['GLV-M', 'Vinyl gloves medium (box of 100)', 10],
  ['SAN-5L', 'Surface sanitiser 5L', 4],
  ['STR-PP', 'Paper straws (box of 250)', 12],
  ['CHO-2K', 'Hot chocolate powder 2kg', 6],
  // More stock lines for the long orders of the hard cases.
  ['FLR-1K', 'Plain flour 1kg', 10],
  ['SUG-1K', 'Granulated sugar 1kg', 10],
  ['RIC-5K', 'Basmati rice 5kg', 4],
  ['OIL-5L', 'Rapeseed oil 5L', 3],
  ['BAK-PAP', 'Baking paper roll 450mm', 6],
  ['FOI-450', 'Catering foil 450mm', 6],
  ['CLG-450', 'Cling film 450mm', 6],
  ['PLT-9', 'Paper plates 9in (pack of 50)', 10],
  ['FRK-W', 'Wooden forks (box of 1000)', 5],
  ['STI-W', 'Wooden stirrers (box of 1000)', 10],
];
/** Lines sold in inner packs: [sku, description, units per case, units per pack]. */
const PACKED = [
  ['YOG-125', 'Yoghurt pots 125g', 24, 6],
  ['JUI-250', 'Apple juice cartons 250ml', 27, 9],
  ['CRS-25', 'Crisps 25g', 32, 8],
];
const SUBS = { 'OAT-1L': ['OAT-1LB', 'Oat milk 1L (barista edition)'], 'COF-1K': ['COF-1KD', 'Espresso beans 1kg decaf'], 'SYR-VAN': ['SYR-VSF', 'Vanilla syrup 1L sugar-free'], 'CUP-12': ['CUP-12W', 'Paper cups 12oz white (sleeve of 50)'], 'LID-12': ['LID-12F', 'Flat lids 12oz (sleeve of 50)'], 'CHO-2K': ['CHO-2KV', 'Hot chocolate powder 2kg vegan'] };
const EXTRA = [['MUG-CER', 'Ceramic mug 12oz', 6], ['TEA-EB', 'English breakfast tea (box of 160)', 6]];

/** Per line: PO unit ('each'|'case'), DN unit, and events. Lines are drawn in order from a shuffled catalogue. */
const PLAN = [
  { lines: [{}, { short: 2 }, {}] },
  { lines: [{ po: 'each', dn: 'case' }, { over: 1 }, {}, { short: 1 }] },
  { lines: [{ po: 'case', dn: 'case', damaged: 3 }, {}, { short: 4 }, {}] },
  { lines: [{ sub: 'all' }, { po: 'case', dn: 'each' }, {}, { damaged: 2 }, {}] },
  { lines: [{ po: 'case', dn: 'case', shortCases: 1 }, { po: 'each', dn: 'case', over: 12 }, { sub: 'all' }, {}, { damaged: 1, short: 1 }] },
  { lines: [{ missing: true }, { po: 'case', dn: 'each', short: 3 }, {}, { damaged: 2 }, { po: 'each', dn: 'case' }, {}], extra: true },
  // Hard third: long orders, inner packs, a second same-day delivery note, the driver taking items back, several
  // corrections on one line.
  { lines: [{ packed: true }, { sub: 'all' }, { po: 'case', dn: 'each', damaged: 4 }, {}, { short: 6 }, { po: 'each', dn: 'case', over: 6, tookBack: 6 }, { po: 'case', dn: 'case', recount: 2 }, { missing: true, second: 'all' }, {}, { damaged: 1 }] },
  { lines: [{ sub: 'part' }, { packed: true, short: 6 }, { po: 'case', dn: 'case', damagedCase: true }, { missing: true, second: 'part' }, { po: 'each', dn: 'case', shortCases: 1 }, {}, { over: 2 }, { damaged: 1 }, { po: 'case', dn: 'each', recount: 3 }, {}, { po: 'each', dn: 'case', over: 12, tookBack: 6 }, {}, { short: 4 }], extra: true },
  { lines: [{ po: 'case', dn: 'each', over: 4, damaged: 2 }, { sub: 'part' }, { packed: true, damagedCase: true }, { po: 'case', dn: 'case', recount: 3 }, { missing: true, second: 'all' }, {}, { po: 'each', dn: 'case', damagedCase: true }, { short: 5 }, { sub: 'all' }, { po: 'case', dn: 'case', shortCases: 1 }, {}, { damaged: 3 }, { po: 'each', dn: 'case', over: 6, tookBack: 3 }, { packed: true }, {}, { missing: true }] },
  { lines: [{ sub: 'part' }, { po: 'case', dn: 'each', recount: 4, damaged: 2 }, { missing: true, second: 'part' }, { po: 'each', dn: 'case', over: 12 }, { damagedCase: true, po: 'case', dn: 'case' }, { sub: 'all' }, { short: 3 }, {}, { packed: true, short: 9 }, { packed: true, damagedCase: true }, { po: 'case', dn: 'case', recount: 2, damaged: 1 }, { po: 'each', dn: 'case', over: 6, tookBack: 6 }, {}, { damaged: 2 }, { sub: 'part' }, { missing: true, second: 'all' }, { po: 'case', dn: 'each', shortCases: 1 }, {}, { short: 2 }, { po: 'each', dn: 'case', tookBack: 0, over: 3 }], extra: true },
];

function build(plan, i) {
  const rng = rngFor(`delivery-note/c${i + 1}`);
  const buyer = person(rng);
  const driver = person(rng);
  const site = rng.pick(['The Copper Kettle café, Leeds', 'Northfield Primary School kitchen', 'Harbourside Deli, Bristol', 'Moorland Leisure Centre café', 'Riverside Hotel kitchen, Cardiff']);
  // Lines needing a substitute must use a product that has one.
  const subNeed = plan.lines.filter((l) => l.sub).length;
  const withSub = rng.shuffle(CAT.filter(([sku]) => SUBS[sku]));
  const withoutSub = rng.shuffle(CAT.filter(([sku]) => !withSub.slice(0, subNeed).some(([s]) => s === sku)));
  const packed = rng.shuffle(PACKED);
  let si = 0;
  let wi = 0;
  let pi = 0;
  const lines = plan.lines.map((spec) => {
    if (spec.packed) {
      // Ordered in packs, delivered in cases (and loose packs).
      const [sku, desc, pack, inner] = packed[pi++];
      const ordered = rng.int(2, 5) * pack;
      return { sku, desc, pack, inner, po: 'pack', dn: 'case', ordered, spec };
    }
    const [sku, desc, pack] = spec.sub ? withSub[si++] : withoutSub[wi++];
    const po = spec.po ?? 'each';
    const dn = spec.dn ?? po;
    const orderedCases = rng.int(2, 6);
    const ordered = po === 'case' || dn === 'case' ? orderedCases * pack : rng.int(Math.max(4, (spec.short ?? 0) + (spec.damaged ?? 0) + 4), 30);
    return { sku, desc, pack, po, dn, ordered, spec };
  });
  const usedSkus = new Set(lines.map((l) => l.sku));
  assert(usedSkus.size === lines.length, 'distinct SKUs');

  const poNo = `PO-${rng.int(30000, 59999)}`;
  const dnNo = `DN${rng.int(700000, 799999)}`;
  const poDate = addDays(D(2026, 2, 2), rng.int(0, 240));
  const delDate = addDays(poDate, rng.int(2, 6));

  const qtyText = (n, unit, pack) => {
    if (unit === 'case') {
      const cases = Math.floor(n / pack);
      const loose = n % pack;
      return loose ? `${cases} cs + ${loose} ea` : `${cases} cs`;
    }
    return `${n} ea`;
  };

  const dnLines = [];
  const dn2Lines = [];
  const checks = [];
  const slip = {};
  const expected = {};
  const careless = {};
  const workings = [];
  for (const l of lines) {
    const s = l.spec;
    let received = l.ordered;
    let damaged = 0;
    let substitute = null;
    let subUnits = 0;
    if (s.short) received -= s.short;
    if (s.shortCases) received -= s.shortCases * l.pack;
    if (s.over) received += s.over;
    if (s.missing) received = 0;
    // A second delivery note the same day brings some or all of a missing line.
    const second = s.second === 'all' ? l.ordered : s.second === 'part' ? l.pack * Math.max(1, Math.floor(l.ordered / l.pack / 2)) : 0;
    received += second;
    // The driver took back units that were sent in excess (they leave with the van, so they were not received).
    const tookBack = s.tookBack ?? 0;
    received -= tookBack;
    if (s.sub === 'all') {
      substitute = SUBS[l.sku][0];
      subUnits = received;
      received = 0;
    } else if (s.sub === 'part') {
      substitute = SUBS[l.sku][0];
      subUnits = received >= 2 * l.pack ? l.pack * Math.floor(received / l.pack / 2) : Math.ceil(received / 2);
      received -= subUnits;
    }
    // What the DN claims (before our recount).
    const dnClaim = (s.recount ? received + s.recount : received) - second + tookBack;
    if (s.damaged) damaged = s.damaged;
    if (s.damagedCase) damaged += l.pack; // one whole case crushed
    assert(received >= 0 && damaged <= received, `sane line c${i + 1} ${l.sku} ${JSON.stringify(s)} ordered ${l.ordered} received ${received} damaged ${damaged}`);
    if (!s.missing && dnClaim > 0) dnLines.push(`${l.sku} | ${l.desc} | ${qtyText(dnClaim, l.dn, l.pack)}`);
    if (second) dn2Lines.push(`${l.sku} | ${l.desc} | ${qtyText(second, l.dn, l.pack)}  (the item to follow from ${dnNo})`);
    if (tookBack) checks.push(`${l.desc}: too many were sent, so the driver took ${tookBack} ea back on the van.`);
    if (s.missing) dnLines.push(`${l.sku} | ${l.desc} | 0 ea  ** OUT OF STOCK, to follow on next delivery **`);
    if (substitute) dnLines.push(`${substitute} | ${SUBS[l.sku][1]} | ${qtyText(subUnits, l.dn, l.pack)}  ** SUBSTITUTE for ${l.sku} **`);
    if (s.recount) checks.push(`Recounted ${l.desc}: we actually received ${qtyText(received, l.dn, l.pack)}, not the ${qtyText(dnClaim, l.dn, l.pack)} on the note.`);
    if (s.damaged && !s.damagedCase) checks.push(`${l.desc}: ${s.damaged} damaged (${rng.pick(['split', 'dented', 'leaking', 'crushed'])}), kept aside for the driver to collect.`);
    if (s.damagedCase) checks.push(`${l.desc}: one whole case (${l.inner ? `${l.pack / l.inner} packs of ${l.inner}` : `${l.pack} units`}) arrived crushed and unusable${s.damaged ? `, plus ${s.damaged} more damaged loose` : ''}.`);
    const short = Math.max(0, l.ordered - received);
    const over = Math.max(0, received - l.ordered);
    expected[l.sku] = { received, damaged, short, over, substitute };
    // Careless: trusts the delivery note (no recount), counts the substitute as the product, misses damage.
    const cr = (s.recount ? dnClaim : received) + subUnits;
    careless[l.sku] = { received: cr - second + tookBack, damaged: 0, short: Math.max(0, l.ordered - (cr - second + tookBack)), over: Math.max(0, cr - second + tookBack - l.ordered), substitute: null };
    // A strong but error-prone checker: everything right except that it misses the second delivery note.
    const sr = received - second;
    slip[l.sku] = { received: sr, damaged, short: Math.max(0, l.ordered - sr), over: Math.max(0, sr - l.ordered), substitute };
    workings.push(`${l.sku}: ordered ${l.ordered}, received ${received}${s.recount ? ` (note claimed ${dnClaim})` : ''}, damaged ${damaged}${substitute ? `, substitute ${substitute} x${subUnits}` : ''}`);
  }
  let extra = [];
  if (plan.extra) {
    const [sku, desc, pack] = rng.pick(EXTRA);
    dnLines.splice(rng.int(1, dnLines.length - 1), 0, `${sku} | ${desc} | ${qtyText(pack, 'case', pack)}`);
    extra = [sku];
  }
  if (!checks.length) checks.push('All items checked against the note; no damage found.');
  // Decoy: substitutes are never in the not_ordered list and never count as received.
  const allExpected = { lines: expected, not_ordered: extra };

  const poText = [
    `PURCHASE ORDER ${poNo}   Date: ${ukNum(poDate)}`,
    `Deliver to: ${site}   Ordered by: ${buyer}`,
    'SKU | Item | Pack size | Quantity ordered',
    ...lines.map((l) => `${l.sku} | ${l.desc} | case of ${l.pack}${l.inner ? ` (${l.pack / l.inner} packs of ${l.inner})` : ''} | ${l.po === 'pack' ? `${l.ordered / l.inner} packs` : l.po === 'case' ? `${l.ordered / l.pack} cases` : `${l.ordered} each`}`),
  ].join('\n');
  const dnText = [
    `DELIVERY NOTE ${dnNo}   Your order: ${poNo}   Delivered: ${ukShort(delDate)}`,
    'SKU | Item | Qty  (cs = case, ea = each)',
    ...dnLines,
    `Driver: ${driver}`,
    ...(dn2Lines.length ? ['', `SECOND DELIVERY NOTE ${dnNo.replace(/^DN/, 'DN-B')}   Your order: ${poNo}   Delivered: ${ukShort(delDate)} (later the same day)`, 'SKU | Item | Qty', ...dn2Lines] : []),
  ].join('\n');
  const prompt = [
    `I run the stockroom at ${site.split(',')[0]}. Please check today's delivery against our purchase order and tell me, for each line on the order, what actually arrived.`,
    '',
    'Purchase order:',
    '<<<',
    poText,
    '>>>',
    '',
    'Supplier’s delivery note:',
    '<<<',
    dnText,
    '>>>',
    '',
    'Our goods-in check (done by us when unpacking; where it differs from the delivery note, it is right):',
    '<<<',
    checks.join('\n'),
    '>>>',
    '',
    'Rules: count everything in single units ("each"), using the pack sizes on the purchase order (a case of 12 is 12 units; a pack is the stated number of units). Everything on any delivery note for this order counts, except units the driver took away again. For each PO line:',
    '- received: units of exactly the ordered product (same SKU) that arrived, INCLUDING damaged ones; a substitute product is not the ordered product;',
    '- damaged: how many of those received units are damaged;',
    '- short: ordered minus received if positive, else 0; over: received minus ordered if positive, else 0;',
    '- substitute: the SKU of a different product sent in its place (all or part), or null.',
    'Also list in not_ordered the SKU of anything delivered that is neither on the order nor a substitute ([] if none).',
    '',
    'Reply with ONE JSON object (a ```json block is fine):',
    `{"lines": {${lines.map((l) => `"${l.sku}": {"received": n, "damaged": n, "short": n, "over": n, "substitute": string or null}`).slice(0, 1).join(', ')}, ... one entry per PO line ...}, "not_ordered": [SKU, ...]}`,
    `${HEDGE_RULE} Every number is checked separately.`,
  ].join('\n');
  // Each line's "received" is worth 2 points (the count that drives everything else).
  const scorer = { ...META.scorer, weights: Object.fromEntries(lines.map((l) => [`lines.${l.sku}.received`, 2])) };
  return { careless: { lines: careless, not_ordered: [] }, slip: { lines: slip, not_ordered: extra }, case: { scorer, id: `c${String(i + 1).padStart(2, '0')}`, prompt, expected: allExpected, notes: workings.join('; ') + (extra.length ? `; not ordered: ${extra[0]}` : '') } };
}

export const META = {
  id: 'work.delivery-note',
  name: 'Check the Delivery',
  description:
    'Business Ladder, Operations, Level 1 (Assist). A purchase order, the supplier’s delivery note and our goods-in check per case: for every line on the order, how many units arrived, how many are damaged, what is short or over and what was substituted, all counted in single units although the documents use cases and eaches. Later deliveries bring a whole crushed case, a recount that corrects the note, part of a line substituted with a look-alike product, an item out of stock "to follow", something delivered that nobody ordered, and in the hard third long orders with inner packs, a second same-day delivery note and units the driver took back.',
  difficulty: 'easy',
  tags: ['business-ladder', 'operations', 'level-1', 'reconciliation', 'units', 'json'],
  hook: 'Cases of 12, a crushed box and a sneaky substitute. What actually arrived?',
  outputTokens: 1500,
  scorer: { type: 'json', lenient: true, unorderedArrays: true, aboveConstant: ['lines.*.damaged', 'lines.*.short', 'lines.*.over', 'lines.*.substitute'] },
};

export function generate() {
  return PLAN.map((p, i) => build(p, i));
}
