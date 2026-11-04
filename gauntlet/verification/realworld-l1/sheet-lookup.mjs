// work.sheet-lookup: "Read the Spreadsheet" (Business Ladder, Data & Reporting, Level 1).
// One CSV export per case (40–150 rows) and 1–3 lookup / aggregate questions with exact answers. Rows are
// generated as data; every answer is computed from the data with the stated rules (duplicates once, subtotal
// rows ignored), and the generator checks that the naive reading gives a different answer where a trap is set.
import { rngFor, person, D, addDays, ukNum, plain, HEDGE_RULE, assert } from './lib.mjs';

const REGIONS = ['North', 'Midlands', 'South West', 'London', 'Scotland'];
const PRODUCTS = [
  ['Oak Desk', 18900], ['Oak Desk XL', 24900], ['Mesh Office Chair', 13450], ['Mesh Office Chair Pro', 21900],
  ['Monitor Arm', 4599], ['Desk Lamp', 2850], ['Filing Cabinet 3-Drawer', 11999], ['Standing Desk Frame', 32900],
  ['Footrest', 1899], ['Whiteboard 120x90', 6475],
];
const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June'];

const PLAN = [
  { rows: 40, qs: ['sumRegionMonth'] },
  { rows: 45, qs: ['unitsRepProduct'] },
  { rows: 50, qs: ['sumRegionMonth', 'countOrders'] },
  { rows: 55, qs: ['unitsRepProduct', 'sumRegionMonth'], cancelled: true },
  { rows: 60, qs: ['sumRegionMonth', 'topRep'], cancelled: true, dup: 1 },
  { rows: 65, qs: ['unitsRepProduct', 'sumRegion', 'countOrders'], subtotals: true, cancelled: true },
  // Hard third: bigger sheets, 4–5 questions, late CORRECTION rows that replace an earlier order, net-of-refund and
  // average questions, and the best month.
  { rows: 110, qs: ['topRep', 'sumRegion', 'unitsRepProduct', 'avgRep'], subtotals: true, dup: 2, cancelled: true, refunds: true, corr: 1 },
  { rows: 150, qs: ['countOrders', 'topRep', 'sumRegionMonth', 'netRegion'], subtotals: true, dup: 2, cancelled: true, refunds: true, corr: 2 },
  { rows: 190, qs: ['sumRegion', 'topMonth', 'unitsRepProduct', 'avgRep', 'netRegion'], subtotals: true, dup: 3, cancelled: true, refunds: true, corr: 2 },
  { rows: 240, qs: ['sumRegionMonth', 'topRep', 'unitsRepProduct', 'avgRep', 'topMonth'], subtotals: true, dup: 3, cancelled: true, refunds: true, corr: 3 },
];
const half = (pence, n) => Math.floor((2 * pence + n) / (2 * n)); // mean in pence, rounded half up

function build(plan, i) {
  const rng = rngFor(`sheet-lookup/c${i + 1}`);
  const used = new Set();
  const reps = Array.from({ length: 6 }, () => person(rng, used));
  const repRegion = Object.fromEntries(reps.map((r, k) => [r, REGIONS[k % REGIONS.length]]));
  const start = D(2026, 1, 5);
  const rows = [];
  let id = 100200 + rng.int(0, 600);
  for (let k = 0; k < plan.rows; k++) {
    const rep = rng.pick(reps);
    const region = rng.chance(0.85) ? repRegion[rep] : rng.pick(REGIONS);
    const [product, unit] = rng.pick(PRODUCTS);
    const qty = rng.int(1, 12);
    let status = 'Completed';
    if (plan.cancelled && rng.chance(0.12)) status = 'Cancelled';
    else if (plan.refunds && rng.chance(0.06)) status = 'Refunded';
    rows.push({ id: String(id++ + rng.int(0, 3)), date: addDays(start, rng.int(0, 150)), region, rep, product, qty, unit, total: qty * unit, status });
  }
  // Unique ids (the generator re-numbers any collision).
  const ids = new Set();
  for (const r of rows) {
    while (ids.has(r.id)) r.id = String(Number(r.id) + 1);
    ids.add(r.id);
  }
  rows.sort((a, b) => a.region.localeCompare(b.region) || a.date - b.date || a.id.localeCompare(b.id));

  // Questions, drawn so the answer is non-trivial and unique.
  const completed = (r) => r.status === 'Completed';
  const qs = [];
  for (const kind of plan.qs) {
    for (let tries = 0; ; tries++) {
      assert(tries < 200, `could not draw ${kind}`);
      if (kind === 'sumRegionMonth') {
        const region = rng.pick(REGIONS);
        const m = rng.int(1, 5);
        const f = (r) => completed(r) && r.region === region && r.date.getUTCMonth() + 1 === m;
        const hits = rows.filter(f);
        if (hits.length < 3) continue;
        qs.push({ kind, filter: f, text: `What is the total value (£, the Total column) of Completed orders in the ${region} region dated in ${MONTHS[m - 1]} 2026?`, answer: (rs) => Number(plain(rs.filter(f).reduce((s, r) => s + r.total, 0))) });
        break;
      }
      if (kind === 'sumRegion') {
        const region = rng.pick(REGIONS);
        const f = (r) => completed(r) && r.region === region;
        if (rows.filter((r) => r.region === region && !completed(r)).length < 1) continue;
        qs.push({ kind, filter: f, region, text: `What is the total value (£, the Total column) of all Completed orders in the ${region} region?`, answer: (rs) => Number(plain(rs.filter(f).reduce((s, r) => s + r.total, 0))) });
        break;
      }
      if (kind === 'unitsRepProduct') {
        const rep = rng.pick(reps);
        const [product] = rng.pick(PRODUCTS.filter(([p]) => PRODUCTS.some(([o]) => o !== p && o.startsWith(p))));
        const f = (r) => completed(r) && r.rep === rep && r.product === product;
        if (rows.filter(f).length < 2) continue;
        qs.push({ kind, filter: f, text: `How many units (the Qty column) of "${product}" (exactly that product) did ${rep} sell in Completed orders?`, answer: (rs) => rs.filter(f).reduce((s, r) => s + r.qty, 0) });
        break;
      }
      if (kind === 'countOrders') {
        const region = rng.pick(REGIONS);
        const f = (r) => r.region === region && r.status === 'Cancelled';
        const g = (r) => r.region === region && completed(r) && r.total >= 50000;
        if (plan.cancelled && rows.filter(f).length >= 2) {
          qs.push({ kind, filter: f, text: `How many orders in the ${region} region were Cancelled?`, answer: (rs) => rs.filter(f).length });
        } else {
          if (rows.filter(g).length < 2) continue;
          qs.push({ kind, filter: g, text: `How many Completed orders in the ${region} region have a Total of £500.00 or more?`, answer: (rs) => rs.filter(g).length });
        }
        break;
      }
      if (kind === 'avgRep') {
        const rep = rng.pick(reps);
        const f = (r) => completed(r) && r.rep === rep;
        if (rows.filter(f).length < 5) continue;
        qs.push({ kind, filter: f, text: `What is the average Total (£) per Completed order for ${rep}? Round to the nearest penny (half a penny rounds up).`, answer: (rs) => { const h = rs.filter(f); return Number(plain(half(h.reduce((s, r) => s + r.total, 0), h.length))); } });
        break;
      }
      if (kind === 'netRegion') {
        const region = rng.pick(REGIONS);
        const f = (r) => r.region === region && (completed(r) || r.status === 'Refunded');
        if (rows.filter((r) => r.region === region && r.status === 'Refunded').length < 1) continue;
        qs.push({ kind, filter: f, region, text: `What are the net sales (£) of the ${region} region: the Total of its Completed orders minus the Total of its Refunded orders?`, answer: (rs) => Number(plain(rs.filter(f).reduce((s, r) => s + (completed(r) ? r.total : -r.total), 0))) });
        break;
      }
      if (kind === 'topMonth') {
        const region = rng.pick(REGIONS);
        const f = (r) => completed(r) && r.region === region;
        const byMonth = (rs) => {
          const m = new Map();
          for (const r of rs.filter(f)) m.set(r.date.getUTCMonth(), (m.get(r.date.getUTCMonth()) ?? 0) + r.total);
          return [...m.entries()].sort((a, b) => b[1] - a[1]);
        };
        const ranked = byMonth(rows);
        if (ranked.length < 2 || ranked[0][1] - ranked[1][1] < 3000) continue;
        qs.push({ kind, filter: f, region, text: `In which month of 2026 was the total value of Completed orders in the ${region} region highest? (the month's name)`, answer: (rs) => MONTHS[byMonth(rs)[0][0]] });
        break;
      }
      if (kind === 'topRep') {
        const tot = (rs) => {
          const m = new Map();
          for (const r of rs) if (completed(r)) m.set(r.rep, (m.get(r.rep) ?? 0) + r.total);
          return [...m.entries()].sort((a, b) => b[1] - a[1]);
        };
        const ranked = tot(rows);
        if (ranked[0][1] - ranked[1][1] < 5000) {
          // Nudge: add one big Completed order for the leader so the winner is clear by at least £50.
          const r0 = rows.find((r) => r.rep === ranked[0][0] && completed(r));
          r0.qty += 4;
          r0.total = r0.qty * r0.unit;
          continue;
        }
        qs.push({ kind, filter: () => true, text: 'Which rep has the highest total value of Completed orders across the whole sheet? (full name as written)', answer: (rs) => tot(rs)[0][0] });
        break;
      }
    }
  }

  // Late corrections: a Completed row that matters to a question gets a new quantity in a CORRECTION row at the end.
  const corrections = [];
  if (plan.corr) {
    const relevant = rows.filter((r) => completed(r) && qs.some((q) => !['topRep', 'countOrders', 'topMonth'].includes(q.kind) && q.filter(r)));
    for (const r of rng.sample(relevant, plan.corr)) {
      const qty = Math.max(1, r.qty + rng.pick([-3, -2, -1, 1, 2, 3]));
      corrections.push({ ...r, qty: qty === r.qty ? qty + 1 : qty, total: (qty === r.qty ? qty + 1 : qty) * r.unit, status: 'Correction' });
    }
  }

  // Render: header, rows, duplicates and subtotal rows.
  const fmt = (r) => [r.id, ukNum(r.date), r.region, r.rep, r.product, r.qty, plain(r.unit), plain(r.total), r.status].join(',');
  const out = ['Order ID,Date,Region,Rep,Product,Qty,Unit £,Total £,Status'];
  const dupIdx = new Set();
  if (plan.dup) {
    // Duplicate rows that matter to at least one question.
    const relevant = rows.map((r, k) => [r, k]).filter(([r]) => qs.some((q) => q.kind !== 'topRep' && q.filter(r)));
    for (const [, k] of rng.sample(relevant, plan.dup)) dupIdx.add(k);
    if (qs.some((q) => q.kind === 'topRep')) {
      const m = new Map();
      for (const r of rows) if (completed(r)) m.set(r.rep, (m.get(r.rep) ?? 0) + r.total);
      const [[, top], [second]] = [...m.entries()].sort((a, b) => b[1] - a[1]);
      const flip = rows.findIndex((r) => completed(r) && r.rep === second && m.get(second) + r.total > top);
      if (flip >= 0) dupIdx.add(flip);
    }
  }
  // The export's own subtotals add up every row above them as printed (duplicates and every status included).
  let regionSum = 0;
  const subtotals = {};
  rows.forEach((r, k) => {
    out.push(fmt(r));
    regionSum += r.total;
    if (dupIdx.has(k)) {
      out.push(fmt(r));
      regionSum += r.total;
    }
    const last = k === rows.length - 1 || rows[k + 1].region !== r.region;
    if (plan.subtotals && last) {
      out.push(`SUBTOTAL ${r.region},,,,,,,${plain(regionSum)},`);
      subtotals[r.region] = regionSum;
      regionSum = 0;
    }
  });
  if (corrections.length) for (const c of corrections) out.push(fmt(c));
  const csv = out.join('\n');

  // Answers from the clean data; naive answers re-read the rendered CSV literally (duplicates and subtotals included).
  const parsed = out.slice(1).map((line) => {
    const c = line.split(',');
    if (c[0].startsWith('SUBTOTAL')) return { id: c[0], date: null, region: c[0].slice(9), rep: '', product: '', qty: 0, unit: 0, total: Math.round(Number(c[7]) * 100), status: '' };
    const [d, m, y] = c[1].split('/').map(Number);
    return { id: c[0], date: D(y, m, d), region: c[2], rep: c[3], product: c[4], qty: Number(c[5]), unit: Math.round(Number(c[6]) * 100), total: Math.round(Number(c[7]) * 100), status: c[8] };
  });
  // Re-derive the clean rows from the CSV by the stated rules, and check they equal the source data.
  const byId = new Map();
  for (const r of parsed) {
    if (r.id.startsWith('SUBTOTAL')) continue;
    if (r.status === 'Correction') {
      assert(byId.has(r.id), 'a correction replaces an earlier order');
      byId.set(r.id, { ...r, status: 'Completed' });
    } else if (!byId.has(r.id)) byId.set(r.id, r);
  }
  const clean = [...byId.values()];
  assert(clean.length === rows.length, 'CSV round-trips to the source rows');
  const expected = {};
  const naive = {};
  qs.forEach((q, k) => {
    expected[`q${k + 1}`] = q.answer(clean);
    // Naive reader: counts a repeated row twice and ignores the late corrections.
    naive[`q${k + 1}`] = q.answer(parsed.filter((r) => r.date !== null && r.status !== 'Correction'));
    if (q.kind === 'sumRegion' && plan.subtotals) {
      assert(Number(plain(subtotals[q.region])) !== expected[`q${k + 1}`], 'the printed subtotal is not the answer');
      naive[`q${k + 1}_subtotal`] = Number(plain(subtotals[q.region]));
    }
  });
  if (plan.dup) assert(qs.some((q, k) => expected[`q${k + 1}`] !== naive[`q${k + 1}`]), 'a repeated row changes at least one answer');
  // A strong but error-prone reader: everything right except that it misses the late corrections.
  const slip = {};
  qs.forEach((q, k) => {
    const seen2 = new Set();
    slip[`q${k + 1}`] = q.answer(parsed.filter((r) => r.date !== null && r.status !== 'Correction' && !seen2.has(r.id) && seen2.add(r.id)));
  });
  if (plan.corr) assert(Object.keys(expected).some((k) => expected[k] !== slip[k]), 'a correction changes at least one answer');

  const prompt = [
    'Here is an export of our furniture sales orders (CSV). Please answer the questions below from it.',
    '',
    '<<<',
    csv,
    '>>>',
    '',
    'Notes on the sheet:',
    '- Dates are DD/MM/YYYY.',
    '- The export sometimes repeats a row. Rows with the same Order ID are the same order: count each order once.',
    ...(plan.subtotals ? ['- Rows starting "SUBTOTAL" are subtotals of the rows above them, not orders: do not count them.'] : []),
    '- Only rows with Status "Completed" are completed orders.',
    ...(plan.corr ? ['- A row with Status "Correction" replaces the earlier row with the same Order ID: use the correction\'s values instead, and count that order as Completed.'] : []),
    '',
    'Questions:',
    ...qs.map((q, k) => `q${k + 1}: ${q.text}`),
    '',
    `Reply with ONE JSON object (a \`\`\`json block is fine): {${qs.map((q, k) => `"q${k + 1}": ${q.kind === 'topRep' || q.kind === 'topMonth' ? 'string' : 'number'}`).join(', ')}}. Money in pounds to 2 decimal places, as plain numbers. ${HEDGE_RULE} Each answer is checked separately.`,
  ].join('\n');
  const notes = `${plan.rows} rows${plan.dup ? `, ${dupIdx.size} duplicated` : ''}${plan.subtotals ? ', subtotal rows' : ''}; naive literal answers ${JSON.stringify(naive)}`;
  // A careless reader counts repeated rows twice and reads a printed subtotal as the regional total.
  const careless = {};
  for (const k of Object.keys(expected)) careless[k] = naive[`${k}_subtotal`] ?? naive[k];
  void careless;
  return { case: { id: `c${String(i + 1).padStart(2, '0')}`, prompt, expected, notes: notes + (corrections.length ? `; corrections: ${corrections.map((c) => c.id).join(', ')}; answers without corrections ${JSON.stringify(slip)}` : '') }, careless, slip, naive };
}

export const META = {
  id: 'work.sheet-lookup',
  name: 'Read the Spreadsheet',
  description:
    'Business Ladder, Data & Reporting, Level 1 (Assist). One sales-orders spreadsheet (CSV, 40 to 240 rows) and one to five questions with exact answers: a filtered total, the units one rep sold of one product, a count, the top rep, an average, net sales after refunds and the best month. Later sheets add the traps real exports have: rows repeated by the export (to be counted once, as the notes say), regional SUBTOTAL rows that must not be double counted, cancelled and refunded orders, product names that start the same ("Oak Desk" and "Oak Desk XL"), day/month dates, and in the hard third late CORRECTION rows that replace an earlier order.',
  difficulty: 'medium',
  tags: ['business-ladder', 'data-reporting', 'level-1', 'spreadsheet', 'csv', 'arithmetic', 'json'],
  hook: 'Two hundred rows, five questions, one exact answer each. No formulas allowed.',
  outputTokens: 4000,
  scorer: { type: 'json', numberTolerance: 1e-8, lenient: true, aliases: Object.fromEntries(MONTHS.flatMap((m) => [[m.slice(0, 3), m], [`${m} 2026`, m]])) },
};

export function generate() {
  return PLAN.map((p, i) => build(p, i));
}
