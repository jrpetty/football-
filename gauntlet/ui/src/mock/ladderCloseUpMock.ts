/**
 * DEMO data (?mock=1) for the Level close-up of the Frontier and Business ladders: the REAL ladder tests
 * (tests/**\/<id>.json, loaded on demand so the demo bundle stays small) and, for the two ladder demo runs, a
 * scripted reply per attempt built from the real answer key: right at or below the demo model's height, one or
 * two realistic slips above it (a swapped pair, one misjudged islander, one move too many, an arithmetic slip, a
 * failing hidden test, a few wrong records). Replies are marked by the real scorers wherever they run in a browser
 * (exact answers, numbers, exact fractions, the JSON comparer); the coding and SQL outcomes are scripted in the
 * scorers' own words. Whether an attempt passes always comes from the demo run (laddersMock.ts), so the close-up
 * never disagrees with the ladder. Everything here is demo data — not real results.
 */
import type { CaseResult, CaseResultLite, PromptTest, ScoreBreakdownItem, ScoreDetail, TestDetail, TranscriptEntry } from '../types.ts';
import { extractFinalAnswer, normalize, parseNumber } from '../../../src/core/extract.ts';
import { compareJson } from '../../../src/scoring/json-compare.ts';
import { scoreLadder } from '../../../src/scoring/ladder.ts';
import { ladderLevel } from '../../../src/core/ladders.ts';
import { LADDER_DEMO_BUSINESS_RUN, LADDER_DEMO_FRONTIER_RUN } from './laddersMock.ts';
import engineLabels from '../../../verification/engineering-ladder/labels.json';

export const DEMO_REPLY_NOTE = 'Demo data — not real results: a scripted reply built from the answer key.';

const LOADERS: Record<string, () => Promise<{ default: string }>> = {
  'reasoning.grid-ladder': () => import('../../../tests/reasoning/grid-ladder.json?raw'),
  'reasoning.truth-ladder': () => import('../../../tests/reasoning/truth-ladder.json?raw'),
  'reasoning.plans-ladder': () => import('../../../tests/reasoning/plans-ladder.json?raw'),
  'math.exact-ladder': () => import('../../../tests/math/exact-ladder.json?raw'),
  'coding.engineering-ladder': () => import('../../../tests/coding/engineering-ladder.json?raw'),
  'work.invoices-ladder': () => import('../../../tests/work/invoices-ladder.json?raw'),
  'work.payroll-ladder': () => import('../../../tests/work/payroll-ladder.json?raw'),
  'work.expenses-ladder': () => import('../../../tests/work/expenses-ladder.json?raw'),
  'work.reconciliation-ladder': () => import('../../../tests/work/reconciliation-ladder.json?raw'),
  'work.crm-ladder': () => import('../../../tests/work/crm-ladder.json?raw'),
  'work.contract-ladder': () => import('../../../tests/work/contract-ladder.json?raw'),
  'work.sql-ladder': () => import('../../../tests/work/sql-ladder.json?raw'),
  'work.support-ladder': () => import('../../../tests/work/support-ladder.json?raw'),
};

const RUNS = new Set([LADDER_DEMO_FRONTIER_RUN, LADDER_DEMO_BUSINESS_RUN]);

/** Hidden tests per level of Engineering: Ladder (checked against the real test file by test/level-closeup.test.ts). */
export const ENGINE_TESTS_PER_LEVEL = [14, 16, 17, 17, 18, 17, 17, 17, 16, 17];

export function isLadderCloseUpTest(testId: string | undefined): boolean {
  return !!testId && testId in LOADERS;
}

const cache = new Map<string, Promise<PromptTest | null>>();

/** The real ladder test (null when it cannot be loaded). */
export function realLadderTest(testId: string): Promise<PromptTest | null> {
  let p = cache.get(testId);
  if (!p) {
    const load = LOADERS[testId];
    p = load ? load().then((m) => JSON.parse(m.default) as PromptTest).catch(() => null) : Promise.resolve(null);
    cache.set(testId, p);
  }
  return p;
}

function turnsOf(t: PromptTest, caseId: string): string[] {
  const c = t.cases.find((x) => x.id === caseId);
  if (!c) return [''];
  const turns = c.turns?.length ? [...c.turns] : [c.prompt ?? ''];
  if (t.preamble) turns[0] = `${t.preamble}\n\n${turns[0]}`;
  return turns;
}

/** GET /api/tests/:id for a ladder in demo mode: the real definition and questions, with a demo note on the description. */
export async function ladderCloseUpDetail(testId: string, base: TestDetail): Promise<TestDetail> {
  const t = await realLadderTest(testId);
  if (!t) return base;
  return {
    ...base,
    definition: t,
    summary: { ...base.summary, name: t.name, description: t.description, version: t.version, caseCount: t.cases.length, scorerType: t.scorer.type },
    rendered: t.cases.map((c) => ({ caseId: c.id, system: t.system, turns: turnsOf(t, c.id), expected: c.expected, notes: c.notes })),
  };
}

// ───────────────────────────── deterministic choices ─────────────────────────────

function hash(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return (h >>> 0) / 4294967296;
}

/** A seeded generator (mulberry32): consecutive draws are independent, unlike hashes of near-identical strings. */
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

const seedOf = (lite: Pick<CaseResultLite, 'contestantId' | 'testId' | 'caseId' | 'repeat'>) => `closeup|${lite.contestantId}|${lite.testId}|${lite.caseId}|${lite.repeat}`;

/** Failing hidden tests of a failed Engineering attempt: 1 (most often), 2 or 3. */
function engineFailCount(lite: Pick<CaseResultLite, 'contestantId' | 'testId' | 'caseId' | 'repeat'>): number {
  const u = hash(`${seedOf(lite)}|fails`);
  return u < 0.6 ? 1 : u < 0.85 ? 2 : 3;
}

/**
 * One-off slips on a rung below the model's demo height (the demo always passes the other two attempts there, so
 * the rung stays passed reliably and no height changes): a near miss worth looking at in the close-up.
 */
const NEAR_MISSES: Array<[string, string, string, number, number]> = [['helios-nova-3-pro', 'work.invoices-ladder', 'L07', 1, 0.88]];

/**
 * The demo run scores a failed Engineering attempt 0; a real code test scores the share of hidden tests passed.
 * Give the demo the share its scripted close-up shows (heights and passes are unchanged: full marks are needed).
 */
export function decorateLadderCloseUp(lite: CaseResultLite): CaseResultLite {
  if (lite.contestantId === 'random-baseline' || lite.status !== 'ok' || typeof lite.score !== 'number') return lite;
  const lv = ladderLevel(lite.caseId);
  // Business: a miss within reach of the pass mark is a near miss (80–89%), as a real model's first missed rung
  // usually is; heights are unchanged (still under 90%).
  const slip = NEAR_MISSES.find(([c, t, k, r]) => c === lite.contestantId && t === lite.testId && k === lite.caseId && r === lite.repeat);
  if (slip && lite.passed) return { ...lite, score: slip[4], passed: false, summary: `Demo: ${Math.round(slip[4] * 100)} of 100 on level ${lv}, below the pass mark` };
  if (lite.testId.startsWith('work.') && lite.passed === false && lite.score >= 0.6 && lite.score < 0.9) {
    const score = Math.min(0.89, Math.round((0.8 + Math.max(0, lite.score - 0.62) * 0.7) * 100) / 100);
    return { ...lite, score, summary: `Demo: ${Math.round(score * 100)} of 100 on level ${lv}, below the pass mark` };
  }
  if (lite.testId !== 'coding.engineering-ladder' || lite.score !== 0) return lite;
  const level = ladderLevel(lite.caseId);
  const n = level ? ENGINE_TESTS_PER_LEVEL[level - 1] : undefined;
  if (!n) return lite;
  const k = engineFailCount(lite);
  const score = Math.round(((n - k) / n) * 10000) / 10000;
  return { ...lite, score, summary: `Demo: ${n - k}/${n} hidden tests passed on level ${level}` };
}

// ───────────────────────────── replies ─────────────────────────────

const ROLES = ['knight', 'knave', 'spy', 'alternator'];

function gridReply(expected: string, ok: boolean, r: () => number): string {
  const groups = expected.split(';').map((g) => g.split(',').map((v) => v.trim()));
  if (!ok) {
    const slips = r() < 0.65 ? 1 : 2;
    for (let s = 0; s < slips; s++) {
      const g = groups[s % groups.length]!;
      const i = Math.floor(r() * (g.length - 1));
      const j = r() < 0.7 ? i + 1 : Math.min(g.length - 1, i + 2);
      [g[i], g[j]] = [g[j]!, g[i]!];
    }
  }
  return `Working through the clues, placing the fixed positions first and then eliminating case by case.\n\nFINAL ANSWER: ${groups.map((g) => g.join(', ')).join('; ')}`;
}

function truthReply(expected: string, ok: boolean, r: () => number): string {
  const pairs = expected.split(/,\s*/).map((p) => p.split(/:\s*/));
  if (!ok) {
    const slips = r() < 0.6 ? 1 : 2;
    const used = new Set<number>();
    for (let s = 0; s < slips; s++) {
      let i = Math.floor(r() * pairs.length);
      while (used.has(i)) i = (i + 1) % pairs.length;
      used.add(i);
      const now = pairs[i]![1]!;
      const others = ROLES.filter((x) => x !== now && (now === 'spy' || now === 'alternator' || x === 'knight' || x === 'knave'));
      pairs[i]![1] = others[Math.floor(r() * others.length)]!;
    }
  }
  return `Testing each speaker in turn against the counts of spies and alternators.\n\nFINAL ANSWER: ${pairs.map((p) => `${p[0]}: ${p[1]}`).join(', ')}`;
}

function plansReply(optimum: number, ok: boolean, r: () => number): string {
  const said = ok ? optimum : r() < 0.7 ? optimum + 1 : r() < 0.6 ? optimum + 2 : optimum - 1;
  return `Moving the small disks out of the way first, then the largest disk straight to peg D.\n\nFINAL ANSWER: ${said}`;
}

function snakesReply(expected: string, ok: boolean, r: () => number): string {
  if (ok) return `Setting up one equation per square and solving from the finish backwards.\n\nFINAL ANSWER: ${expected}`;
  const [p, q] = expected.split('/').map((x) => BigInt(x)) as [bigint, bigint];
  // An arithmetic slip somewhere in the elimination: a nearby denominator, the numerator scaled to match.
  const bump = BigInt(1 + Math.floor(r() * 9)) * (q > 1000n ? q / 1000n : 1n) + 1n;
  const q2 = q + bump;
  // …and the slip moves the value itself by a few parts in ten thousand.
  let p2 = (p * q2) / q;
  p2 += p2 / BigInt(3000 + Math.floor(r() * 20000));
  if (p2 * q === p * q2) p2 += 1n;
  return `Setting up one equation per square and solving from the finish backwards.\n\nFINAL ANSWER: ${p2}/${q2}`;
}

function preview(v: unknown): string {
  const s = JSON.stringify(v);
  return s === undefined ? 'undefined' : s.length > 80 ? s.slice(0, 77) + '…' : s;
}

async function engineCode(ok: boolean): Promise<string> {
  try {
    const m = ok ? await import('../../../verification/engineering-ladder/ref.js?raw') : await import('../../../verification/engineering-ladder/solutions/slips.js?raw');
    // The verification file's header comment says what the file is for; a model's reply would not.
    return m.default.replace(/^(?:\/\/[^\n]*\n)+/, '');
  } catch {
    return 'function runEngine(input) {\n  // (demo code not available)\n}\n';
  }
}

function engineItems(lite: CaseResultLite, t: PromptTest, ok: boolean): ScoreBreakdownItem[] {
  const c = t.cases.find((x) => x.id === lite.caseId)!;
  const tests = (c.expected as { tests: Array<{ args: unknown[]; expected: unknown }> }).tests;
  const fn = (c.expected as { functionName: string }).functionName;
  const labels = (engineLabels as Record<string, string[]>)[lite.caseId] ?? [];
  const r = rng(`${seedOf(lite)}|tests`);
  const fails = new Map<number, 'fail' | 'timeout'>();
  if (!ok) {
    const k = engineFailCount(lite);
    // The first slip is on a rule test of the newest operation; more failures are large inputs running out of time.
    const ruleIdx = labels.findIndex((l) => !/^random|generated input/.test(l));
    fails.set(Math.max(0, ruleIdx + Math.floor(r() * 3)), 'fail');
    for (let i = tests.length - 1; fails.size < k && i >= 0; i--) if (!fails.has(i) && /large|generated/.test(labels[i] ?? 'large')) fails.set(i, 'timeout');
  }
  const limit = (c.scorer as { timeoutMs?: number } | undefined)?.timeoutMs ?? 10000;
  return tests.map((tc, i) => {
    const label = `test ${i + 1}: ${fn}(${preview(tc.args).replace(/^\[|\]$/g, '')})`;
    const f = fails.get(i);
    if (f === 'timeout') return { label, passed: false, detail: `Timed out after ${limit} ms` };
    if (f === 'fail') {
      const exp = tc.expected as [number, number];
      const got = Array.isArray(exp) ? [exp[0], (Number(exp[1]) + 1 + Math.floor(r() * 99990)) % 1000000007] : exp;
      return { label, passed: false, detail: `expected ${preview(exp)}, got ${preview(got)}` };
    }
    return { label, passed: true, detail: `${(2 + r() * (i > tests.length - 4 ? 900 : 40)).toFixed(1)} ms` };
  });
}

// ── Business: the key with a few realistic wrong fields ──

type Json = null | boolean | number | string | Json[] | { [k: string]: Json };

/** Walk a scorer path ("documents.DOC-30551.outcome", "changes.1.3.type", "bank.B101[0]") to its parent and key. */
function locate(root: Json, path: string): { parent: Record<string, Json> | Json[]; key: string | number } | null {
  let cur: Json = root;
  let rest = path;
  for (;;) {
    if (Array.isArray(cur)) {
      const m = rest.match(/^\[(\d+)\](?:\.)?(.*)$/);
      if (!m) return null;
      if (!m[2]) return { parent: cur, key: Number(m[1]) };
      cur = cur[Number(m[1])] ?? null;
      rest = m[2];
      continue;
    }
    if (!cur || typeof cur !== 'object') return null;
    const obj: Record<string, Json> = cur;
    // Keys may contain dots ("1.3"): take the longest key that the path starts with.
    const key = Object.keys(obj)
      .filter((k) => rest === k || rest.startsWith(`${k}.`) || rest.startsWith(`${k}[`))
      .sort((a, b) => b.length - a.length)[0];
    if (key === undefined) return null;
    if (rest === key) return { parent: obj, key };
    cur = obj[key] ?? null;
    rest = rest.slice(key.length).replace(/^\./, '');
  }
}

function valuesByField(root: Json, out = new Map<string, Set<string>>(), field = ''): Map<string, Set<string>> {
  if (Array.isArray(root)) root.forEach((x) => valuesByField(x, out, field));
  else if (root && typeof root === 'object') for (const [k, v] of Object.entries(root)) valuesByField(v, out, k);
  else if (typeof root === 'string') {
    const s = out.get(field) ?? new Set<string>();
    s.add(root);
    out.set(field, s);
  }
  return out;
}

function shiftDate(s: string, days: number): string {
  const d = new Date(`${s.slice(0, 10)}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10) + s.slice(10);
}

function wrongValue(v: Json, field: string, siblings: Json[], pool: Map<string, Set<string>>, r: () => number): Json {
  if (typeof v === 'boolean') return !v;
  if (typeof v === 'number') {
    // A zero (nothing to pay) slipped into a plausible amount; small counts off by one to three, never below zero.
    if (v === 0) return Math.round((12 + r() * 300) * 100) / 100;
    if (Number.isInteger(v) && Math.abs(v) < 1000) return v > 0 && (v <= 3 || r() < 0.5) ? v + 1 + Math.floor(r() * 3) : v - 1 - Math.floor(r() * Math.min(3, Math.abs(v) - 1));
    const f = 1 + (r() < 0.5 ? -1 : 1) * (0.02 + r() * 0.1);
    const out = Math.round(v * f * 100) / 100;
    return out === v ? v + 1 : out;
  }
  if (typeof v === 'string') {
    if (/^\d{4}-\d{2}-\d{2}/.test(v)) return shiftDate(v, r() < 0.5 ? 1 : 7);
    const alts = [...(pool.get(field) ?? [])].filter((x) => x !== v);
    if (alts.length) return alts[Math.floor(r() * alts.length)]!;
    const sib = siblings.filter((x): x is string => typeof x === 'string' && x !== v);
    if (sib.length) return sib[Math.floor(r() * sib.length)]!;
    return v.replace(/\d(?=\D*$)/, (d) => String((Number(d) + 1) % 10)) + (/\d/.test(v) ? '' : ' (check)');
  }
  if (v === null) {
    const alts = [...(pool.get(field) ?? [])];
    if (alts.length) return alts[Math.floor(r() * alts.length)]!;
    const num = siblings.find((x) => typeof x === 'number');
    return typeof num === 'number' ? num : 'pending';
  }
  return v;
}

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

const jsonOpts = (s: JsonScorer) => ({ unorderedArrays: s.unorderedArrays, numberTolerance: s.numberTolerance, aliases: s.aliases, lenient: s.lenient, sets: s.sets, partitions: s.partitions, weights: s.weights, aboveConstant: s.aboveConstant });

/**
 * The key with wrong fields until the comparer's score comes down to `target` (the demo run's score for this
 * attempt). Whole records go wrong together where a real slip would do that: a document held instead of approved
 * also gets the amount wrong. Later records first (the end of a dependent chain is where slips pile up).
 */
function businessAnswer(expected: Json, scorer: JsonScorer, target: number, r: () => number): Json {
  const answer = structuredClone(expected);
  if (target >= 0.9995) return answer;
  const self = compareJson(expected, expected, jsonOpts(scorer));
  const total = self.total;
  const need = (1 - target) * total;
  const pool = valuesByField(expected);
  const leaves = self.items
    .filter((it) => !/: | has \d+ items$| has no items$| is \{\}$/.test(it.label.replace(/\s\(×[\d.]+\)$/, '')))
    .map((it) => {
      const m = it.label.match(/^(.*?)(?:\s\(×([\d.]+)\))?$/)!;
      return { path: m[1]!, w: Number(m[2] ?? 1) };
    });
  // Group leaves by their record (path minus the last segment), then take records from the back, shuffled a little.
  const byRec = new Map<string, Array<{ path: string; w: number }>>();
  for (const l of leaves) {
    const rec = l.path.replace(/\.[^.[\]]+$/, '');
    byRec.set(rec, [...(byRec.get(rec) ?? []), l]);
  }
  const recs = [...byRec.keys()];
  const order = recs.map((k, i) => ({ k, s: i / recs.length + r() * 0.6 })).sort((a, b) => b.s - a.s).map((x) => x.k);
  let lost = 0;
  for (const rec of order) {
    if (lost >= need - 0.25) break;
    const fields = byRec.get(rec)!;
    // One to three fields of the record, the categorical one first.
    const howMany = 1 + Math.floor(r() * Math.min(3, fields.length));
    for (const f of fields.slice(0, howMany)) {
      if (lost + f.w > need + Math.max(0.6, f.w * 0.5)) continue;
      const at = locate(answer, f.path);
      if (!at) continue;
      const field = typeof at.key === 'string' ? at.key : '';
      const cur = (at.parent as Record<string, Json>)[at.key as never] as Json;
      const siblings = Array.isArray(at.parent) ? at.parent : Object.values(at.parent);
      const next = wrongValue(cur, field, siblings, pool, r);
      if (JSON.stringify(next) === JSON.stringify(cur)) continue;
      (at.parent as Record<string, Json>)[at.key as never] = next as never;
      lost += f.w;
      if (lost >= need - 0.25) break;
    }
  }
  return answer;
}

function sqlTweak(sql: string): string {
  if (/>=/.test(sql)) return sql.replace(/>=/, '>');
  if (/<=/.test(sql)) return sql.replace(/<=/, '<');
  if (/\bDISTINCT\b/i.test(sql)) return sql.replace(/\bDISTINCT\b\s*/i, '');
  return sql.replace(/\bLEFT JOIN\b/i, 'JOIN');
}

function sqlItems(score: number, r: () => number): ScoreBreakdownItem[] {
  const ids = ['live', 'A', 'B', 'C'];
  const label = (id: string) => (id === 'live' ? 'live database (sampled in the prompt)' : `hidden copy ${id}`);
  if (score >= 0.9995) return ids.map((id) => ({ label: label(id), passed: true, detail: 'exact match' }));
  // Spread the lost share over the hidden copies (the sample in the prompt is usually right).
  const shares = ids.map(() => 1);
  let lost = (1 - score) * ids.length;
  for (let i = ids.length - 1; i >= 1 && lost > 1e-6; i--) {
    const take = Math.min(lost, i === 1 ? lost : lost * (0.4 + r() * 0.4));
    shares[i] = Math.max(0, 1 - take);
    lost -= 1 - shares[i]!;
  }
  if (lost > 1e-6) shares[0] = Math.max(0, shares[0]! - lost);
  return ids.map((id, i) => {
    const s = shares[i]!;
    if (s >= 0.9995) return { label: label(id), passed: true, detail: 'exact match' };
    const rows = 20 + Math.floor(r() * 40);
    const right = Math.max(0, Math.round(s * rows));
    return { label: label(id), passed: false, detail: `${right} of ${rows} rows right (${Math.round((right / rows) * 100)}%)` };
  });
}

/** The full result of one attempt of a ladder demo run, with the real question and a scripted reply. */
export async function ladderCloseUpResult(lite: CaseResultLite, base: CaseResult): Promise<CaseResult> {
  if (!RUNS.has(lite.runId) || lite.status !== 'ok' || typeof lite.score !== 'number') return base;
  const t = await realLadderTest(lite.testId);
  const c = t?.cases.find((x) => x.id === lite.caseId);
  if (!t || !c) return base;
  const r = rng(seedOf(lite));
  const ok = lite.passed === true;
  const scorer = (c.scorer ?? t.scorer) as { type: string; normalize?: 'none' | 'trim' | 'lower' | 'alnum' };
  let reply = '';
  let score = lite.score;
  let passed = ok;
  let summary = lite.summary;
  let detail: ScoreDetail = {};
  switch (t.id) {
    case 'reasoning.grid-ladder':
    case 'reasoning.truth-ladder': {
      const key = String((c.expected as string[])[0]);
      reply = t.id === 'reasoning.grid-ladder' ? gridReply(key, ok, r) : truthReply(key, ok, r);
      const { answer, formatOk } = extractFinalAnswer(reply);
      const accepted = c.expected as string[];
      const right = accepted.some((e) => normalize(answer, scorer.normalize ?? 'lower') === normalize(e, scorer.normalize ?? 'lower'));
      score = right ? 1 : 0;
      passed = right;
      summary = right ? 'Correct' : `Answered "${answer.slice(0, 46)}…" · expected another arrangement`;
      detail = { extracted: answer, expected: c.expected, formatOk };
      break;
    }
    case 'reasoning.plans-ladder': {
      reply = plansReply(Number(c.expected), ok, r);
      const { answer, formatOk } = extractFinalAnswer(reply);
      const value = parseNumber(answer);
      passed = value === Number(c.expected);
      score = passed ? 1 : 0;
      summary = passed ? `Correct: ${answer}` : `Answered "${answer}" · expected ${c.expected}`;
      detail = { extracted: answer, expected: c.expected, formatOk, parsed: value };
      break;
    }
    case 'math.exact-ladder': {
      reply = snakesReply(String(c.expected), ok, r);
      const o = scoreLadder('fraction', c.expected, reply);
      ({ score, passed, summary } = o);
      detail = o.detail;
      break;
    }
    case 'coding.engineering-ladder': {
      if (lite.contestantId === 'random-baseline') {
        // The baseline returns a constant: every hidden test fails.
        const c0 = t.cases.find((x) => x.id === lite.caseId)!;
        const tests = (c0.expected as { tests: Array<{ args: unknown[]; expected: unknown }> }).tests;
        const fn = (c0.expected as { functionName: string }).functionName;
        const code = `function ${fn}(input) {\n  return [0, 0];\n}`;
        reply = `\`\`\`javascript\n${code}\n\`\`\``;
        const items = tests.map((tc, i) => ({ label: `test ${i + 1}: ${fn}(${preview(tc.args).replace(/^\[|\]$/g, '')})`, passed: false, detail: `expected ${preview(tc.expected)}, got [0,0]` }));
        score = 0;
        passed = false;
        summary = `0/${items.length} unit tests passed`;
        detail = { formatOk: true, items, extracted: code };
        break;
      }
      const code = await engineCode(ok);
      reply = `Here is a segment tree that keeps the sums, extremes and lazy tags for every range operation.\n\n\`\`\`javascript\n${code.trim()}\n\`\`\``;
      const items = engineItems(lite, t, ok);
      const n = items.filter((i) => i.passed).length;
      score = Math.round((n / items.length) * 10000) / 10000;
      passed = n === items.length;
      summary = `${n}/${items.length} unit tests passed`;
      detail = { formatOk: true, items, extracted: code.slice(0, 20000) };
      break;
    }
    case 'work.sql-ladder': {
      const ref = String((c.expected as { reference: string }).reference);
      const sql = ok && lite.score >= 0.9995 ? ref : sqlTweak(ref);
      reply = `This query applies the rules in order, one CTE per step.\n\n\`\`\`sql\n${sql}\n\`\`\``;
      const items = sqlItems(lite.score, r);
      const perfect = items.filter((i) => i.passed).length;
      summary = perfect === items.length ? `Correct on all ${items.length} databases` : `${perfect}/${items.length} databases exactly right · score ${Math.round(lite.score * 100)}%`;
      detail = { formatOk: true, items, extracted: sql, expected: ref };
      break;
    }
    default: {
      if (scorer.type !== 'json') return base;
      const js = scorer as unknown as JsonScorer;
      const answer = businessAnswer(c.expected as Json, js, lite.score, r);
      reply = `I went through every item in order against the rules.\n\n\`\`\`json\n${JSON.stringify(answer, null, 1)}\n\`\`\``;
      const o = compareJson(c.expected, answer, jsonOpts(js));
      // The demo run's own score stands (the scripted slips land within a hair of it).
      score = lite.score;
      passed = lite.passed === true;
      summary = js.weights || js.aboveConstant ? `${o.matched}/${o.total} points` : `${o.matched}/${o.total} fields correct`;
      detail = { formatOk: true, items: o.items, expected: c.expected, extracted: JSON.stringify(answer).slice(0, 4000) };
    }
  }
  detail = { ...detail, notes: DEMO_REPLY_NOTE };
  const m = lite.metrics;
  const transcript: TranscriptEntry[] = [
    {
      label: 'answer',
      system: t.system,
      messages: [{ role: 'user', content: turnsOf(t, c.id)[0] ?? '' }],
      response: reply,
      usage: { inputTokens: m.inputTokens, outputTokens: m.outputTokens, reasoningTokens: m.reasoningTokens ?? 0, cachedInputTokens: 0, cacheWriteTokens: 0 },
      ttftMs: m.ttftMs,
      totalMs: m.wallMs,
      stopReason: 'end',
      rawStopReason: 'stop',
      costUsd: m.costUsd,
      retries: 0,
    },
  ];
  return { ...base, status: 'ok', score, passed, summary, scoreDetail: detail, transcript, artifacts: [], replay: undefined };
}
