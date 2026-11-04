// Builds two Business ladders from the business pack's generators and rules engines (verification/realworld-business/):
//   work.crm-ladder      "Clean the Customer List: Ladder"   (crm.mjs)
//   work.support-ladder  "Customer Support Queue: Ladder"    (support.mjs)
//
//   node build.mjs            write tests/work/{crm-ladder,support-ladder}.json and scripted.json
//   node build.mjs --check    exit 1 unless every file is byte-identical to a fresh build
//   node build.mjs --stats    print the growth parameters and token sizes per level
//
// Each ladder is ten frozen levels L01..L10 of the same task. Levels 1-2 use the business pack's rules as they are;
// from level 3 a layer makes answers depend on state built up across items: for the customer list an ordered merge
// procedure with refused merges, planted link records and survivorship precedence (crm-ladder.mjs); for the support
// queue account limits used up over each customer's tickets in received order (support-accounts.mjs). Every key is
// computed by an engine and re-derived independently (mergeCheck/goldenCheck, checkAccounts), and every new rule must
// change the answer on its level; L10 stays under ~40k input tokens.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { serializeTest, testPath, writeOrCheck, tok } from '../realworld-business/lib.mjs';
import { buildWorld } from '../realworld-business/build.mjs';
import * as crm from '../realworld-business/crm.mjs';
import { plantLinks, solveLadder, mergeGroups, mergeCheck, goldenOf, goldenCheck, masterOf, rulesFor } from './crm-ladder.mjs';
import { rng as mkRng } from '../realworld-business/lib.mjs';
import * as support from '../realworld-business/support.mjs';
import { addAccounts, applyAccounts, checkAccounts, changedByAccounts, rulesText, renderAccounts, answerText } from './support-accounts.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));

// ───────────────────────────── Clean the Customer List: Ladder ─────────────────────────────
// Families, in the order the levels add them:
//   formats   R1 email ignoring capitals, R2 phone digits / +44 (exact copies across the two systems)
//   names     nickname table, Mac = Mc, typos, swapped name fields
//   mailbox   shared office mailboxes never link; the same name at another company is a different person
//   people    moves (R1/R3), married names (R4), the DOB block, father and son, staff "not the same person" notes
//   survive   "gone away" masters, golden email/phone from ALL records (not just the master)
//   chains    records linked only through other records (A-B by one rule, B-C by another), up to six hops
const FORMATS = ['copy', 'phone44', 'emailCase'];
const NAMES1 = [...FORMATS, 'nickname', 'mac'];
const hops = (n) => Array.from({ length: n }, () => 'hop');
const ALL_SHAPES = ['join', 'single', 'join', 'swap', 'join', 'chain', 'join', 'chainMid', 'note', 'join', 'deep'];
export const CRM_LEVELS = [
  { id: 'L01', seed: 3101, n: 24, dupShare: 0.4, tricky: 0, easyOps: FORMATS, distract: [], nDistract: 0, goneAway: 0, goneAwaySingle: 0, noteNoise: 0, mustMatter: [], families: ['formats'] },
  { id: 'L02', seed: 3102, n: 40, dupShare: 0.4, tricky: 0, easyOps: NAMES1, distract: ['shared-mailbox'], nDistract: 1, goneAway: 0, goneAwaySingle: 0, mustMatter: ['names'], families: ['formats', 'nicknames'] },
  { id: 'L03', seed: 3103, ladder: { merge: true, links: 3, shapes: ['single', 'swap', 'note'], vipNotes: 0 }, n: 70, dupShare: 0.42, tricky: 0.4, easyOps: NAMES1, hardOps: ['typo', 'swapped', 'nickname', 'mac'], distract: ['shared-mailbox', 'same-name'], nDistract: 1, goneAway: 0.15, mustMatter: ['names', 'goneAway'], families: ['formats', 'names', 'mailbox'] },
  { id: 'L04', seed: 3104, ladder: { merge: true, survive: true, links: 5, shapes: ['join', 'single', 'swap', 'join', 'note'], vipNotes: 0 }, n: 120, dupShare: 0.45, tricky: 0.6, hardOps: ['typo', 'swapped', 'moved', 'married', 'nickname', 'mac'], distract: ['shared-mailbox', 'same-name', 'same-name-same-dob', 'father-son'], nDistract: 2, mustMatter: ['names', 'goneAway', 'survivorship', 'notes'], families: ['formats', 'names', 'mailbox', 'people', 'survive'] },
  { id: 'L05', seed: 3105, ladder: { merge: true, survive: true, vip: true, links: 7, shapes: ALL_SHAPES, vipNotes: 4 }, n: 220, dupShare: 0.5, tricky: 0.85, mustMatter: ['names', 'goneAway', 'survivorship', 'notes', 'sharedBlock'], families: ['formats', 'names', 'mailbox', 'people', 'survive', 'chains2'] },
  { id: 'L06', seed: 3206, ladder: { merge: true, survive: true, vip: true, links: 12, shapes: ALL_SHAPES, vipNotes: 6 }, n: 320, dupShare: 0.5, tricky: 0.9, unique: true, hardOps: ['typo', 'swapped', 'moved', 'married', 'nickname', 'mac', 'chain', ...hops(4)], extras: [1, 2, 2, 3, 3], nDistract: 8, mustMatter: ['names', 'goneAway', 'survivorship', 'notes', 'sharedBlock'], families: ['formats', 'names', 'mailbox', 'people', 'survive', 'chains4'] },
  { id: 'L07', seed: 3107, ladder: { merge: true, survive: true, vip: true, links: 18, shapes: ALL_SHAPES, vipNotes: 8 }, n: 450, dupShare: 0.52, tricky: 0.9, unique: true, hardOps: ['typo', 'swapped', 'moved', 'married', 'nickname', 'mac', 'chain', ...hops(5)], extras: [1, 2, 2, 3, 3, 4], nDistract: 12, mustMatter: ['names', 'goneAway', 'survivorship', 'notes', 'sharedBlock'], families: ['formats', 'names', 'mailbox', 'people', 'survive', 'chains5'] },
  { id: 'L08', seed: 3108, ladder: { merge: true, survive: true, vip: true, links: 24, shapes: ALL_SHAPES, vipNotes: 10 }, n: 560, dupShare: 0.54, tricky: 0.92, unique: true, hardOps: ['typo', 'swapped', 'moved', 'married', 'nickname', 'mac', 'chain', ...hops(9)], extras: [2, 3, 3, 4, 5, 5], nDistract: 16, mustMatter: ['names', 'goneAway', 'survivorship', 'notes', 'sharedBlock'], families: ['formats', 'names', 'mailbox', 'people', 'survive', 'chains5'] },
  { id: 'L09', seed: 3109, ladder: { merge: true, survive: true, vip: true, links: 30, shapes: ALL_SHAPES, vipNotes: 12 }, n: 700, dupShare: 0.56, tricky: 0.94, unique: true, hardOps: ['typo', 'swapped', 'moved', 'married', 'nickname', 'mac', 'chain', ...hops(11)], extras: [2, 2, 3, 4, 5, 6], nDistract: 20, mustMatter: ['names', 'goneAway', 'survivorship', 'notes', 'sharedBlock'], families: ['formats', 'names', 'mailbox', 'people', 'survive', 'chains6'] },
  { id: 'L10', seed: 3110, ladder: { merge: true, survive: true, vip: true, links: 36, shapes: ALL_SHAPES, vipNotes: 14 }, n: 860, dupShare: 0.58, tricky: 0.95, unique: true, hardOps: ['typo', 'swapped', 'moved', 'married', 'nickname', 'mac', 'chain', ...hops(14)], extras: [2, 3, 4, 5, 6, 7], nDistract: 26, mustMatter: ['names', 'goneAway', 'survivorship', 'notes', 'sharedBlock'], families: ['formats', 'names', 'mailbox', 'people', 'survive', 'chains6'] },
].map((s) => ({ ...s, tier: 'ladder' }));

/** Pairs of records in one group that no rule links directly: only a chain through other records joins them. */
export function chainOnlyPairs(records) {
  let n = 0;
  for (const g of crm.clustersOf(records)) for (let i = 0; i < g.length; i++) for (let j = i + 1; j < g.length; j++) if (!crm.pairRule(g[i], g[j])) n++;
  return n;
}

/** The longest chain of a group: the most pairwise-rule hops needed between two of its records (graph diameter). */
export function chainDepth(records) {
  const groups = crm.clustersOf(records).filter((g) => g.length > 1);
  let worst = 0;
  for (const g of groups) {
    const adj = g.map((a) => g.map((b) => a !== b && !!crm.pairRule(a, b)));
    for (let s = 0; s < g.length; s++) {
      const dist = g.map(() => Infinity);
      dist[s] = 0;
      const q = [s];
      while (q.length) {
        const i = q.shift();
        for (let j = 0; j < g.length; j++) if (adj[i][j] && dist[j] === Infinity) (dist[j] = dist[i] + 1), q.push(j);
      }
      worst = Math.max(worst, ...dist);
    }
  }
  return worst;
}

/**
 * Points per level. The grouping earns one point per same-customer pair (pairwise F1); groups of five or six records
 * hold 10-15 pairs each, so on the big levels the pairs would swamp the golden records. Each golden email and phone
 * is therefore worth W = (key pairs) / (2 x groups), at least 1, which keeps grouping and golden records about equal
 * on every level; the prompt states W.
 */
export function crmScorer(sol, base, fields = 2) {
  const pairs = sol.clusters.reduce((a, g) => a + (g.length * (g.length - 1)) / 2, 0);
  const w = Math.max(1, Math.round(pairs / (fields * sol.clusters.length)));
  const sc = fields === 2 ? base : { ...base, sets: { golden: fields } };
  const names = fields === 2 ? 'each golden email and each golden phone' : 'each golden email, phone, address and postcode';
  return {
    scorer: w > 1 ? { ...sc, weights: { golden: w } } : sc,
    marking: `Points at this level: each pair of records correctly put in one group is 1 point (pairwise F1 over all pairs); ${names} is ${w} point${w > 1 ? 's' : ''}, and a golden entry under a wrong master id costs as much as a missing one.`,
  };
}

/** A customer-list level with its merge layer (levels 3-10): link records planted, the ordered merge procedure and
 *  the survivorship precedence rules applied, each re-derived independently (mergeCheck, goldenCheck); every new rule
 *  must change the answer and every planted link must cause a refused merge. */
export function crmLevel(spec) {
  const { world, sol: base, salt } = buildWorld(crm, spec);
  const lv = spec.ladder;
  if (!lv) return { world, sol: base, salt, lv: null, refused: 0 };
  plantLinks(world, { seed: spec.seed, links: lv.links, shapes: lv.shapes });
  if (lv.vipNotes) {
    const r = mkRng(spec.seed * 4451);
    const groups = r.shuffle(mergeGroups(world.records).groups.filter((g) => g.length > 2));
    let n = 0;
    for (const g of groups) {
      if (n >= lv.vipNotes) break;
      const m = masterOf(g, { vip: false });
      const cand = g.filter((x) => x !== m && !x.note && x.status !== 'gone away');
      if (!cand.length) continue;
      r.pick(cand).note = 'VIP - corporate scheme';
      n++;
    }
  }
  const { sol, refused } = solveLadder(world.records, lv);
  const fail = (m) => {
    throw new Error(`${spec.id}: ${m}`);
  };
  if (JSON.stringify(mergeCheck(world.records)) !== JSON.stringify(sol.clusters)) fail('merge engine and replay disagree');
  for (const g of mergeGroups(world.records).groups.filter((x) => x.length > 1)) if (JSON.stringify(goldenOf(g, lv)) !== JSON.stringify(goldenCheck(g, lv))) fail(`golden disagree for ${g[0].id}`);
  if (refused < lv.links) fail(`only ${refused} refused merges for ${lv.links} links`);
  const same = (f) => JSON.stringify(solveLadder(world.records, lv, f).sol) === JSON.stringify(sol);
  if (same({ unordered: true })) fail('the merge procedure changes nothing');
  if (lv.survive && same({ oldSurvivorship: true })) fail('survivorship changes nothing');
  if (lv.vip && same({ noVip: true })) fail('the VIP master rule changes nothing');
  return { world, sol, salt, lv, refused };
}

function crmCase(spec, i, meta) {
  const { world, sol, salt, lv, refused } = crmLevel(spec);
  const prompt0 = lv ? rulesFor(crm.render(world), lv) : crm.render(world);
  const { scorer, marking } = crmScorer(sol, meta.scorer, lv?.survive ? 4 : 2);
  const scripted = crm.scripted(world, sol);
  if (lv) {
    scripted.business_pack_union_find = solveLadder(world.records, lv, { unordered: true }).sol;
    if (lv.survive) scripted.slip_old_survivorship = solveLadder(world.records, lv, { oldSurvivorship: true }).sol;
    if (lv.vip) scripted.slip_ignores_vip_master = solveLadder(world.records, lv, { noVip: true }).sol;
    scripted.careful_human_slips = lv.vip ? scripted.slip_ignores_vip_master : lv.survive ? scripted.slip_old_survivorship : scripted.business_pack_union_find;
  }
  const notes = `[level ${i + 1}] ${crm.summary(world, sol)}; rule families in play: ${spec.families.join(', ')}${lv ? `; ${lv.links} planted link records, ${refused} merges refused by the merge procedure` : ''}${salt ? ` (generator salt ${salt})` : ''}. Key: ${lv ? 'the ordered merge procedure (mergeGroups), replayed independently by mergeCheck; golden records re-derived by goldenCheck' : "the business pack's rules engine, checked against the planted people"}.`;
  return { c: { id: spec.id, prompt: `${prompt0}\n${marking}`, expected: sol, notes, ...(scorer !== meta.scorer ? { scorer } : {}) }, scripted, world, sol, refused };
}

// ───────────────────────────── Customer Support Queue: Ladder ─────────────────────────────
// Rule families (handbook section 4), in the order the levels add them:
//   L01 change of mind (4.2) and faulty goods up to 30 days (4.3a), GMT, no bank holidays
//   L02 + missing parcels (4.5), wrong items (4.4), declines outside 14 days
//   L03 + late delivery (4.6), billing (4.7), personalised items, sale prices, express delivery caps, British Summer Time
//   L04 + faulty after 30 days / six months / repeat faults (4.3b-c), P2 triggers (4.11), a bank holiday, Welsh and vague tickets
//   L05 the business pack's own hardest mix: + safety, fraud (4.8), discount codes (4.9), "I'm the manager" (4.10),
//       compensation demands, day-14/15 and other edges, split parcels, earlier returns, minimum-spend codes,
//       corrections and withdrawals (4.13), mixed tickets (4.14) and reopened tickets
//   L06-L10 the same families at 40 / 54 / 70 / 90 / 120 tickets, a growing share of them interacting traps, around
//       clock changes and bank holidays.
const PLAIN = ['com-one', 'com-all', 'faulty-refund', 'faulty-replace', 'faulty-damaged', 'com-late', 'missing-lost', 'missing-recent', 'wrong-instock', 'wrong-oos', 'late-express', 'late-standard', 'billing-dup', 'billing-pending', 'com-pers', 'com-sale', 'com-all-express', 'missing-delivered'];
const MID = ['faulty-late-el', 'faulty-late-home', 'faulty-old', 'faulty-late-prior', 'third-contact', 'legal-threat', 'vague-missing', 'angry-wrong'];
const TRAPS = ['faulty-safety', 'fraud-claims', 'fraud-other-card', 'com-discount', 'com-fixed-code', 'manager-pressure', 'late-demand', 'com-in-edge', 'com-late-edge', 'faulty-refund-edge', 'faulty-old-edge', 'missing-recent-edge', 'com-split', 'com-minspend', 'com-rest', 'update-found', 'update-item', 'multi-mixed', 'reopened'];
/** A deterministic queue: every trap kind at least once from L05, then a mix with the given trap share. */
function queue(n, trapShare, salt) {
  let x = salt >>> 0;
  const next = () => ((x = (Math.imul(x, 1103515245) + 12345) >>> 0) / 4294967296);
  const out = [...TRAPS, ...MID, 'welsh-faulty', 'com-pers', 'missing-lost', 'wrong-oos', 'late-express', 'billing-pending', 'com-one', 'faulty-damaged'];
  const welshMax = 3;
  while (out.length < n) {
    const u = next();
    const pool = u < trapShare ? TRAPS : u < trapShare + (1 - trapShare) / 3 ? MID : PLAIN;
    let k = pool[Math.floor(next() * pool.length)];
    if (k === 'welsh-faulty' && out.filter((y) => y === k).length >= welshMax) continue;
    out.push(k);
  }
  return out;
}
export const SUPPORT_LEVELS = [
  { id: 'L01', seed: 4101, now: [2026, 11, 25, 9, 0], kinds: ['com-one', 'faulty-refund', 'com-all', 'faulty-replace'], families: ['change-of-mind', 'faulty-30-days'] },
  { id: 'L02', seed: 4102, now: [2026, 2, 11, 9, 0], kinds: ['com-all', 'faulty-damaged', 'missing-lost', 'wrong-instock', 'com-late', 'missing-recent'], families: ['change-of-mind', 'faulty-30-days', 'missing-parcel', 'wrong-item'] },
  { id: 'L03', seed: 4103, accounts: { rules: ['a'], groupShare: 0.5, maxPer: 3, historyShare: 0.3 }, now: [2026, 7, 15, 9, 0], kinds: ['com-sale', 'faulty-refund', 'late-express', 'billing-dup', 'com-pers', 'wrong-oos', 'billing-pending', 'late-standard', 'com-all-express', 'missing-lost'], families: ['change-of-mind', 'faulty-30-days', 'missing-parcel', 'wrong-item', 'late-delivery', 'billing', 'personalised-sale-express', 'bst'] },
  { id: 'L04', seed: 4104, accounts: { rules: ['a', 'b', 'd'], groupShare: 0.55, maxPer: 3, historyShare: 0.4 }, now: [2026, 9, 2, 9, 0], kinds: ['faulty-late-el', 'third-contact', 'com-one', 'faulty-old', 'missing-delivered', 'welsh-faulty', 'faulty-late-home', 'legal-threat', 'wrong-instock', 'vague-missing', 'faulty-late-prior', 'late-express', 'com-late', 'billing-dup', 'angry-wrong'], families: ['change-of-mind', 'faulty-30-days', 'missing-parcel', 'wrong-item', 'late-delivery', 'billing', 'personalised-sale-express', 'bst', 'faulty-age-bands', 'priority-p2', 'bank-holiday'] },
  { id: 'L05', seed: 4105, accounts: { rules: ['a', 'b', 'c', 'd'], groupShare: 0.6, maxPer: 4, historyShare: 0.4 }, now: [2027, 1, 4, 9, 0], kinds: support.SPECS.find((s) => s.id === 's09').kinds, families: ['all'] },
  { id: 'L06', seed: 4106, accounts: { rules: ['a', 'b', 'c', 'd', 'e'], groupShare: 0.65, maxPer: 4, historyShare: 0.5 }, now: [2026, 12, 29, 9, 0], kinds: queue(40, 0.62, 6), families: ['all'] },
  { id: 'L07', seed: 4107, accounts: { rules: ['a', 'b', 'c', 'd', 'e', 'x'], groupShare: 0.7, maxPer: 5, historyShare: 0.5 }, now: [2026, 9, 1, 9, 0], kinds: queue(54, 0.68, 7), families: ['all'] },
  { id: 'L08', seed: 4108, accounts: { rules: ['a', 'b', 'c', 'd', 'e', 'x'], groupShare: 0.75, maxPer: 5, historyShare: 0.6 }, now: [2026, 10, 26, 9, 0], kinds: queue(70, 0.74, 8), families: ['all'] },
  { id: 'L09', seed: 4109, accounts: { rules: ['a', 'b', 'c', 'd', 'e', 'x'], groupShare: 0.8, maxPer: 6, historyShare: 0.6 }, now: [2027, 3, 31, 9, 0], kinds: queue(90, 0.8, 9), families: ['all'] },
  { id: 'L10', seed: 4110, accounts: { rules: ['a', 'b', 'c', 'd', 'e', 'x'], groupShare: 0.85, maxPer: 6, historyShare: 0.7 }, now: [2027, 3, 30, 9, 0], kinds: queue(120, 0.85, 10), families: ['all'] },
].map((s) => ({ ...s, tier: 'ladder' }));
/** Ticket kinds that carry a planted trap (the same list support.caseScorer weights at 5 points). */
export const SUPPORT_TRAP_KINDS = new Set([...MID, ...TRAPS, 'welsh-faulty']);

// ───────────────────────────── Test metadata ─────────────────────────────
const LADDERS = [
  {
    mod: crm,
    levels: CRM_LEVELS,
    meta: {
      id: 'work.crm-ladder',
      name: 'Clean the Customer List: Ladder',
      description:
        'Ten levels of the same job: merge the duplicate customers in a leisure centre’s export from two systems by exact written rules, and give each group’s surviving master record and golden record. Level 1 has 24 records that differ only in capitals and phone formats; level 2 adds nicknames and Mac/Mc. From level 3 the groups are built by a stated merge procedure, one matching pair at a time in a fixed order, and a merge is refused when it would put two different dates of birth (or a staff note saying two records are different people) into one group, so planted link records (a booking with one person’s email and a namesake’s phone, chains of them, bookings that join two existing customers) end up with whichever group the earlier decisions built. Survivorship gains precedence rules with exceptions (a mobile beats a landline, the address comes from the most recent MemberHub record that is not gone away, a corporate-scheme VIP record is the master), so every golden record depends on every merge decision before it. Levels 6 to 10 grow to about 950 records and over a hundred refused merges. Groups are marked pair by pair and golden records as a set; a level is passed at 90% of its points.',
      difficulty: 'extreme',
      tags: ['real-world', 'work', 'ladder', 'data-cleaning', 'deduplication', 'crm', 'entity-resolution', 'json'],
      hook: 'From 24 records to 950, merged one decision at a time: how far up can it go before an early merge sends a later one wrong?',
      maxOutputTokens: 32000,
      outputTokens: 9000,
      scorer: { type: 'json', lenient: true, partitions: ['clusters'], sets: { golden: 2 } },
    },
    ladderScorer: crmScorer,
    stats: (world, sol) => ({ records: world.records.length, groups: sol.clusters.length, chainDepth: chainDepth(world.records), chainOnlyPairs: chainOnlyPairs(world.records), distractors: world.distractors.length }),
  },
  {
    mod: support,
    levels: SUPPORT_LEVELS,
    meta: {
      id: 'work.support-ladder',
      name: 'Customer Support Queue: Ladder',
      description:
        'Ten levels of the same overnight support queue for an online homeware shop, worked by the handbook (change of mind, faulty goods, wrong items, missing and late parcels, billing, fraud, discount codes, priorities and SLA deadlines in UK business hours). Level 1 is four simple returns in winter and level 2 adds missing parcels and wrong items. From level 3 customers have several tickets and an account history, and section 4.15 adds account limits applied in received order: a change-of-mind allowance over 90 days, a yearly goodwill cap, repeat-fault escalation over 180 days, escalation memory that raises later priorities (and so SLA deadlines), and Fernbrook Plus exceptions that end on a date. Each ticket’s outcome depends on the final outcomes of that customer’s earlier tickets, and the answer carries each customer’s refunded total and remaining goodwill. Levels 6 to 10 grow to 120 tickets from 36 accounts around clock changes and bank holidays. A level is passed at 90% of its points.',
      difficulty: 'extreme',
      tags: ['real-world', 'work', 'ladder', 'customer-service', 'refunds', 'consumer-law', 'sla', 'json'],
      hook: 'From four easy returns to 120 tickets whose outcomes depend on each customer’s history: how far up can it climb?',
      maxOutputTokens: 32000,
      outputTokens: 7000,
      scorer: { type: 'json', numberTolerance: 1e-8, lenient: true },
    },
    stats: (world) => ({ tickets: world.tickets.length, trapTickets: world.tickets.filter((t) => SUPPORT_TRAP_KINDS.has(t.kind)).length, families: new Set(world.tickets.map((t) => familyOf(t.kind))).size }),
  },
];

/** The handbook rule family a support ticket kind exercises (for the growth report and the regression test). */
export function familyOf(kind) {
  if (/^com-(split|rest)$|^multi-mixed$|^reopened$/.test(kind)) return 'multi-item';
  if (/^update-/.test(kind)) return 'updates';
  if (/-edge$/.test(kind)) return 'edges';
  if (/^com-(discount|fixed-code|minspend)$/.test(kind)) return 'discount-codes';
  if (/^fraud-/.test(kind)) return 'fraud';
  if (kind === 'faulty-safety') return 'safety';
  if (kind === 'manager-pressure' || kind === 'late-demand') return 'pressure';
  if (kind === 'third-contact' || kind === 'legal-threat') return 'priority-p2';
  if (/^faulty-(late|old)/.test(kind)) return 'faulty-age-bands';
  if (/^com-(pers|sale|all-express)$/.test(kind)) return 'personalised-sale-express';
  if (/^com-/.test(kind)) return 'change-of-mind';
  if (/^faulty-|^welsh-/.test(kind)) return 'faulty-30-days';
  if (/^missing-|^vague-/.test(kind)) return 'missing-parcel';
  if (/wrong/.test(kind)) return 'wrong-item';
  if (/^late-/.test(kind)) return 'late-delivery';
  if (/^billing-/.test(kind)) return 'billing';
  return kind;
}

/** A support level with its account layer (levels 3-10): the business pack's world and key, customers grouped and
 *  given histories, the key re-derived by applyAccounts() and confirmed by checkAccounts(); every active account rule
 *  (and, where expired memberships are planted, the expiry) must change at least one ticket, else the next accounts
 *  seed is tried. */
export function supportLevel(spec) {
  for (let k = 0; k < 80; k++) {
    const { world, sol: base, salt } = buildWorld(support, spec);
    if (!spec.accounts) return { world, base, sol: base, salt, acctSalt: 0, cfg: null };
    const cfg = { ...spec.accounts, seed: spec.seed * 31 + k };
    addAccounts(world, cfg);
    const sol = applyAccounts(world, base, cfg);
    const bad = checkAccounts(world, base, cfg, sol);
    if (bad.length) throw new Error(`${spec.id}: account engine and recount disagree: ${bad.slice(0, 3).join('; ')}`);
    const same = (c, f) => JSON.stringify(applyAccounts(world, base, c, f)) === JSON.stringify(sol);
    if (cfg.rules.filter((x) => x !== 'x').some((x) => same({ ...cfg, rules: cfg.rules.filter((y) => y !== x) }))) continue;
    if (cfg.rules.includes('x') && same(cfg, { plusForever: true })) continue;
    return { world, base, sol, salt, acctSalt: k, cfg };
  }
  throw new Error(`${spec.id}: no account layout makes every rule bite`);
}

function supportCase(spec, i, meta) {
  const { world, base, sol, salt, acctSalt, cfg } = supportLevel(spec);
  let prompt = support.render(world);
  const scorer = support.caseScorer(world, meta.scorer);
  const scripted = support.scripted(world, base);
  let changed = [];
  if (cfg) {
    const cut = (marker, insert) => {
      const at = prompt.indexOf(marker);
      if (at < 0) throw new Error(`marker ${marker}`);
      prompt = prompt.slice(0, at) + insert + prompt.slice(at);
    };
    cut('\n\nSTOCK (ordered items', `\n\n${rulesText(cfg)}`);
    cut('\n\nOPEN TICKETS', `\n\n${renderAccounts(world)}`);
    prompt += `\n${answerText(cfg)}`;
    changed = changedByAccounts(base, sol);
    for (const id of changed) Object.assign(scorer.weights, { [`tickets.${id}.decision`]: 5, [`tickets.${id}.refund`]: 5, [`tickets.${id}.priority`]: 4, [`tickets.${id}.sla_due`]: 4 });
    scorer.weights.accounts = 8;
    const noAcc = applyAccounts(world, base, { ...cfg, rules: [] });
    for (const v of Object.values(scripted)) v.accounts = noAcc.accounts;
    scripted.careful_ignores_account_rules = { tickets: base.tickets, accounts: noAcc.accounts };
    scripted.slip_declined_return_counts = applyAccounts(world, base, cfg, { countDeclined: true });
    scripted.slip_no_escalation_memory = applyAccounts(world, base, cfg, { noMemory: true });
    if (cfg.rules.includes('e')) scripted.slip_expired_plus_counts = applyAccounts(world, base, cfg, { plusForever: true });
    if (cfg.rules.includes('b')) scripted.slip_last_years_goodwill = applyAccounts(world, base, cfg, { lastYear: true });
  }
  const notes = `[level ${i + 1}] ${support.summary(world, base)}; rule families in play: ${spec.families.join(', ')}${cfg ? `; account limits 4.15 ${cfg.rules.filter((x) => x !== 'x').join(', ')} over ${world.accounts.length} accounts change ${changed.length} tickets` : ''}${salt ? ` (generator salt ${salt})` : ''}${acctSalt ? ` (accounts salt ${acctSalt})` : ''}. Ticket key by the business pack's rules engine checked against the planted intent; account limits by applyAccounts(), confirmed ticket by ticket by an independent recount (checkAccounts).`;
  return { c: { id: spec.id, prompt, expected: sol, notes, scorer }, scripted, world, sol, changed };
}

export function buildLadders() {
  const files = [];
  const scripted = {};
  const report = {};
  for (const { mod, levels, meta, stats, ladderScorer } of LADDERS) {
    const cases = [];
    scripted[meta.id] = {};
    report[meta.id] = [];
    for (const [i, spec] of levels.entries()) {
      if (mod === crm) {
        const { c, scripted: sc, world, sol, refused } = crmCase(spec, i, meta);
        cases.push(c);
        scripted[meta.id][spec.id] = sc;
        report[meta.id].push({ level: spec.id, tokens: tok(c.prompt), ...stats(world, sol), refusedMerges: refused });
        continue;
      }
      if (mod === support) {
        const { c, scripted: sc, world, changed } = supportCase(spec, i, meta);
        cases.push(c);
        scripted[meta.id][spec.id] = sc;
        report[meta.id].push({ level: spec.id, tokens: tok(c.prompt), ...stats(world), accounts: world.accounts?.length ?? 0, multiTicketCustomers: new Set(world.tickets.map((t) => t.customer.email)).size < world.tickets.length ? world.tickets.length - new Set(world.tickets.map((t) => t.customer.email)).size : 0, changedByAccounts: changed.length });
        continue;
      }
      const { world, sol, salt } = buildWorld(mod, spec);
      let prompt = mod.render(world);
      const st = stats(world, sol);
      const notes = `[level ${i + 1}] ${mod.summary(world, sol)}; rule families in play: ${spec.families.join(', ')}${salt ? ` (generator salt ${salt})` : ''}. Same rules text as every other level; key computed by the business pack's rules engine and checked against the planted intent.`;
      const c = { id: spec.id, prompt, expected: sol, notes };
      if (mod.caseScorer) c.scorer = mod.caseScorer(world, meta.scorer);
      if (ladderScorer) {
        const { scorer, marking } = ladderScorer(sol, meta.scorer);
        c.scorer = scorer;
        c.prompt = `${prompt}\n${marking}`;
      }
      cases.push(c);
      scripted[meta.id][spec.id] = mod.scripted(world, sol);
      report[meta.id].push({ level: spec.id, tokens: tok(prompt), ...st });
    }
    const est = Math.round(cases.reduce((s, c) => s + tok(c.prompt), 0) / cases.length);
    const m = { ...meta, version: '1.0.0', category: 'work', estimate: { inputTokens: est, outputTokens: meta.outputTokens } };
    delete m.outputTokens;
    files.push({ file: testPath(meta.id), text: serializeTest(m, cases) });
  }
  files.push({ file: path.join(HERE, 'scripted.json'), text: JSON.stringify(scripted) + '\n' });
  files.push({ file: path.join(HERE, 'growth.json'), text: JSON.stringify(report, null, 1) + '\n' });
  return files;
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.join(HERE, 'build.mjs')) {
  const check = process.argv.includes('--check');
  let ok = true;
  for (const f of buildLadders()) {
    const same = writeOrCheck(f.file, f.text, check);
    if (!same) ok = false;
    console.log(`${check ? (same ? 'same   ' : 'DIFFERS') : 'wrote  '} ${path.relative(path.join(HERE, '..', '..'), f.file)} (${(f.text.length / 1024).toFixed(0)} KB)`);
    if (f.file.endsWith('growth.json') && process.argv.includes('--stats')) console.log(f.text);
  }
  if (!ok) process.exit(1);
  void fs;
}
