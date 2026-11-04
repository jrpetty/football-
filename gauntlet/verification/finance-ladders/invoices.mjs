// work.invoices-ladder — "Invoices to Pay: Ladder".
// Ten frozen levels of the same accounts-payable job as work.invoice-matching, built with the SAME generator,
// rules engine and policy text (verification/realworld-business/invoices.mjs). A level prints only the rule
// families it uses, word for word as in the full policy, and each level adds families and volume:
//   L01  5 documents,  2 families (price tolerance, quantity)        ...  L05  the full policy at the size of the
//   current hardest case  ...  L10  the full policy, ~120 documents and chains of interacting rules.
// Every key is the rules engine's answer, cross-checked as work.invoice-matching is: every planted document's
// intended outcome must equal the engine's, every planted rule must change the answer when ignored, and (new
// here) every rule family a level does not print must change nothing when switched off in the engine.

import * as inv from '../realworld-business/invoices.mjs';
import { D, iso } from '../realworld-business/lib.mjs';

const F = inv.FAMILIES;
const upto = (n) => F.slice(0, n);
// Families in the order the ladder adds them (inv.FAMILIES is already in this order).
const FAMS = { 1: upto(2), 2: upto(4), 3: upto(7), 4: upto(10) };
for (let l = 5; l <= 10; l++) FAMS[l] = F.slice();

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
  4: [22, 2, 0],
  5: [40, 5, 0],
  6: [44, 5, 4],
  7: [48, 5, 7],
  8: [52, 5, 10],
  9: [56, 5, 14],
  10: [60, 5, 18],
};
const HAND = {
  1: ['ok', 'hold-price-clear', 'ok-partial', 'hold-qty', 'ok-tolvar'],
  2: ['ok', 'ok-eom', 'hold-price-5', 'ok-tolvar', 'hold-qty', 'ok-partial', 'reject-entity', 'hold-vat'],
  3: ['ok', 'hold-price-2pc', 'ok-tolvar', 'hold-qty', 'ok-partial-earlier', 'ok-delivered-sister', 'hold-vat-rate', 'ok-mixed-vat', 'reject-dup-ledger', 'pair:dup-batch', 'hold-bank', 'hold-no-po', 'ok-ocr'],
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
  for (const x of intents) for (const f of inv.INTENT_FAMILIES[x]) if (!fams.includes(f)) throw new Error(`L${level}: ${x} needs family ${f}`);
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
  for (const d of world.docs) for (const f of inv.INTENT_FAMILIES[d.tag] ?? []) if (!fams.has(f)) bad.push(`${d.docId} (${d.tag}) needs ${f}`);
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

export function scripted(world, sol) {
  const key = JSON.stringify(sol);
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
    'Ten frozen levels of the same accounts-payable job: an AP inbox of supplier invoices and credit notes checked against purchase orders, goods-received notes and a written three-way-match policy, with an outcome, amount to pay and pay-by date for every document and the payment-run total. Each level is strictly bigger and stacks more rule families of the same policy: level 1 has 5 documents and two rules (price tolerance, quantity received); level 5 is the full policy at about the size of the hardest Invoices to Pay case; levels 6 to 10 grow to 130 documents with chains of interacting rules (invoices that use up a part delivery in turn, credit notes that free quantity for a later invoice, a held invoice re-sent and then re-issued). A level is passed at 90% of its points; the headline is the highest level passed reliably.',
  difficulty: 'extreme',
  tags: ['real-world', 'work', 'finance', 'accounts-payable', 'three-way-match', 'ladder', 'json'],
  hook: 'Ten rungs of supplier invoices, from five documents to a hundred and thirty. How far up can it pay the right ones to the penny?',
  maxOutputTokens: 64000,
  timeLimitSec: 1800,
  scorer: { type: 'json', numberTolerance: 1e-8, lenient: true },
  engine: 'verification/realworld-business/invoices.mjs (solve)',
  check: "every planted document's intended outcome equals the engine's, every planted rule changes the answer when ignored, and every rule family the level does not print changes nothing when switched off.",
};
