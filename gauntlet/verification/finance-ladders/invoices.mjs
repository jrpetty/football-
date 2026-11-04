// work.invoices-ladder — "Invoices to Pay: Ladder".
// Ten frozen levels of the accounts-payable job of work.invoice-matching, built with the SAME generator, rules
// engine and policy text (verification/realworld-business/invoices.mjs). A level prints only the rule families it
// uses, word for word as in the full policy. What makes the levels harder is dependency depth: from level 2 on, a
// level has POOLS, PO lines delivered in parts and billed by several documents in turn (3 to 8 per pool, 1 to 10
// pools), each document drawing on what the earlier approved ones left. Between them sit documents held for their
// own reason (price, VAT, changed bank details: they use up nothing), duplicates, an invoice for more than is left
// and credit notes that give quantity back, and a site manager's recount can change a delivery. One misjudged
// document changes what every later document of its pool can draw. The answer also gives each pool's quantity
// still not invoiced after the run.
// Keys: the rules engine. Second derivation for the pools: the planter simulates the policy itself while it plants
// each pool (outcomes in DOC order, quantity left); selfCheck requires the engine to agree with it on every
// document and on every pool's quantity left. Plus the existing checks: every planted rule changes the answer when
// ignored, and every rule family a level does not print changes nothing when switched off.

import * as inv from '../realworld-business/invoices.mjs';
import { D, iso } from '../realworld-business/lib.mjs';

// Families in the order the ladder adds them: inbox consumption (the pools) comes early.
const F = ['price', 'qty', 'consume', 'vat', 'entity', 'dup', 'bank', 'po', 'credit', 'grn-emails', 'discount', 'total-cap', 'amend', 'supplier-net'];
if (F.length !== inv.FAMILIES.length || F.some((f) => !inv.FAMILIES.includes(f))) throw new Error('ladder family order');
const upto = (n) => F.slice(0, n);
const FAMS = { 1: upto(2), 2: upto(4), 3: upto(7), 4: upto(10) };
for (let l = 5; l <= 10; l++) FAMS[l] = F.slice();
/** Pools per level: [number of pools, documents per pool]. */
const POOLS = { 1: [0, 0], 2: [1, 3], 3: [2, 4], 4: [3, 5], 5: [4, 5], 6: [5, 6], 7: [6, 6], 8: [7, 7], 9: [8, 8], 10: [10, 8] };
export const intentFamilies = (x) => (x.startsWith('pool:') ? ['consume', 'qty'] : inv.INTENT_FAMILIES[x]);

/** Intents each family brings, in the order they are dealt. */
const DECK = {
  price: ['hold-price-clear', 'ok-tolvar', 'hold-price-5', 'hold-price-2pc', 'hold-price-under'],
  qty: ['hold-qty', 'ok-partial', 'ok-partial-earlier'],
  entity: ['reject-entity', 'ok-delivered-sister'],
  vat: ['hold-vat', 'hold-vat-rate', 'ok-vat-rounding', 'ok-mixed-vat'],
  dup: ['reject-dup-ledger', 'pair:dup-batch'],
  bank: ['hold-bank', 'ok-bank-format'],
  po: ['hold-no-po', 'ok-ocr', 'hold-no-po-wrong-supplier'],
  discount: ['ok-disc', 'ok-settle', 'ok-disc-expired'],
  credit: ['credit-ledger', 'pair:credit-combined-qty', 'pair:credit-linked-hold', 'pair:credit-combined-price'],
  'grn-emails': ['hold-qty-correction', 'ok-correction', 'grn-revert', 'grn-double'],
  consume: ['pair:consume-hold', 'pair:consume-freed'],
  'total-cap': ['hold-price-total', 'ok-tolvar-multi'],
  amend: ['po-amend-supplier', 'po-amend-ok'],
  'supplier-net': ['credit-exceeds'],
};
const CHAINS = ['chain:consume-3', 'chain:credit-consume', 'chain:reissue'];
const ONCE = new Set(['credit-exceeds', 'ok-ocr']);

/**
 * The intents of one level: plain invoices, then every active family's deck (the first `per` of each), then more
 * rounds of the decks until `n` single intents are dealt, plus `chains` chains of interacting rules.
 */
function deal(level, n, per, chains, plain = 2) {
  const fams = FAMS[level];
  const out = [];
  for (let i = 0; i < plain; i++) out.push(i % 2 ? 'ok-eom' : 'ok');
  for (let round = 0; out.length < n; round++) {
    let added = false;
    for (const f of fams) {
      const deck = DECK[f];
      const take = round === 0 ? deck.slice(0, per) : deck[round % deck.length] ? [deck[round % deck.length]] : [];
      for (const x of take) {
        if (out.length >= n) break;
        if (ONCE.has(x) && out.includes(x)) continue;
        out.push(x);
        added = true;
      }
    }
    if (round % 2 === 1 && out.length < n) out.push(round % 4 === 1 ? 'ok' : 'ok-eom');
    if (!added && round > 8) break;
  }
  for (let i = 0; i < chains; i++) out.push(CHAINS[i % CHAINS.length]);
  return out;
}

// Business, seed, run date and size of every level. The run dates are Thursdays (weekly payment runs).
const BIZ = ['cafe', 'baby', 'plumbing', 'dental'];
const RUNS = [D(2026, 11, 5), D(2026, 11, 12), D(2026, 11, 19), D(2026, 11, 26), D(2026, 12, 3), D(2026, 12, 10), D(2026, 12, 17), D(2027, 1, 7), D(2027, 1, 14), D(2027, 1, 21)];
const SIZE = {
  // level: [single intents, deck cards per family in the first round, chains]
  1: null,
  2: null,
  3: null,
  4: [14, 1, 0],
  5: [22, 1, 0],
  6: [24, 1, 0],
  7: [26, 1, 0],
  8: [28, 1, 0],
  9: [28, 1, 0],
  10: [30, 1, 0],
};
const HAND = {
  1: ['ok', 'hold-price-clear', 'ok-partial', 'hold-qty', 'ok-tolvar'],
  2: ['ok', 'ok-eom', 'hold-price-5', 'ok-tolvar', 'ok-partial', 'hold-vat'],
  3: ['ok', 'hold-price-2pc', 'ok-tolvar', 'hold-qty', 'ok-delivered-sister', 'reject-entity', 'hold-vat-rate', 'reject-dup-ledger', 'hold-bank'],
};

export const SPECS = Array.from({ length: 10 }, (_, i) => {
  const level = i + 1;
  const biz = BIZ[i % 4];
  const fams = FAMS[level];
  const base = inv.BUSINESSES[biz];
  // Discount terms only once the discount family is printed: before that, those suppliers are not in the file.
  const bizData = fams.includes('discount') ? base : { ...base, suppliers: base.suppliers.filter((s) => s.terms.kind === 'net' || s.terms.kind === 'eom') };
  let intents = HAND[level] ?? deal(level, ...SIZE[level]);
  // The dental practice has one supplier with zero- or reduced-rate goods, and the supplier-net case reserves it.
  if (biz === 'dental' && intents.includes('credit-exceeds')) intents = intents.map((x) => ({ 'hold-vat-rate': 'hold-vat', 'ok-mixed-vat': 'ok-vat-rounding' })[x] ?? x);
  const [np, nd] = POOLS[level];
  for (let k = 0; k < np; k++) intents.push(`pool:${nd}`);
  for (const x of intents) for (const f of intentFamilies(x)) if (!fams.includes(f)) throw new Error(`L${level}: ${x} needs family ${f}`);
  return { id: `L${String(level).padStart(2, '0')}`, tier: `level ${level}`, level, biz, seed: 7100 + level * 37, run: RUNS[i], intents, families: fams, bizData };
});

/** Every family the level does not print must change nothing when the engine switches it off. */
export function familyCheck(world, sol) {
  const bad = [];
  const fams = new Set(world.spec.families);
  for (const f of inv.FAMILIES) {
    if (fams.has(f)) continue;
    if (JSON.stringify(inv.solve(world, inv.FAMILY_OFF[f])) !== JSON.stringify(sol)) bad.push(`family ${f} is not printed but changes the key`);
  }
  for (const d of world.docs) for (const f of intentFamilies(d.tag) ?? []) if (!fams.has(f)) bad.push(`${d.docId} (${d.tag}) needs ${f}`);
  // the pools: the engine's quantity left must equal the planter's own simulation
  for (const p of world.pools ?? []) if (sol.remaining_received?.[`${p.po} ${p.code}`] !== p.remaining) bad.push(`pool ${p.po}: engine leaves ${sol.remaining_received?.[`${p.po} ${p.code}`]}, simulation ${p.remaining}`);
  return bad;
}

export const mod = {
  generate: inv.generate,
  solve: (w) => inv.solve(w),
  selfCheck: (w, s) => [...inv.selfCheck(w, s), ...familyCheck(w, s), ...(s.total_payable_this_run > 0 ? [] : ['nothing is paid in this run'])],
};

/** Slips of a careful clerk, most subtle first: the first one that changes this level's key is its "slip" answer. */
const SLIPS = [
  ['supplier-net exception missed', { noSupplierNet: true }],
  ['settlement discount taken on the gross', { settleOnGross: true }],
  ['held invoices still use up the received quantity', { consumeHeld: true }],
  ['£10 cap on the document missed', { noTotalCap: true }],
  ['OCR O/0 not forgiven', { noOcr: true }],
  ['tolerance read as £5 only', { tolFiveOnly: true }],
];

/**
 * One slip that spreads: the first document held for its own reason in the first pool is taken as fine (its price,
 * VAT or bank details misread as matching), and everything after it in that pool is worked out from that mistake.
 */
function poolSlip(world) {
  const d = world.docs.find((x) => x.tag?.startsWith('pool:') && ['hold-price', 'hold-vat', 'hold-bank-details'].includes(world.intent[x.docId]));
  if (!d) return null;
  const s = inv.supplierOf(world, d);
  const po = world.pos.find((p) => p.no === d.poRef);
  const save = { lines: d.lines, vat: d.vat, gross: d.gross, net: d.net, bank: d.bank };
  d.lines = d.lines.map((l) => ({ ...l, price: po.lines.find((x) => x.code === l.code).price }));
  d.net = d.lines.reduce((t, l) => t + l.qty * l.price, 0);
  d.vat = inv.correctVatOf(d.lines);
  d.gross = d.net + d.vat;
  d.bank = { sort: s.sort, acct: s.acct, style: 0 };
  const out = inv.solve(world);
  Object.assign(d, save);
  return out;
}

export function scripted(world, sol) {
  const key = JSON.stringify(sol);
  const ps = world.pools ? poolSlip(world) : null;
  if (ps) return { careless: inv.careless(world), slip: ps, slip_kind: 'one pool document taken as fine, the rest of its pool worked out from that' };
  const slip = SLIPS.find(([, f]) => JSON.stringify(inv.solve(world, f)) !== key);
  let slipAnswer = slip ? inv.solve(world, slip[1]) : null;
  if (!slipAnswer) {
    // No subtle rule in play: the slip is one penny of rounding on the largest invoice paid in this run (its amount
    // and the run total both come out a penny high).
    slipAnswer = JSON.parse(key);
    const paid = Object.entries(slipAnswer.documents).filter(([, d]) => d.outcome === 'approve' && d.amount_to_pay > 0 && d.pay_by < iso(world.nextRun));
    paid.sort((a, b) => b[1].amount_to_pay - a[1].amount_to_pay);
    const [, d] = paid[0];
    d.amount_to_pay = Math.round(d.amount_to_pay * 100 + 1) / 100;
    slipAnswer.total_payable_this_run = Math.round(slipAnswer.total_payable_this_run * 100 + 1) / 100;
  }
  return { careless: inv.careless(world), slip: slipAnswer, slip_kind: slip ? slip[0] : 'one penny of rounding on the largest payment' };
}

export const META = {
  id: 'work.invoices-ladder',
  name: 'Invoices to Pay: Ladder',
  description:
    "Ten frozen levels of accounts payable: an AP inbox of supplier invoices and credit notes checked against purchase orders, goods-received notes and a written three-way-match policy, with an outcome, amount to pay and pay-by date for every document, the payment-run total and, for each pool, the quantity received still not invoiced. Level 1 is 5 documents under two rules; each level adds rules of the same policy and, from level 2, pools: PO lines delivered in parts and billed by several documents in turn (1 pool of 3 documents at level 2, 10 pools of 8 at level 10), where every document draws on what the approved ones before it left, between invoices held for price, VAT or changed bank details (which use up nothing), duplicates, an invoice for more than is left, credit notes that give quantity back and a recount by the site manager. One misjudged document changes what every later one in its pool can draw. A level is passed at 90% of its points; the headline is the highest level passed reliably.",
  difficulty: 'extreme',
  tags: ['real-world', 'work', 'finance', 'accounts-payable', 'three-way-match', 'ladder', 'json'],
  hook: 'Deliveries drawn down invoice by invoice, every outcome resting on the ones before. How far up can it pay the right ones?',
  maxOutputTokens: 64000,
  timeLimitSec: 1800,
  scorer: { type: 'json', numberTolerance: 1e-8, lenient: true },
  engine: 'verification/realworld-business/invoices.mjs (solve)',
  check: "every planted document's intended outcome (for the pools: the planter's own simulation of the policy, document by document) equals the engine's, so does every pool's quantity left, every planted rule changes the answer when ignored, and every rule family the level does not print changes nothing when switched off.",
};
