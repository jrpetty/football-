/**
 * DEMO data (?mock=1) for the customers, comms and data Business tests (inbox, email, meetings, helpdesk, replies,
 * contracts, customer list, spreadsheet, SQL, van routes, rota): a scripted reply for every demo answer, built from
 * the REAL answer key with realistic mistakes for the weaker demo models — the generators' own careless and flawed
 * answers mixed into the key record by record (verification/biz-docs/customers-data.mts collects them), the naive
 * answers the case notes record, careless SQL queries, and plans with a swapped drop or shift.
 *
 * Every reply is marked by the real scorer wherever it runs in a browser (the JSON comparer, the reply, routes and
 * rota verifiers); SQL outcomes were worked out by the real result comparison when the demo data was built. The
 * mistakes are chosen so the real mark lands as close as possible to the demo run's score, and the run's own score
 * stands, as for the ladder close-ups. Everything here is demo data — not real results.
 */
import type { CaseResult, CaseResultLite, PromptTest, ScoreDetail, TestDefinition } from '../types.ts';
import { compareJson } from '../../../src/scoring/json-compare.ts';
import { scoreVerifier } from '../../../src/scoring/verifier.ts';
import demoJson from './customersDataDemo.json';
import sqlResults from '../business/docs/customers-data/sqlResults.json';
import { WORK_TESTS } from './areaTests.ts';
import { DEMO_REPLY_NOTE } from './ladderCloseUpMock.ts';

type Json = null | boolean | number | string | Json[] | { [k: string]: Json };

const IDS = ['work.inbox-triage', 'work.email-actions', 'work.meeting-scheduler', 'work.ticket-tag', 'work.support-refunds', 'work.support-replies', 'work.contract-redline', 'work.crm-dedupe', 'work.sheet-lookup', 'work.sql-questions', 'work.sql-hard', 'work.delivery-routes', 'work.shift-rota'];

export function isCustomersDataTest(testId: string | undefined): boolean {
  return !!testId && IDS.includes(testId);
}

interface Slip {
  why: string;
  sql: string;
  score: number;
  live?: { columns: string[]; rows: Array<Array<string | number | null>>; total: number };
  items: Array<{ label: string; passed: boolean; detail: string }>;
}
const DEMO = demoJson as unknown as Record<string, Record<string, { variants?: Record<string, Json>; slips?: Slip[] }>>;

function hash(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return (h >>> 0) / 4294967296;
}
function rng(seed: string): () => number {
  let a = Math.floor(hash(seed) * 4294967296) >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

interface Candidate {
  reply: string;
  score: number;
  passed: boolean;
  summary: string;
  detail: ScoreDetail;
}

// ───────────────────────────── JSON answers ─────────────────────────────

interface JsonScorer {
  type: 'json';
  numberTolerance?: number;
  lenient?: boolean;
  unorderedArrays?: boolean;
  aliases?: Record<string, string>;
  sets?: Record<string, number>;
  partitions?: string[];
  weights?: Record<string, number>;
  aboveConstant?: string[];
}

function markJson(expected: Json, answer: Json, s: JsonScorer, reply: string): Candidate {
  const o = compareJson(expected, answer, { unorderedArrays: s.unorderedArrays, numberTolerance: s.numberTolerance, aliases: s.aliases, lenient: s.lenient, sets: s.sets, partitions: s.partitions, weights: s.weights, aboveConstant: s.aboveConstant });
  const all = o.matched === o.total;
  return { reply, score: Math.round(o.score * 10000) / 10000, passed: all, summary: s.weights || s.aboveConstant ? `${o.matched}/${o.total} points` : `${o.matched}/${o.total} fields correct`, detail: { formatOk: true, items: o.items, expected, extracted: JSON.stringify(answer).slice(0, 4000) } };
}

const isMap = (v: unknown): v is Record<string, Json> => !!v && typeof v === 'object' && !Array.isArray(v);

/** The record ids of an answer: the keys of its id-keyed parts (messages + actions, tickets, changes…) or its own keys. */
function recordIds(key: Json, other: Json): string[] {
  const ids = new Set<string>();
  const maps = isMap(key) ? Object.entries(key).filter(([, v]) => isMap(v)) : [];
  if (maps.length) {
    for (const [k] of maps) {
      for (const id of Object.keys((key as Record<string, Json>)[k] as object)) ids.add(id);
      if (isMap(other) && isMap(other[k])) for (const id of Object.keys(other[k] as object)) ids.add(id);
    }
  } else if (isMap(key)) for (const id of Object.keys(key)) ids.add(id);
  return [...ids];
}

/** The key with the records in `take` replaced by the other answer's version of them (present or absent). */
function mix(key: Json, other: Json, take: Set<string>): Json {
  if (!isMap(key) || !isMap(other)) return key;
  const out: Record<string, Json> = structuredClone(key);
  const maps = Object.keys(key).filter((k) => isMap(key[k]));
  if (maps.length) {
    for (const k of maps) {
      const mine = out[k] as Record<string, Json>;
      const theirs = isMap(other[k]) ? (other[k] as Record<string, Json>) : {};
      for (const id of take) {
        if (id in theirs) mine[id] = structuredClone(theirs[id]!);
        else delete mine[id];
      }
    }
    // Lists (customer groups): the other answer's whole list once half the records are taken.
    for (const k of Object.keys(key).filter((x) => Array.isArray(key[x]))) if (take.size * 2 >= recordIds(key, other).length && Array.isArray(other[k])) out[k] = structuredClone(other[k]!);
    return out;
  }
  for (const id of take) {
    if (id in other) out[id] = structuredClone(other[id]!);
    else delete out[id];
  }
  return out;
}

/** The mix of the key and one wrong answer whose real mark is nearest `target` (binary search on how many records). */
function nearestMix(expected: Json, wrong: Json, s: JsonScorer, target: number, seed: string, wrap: (a: Json) => string): Candidate {
  const r = rng(seed);
  const ids = recordIds(expected, wrong)
    .map((id) => ({ id, u: r() }))
    .sort((a, b) => a.u - b.u)
    .map((x) => x.id);
  const at = (k: number) => {
    const a = mix(expected, wrong, new Set(ids.slice(0, k)));
    return markJson(expected, a, s, wrap(a));
  };
  let lo = 0;
  let hi = ids.length;
  let best = at(0);
  const consider = (c: Candidate) => {
    if (Math.abs(c.score - target) < Math.abs(best.score - target)) best = c;
  };
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    const c = at(mid + 1);
    consider(c);
    if (c.score > target) lo = mid + 1;
    else hi = mid;
  }
  consider(at(Math.min(ids.length, lo)));
  return best;
}

const jsonWrap = (lead: string) => (a: Json) => `${lead}\n\n\`\`\`json\n${JSON.stringify(a, null, 1)}\n\`\`\``;

// ───────────────────────────── wrong answers for tests without scripted ones ─────────────────────────────

function shiftDay(s: string, days: number): string {
  const d = new Date(`${s.slice(0, 10)}T12:00:00Z`);
  if (Number.isNaN(d.getTime())) return s;
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10) + s.slice(10);
}

/** Typical slips per test, as a whole wrong answer to mix in record by record. */
function madeUpWrong(t: PromptTest, c: PromptTest['cases'][number], r: () => number): Json | null {
  const key = c.expected as Json;
  switch (t.id) {
    case 'work.email-actions': {
      const out: Record<string, Json> = {};
      for (const [id, v] of Object.entries(key as Record<string, { mine: boolean; due: string | null }>)) {
        if (v.mine && v.due) out[id] = { mine: true, due: shiftDay(v.due, r() < 0.5 ? 1 : -1 - Math.floor(r() * 3)) };
        else if (v.mine) out[id] = { mine: false, due: null };
        else out[id] = { mine: true, due: null };
      }
      return out;
    }
    case 'work.ticket-tag': {
      const k = key as Record<string, Json>;
      const owed = Number(k.overcharge_owed ?? 0);
      const bump: Record<string, string> = { P1: 'P2', P2: 'P3', P3: 'P2', P4: 'P3' };
      return { ...k, urgency: bump[String(k.urgency)] ?? 'P3', overcharge_owed: owed > 0 ? 0 : 5, sentiment: k.sentiment === 'negative' ? 'neutral' : 'negative', wants: k.wants === 'refund' ? 'information' : 'refund' };
    }
    case 'work.sheet-lookup': {
      const notes = c.notes ?? '';
      const parse = (re: RegExp) => {
        const m = notes.match(re);
        try {
          return m ? (JSON.parse(m[1]!) as Record<string, Json>) : null;
        } catch {
          return null;
        }
      };
      const naive = parse(/naive literal answers (\{[^}]*\})/);
      const noCorr = parse(/answers without corrections (\{[^}]*\})/);
      const out: Record<string, Json> = { ...(key as Record<string, Json>) };
      for (const q of Object.keys(out)) {
        const alt = noCorr?.[q] !== undefined && JSON.stringify(noCorr[q]) !== JSON.stringify(out[q]) ? noCorr[q] : naive?.[q];
        if (alt !== undefined && JSON.stringify(alt) !== JSON.stringify(out[q])) out[q] = alt!;
        else if (typeof out[q] === 'number') out[q] = Math.round((out[q] as number) * 1.07 * 100) / 100;
      }
      return out;
    }
    case 'work.meeting-scheduler': {
      const out: Record<string, Json> = {};
      for (const [id, v] of Object.entries(key as Record<string, string | null>)) out[id] = typeof v === 'string' ? v.replace(/(\d{2}):(\d{2})$/, (_, h, m) => `${String(Number(h) + (Number(m) >= 30 ? 1 : 0)).padStart(2, '0')}:${String((Number(m) + 30) % 60).padStart(2, '0')}`) : v;
      return out;
    }
  }
  return null;
}

// ───────────────────────────── plans and replies (verifiers) ─────────────────────────────

function markVerifier(name: string, expected: unknown, answer: Json, lead: string, taskText: string): Candidate {
  const reply = `${lead}\n\n\`\`\`json\n${JSON.stringify(answer, null, 1)}\n\`\`\``;
  const o = scoreVerifier(name, expected, reply, taskText);
  return { reply, score: o.score, passed: o.passed, summary: o.summary, detail: o.detail };
}

function replyCandidates(c: PromptTest['cases'][number]): Json[] {
  const ref = (c.expected as { reference: Record<string, Json> }).reference;
  const text = String(ref.reply ?? '');
  const out: Json[] = [ref];
  out.push({ ...ref, reply: text.replace(/^(Hi|Hello|Dear) \w+,/, '$1 there,') });
  out.push({ ...ref, reply: `${text} Our courier has been told about this.` });
  out.push({ ...ref, reply: text.replace(/I am (really )?sorry[^.]*\.\s*|We are sorry[^.]*\.\s*|Sorry[^.]*\.\s*/i, '') });
  out.push({ ...ref, reply: `${text} As a goodwill gesture we have added a voucher to your account.` });
  if (typeof ref.refund === 'number' && ref.refund > 0) {
    const wrong = Math.round((ref.refund as number) * 1.1 * 100) / 100;
    const shown = (n: number) => `£${n.toFixed(2)}`;
    out.push({ ...ref, refund: wrong, reply: text.split(shown(ref.refund as number)).join(shown(wrong)) });
  } else out.push({ ...ref, reply: `${text} We have refunded your delivery charge of £3.99.` });
  return out;
}

type RoutesKey = { reference: { routes: Record<string, string[]>; total_minutes: number } };
function routeCandidates(c: PromptTest['cases'][number], r: () => number): Json[] {
  const ref = (c.expected as RoutesKey).reference;
  const out: Json[] = [ref as unknown as Json, { ...ref, total_minutes: ref.total_minutes + 15 }];
  const vans = Object.keys(ref.routes).filter((v) => ref.routes[v]!.filter((s) => s !== 'BREAK').length >= 2);
  for (let n = 0; n < 6 && vans.length; n++) {
    const v = vans[Math.floor(r() * vans.length)]!;
    const stops = [...ref.routes[v]!];
    const idx = stops.map((s, i) => (s === 'BREAK' ? -1 : i)).filter((i) => i >= 0);
    const i = idx[Math.floor(r() * (idx.length - 1))]!;
    const j = idx[idx.indexOf(i) + 1]!;
    [stops[i], stops[j]] = [stops[j]!, stops[i]!];
    out.push({ ...ref, routes: { ...ref.routes, [v]: stops } });
    // Move one drop to another van.
    const others = Object.keys(ref.routes).filter((x) => x !== v);
    if (others.length) {
      const to = others[Math.floor(r() * others.length)]!;
      const moved = ref.routes[v]!.filter((s) => s !== stops[i]);
      out.push({ ...ref, routes: { ...ref.routes, [v]: moved, [to]: [...ref.routes[to]!, stops[i]!] } });
    }
  }
  return out;
}

type RotaKey = { reference: { rota: Record<string, string[]>; total: number } };
function rotaCandidates(c: PromptTest['cases'][number], r: () => number): Json[] {
  const ref = (c.expected as RotaKey).reference;
  const out: Json[] = [ref as unknown as Json, { ...ref, total: Math.round(ref.total * 1.04 * 100) / 100 }];
  const shifts = Object.keys(ref.rota);
  const people = [...new Set(Object.values(ref.rota).flat())];
  for (let n = 0; n < 5 && shifts.length > 1; n++) {
    const s = shifts[Math.floor(r() * shifts.length)]!;
    const list = ref.rota[s]!;
    if (!list.length) continue;
    const who = list[Math.floor(r() * list.length)]!;
    const sub = people.filter((p) => !list.includes(p));
    if (!sub.length) continue;
    const swapIn = sub[Math.floor(r() * sub.length)]!;
    out.push({ ...ref, rota: { ...ref.rota, [s]: list.map((p) => (p === who ? swapIn : p)) } });
  }
  return out;
}

// ───────────────────────────── the result ─────────────────────────────

const cache = new Map<string, Candidate | null>();

function nearest(cands: Candidate[], target: number): Candidate | null {
  if (!cands.length) return null;
  return cands.reduce((b, c) => (Math.abs(c.score - target) < Math.abs(b.score - target) - 1e-9 ? c : b));
}

/** The area demo's invented score of each result before the real scorer re-marked it (run|key → target). */
const TARGETS = new Map<string, number>();

/**
 * The demo reply for one result, built from its invented target score (null: leave the generic one). Cached by
 * key and target, and the same input always builds the same reply, whatever was opened first.
 */
export function customersDataReply(lite: Pick<CaseResultLite, 'key' | 'testId' | 'caseId' | 'contestantId' | 'repeat' | 'score' | 'status'>): Candidate | null {
  const k = `${lite.key}|${lite.score}`;
  if (cache.has(k)) return cache.get(k)!;
  let out: Candidate | null = null;
  try {
    out = build(lite);
  } catch (err) {
    console.warn('demo reply could not be built', lite.testId, lite.caseId, err);
  }
  cache.set(k, out);
  return out;
}

/**
 * The demo run's result with the real mark of its scripted reply: the reply is chosen to land as near the demo
 * score as the case allows, and the real scorer's mark then stands everywhere (run, inspector, close-up, jobs).
 */
export function decorateCustomersData(lite: CaseResultLite): CaseResultLite {
  if (!isCustomersDataTest(lite.testId) || lite.status !== 'ok' || typeof lite.score !== 'number') return lite;
  if (lite.scoreDetail?.integrity?.verdict === 'cheating') return lite;
  const cand = customersDataReply(lite);
  if (!cand) return lite;
  TARGETS.set(`${lite.runId}|${lite.key}`, lite.score);
  return { ...lite, score: cand.score, passed: cand.passed, summary: cand.summary };
}

function build(lite: Pick<CaseResultLite, 'testId' | 'caseId' | 'contestantId' | 'repeat' | 'score' | 'status'>): Candidate | null {
  if (lite.status !== 'ok' || typeof lite.score !== 'number') return null;
  const t = (WORK_TESTS as TestDefinition[]).find((x) => x.id === lite.testId) as PromptTest | undefined;
  const c = t?.cases.find((x) => x.id === lite.caseId);
  if (!t || !c) return null;
  const target = lite.score;
  const seed = `cd|${lite.contestantId}|${lite.testId}|${lite.caseId}|${lite.repeat}`;
  const r = rng(seed);
  const scorer = (c.scorer ?? t.scorer) as { type: string };
  const task = `${t.preamble ? `${t.preamble}\n\n` : ''}${c.prompt ?? ''}`;
  if (scorer.type === 'json') {
    const js = scorer as JsonScorer;
    const lead = 'I applied the rules item by item.';
    const variants = Object.values(DEMO[t.id]?.[c.id]?.variants ?? {});
    const made = madeUpWrong(t, c, r);
    if (made) variants.push(made);
    const cands = variants.map((v, i) => nearestMix(c.expected as Json, v, js, target, `${seed}|${i}`, jsonWrap(lead)));
    if (!cands.length) cands.push(markJson(c.expected as Json, c.expected as Json, js, jsonWrap(lead)(c.expected as Json)));
    return nearest(cands, target);
  }
  if (scorer.type === 'verifier') {
    const name = (scorer as unknown as { verifier: string }).verifier;
    const answers = name === 'reply' ? replyCandidates(c) : name === 'routes' ? routeCandidates(c, r) : name === 'rota' ? rotaCandidates(c, r) : [];
    const lead = name === 'reply' ? 'I checked the policy in order and wrote the reply.' : 'I checked every rule and kept the total as low as I could.';
    return nearest(answers.map((a) => markVerifier(name, c.expected, a, lead, task)), target);
  }
  if (scorer.type === 'sql') {
    const ref = String((c.expected as { reference: string }).reference);
    const right: Candidate = {
      reply: `This query follows the business rules step by step.\n\n\`\`\`sql\n${ref}\n\`\`\``,
      score: 1,
      passed: true,
      summary: 'Correct on all 4 databases',
      detail: { formatOk: true, items: ['live database (sampled in the prompt)', 'hidden copy A', 'hidden copy B', 'hidden copy C'].map((label) => ({ label, passed: true, detail: 'exact match' })), extracted: ref, expected: ref, liveRows: (sqlResults as Record<string, Record<string, { live: unknown }>>)[t.id]?.[c.id]?.live },
    };
    const slips = (DEMO[t.id]?.[c.id]?.slips ?? []).map<Candidate>((s) => {
      const perfect = s.items.filter((i) => i.passed).length;
      return { reply: `This query follows the business rules step by step.\n\n\`\`\`sql\n${s.sql}\n\`\`\``, score: s.score, passed: perfect === s.items.length, summary: `${perfect}/${s.items.length} databases exactly right · score ${Math.round(s.score * 100)}%`, detail: { formatOk: true, items: s.items, extracted: s.sql, expected: ref, liveRows: s.live } };
    });
    return nearest([right, ...slips], target);
  }
  return null;
}

/** The full result of one demo answer, with the scripted reply and its real mark. */
export function customersDataResult(lite: CaseResultLite, base: CaseResult): CaseResult {
  if (!isCustomersDataTest(lite.testId) || base.status !== 'ok') return base;
  const target = TARGETS.get(`${lite.runId}|${lite.key}`);
  if (target === undefined) return base;
  const cand = customersDataReply({ ...lite, score: target });
  if (!cand || !base.transcript?.length) return base;
  const transcript = base.transcript.map((e, i) => (i === base.transcript!.length - 1 && !e.judge ? { ...e, response: cand.reply } : e));
  return { ...base, score: cand.score, passed: cand.passed, summary: cand.summary, scoreDetail: { ...cand.detail, notes: DEMO_REPLY_NOTE }, transcript };
}
