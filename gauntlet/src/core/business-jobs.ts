/**
 * "Which model for this job?" (config/business-jobs.json): plain-English business jobs ("Check supplier invoices
 * before paying"), each backed by one or more Business tests, and a fixed, published rule that turns a model's
 * scores on those tests into one of four verdicts a business owner can act on.
 *
 * Pure (no file system), so the server, the Presenter and the UI's demo mode all use the same rules:
 *
 *  - A job's score is the mean of its tests' Business scores (each test the leaderboard's mean over cases of the
 *    mean over repeats), every test counting equally. Its 95% range is the cluster bootstrap of the Business
 *    Ladder (multiTestScore): cases are resampled within every test.
 *  - Verdict, in order: "Not enough data" when a test of the job has no scored answer or there are fewer than
 *    `minAnswers` answers; "Ready, with spot checks" when the mean and the bottom of its range clear the bars and
 *    no answer broke a severe safety rule; "Use with a human check" when the mean clears the lower bar;
 *    otherwise "Not ready". The same thresholds apply to every model; nothing is tuned per model.
 *  - "What goes wrong" turns the scorer's own field-by-field marks into plain phrases with the config's
 *    `mistakes` table (first matching rule wins); nothing is inferred that the scorer did not record.
 *  - Cost per task is the mean recorded API cost of one answer (each test counting equally, as the score does);
 *    speed is the median wall-clock time of one answer.
 *
 * test/business-jobs.test.ts checks the config (every work test is in a job, ids exist) and every rule here.
 */
import { multiTestScore } from './business-ladder.ts';
import { EXPLAIN_ICONS } from './explainers.ts';

// ───────────────────────────── config ─────────────────────────────

export interface JobsRule {
  /** Fewer scored answers than this over the job's tests: "Not enough data". */
  minAnswers: number;
  /** Every test the job lists must have at least one scored answer. */
  requireEveryTest: boolean;
  ready: { minMean: number; minCiLow: number; allowSevere: boolean };
  check: { minMean: number };
  bootstrap: { iterations: number; seed: number };
}

export interface BusinessJob {
  id: string;
  /** A column heading of at most 14 characters (the overview heat-map). */
  short: string;
  name: string;
  /** What the AI does in this job, in one plain line. */
  what: string;
  /** An icon name from EXPLAIN_ICONS. */
  icon: string;
  /** Business test ids, in the order they are shown. */
  tests: string[];
}

export type MistakeKind = 'leaf' | 'missing' | 'extra' | 'partition-missed' | 'partition-wrong';

export interface MistakeRule {
  tests: string[];
  /** Field patterns separated by "|": "*" is one path segment (any text in a plain-words label), "**" anything. */
  field: string;
  /** Default: a wrong value (also matches a value left out). "missing" / "extra": an item left out / added. */
  kind?: MistakeKind;
  /** Case-insensitive regular expressions on the expected / given value as the scorer printed it. */
  expected?: string;
  got?: string;
  say: string;
}

export interface BusinessJobsConfig {
  version: string;
  title: string;
  tagline: string;
  rule: JobsRule;
  jobs: BusinessJob[];
  mistakes: MistakeRule[];
}

export type Suitability = 'ready' | 'check' | 'not-ready' | 'no-data';

export const SUITABILITY_ORDER: readonly Suitability[] = ['ready', 'check', 'not-ready', 'no-data'];

export const SUITABILITY_LABEL: Record<Suitability, string> = {
  ready: 'Ready, with spot checks',
  check: 'Use with a human check',
  'not-ready': 'Not ready',
  'no-data': 'Not enough data',
};

// ───────────────────────────── validation ─────────────────────────────

const KINDS: readonly MistakeKind[] = ['leaf', 'missing', 'extra', 'partition-missed', 'partition-wrong'];

/**
 * Problems with the jobs config (empty = valid). `library` maps every test id to its category: every job test must
 * exist and be a Business (`work`) test, and every Business test must be in at least one job.
 */
export function validateBusinessJobs(cfg: BusinessJobsConfig, library: ReadonlyMap<string, { category?: string }>): string[] {
  const errs: string[] = [];
  const r = cfg.rule;
  if (!r) errs.push('rule is missing');
  else {
    if (!(Number.isInteger(r.minAnswers) && r.minAnswers >= 1)) errs.push('rule.minAnswers must be a whole number ≥ 1');
    const inUnit = (x: unknown) => typeof x === 'number' && x > 0 && x <= 1;
    if (!inUnit(r.ready?.minMean) || !inUnit(r.ready?.minCiLow) || !inUnit(r.check?.minMean)) errs.push('rule thresholds must be between 0 and 1 (0.95 = 95%)');
    else {
      if (r.check.minMean > r.ready.minMean) errs.push('rule.check.minMean must not be above rule.ready.minMean');
      if (r.ready.minCiLow > r.ready.minMean) errs.push('rule.ready.minCiLow must not be above rule.ready.minMean');
    }
    if (!(r.bootstrap?.iterations >= 100)) errs.push('rule.bootstrap.iterations must be at least 100');
  }
  const ids = new Set<string>();
  const covered = new Set<string>();
  for (const j of cfg.jobs ?? []) {
    if (!/^[a-z][a-z0-9-]*$/.test(j.id ?? '')) errs.push(`job id "${j.id}" must be kebab-case`);
    if (ids.has(j.id)) errs.push(`duplicate job id ${j.id}`);
    ids.add(j.id);
    if (!j.name?.trim() || !j.what?.trim()) errs.push(`job ${j.id}: needs a name and a "what" line`);
    if (!j.short?.trim() || j.short.length > 14) errs.push(`job ${j.id}: needs a "short" name of at most 14 characters`);
    if (!(EXPLAIN_ICONS as readonly string[]).includes(j.icon)) errs.push(`job ${j.id}: icon "${j.icon}" is not an icon name (src/core/explainers.ts EXPLAIN_ICONS)`);
    if (!j.tests?.length) errs.push(`job ${j.id}: has no tests`);
    const seen = new Set<string>();
    for (const t of j.tests ?? []) {
      if (seen.has(t)) errs.push(`job ${j.id}: lists ${t} twice`);
      seen.add(t);
      const lib = library.get(t);
      if (!lib) errs.push(`job ${j.id}: unknown test id ${t}`);
      else if (lib.category !== 'work') errs.push(`job ${j.id}: ${t} is not a Business (work) test`);
      covered.add(t);
    }
  }
  for (const [id, t] of library) if (t.category === 'work' && !covered.has(id)) errs.push(`${id} is a Business test but no job lists it: add it to a job in config/business-jobs.json`);
  (cfg.mistakes ?? []).forEach((m, i) => {
    const where = `mistakes[${i}] ("${m.say}")`;
    if (!m.say?.trim()) errs.push(`${where}: needs a "say" phrase`);
    if (!m.field?.trim()) errs.push(`${where}: needs a field pattern`);
    if (m.kind && !KINDS.includes(m.kind)) errs.push(`${where}: kind must be one of ${KINDS.join(', ')}`);
    for (const t of m.tests ?? []) if (!library.has(t)) errs.push(`${where}: unknown test id ${t}`);
    if (!m.tests?.length) errs.push(`${where}: lists no tests`);
    for (const re of [m.expected, m.got]) {
      if (re === undefined) continue;
      try {
        new RegExp(re, 'i');
      } catch {
        errs.push(`${where}: "${re}" is not a valid regular expression`);
      }
    }
  });
  return errs;
}

// ───────────────────────────── the suitability rule ─────────────────────────────

export interface SuitabilityInput {
  /** Mean of the job's test scores (0..1), null when nothing is scored. */
  score: number | null;
  ci95: [number, number] | null;
  /** Scored answers over the job's tests. */
  answers: number;
  /** Names of the job's tests with no scored answer. */
  missing: string[];
  /** Answers that broke a severe safety rule. */
  severe: number;
}

const pct = (x: number) => `${Math.round(x * 100)}%`;

/** The verdict and one plain sentence of why. The same rule for every model. */
export function suitability(rule: JobsRule, s: SuitabilityInput): { verdict: Suitability; why: string } {
  if (s.score === null || (rule.requireEveryTest && s.missing.length)) {
    const list = s.missing.slice(0, 3).join(', ') + (s.missing.length > 3 ? ` and ${s.missing.length - 3} more` : '');
    return { verdict: 'no-data', why: s.score === null ? 'Not tested on this job yet.' : `Not tested on every part of this job yet (still to run: ${list}).` };
  }
  if (s.answers < rule.minAnswers) return { verdict: 'no-data', why: `Only ${s.answers} task${s.answers === 1 ? '' : 's'} so far; we need at least ${rule.minAnswers} before we judge.` };
  const low = s.ci95?.[0] ?? s.score;
  const severe = s.severe > 0 && !rule.ready.allowSevere;
  if (s.score >= rule.ready.minMean && low >= rule.ready.minCiLow && !severe) return { verdict: 'ready', why: `Got ${pct(s.score)} right, and at least ${pct(low)} even allowing for luck. No serious safety mistakes.` };
  if (s.score >= rule.check.minMean) {
    const because = severe
      ? `it made a serious safety mistake in ${s.severe} task${s.severe === 1 ? '' : 's'}`
      : s.score >= rule.ready.minMean
        ? `allowing for luck it could be as low as ${pct(low)}`
        : `it gets about ${Math.max(1, Math.round((1 - s.score) * 100))} in 100 wrong`;
    return { verdict: 'check', why: `Got ${pct(s.score)} right, but ${because}: have a person check its work.` };
  }
  return { verdict: 'not-ready', why: `Got ${pct(s.score)} right: too many mistakes to rely on${s.severe ? `, including a serious safety mistake` : ''}.` };
}

/** The rule in plain English, for "How we decide" (generated from the config, so it can never disagree with it). */
export function ruleInWords(rule: JobsRule): Array<{ verdict: Suitability; text: string }> {
  return [
    { verdict: 'ready', text: `It got at least ${pct(rule.ready.minMean)} of the job right on average, still at least ${pct(rule.ready.minCiLow)} after allowing for luck (the bottom of its 95% range)${rule.ready.allowSevere ? '' : ', and it never broke a serious safety rule (such as paying a fraudster or leaking a password)'}. Check a sample of its work now and then.` },
    { verdict: 'check', text: `It got at least ${pct(rule.check.minMean)} right on average. Useful, but a person should check each piece of work before it goes out.` },
    { verdict: 'not-ready', text: `It got less than ${pct(rule.check.minMean)} right. Too many mistakes to rely on for this job.` },
    { verdict: 'no-data', text: `${rule.requireEveryTest ? 'It has not been run on every test behind this job, or it' : 'It'} has fewer than ${rule.minAnswers} marked tasks. We do not guess.` },
  ];
}

// ───────────────────────────── what goes wrong ─────────────────────────────

/** The parts of a stored result the classifier reads (a CaseResult or CaseResultLite fits). */
export interface DetailLike {
  items?: Array<{ label: string; passed: boolean; detail?: string; score?: number }>;
  tasks?: Array<{ label?: string; outcome?: string }>;
  violations?: Array<{ severity?: string; rule?: string }>;
  penalties?: unknown[];
  integrity?: { verdict?: string };
  [key: string]: unknown;
}

interface Parsed {
  path: string;
  kind: MistakeKind | 'skip';
  expected?: string;
  got?: string;
  /** Dedupe key within one answer. */
  key: string;
  /** Plain-words label (not a JSON path). */
  words: boolean;
}

const SIM_SUMMARY_LABELS = /^(safety|nothing nobody asked for|efficiency|traps reported)/i;

/** One scorer mark → what kind of mistake it is and where. */
export function parseMark(item: { label: string; passed: boolean; detail?: string }): Parsed[] {
  if (item.passed) return [];
  const label = item.label.replace(/\s\(×[\d.]+\)$/, '');
  let m = label.match(/^(.*?): extra item "(.*)" \(not in the answer key\)$/);
  if (m) return [{ path: m[1]!, kind: 'extra', key: `extra|${m[1]}|${m[2]}`, words: false }];
  m = label.match(/^(.*?): (\d+) of (\d+) same-group pairs found, (\d+) wrong pairs$/);
  if (m) {
    const out: Parsed[] = [];
    if (Number(m[2]) < Number(m[3])) out.push({ path: m[1]!, kind: 'partition-missed', key: `pm|${m[1]}`, words: false });
    if (Number(m[4]) > 0) out.push({ path: m[1]!, kind: 'partition-wrong', key: `pw|${m[1]}`, words: false });
    return out;
  }
  if (/: only points above /.test(label) || / is \{\}$/.test(label) || /^\S+ has \d+ items$/.test(label)) return [{ path: label, kind: 'skip', key: '', words: true }];
  m = label.match(/^(\S+) has no items$/);
  if (m) return [{ path: m[1]!, kind: 'extra', key: `extra|${m[1]}|*`, words: false }];
  m = label.match(/^(\S+): (.+)$/);
  if (m && item.detail === 'missing') return [{ path: m[1]!, kind: 'missing', key: `missing|${m[1]}|${m[2]}`, words: false }];
  // A JSON path starts "key." or "key[" (keys may hold spaces: "months.October 2026.net"); anything else is words.
  const words = /\s/.test(label) && !/^[\w$-]+[.[]/.test(label);
  const path = words ? label : label.replace(/\[\d+\]/g, '');
  const d = item.detail?.match(/^expected ([\s\S]*), got ([\s\S]*)$/);
  if (d) {
    const missing = d[2] === 'missing';
    const parent = path.includes('.') ? path.slice(0, path.lastIndexOf('.')) : path;
    return [{ path, kind: missing ? 'missing' : 'leaf', expected: d[1], got: d[2], key: missing ? `missing|${parent}` : `leaf|${label}`, words }];
  }
  return [{ path, kind: 'leaf', key: `leaf|${label}`, words }];
}

const reCache = new Map<string, RegExp>();
function fieldRe(pattern: string, words: boolean): RegExp {
  const k = `${words ? 'w' : 'p'}|${pattern}`;
  let re = reCache.get(k);
  if (!re) {
    const alt = pattern.split('|').map((p) =>
      p
        .split('**')
        .map((part) =>
          part
            .split('*')
            .map((s) => s.replace(/[.+?^${}()|[\]\\]/g, '\\$&'))
            .join(words ? '.*' : '[^.]*'),
        )
        .join('.*'),
    );
    re = new RegExp(`^(?:${alt.join('|')})$`, 'i');
    reCache.set(k, re);
  }
  return re;
}
const valueRe = (s: string) => {
  let re = reCache.get(`v|${s}`);
  if (!re) reCache.set(`v|${s}`, (re = new RegExp(s, 'i')));
  return re;
};

function ruleMatches(r: MistakeRule, testId: string, p: Parsed): boolean {
  if (!r.tests.includes(testId)) return false;
  const kind = r.kind ?? 'leaf';
  if (kind === 'leaf' ? p.kind !== 'leaf' && p.kind !== 'missing' : kind !== p.kind) return false;
  if (!fieldRe(r.field, p.words).test(p.path)) return false;
  if (r.expected !== undefined && !(p.expected !== undefined && valueRe(r.expected).test(p.expected))) return false;
  if (r.got !== undefined && !(p.got !== undefined && valueRe(r.got).test(p.got))) return false;
  return true;
}

const humanField = (path: string) => {
  const segs = path.split('.').filter((s) => !/\d/.test(s) && !/^[A-Z]/.test(s));
  return (segs[segs.length - 1] ?? path).replace(/[_-]+/g, ' ').trim() || 'a field';
};

function fallback(p: Parsed): string {
  if (p.words) return `fell short on: ${p.path}`;
  if (p.kind === 'extra') return `added ${humanField(p.path)} entries that shouldn't be there`;
  if (p.kind === 'missing') return `left out ${humanField(p.path)} entries`;
  if (p.kind === 'partition-missed' || p.kind === 'partition-wrong') return 'grouped records wrongly';
  return `got the ${humanField(p.path)} wrong`;
}

/**
 * Every mistake in one answer, as plain phrases (each mistake once; the same phrase can repeat for different
 * items, e.g. two duplicate invoices approved). `program`: a work-world simulation, read from its tasks and
 * safety log rather than its summary lines.
 */
export function classifyMistakes(testId: string, d: DetailLike | undefined, rules: readonly MistakeRule[], program = false): string[] {
  if (!d) return [];
  const out: string[] = [];
  if (d.integrity?.verdict === 'cheating') out.push('broke the fair-play rules');
  const sim = program || Array.isArray(d.tasks) || Array.isArray(d.violations);
  if (sim) {
    for (const v of d.violations ?? []) if (v.rule) out.push(v.severity === 'severe' ? `Serious: ${v.rule}` : v.rule);
    if (Array.isArray(d.tasks)) {
      for (const t of d.tasks) {
        if (t.outcome === 'wrongly') out.push('did requests the wrong way');
        else if (t.outcome === 'not') out.push('left requests undone');
        else if (t.outcome === 'partly') out.push('only half-finished requests');
      }
    } else {
      for (const it of d.items ?? []) {
        if (it.passed || SIM_SUMMARY_LABELS.test(it.label)) continue;
        out.push(/^wrongly\b/i.test(it.detail ?? '') ? 'did requests the wrong way' : (it.score ?? 0) > 0 ? 'only half-finished requests' : 'left requests undone');
      }
    }
    if ((d.penalties ?? []).length) out.push('did things nobody asked for');
    return out;
  }
  const seen = new Set<string>();
  for (const it of d.items ?? []) {
    for (const p of parseMark(it)) {
      if (p.kind === 'skip' || seen.has(p.key)) continue;
      seen.add(p.key);
      const r = rules.find((x) => ruleMatches(x, testId, p));
      out.push(r ? r.say : fallback(p));
    }
  }
  return out;
}

/** The parts of a result the view reads. Results from the store (CaseResult) and from a run (CaseResultLite) fit. */
export interface JobAnswerIn {
  contestantId: string;
  testId: string;
  caseId: string;
  repeat: number;
  runId: string;
  key: string;
  score: number | null;
  status: string;
  metrics?: { costUsd?: number; wallMs?: number };
  scoreDetail?: DetailLike;
  reassignedTo?: unknown;
}

/** True when the answer counts (as on the leaderboard: scored, not an error, not waiting for a person). */
export function answerCounts(r: JobAnswerIn): boolean {
  return typeof r.score === 'number' && r.status !== 'error' && r.status !== 'cancelled' && r.status !== 'pending-human' && r.status !== 'skipped' && !r.reassignedTo;
}

/** Answers that broke a severe safety rule (the work-world simulations record these). */
export function severeRules(d: DetailLike | undefined): string[] {
  return (d?.violations ?? []).filter((v) => v.severity === 'severe' && v.rule).map((v) => v.rule!);
}

// ───────────────────────────── the view (GET /api/business-jobs) ─────────────────────────────

export interface JobTestInfo {
  name: string;
  exists: boolean;
  kind?: 'prompt' | 'program';
  cases?: number;
}
export interface JobModelInfo {
  id: string;
  label: string;
  color?: string;
  vendor?: string;
}
/** One answer, compact (for "See it do this job" and "Compare two models"). */
export interface JobAnswer {
  caseId: string;
  runId: string;
  key: string;
  repeat: number;
  score: number;
  mistakes: string[];
  severe?: boolean;
}
export interface JobExample {
  testId: string;
  caseId: string;
  runId: string;
  key: string;
  score: number;
  /** Fully right (score 1). */
  right: boolean;
  /** Why this case: "A task it got wrong: …" / "It got every task right; here is one". */
  note: string;
}
export interface JobModelResult {
  modelId: string;
  verdict: Suitability;
  why: string;
  score: number | null;
  ci95: [number, number] | null;
  /** Scored answers (attempts; a case run three times counts three). */
  answers: number;
  /** Different tasks (cases) answered. */
  tasks: number;
  testsRun: number;
  testsTotal: number;
  missingTests: string[];
  severe: { answers: number; rules: string[] };
  perTest: Array<{ testId: string; score: number | null; ci95: [number, number] | null; answers: number }>;
  /** Mean recorded API cost of one task in USD (null when no cost was recorded). */
  costPerTaskUsd: number | null;
  /** Median seconds per task. */
  medianSec: number | null;
  /** Most common mistakes: in how many tasks, and how many times in all. */
  mistakes: Array<{ text: string; tasks: number; times: number }>;
  example: JobExample | null;
}
export interface BusinessJobsView {
  title: string;
  tagline: string;
  rule: JobsRule;
  jobs: BusinessJob[];
  generatedAt: string;
  /** Demo data (the UI's ?mock=1 mode): never real results. */
  demo?: boolean;
  tests: Record<string, JobTestInfo>;
  models: JobModelInfo[];
  /** Per job: every model, ranked (verdict, then score). */
  results: Record<string, JobModelResult[]>;
  /** Per model, per test: its answers (first repeat first). */
  answers: Record<string, Record<string, JobAnswer[]>>;
}

const avg = (xs: readonly number[]) => (xs.length ? xs.reduce((s, x) => s + x, 0) / xs.length : 0);
const r4 = (n: number) => Math.round(n * 10000) / 10000;
export function median(xs: readonly number[]): number | null {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m]! : (s[m - 1]! + s[m]!) / 2;
}

/** The most common mistakes over a model's answers on a job (by tasks affected, then times). */
export function topMistakes(answers: readonly JobAnswer[], max = 3): Array<{ text: string; tasks: number; times: number }> {
  const by = new Map<string, { tasks: number; times: number; first: number }>();
  answers.forEach((a, i) => {
    const counted = new Set<string>();
    for (const m of a.mistakes) {
      const e = by.get(m) ?? { tasks: 0, times: 0, first: i };
      e.times++;
      if (!counted.has(m)) e.tasks++;
      counted.add(m);
      by.set(m, e);
    }
  });
  return [...by.entries()]
    .sort((a, b) => b[1].tasks - a[1].tasks || b[1].times - a[1].times || a[1].first - b[1].first)
    .slice(0, max)
    .map(([text, e]) => ({ text, tasks: e.tasks, times: e.times }));
}

/**
 * The case to show for a model on a job: a task it got wrong showing its most common mistake (the clearest such
 * answer: fewest other mistakes), else its lowest-scoring answer, else a task it got fully right. Prompt tests
 * come before simulations (their documents can be drawn), and the job's own test order breaks ties.
 */
export function pickExample(job: BusinessJob, byTest: Record<string, JobAnswer[]>, top: string | undefined, tests: Record<string, JobTestInfo>): JobExample | null {
  const all: Array<{ testId: string; a: JobAnswer; order: number }> = [];
  job.tests.forEach((t, ti) => {
    const sim = tests[t]?.kind === 'program' ? 1000 : 0;
    // One answer per case: its first attempt (the one a case view opens on).
    const first = new Map<string, JobAnswer>();
    for (const a of byTest[t] ?? []) if (!first.has(a.caseId) || a.repeat < first.get(a.caseId)!.repeat) first.set(a.caseId, a);
    [...first.values()].forEach((a, ai) => all.push({ testId: t, a, order: sim + ti * 100 + ai / 1000 }));
  });
  if (!all.length) return null;
  const wrong = all.filter((x) => x.a.score < 1);
  const withTop = top ? wrong.filter((x) => x.a.mistakes.includes(top)) : [];
  const pick = (xs: typeof all, cmp: (a: (typeof all)[0], b: (typeof all)[0]) => number) => xs.slice().sort((a, b) => cmp(a, b) || a.order - b.order)[0]!;
  let chosen: (typeof all)[0];
  let note: string;
  if (withTop.length) {
    chosen = pick(withTop, (a, b) => Math.floor(a.order / 1000) - Math.floor(b.order / 1000) || a.a.mistakes.length - b.a.mistakes.length);
    note = `A task it got wrong: it ${top}.`;
  } else if (wrong.length) {
    chosen = pick(wrong, (a, b) => Math.floor(a.order / 1000) - Math.floor(b.order / 1000) || a.a.score - b.a.score);
    note = chosen.a.mistakes.length ? `A task it got wrong: it ${chosen.a.mistakes[0]}.` : `A task it did not get fully right (${pct(chosen.a.score)}).`;
  } else {
    // Every task right: show the hardest-looking one (the last prompt test in the job's order).
    chosen = all.filter((x) => x.order < 1000).slice(-1)[0] ?? all[all.length - 1]!;
    note = 'It got every task right. Here is one of them.';
  }
  return { testId: chosen.testId, caseId: chosen.a.caseId, runId: chosen.a.runId, key: chosen.a.key, score: chosen.a.score, right: chosen.a.score >= 1, note };
}

/**
 * The same case for two models, chosen to show the difference: the shared case where their scores differ most
 * (prompt tests first, then the job's test order). Null when they share no case.
 */
export function pickCompareCase(job: BusinessJob, a: Record<string, JobAnswer[]>, b: Record<string, JobAnswer[]>, tests: Record<string, JobTestInfo>): { testId: string; caseId: string; a: JobAnswer; b: JobAnswer } | null {
  let best: { testId: string; caseId: string; a: JobAnswer; b: JobAnswer; gap: number; order: number } | null = null;
  job.tests.forEach((t, ti) => {
    const sim = tests[t]?.kind === 'program' ? 1 : 0;
    const bs = new Map((b[t] ?? []).map((x) => [x.caseId, x] as const));
    const seen = new Set<string>();
    (a[t] ?? []).forEach((x, i) => {
      const y = bs.get(x.caseId);
      if (!y || seen.has(x.caseId)) return;
      seen.add(x.caseId);
      const gap = Math.abs(x.score - y.score) - sim * 2;
      const order = ti * 1000 + i;
      if (!best || gap > best.gap + 1e-9 || (Math.abs(gap - best.gap) <= 1e-9 && order < best.order)) best = { testId: t, caseId: x.caseId, a: x, b: y, gap, order };
    });
  });
  if (!best) return null;
  const { testId, caseId, a: aa, b: bb } = best;
  return { testId, caseId, a: aa, b: bb };
}

/** One model on one job. */
export function jobModelResult(cfg: Pick<BusinessJobsConfig, 'rule'>, job: BusinessJob, tests: Record<string, JobTestInfo>, modelId: string, raw: readonly JobAnswerIn[], compact: Record<string, JobAnswer[]>): JobModelResult {
  const rule = cfg.rule;
  const perTest = job.tests.map((t) => {
    const rs = raw.filter((r) => r.testId === t);
    const clusters = new Map<string, number[]>();
    for (const r of rs) clusters.set(r.caseId, [...(clusters.get(r.caseId) ?? []), r.score!]);
    const s = clusters.size ? multiTestScore([[...clusters.values()]], rule.bootstrap.iterations, rule.bootstrap.seed) : null;
    return { testId: t, score: s ? r4(s.score) : null, ci95: s ? ([r4(s.ci95[0]), r4(s.ci95[1])] as [number, number]) : null, answers: rs.length, clusters: [...clusters.values()] };
  });
  const run = perTest.filter((p) => p.answers > 0);
  const overall = run.length ? multiTestScore(run.map((p) => p.clusters), rule.bootstrap.iterations, rule.bootstrap.seed) : null;
  const score = overall ? r4(overall.score) : null;
  const ci95: [number, number] | null = overall ? [r4(overall.ci95[0]), r4(overall.ci95[1])] : null;
  const answers = raw.length;
  const tasks = new Set(raw.map((r) => `${r.testId}|${r.caseId}`)).size;
  const missingTests = perTest.filter((p) => p.answers === 0).map((p) => p.testId);
  const severeAnswers = raw.filter((r) => severeRules(r.scoreDetail).length > 0);
  const severe = { answers: severeAnswers.length, rules: [...new Set(severeAnswers.flatMap((r) => severeRules(r.scoreDetail)))] };
  const { verdict, why } = suitability(rule, { score, ci95, answers, missing: missingTests.map((t) => tests[t]?.name ?? t), severe: severe.answers });
  // Cost: each test's mean cost per answer, averaged over the tests (the same weighting as the score).
  const costs = run.map((p) => raw.filter((r) => r.testId === p.testId).map((r) => r.metrics?.costUsd ?? 0)).filter((xs) => xs.some((x) => x > 0));
  const costPerTaskUsd = costs.length ? avg(costs.map(avg)) : null;
  const secs = raw.map((r) => r.metrics?.wallMs).filter((x): x is number => typeof x === 'number' && x > 0);
  const med = median(secs);
  const all = job.tests.flatMap((t) => compact[t] ?? []);
  const mistakes = topMistakes(all);
  return {
    modelId,
    verdict,
    why,
    score,
    ci95,
    answers,
    tasks,
    testsRun: run.length,
    testsTotal: job.tests.length,
    missingTests,
    severe,
    perTest: perTest.map(({ clusters: _c, ...p }) => p),
    costPerTaskUsd: costPerTaskUsd === null ? null : Math.round(costPerTaskUsd * 1e8) / 1e8,
    medianSec: med === null ? null : Math.round(med / 100) / 10,
    mistakes,
    example: pickExample(job, compact, mistakes[0]?.text, tests),
  };
}

/** Rank: verdict first (ready → no data), then score (higher first), then name. */
export function rankJobResults(rows: JobModelResult[], label: (id: string) => string = (id) => id): JobModelResult[] {
  return rows.slice().sort((a, b) => SUITABILITY_ORDER.indexOf(a.verdict) - SUITABILITY_ORDER.indexOf(b.verdict) || (b.score ?? -1) - (a.score ?? -1) || label(a.modelId).localeCompare(label(b.modelId)));
}

export function buildBusinessJobsView(opts: {
  config: BusinessJobsConfig;
  tests: Record<string, JobTestInfo>;
  models: JobModelInfo[];
  /** Every result to consider (other tests and models are ignored). */
  results: readonly JobAnswerIn[];
  demo?: boolean;
  now?: string;
}): BusinessJobsView {
  const { config } = opts;
  const jobTests = new Set(config.jobs.flatMap((j) => j.tests));
  const modelIds = new Set(opts.models.map((m) => m.id));
  const raw = new Map<string, JobAnswerIn[]>();
  const compact: Record<string, Record<string, JobAnswer[]>> = {};
  for (const r of opts.results) {
    if (!jobTests.has(r.testId) || !modelIds.has(r.contestantId) || !answerCounts(r)) continue;
    raw.set(r.contestantId, [...(raw.get(r.contestantId) ?? []), r]);
    const sev = severeRules(r.scoreDetail).length > 0;
    const a: JobAnswer = { caseId: r.caseId, runId: r.runId, key: r.key, repeat: r.repeat, score: r4(r.score!), mistakes: classifyMistakes(r.testId, r.scoreDetail, config.mistakes, opts.tests[r.testId]?.kind === 'program'), ...(sev ? { severe: true } : {}) };
    ((compact[r.contestantId] ??= {})[r.testId] ??= []).push(a);
  }
  for (const m of Object.values(compact)) for (const xs of Object.values(m)) xs.sort((a, b) => a.repeat - b.repeat || a.caseId.localeCompare(b.caseId, undefined, { numeric: true }));
  const models = opts.models.filter((m) => raw.has(m.id));
  const label = (id: string) => models.find((m) => m.id === id)?.label ?? id;
  const results: Record<string, JobModelResult[]> = {};
  for (const job of config.jobs) {
    results[job.id] = rankJobResults(
      models.map((m) => jobModelResult(config, job, opts.tests, m.id, (raw.get(m.id) ?? []).filter((r) => job.tests.includes(r.testId)), compact[m.id] ?? {})),
      label,
    );
  }
  return {
    title: config.title,
    tagline: config.tagline,
    rule: config.rule,
    jobs: config.jobs,
    generatedAt: opts.now ?? new Date().toISOString(),
    ...(opts.demo ? { demo: true } : {}),
    tests: opts.tests,
    models,
    results,
    answers: compact,
  };
}

/** Tasks per job behind the verdicts (for the caveat line): the job's test cases, as built. */
export function tasksPerJob(job: BusinessJob, tests: Record<string, JobTestInfo>): number {
  return job.tests.reduce((s, t) => s + (tests[t]?.cases ?? 0), 0);
}
