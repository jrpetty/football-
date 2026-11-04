/**
 * The Business Ladder (config/business-ladder.json): how companies actually adopt AI, from the easiest step to
 * the most ambitious, the same five levels in six business areas ("scenarios").
 *
 * Pure (no file system), so the server, the Presenter and the UI's demo mode all use the same rules:
 *
 *  - A test's score is the mean over its cases of the mean over that case's repeats (as on the leaderboard),
 *    with a 95% cluster-bootstrap range over cases.
 *  - A level's score is the mean of its existing tests' scores; its range resamples cases within every test.
 *  - Height reached in a scenario = the highest level L such that every level 1..L that has tests has ALL of
 *    them run and scored at or above the pass mark (70). Levels with no tests yet are "coming soon": skipped in
 *    the climb, never counted as reached.
 *  - Within noise: a passed test whose range dips below 70 is "borderline"; a failed test whose range reaches
 *    70 is "just short". The climb reports both, so a lucky or unlucky sample is never sold as a clear result.
 *
 * test/business-ladder.test.ts checks the config and every rule here.
 */
import { createRng } from './rng.ts';
import { pairedDifference, type PairedResult, type PairedTestInput } from './paired.ts';

export interface LadderLevel {
  level: number;
  name: string;
  /** What the AI does at this level, in plain English. */
  what: string;
  /** Who checks the AI's work in real life. */
  check: string;
}
export interface LadderTestRef {
  id: string;
  level: number;
  /** Not built yet: shown as "coming soon". */
  planned?: boolean;
}
export interface LadderScenario {
  id: string;
  name: string;
  color: string;
  /** An icon name from EXPLAIN_ICONS (src/core/explainers.ts). */
  icon: string;
  summary?: string;
  tests: LadderTestRef[];
}
export interface LadderDefinition {
  version: string;
  title: string;
  tagline: string;
  /** Pass mark out of 100 (70). */
  passMark: number;
  levels: LadderLevel[];
  scenarios: LadderScenario[];
}

/** Repeat scores (0..1) per case id. */
export type CaseClusters = Record<string, readonly number[]>;
/** A model's results: per test id, its case clusters. */
export type ModelClusters = Record<string, CaseClusters>;

export type ScoreBand = 'clear-pass' | 'borderline-pass' | 'just-short' | 'clear-fail';

export interface LadderTestResult {
  id: string;
  level: number;
  exists: boolean;
  /** 0..1, null when not run (or not built). */
  score: number | null;
  ci95: [number, number] | null;
  cases: number;
  band: ScoreBand | null;
}
export type LevelStatus = 'coming-soon' | 'not-run' | 'partial' | 'passed' | 'failed';
export interface LadderLevelResult {
  level: number;
  status: LevelStatus;
  /** Mean of the level's run tests (0..1), null if none run. */
  score: number | null;
  ci95: [number, number] | null;
  tests: LadderTestResult[];
  /** Passed, but a test's range dips below the pass mark. */
  borderline: boolean;
  /** Failed only on tests whose range reaches the pass mark. */
  justShort: boolean;
}
export interface LadderScenarioResult {
  id: string;
  /** Highest level reached (0: not even level 1). */
  height: number;
  /** Highest level that has any built test (the most a model can reach today). */
  topBuilt: number;
  /** The level the climb stopped at, and why. */
  stoppedAt: number | null;
  stoppedBecause: 'failed' | 'not-run' | 'partial' | null;
  /** The reached height includes a borderline pass. */
  borderline: boolean;
  /** The climb stopped on a level failed within the noise. */
  justShort: boolean;
  levels: LadderLevelResult[];
}

export const LADDER_LEVELS = [1, 2, 3, 4, 5] as const;

// ───────────────────────────── validation ─────────────────────────────

/**
 * Problems with a ladder definition (empty = valid). `existing` is the set of test ids in the library: every
 * test not marked planned must exist, and a planned test that now exists must be flipped to planned: false.
 */
export function validateBusinessLadder(def: LadderDefinition, existing: ReadonlySet<string>): string[] {
  const errs: string[] = [];
  const levels = (def.levels ?? []).map((l) => l.level);
  if (JSON.stringify(levels) !== JSON.stringify([...LADDER_LEVELS])) errs.push(`levels must be exactly 1–5 in order (got ${levels.join(', ')})`);
  for (const l of def.levels ?? []) if (!l.name?.trim() || !l.what?.trim() || !l.check?.trim()) errs.push(`level ${l.level}: needs name, what and check`);
  if (!(def.passMark > 0 && def.passMark <= 100)) errs.push('passMark must be 1–100');
  const scenarioIds = new Set<string>();
  const testIds = new Map<string, string>();
  for (const s of def.scenarios ?? []) {
    if (!/^[a-z][a-z0-9-]*$/.test(s.id)) errs.push(`scenario id "${s.id}" must be kebab-case`);
    if (scenarioIds.has(s.id)) errs.push(`duplicate scenario id ${s.id}`);
    scenarioIds.add(s.id);
    if (!s.name?.trim() || !/^#[0-9a-f]{6}$/i.test(s.color ?? '') || !s.icon) errs.push(`scenario ${s.id}: needs name, #rrggbb color and icon`);
    if (!s.tests?.length) errs.push(`scenario ${s.id}: has no tests`);
    for (const t of s.tests ?? []) {
      if (!Number.isInteger(t.level) || t.level < 1 || t.level > 5) errs.push(`${s.id} → ${t.id}: level must be 1–5`);
      if (testIds.has(t.id)) errs.push(`${t.id} is listed twice (${testIds.get(t.id)} and ${s.id})`);
      testIds.set(t.id, s.id);
      const has = existing.has(t.id);
      if (t.planned && has) errs.push(`${t.id} now exists in the test library: set "planned": false (or remove the flag) in config/business-ladder.json`);
      if (!t.planned && !has) errs.push(`${t.id} is not in the test library: mark it "planned": true until it is built`);
    }
  }
  return errs;
}

// ───────────────────────────── scoring ─────────────────────────────

const avg = (xs: readonly number[]) => (xs.length ? xs.reduce((s, x) => s + x, 0) / xs.length : 0);

/** Mean of test means and its 95% range, resampling cases within each test (cases are the independent unit). */
export function multiTestScore(tests: ReadonlyArray<readonly (readonly number[])[]>, iterations = 1000, seed = 77): { score: number; ci95: [number, number] } | null {
  const ts = tests.filter((t) => t.length > 0).map((t) => t.map(avg));
  if (!ts.length) return null;
  const score = avg(ts.map(avg));
  const rng = createRng(seed);
  const samples: number[] = new Array(iterations);
  for (let i = 0; i < iterations; i++) {
    let s = 0;
    for (const t of ts) {
      let x = 0;
      for (let k = 0; k < t.length; k++) x += t[Math.floor(rng.next() * t.length)]!;
      s += x / t.length;
    }
    samples[i] = s / ts.length;
  }
  samples.sort((a, b) => a - b);
  return { score, ci95: [samples[Math.floor(0.025 * (iterations - 1))]!, samples[Math.ceil(0.975 * (iterations - 1))]!] };
}

const r4 = (n: number) => Math.round(n * 10000) / 10000;

export function bandOf(score: number, ci: [number, number] | null, pass: number): ScoreBand {
  if (score >= pass) return ci && ci[0] < pass ? 'borderline-pass' : 'clear-pass';
  return ci && ci[1] >= pass ? 'just-short' : 'clear-fail';
}

/** One model's ladder in one scenario. */
export function scenarioLadder(def: LadderDefinition, scenario: LadderScenario, existing: ReadonlySet<string>, clusters: ModelClusters): LadderScenarioResult {
  const pass = def.passMark / 100;
  const levels: LadderLevelResult[] = LADDER_LEVELS.map((level) => {
    const refs = scenario.tests.filter((t) => t.level === level);
    const tests: LadderTestResult[] = refs.map((t) => {
      const exists = existing.has(t.id);
      const cl = exists ? Object.values(clusters[t.id] ?? {}).filter((c) => c.length > 0) : [];
      const s = cl.length ? multiTestScore([cl]) : null;
      return { id: t.id, level, exists, score: s ? r4(s.score) : null, ci95: s ? [r4(s.ci95[0]), r4(s.ci95[1])] : null, cases: cl.length, band: s ? bandOf(r4(s.score), s.ci95, pass) : null };
    });
    const built = tests.filter((t) => t.exists);
    const run = built.filter((t) => t.score !== null);
    const lvl = multiTestScore(built.map((t) => Object.values(clusters[t.id] ?? {}).filter((c) => c.length > 0)));
    let status: LevelStatus;
    if (!built.length) status = 'coming-soon';
    else if (!run.length) status = 'not-run';
    else if (run.length < built.length) status = 'partial';
    else status = run.every((t) => t.score! >= pass) ? 'passed' : 'failed';
    return {
      level,
      status,
      score: lvl ? r4(lvl.score) : null,
      ci95: lvl ? [r4(lvl.ci95[0]), r4(lvl.ci95[1])] : null,
      tests,
      borderline: status === 'passed' && run.some((t) => t.band === 'borderline-pass'),
      justShort: status === 'failed' && run.filter((t) => t.score! < pass).every((t) => t.band === 'just-short'),
    };
  });
  let height = 0;
  let stoppedAt: number | null = null;
  let stoppedBecause: LadderScenarioResult['stoppedBecause'] = null;
  for (const l of levels) {
    if (l.status === 'coming-soon') continue;
    if (l.status === 'passed') {
      height = l.level;
      continue;
    }
    stoppedAt = l.level;
    stoppedBecause = l.status === 'failed' ? 'failed' : l.status === 'partial' ? 'partial' : 'not-run';
    break;
  }
  const topBuilt = Math.max(0, ...levels.filter((l) => l.status !== 'coming-soon').map((l) => l.level));
  return {
    id: scenario.id,
    height,
    topBuilt,
    stoppedAt,
    stoppedBecause,
    borderline: levels.some((l) => l.level <= height && l.borderline),
    justShort: stoppedBecause === 'failed' && !!levels.find((l) => l.level === stoppedAt)?.justShort,
    levels,
  };
}

/** Every scenario for one model. */
export function modelLadder(def: LadderDefinition, existing: ReadonlySet<string>, clusters: ModelClusters): LadderScenarioResult[] {
  return def.scenarios.map((s) => scenarioLadder(def, s, existing, clusters));
}

// ───────────────────────────── comparing two models ─────────────────────────────

export interface LadderComparison {
  scenarioId: string;
  /** Every built test in the scenario. */
  overall: PairedResult;
  /** Per level (null when the level has no built test). */
  levels: Array<{ level: number; result: PairedResult | null }>;
}

function pairedInputs(ids: string[], a: ModelClusters, b: ModelClusters): PairedTestInput[] {
  return ids.map((id) => ({ a: a[id] ?? {}, b: b[id] ?? {} }));
}

/** A vs B on the same cases, per scenario and level (paired bootstrap; see src/core/paired.ts). */
export function compareLadders(def: LadderDefinition, existing: ReadonlySet<string>, a: ModelClusters, b: ModelClusters): { all: PairedResult; scenarios: LadderComparison[] } {
  const builtIds = (s: LadderScenario, level?: number) => s.tests.filter((t) => existing.has(t.id) && (level === undefined || t.level === level)).map((t) => t.id);
  const scenarios = def.scenarios.map((s) => ({
    scenarioId: s.id,
    overall: pairedDifference(pairedInputs(builtIds(s), a, b)),
    levels: LADDER_LEVELS.map((level) => {
      const ids = builtIds(s, level);
      return { level, result: ids.length ? pairedDifference(pairedInputs(ids, a, b)) : null };
    }),
  }));
  const all = pairedDifference(pairedInputs(def.scenarios.flatMap((s) => builtIds(s)), a, b));
  return { all, scenarios };
}

/** Every test id on the ladder, built or planned. */
export function ladderTestIds(def: LadderDefinition): string[] {
  return def.scenarios.flatMap((s) => s.tests.map((t) => t.id));
}

// ───────────────────────────── the API view (GET /api/business-ladder) ─────────────────────────────

export interface LadderTestInfo {
  name: string;
  exists: boolean;
  difficulty?: string;
  cases?: number;
  /** From the test's hand-written explainer. */
  hook?: string;
  whatItTests?: string;
  howScored?: string[];
  icon?: string;
}
export interface LadderModelView {
  id: string;
  label: string;
  color?: string;
  vendor?: string;
  scenarios: LadderScenarioResult[];
  /** Built tests this model has results for / built tests on the ladder. */
  coverage: { run: number; built: number };
}
export interface BusinessLadderView {
  definition: LadderDefinition;
  /** The results pool (a suite id) the ladder was computed from. */
  suiteId: string;
  generatedAt: string;
  /** Demo data (the UI's ?mock=1 mode): never real results. */
  demo?: boolean;
  tests: Record<string, LadderTestInfo>;
  models: LadderModelView[];
  /** Paired comparisons for every pair of the requested models (in the order asked). */
  comparisons: Array<{ a: string; b: string; all: PairedResult; scenarios: LadderComparison[] }>;
}

export function buildBusinessLadderView(opts: {
  definition: LadderDefinition;
  suiteId: string;
  tests: Record<string, LadderTestInfo>;
  models: Array<{ id: string; label: string; color?: string; vendor?: string; clusters: ModelClusters }>;
  /** Model ids to compare pairwise (2–4). */
  compare?: string[];
  demo?: boolean;
  now?: string;
}): BusinessLadderView {
  const existing = new Set(Object.entries(opts.tests).filter(([, t]) => t.exists).map(([id]) => id));
  const built = ladderTestIds(opts.definition).filter((id) => existing.has(id));
  const models: LadderModelView[] = opts.models.map((m) => ({
    id: m.id,
    label: m.label,
    color: m.color,
    vendor: m.vendor,
    scenarios: modelLadder(opts.definition, existing, m.clusters),
    coverage: { run: built.filter((id) => Object.values(m.clusters[id] ?? {}).some((c) => c.length)).length, built: built.length },
  }));
  const pick = (opts.compare ?? []).filter((id, i, arr) => arr.indexOf(id) === i && opts.models.some((m) => m.id === id)).slice(0, 4);
  const comparisons: BusinessLadderView['comparisons'] = [];
  for (let i = 0; i < pick.length; i++)
    for (let j = i + 1; j < pick.length; j++) {
      const a = opts.models.find((m) => m.id === pick[i])!;
      const b = opts.models.find((m) => m.id === pick[j])!;
      comparisons.push({ a: a.id, b: b.id, ...compareLadders(opts.definition, existing, a.clusters, b.clusters) });
    }
  return { definition: opts.definition, suiteId: opts.suiteId, generatedAt: opts.now ?? new Date().toISOString(), ...(opts.demo ? { demo: true } : {}), tests: opts.tests, models, comparisons };
}

/** Test info for the view from loaded definitions and explainers (both sides use it: server and demo mode). */
export function ladderTestInfo(
  def: LadderDefinition,
  lookup: (id: string) => { name: string; difficulty?: string; cases?: number } | undefined,
  explainer: (id: string) => { hook: string; whatItTests: string; howScored: Array<{ text: string }>; icon: string } | undefined,
): Record<string, LadderTestInfo> {
  const out: Record<string, LadderTestInfo> = {};
  for (const id of ladderTestIds(def)) {
    const t = lookup(id);
    const x = t ? explainer(id) : undefined;
    out[id] = t
      ? { name: t.name, exists: true, difficulty: t.difficulty, cases: t.cases, ...(x ? { hook: x.hook, whatItTests: x.whatItTests, howScored: x.howScored.map((s) => s.text), icon: x.icon } : {}) }
      : { name: plannedName(id), exists: false };
  }
  return out;
}

/** Display names for tests that are not built yet (from docs: the Business Ladder plan). */
export const PLANNED_NAMES: Record<string, string> = {
  'work.invoice-matching': 'Invoices to Pay',
  'work.expense-audit': 'Expense Policy Check',
  'work.bank-reconciliation': 'Reconcile the Account',
  'work.accounts-clerk': 'The Accounts Clerk',
  'work.month-end': 'Month-End Close',
  'work.support-refunds': 'Customer Support Queue',
  'work.support-replies': 'Reply Within Policy',
  'work.helpdesk-agent': 'The Helpdesk Agent',
  'work.support-week': 'Run Customer Service for a Week',
  'work.cv-shortlist': 'Shortlist the Candidates',
  'work.payroll-check': 'Check the Payslips',
  'work.recruiter': 'The Recruiter',
  'work.inbox-triage': 'Monday Inbox',
  'work.meeting-scheduler': 'Find a Slot',
  'work.contract-redline': 'What Changed in the Contract?',
  'work.assistant': 'The Personal Assistant',
  'work.assistant-hard': 'The Personal Assistant: Hard',
  'work.office-week': 'Run the Office for a Week',
  'work.crm-dedupe': 'Clean the Customer List',
  'work.sql-questions': 'Ask the Database',
  'work.sql-hard': 'Ask the Database: Hard',
  'work.board-report': 'Numbers for the Board',
  'work.analyst-agent': 'The Analyst',
  'work.stock-reorder': 'What Do We Reorder?',
  'work.shift-rota': 'Build the Rota',
  'work.delivery-routes': 'Plan the Van Routes',
  'work.warehouse-week': 'Run the Warehouse for a Week',
};
function plannedName(id: string): string {
  return PLANNED_NAMES[id] ?? id.replace(/^work\./, '').replace(/-/g, ' ').replace(/^./, (c) => c.toUpperCase());
}
