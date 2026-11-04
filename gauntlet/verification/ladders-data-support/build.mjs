// Builds two Business ladders from the business pack's generators and rules engines (verification/realworld-business/):
//   work.crm-ladder      "Clean the Customer List: Ladder"   (crm.mjs)
//   work.support-ladder  "Customer Support Queue: Ladder"    (support.mjs)
//
//   node build.mjs            write tests/work/{crm-ladder,support-ladder}.json and scripted.json
//   node build.mjs --check    exit 1 unless every file is byte-identical to a fresh build
//   node build.mjs --stats    print the growth parameters and token sizes per level
//
// Each ladder is ten frozen levels L01..L10 of the SAME task with the SAME written rules (the full matching rules /
// the full handbook section 4 on every level). What grows is the volume (records, tickets) and the rule families the
// data exercises: L01 needs two families, every level adds more, L05 is the business pack's own hardest mix, and
// L06-L10 go beyond it (longer matching chains and more distractors; longer queues made of interacting traps around
// clock changes and bank holidays), with L10 kept under ~40k input tokens. The answer key of every level is computed
// by the pack's rules engine and accepted only when the engine agrees with what the generator planted (buildWorld).

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { serializeTest, testPath, writeOrCheck, tok } from '../realworld-business/lib.mjs';
import { buildWorld } from '../realworld-business/build.mjs';
import * as crm from '../realworld-business/crm.mjs';
import * as support from '../realworld-business/support.mjs';

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
export const CRM_LEVELS = [
  { id: 'L01', seed: 3101, n: 24, dupShare: 0.4, tricky: 0, easyOps: FORMATS, distract: [], nDistract: 0, goneAway: 0, goneAwaySingle: 0, noteNoise: 0, mustMatter: [], families: ['formats'] },
  { id: 'L02', seed: 3102, n: 40, dupShare: 0.4, tricky: 0, easyOps: NAMES1, distract: ['shared-mailbox'], nDistract: 1, goneAway: 0, goneAwaySingle: 0, mustMatter: ['names'], families: ['formats', 'nicknames'] },
  { id: 'L03', seed: 3103, n: 70, dupShare: 0.42, tricky: 0.4, easyOps: NAMES1, hardOps: ['typo', 'swapped', 'nickname', 'mac'], distract: ['shared-mailbox', 'same-name'], nDistract: 1, goneAway: 0.15, mustMatter: ['names', 'goneAway'], families: ['formats', 'names', 'mailbox'] },
  { id: 'L04', seed: 3104, n: 120, dupShare: 0.45, tricky: 0.6, hardOps: ['typo', 'swapped', 'moved', 'married', 'nickname', 'mac'], distract: ['shared-mailbox', 'same-name', 'same-name-same-dob', 'father-son'], nDistract: 2, mustMatter: ['names', 'goneAway', 'survivorship', 'notes'], families: ['formats', 'names', 'mailbox', 'people', 'survive'] },
  { id: 'L05', seed: 3105, n: 220, dupShare: 0.5, tricky: 0.85, mustMatter: ['names', 'goneAway', 'survivorship', 'notes', 'sharedBlock'], families: ['formats', 'names', 'mailbox', 'people', 'survive', 'chains2'] },
  { id: 'L06', seed: 3206, n: 320, dupShare: 0.5, tricky: 0.9, unique: true, hardOps: ['typo', 'swapped', 'moved', 'married', 'nickname', 'mac', 'chain', ...hops(4)], extras: [1, 2, 2, 3, 3], nDistract: 8, mustMatter: ['names', 'goneAway', 'survivorship', 'notes', 'sharedBlock'], families: ['formats', 'names', 'mailbox', 'people', 'survive', 'chains4'] },
  { id: 'L07', seed: 3107, n: 450, dupShare: 0.52, tricky: 0.9, unique: true, hardOps: ['typo', 'swapped', 'moved', 'married', 'nickname', 'mac', 'chain', ...hops(5)], extras: [1, 2, 2, 3, 3, 4], nDistract: 12, mustMatter: ['names', 'goneAway', 'survivorship', 'notes', 'sharedBlock'], families: ['formats', 'names', 'mailbox', 'people', 'survive', 'chains5'] },
  { id: 'L08', seed: 3108, n: 600, dupShare: 0.54, tricky: 0.92, unique: true, hardOps: ['typo', 'swapped', 'moved', 'married', 'nickname', 'mac', 'chain', ...hops(9)], extras: [2, 3, 3, 4, 5, 5], nDistract: 16, mustMatter: ['names', 'goneAway', 'survivorship', 'notes', 'sharedBlock'], families: ['formats', 'names', 'mailbox', 'people', 'survive', 'chains5'] },
  { id: 'L09', seed: 3109, n: 780, dupShare: 0.56, tricky: 0.94, unique: true, hardOps: ['typo', 'swapped', 'moved', 'married', 'nickname', 'mac', 'chain', ...hops(11)], extras: [2, 2, 3, 4, 5, 6], nDistract: 20, mustMatter: ['names', 'goneAway', 'survivorship', 'notes', 'sharedBlock'], families: ['formats', 'names', 'mailbox', 'people', 'survive', 'chains6'] },
  { id: 'L10', seed: 3110, n: 1000, dupShare: 0.58, tricky: 0.95, unique: true, hardOps: ['typo', 'swapped', 'moved', 'married', 'nickname', 'mac', 'chain', ...hops(14)], extras: [2, 3, 4, 5, 6, 7], nDistract: 26, mustMatter: ['names', 'goneAway', 'survivorship', 'notes', 'sharedBlock'], families: ['formats', 'names', 'mailbox', 'people', 'survive', 'chains6'] },
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
export function crmScorer(sol, base) {
  const pairs = sol.clusters.reduce((a, g) => a + (g.length * (g.length - 1)) / 2, 0);
  const w = Math.max(1, Math.round(pairs / (2 * sol.clusters.length)));
  return {
    scorer: w > 1 ? { ...base, weights: { golden: w } } : base,
    marking: `Points at this level: each pair of records correctly put in one group is 1 point (pairwise F1 over all pairs); each golden email and each golden phone is ${w} point${w > 1 ? 's' : ''}, and a golden entry under a wrong master id costs as much as a missing one.`,
  };
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
  { id: 'L03', seed: 4103, now: [2026, 7, 15, 9, 0], kinds: ['com-sale', 'faulty-refund', 'late-express', 'billing-dup', 'com-pers', 'wrong-oos', 'billing-pending', 'late-standard', 'com-all-express', 'missing-lost'], families: ['change-of-mind', 'faulty-30-days', 'missing-parcel', 'wrong-item', 'late-delivery', 'billing', 'personalised-sale-express', 'bst'] },
  { id: 'L04', seed: 4104, now: [2026, 9, 2, 9, 0], kinds: ['faulty-late-el', 'third-contact', 'com-one', 'faulty-old', 'missing-delivered', 'welsh-faulty', 'faulty-late-home', 'legal-threat', 'wrong-instock', 'vague-missing', 'faulty-late-prior', 'late-express', 'com-late', 'billing-dup', 'angry-wrong'], families: ['change-of-mind', 'faulty-30-days', 'missing-parcel', 'wrong-item', 'late-delivery', 'billing', 'personalised-sale-express', 'bst', 'faulty-age-bands', 'priority-p2', 'bank-holiday'] },
  { id: 'L05', seed: 4105, now: [2027, 1, 4, 9, 0], kinds: support.SPECS.find((s) => s.id === 's09').kinds, families: ['all'] },
  { id: 'L06', seed: 4106, now: [2026, 12, 29, 9, 0], kinds: queue(40, 0.62, 6), families: ['all'] },
  { id: 'L07', seed: 4107, now: [2026, 9, 1, 9, 0], kinds: queue(54, 0.68, 7), families: ['all'] },
  { id: 'L08', seed: 4108, now: [2026, 10, 26, 9, 0], kinds: queue(70, 0.74, 8), families: ['all'] },
  { id: 'L09', seed: 4109, now: [2027, 3, 31, 9, 0], kinds: queue(90, 0.8, 9), families: ['all'] },
  { id: 'L10', seed: 4110, now: [2027, 3, 30, 9, 0], kinds: queue(120, 0.85, 10), families: ['all'] },
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
        'Ten levels of the same job: merge the duplicate customers in a leisure centre’s export from two systems by exact written matching rules, and give each group’s surviving master record and golden email and phone. Level 1 has 24 records that differ only in capitals and phone formats; every level adds records and rule families (nicknames, Mac/Mc and typos, shared office mailboxes, moves, married names and the date-of-birth block, staff notes, “gone away” masters, golden records from every record of a group), level 5 matches the hard cases of Clean the Customer List, and levels 6 to 10 grow to 1,000 records with matching chains up to six hops long and more planted look-alikes. Groups are marked pair by pair and golden records as a set; a level is passed at 90% of its points.',
      difficulty: 'extreme',
      tags: ['real-world', 'work', 'ladder', 'data-cleaning', 'deduplication', 'crm', 'entity-resolution', 'json'],
      hook: 'From 24 customer records to 1,000: how far up the clean-up ladder can it merge without merging two different people?',
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
        'Ten levels of the same overnight support queue for an online homeware shop, worked by the same handbook (section 4: change of mind, faulty goods, wrong items, missing and late parcels, billing, fraud, discount codes, priorities and SLA deadlines in UK business hours). Level 1 is four simple returns in winter; every level adds tickets and rule families (missing parcels and wrong items, late delivery and billing, British Summer Time, six-month faults, P2 triggers and bank holidays, then safety, fraud, discount-code shares, minimum-spend codes, split parcels, corrections and reopened tickets); level 5 is the hard mix of Customer Support Queue, and levels 6 to 10 grow to 120 tickets, most of them interacting traps around clock changes and bank holidays. Each ticket’s category, priority, SLA due time, decision and refund are marked; a level is passed at 90% of its points.',
      difficulty: 'extreme',
      tags: ['real-world', 'work', 'ladder', 'customer-service', 'refunds', 'consumer-law', 'sla', 'json'],
      hook: 'From four easy returns to 120 tangled tickets: how far up the support queue can it climb by the handbook?',
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

export function buildLadders() {
  const files = [];
  const scripted = {};
  const report = {};
  for (const { mod, levels, meta, stats, ladderScorer } of LADDERS) {
    const cases = [];
    scripted[meta.id] = {};
    report[meta.id] = [];
    for (const [i, spec] of levels.entries()) {
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
