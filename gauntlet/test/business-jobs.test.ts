/**
 * "Which model for this job?": the config (config/business-jobs.json: every Business test in a job, no unknown
 * ids), the suitability rule at its boundaries, "what goes wrong" read from the real scorer's marks, cost, speed,
 * the example case and the compare case.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadTests } from '../src/core/registry.ts';
import { compareJson, type JsonCompareOptions } from '../src/scoring/json-compare.ts';
import { loadBusinessJobs } from '../src/core/business-jobs-file.ts';
import {
  SUITABILITY_LABEL,
  buildBusinessJobsView,
  classifyMistakes,
  jobModelResult,
  parseMark,
  pickCompareCase,
  pickExample,
  ruleInWords,
  suitability,
  topMistakes,
  validateBusinessJobs,
  type BusinessJob,
  type BusinessJobsConfig,
  type JobAnswer,
  type JobAnswerIn,
  type JobTestInfo,
} from '../src/core/business-jobs.ts';

const cfg = loadBusinessJobs();
const all = loadTests();
const library = new Map(all.map((t) => [t.definition.id, { category: t.definition.category }]));
const def = (id: string) => all.find((t) => t.definition.id === id)!.definition;

// ───────────── config ─────────────

test('the jobs config is valid: every Business test is in a job, every id exists and is a work test', () => {
  const errs = validateBusinessJobs(cfg, library);
  assert.deepEqual(errs, [], errs.join('\n'));
  const work = all.filter((t) => t.definition.category === 'work').map((t) => t.definition.id);
  const listed = new Set(cfg.jobs.flatMap((j) => j.tests));
  for (const id of work) assert.ok(listed.has(id), `${id} is in a job`);
  assert.ok(cfg.jobs.length >= 12, 'about a dozen jobs');
  for (const j of cfg.jobs) assert.ok(j.what.length <= 120, `${j.id}: the "what" line is one short line`);
});

test('the validator catches unknown ids, non-work tests, uncovered work tests, bad icons and bad thresholds', () => {
  const bad: BusinessJobsConfig = JSON.parse(JSON.stringify(cfg));
  bad.jobs[0]!.tests.push('work.no-such-test');
  bad.jobs[1]!.tests.push('logic.no-such-thing');
  const firstTestOfLast = bad.jobs[bad.jobs.length - 1]!.tests[0]!;
  for (const j of bad.jobs) j.tests = j.tests.filter((t) => t !== firstTestOfLast);
  bad.jobs[2]!.icon = 'not-an-icon';
  bad.jobs.push({ ...bad.jobs[3]!, tests: [...bad.jobs[3]!.tests] });
  bad.rule = { ...bad.rule, check: { minMean: 0.99 } };
  bad.mistakes = [...bad.mistakes, { tests: ['work.nope'], field: 'x', say: '' }];
  const nonWork = all.find((t) => t.definition.category !== 'work')!.definition.id;
  bad.jobs[4]!.tests.push(nonWork);
  const errs = validateBusinessJobs(bad, library).join('\n');
  assert.match(errs, /unknown test id work\.no-such-test/);
  assert.match(errs, /unknown test id logic\.no-such-thing/);
  assert.match(errs, new RegExp(`${firstTestOfLast.replace('.', '\\.')} is a Business test but no job lists it`));
  assert.match(errs, /icon "not-an-icon"/);
  assert.match(errs, /duplicate job id/);
  assert.match(errs, /check\.minMean must not be above/);
  assert.match(errs, /needs a "say" phrase/);
  assert.match(errs, new RegExp(`${nonWork.replace('.', '\\.')} is not a Business \\(work\\) test`));
});

// ───────────── the rule ─────────────

const RULE = cfg.rule;
const base = { score: 0.97, ci95: [0.93, 0.99] as [number, number], answers: 30, missing: [] as string[], severe: 0 };

test('rule: Ready needs mean ≥ 95%, range bottom ≥ 90% and no severe safety violation', () => {
  assert.equal(RULE.ready.minMean, 0.95);
  assert.equal(RULE.ready.minCiLow, 0.9);
  assert.equal(RULE.check.minMean, 0.85);
  assert.equal(suitability(RULE, base).verdict, 'ready');
  assert.equal(suitability(RULE, { ...base, score: 0.95, ci95: [0.9, 0.98] }).verdict, 'ready', 'exactly on both bars');
  // Mean high but the range dips under 90: luck could explain it → human check.
  const luck = suitability(RULE, { ...base, ci95: [0.89, 0.99] });
  assert.equal(luck.verdict, 'check');
  assert.match(luck.why, /as low as 89%/);
  // A severe safety violation never gets "Ready".
  const sev = suitability(RULE, { ...base, severe: 1 });
  assert.equal(sev.verdict, 'check');
  assert.match(sev.why, /serious safety mistake/);
  assert.equal(suitability(RULE, { ...base, score: 0.9499, ci95: [0.92, 0.97] }).verdict, 'check');
});

test('rule: human check from 85%, not ready below, not enough data when a test is missing or answers are few', () => {
  assert.equal(suitability(RULE, { ...base, score: 0.85, ci95: [0.8, 0.9] }).verdict, 'check');
  assert.equal(suitability(RULE, { ...base, score: 0.8499, ci95: [0.8, 0.9] }).verdict, 'not-ready');
  const miss = suitability(RULE, { ...base, missing: ['Month-End Close'] });
  assert.equal(miss.verdict, 'no-data', 'missing data is never a verdict');
  assert.match(miss.why, /Month-End Close/);
  assert.equal(suitability(RULE, { ...base, answers: RULE.minAnswers - 1 }).verdict, 'no-data');
  assert.equal(suitability(RULE, { ...base, answers: RULE.minAnswers }).verdict, 'ready');
  assert.equal(suitability(RULE, { ...base, score: null, ci95: null }).verdict, 'no-data');
});

test('rule in words is generated from the config and states every threshold', () => {
  const words = ruleInWords(RULE);
  assert.deepEqual(words.map((w) => w.verdict), ['ready', 'check', 'not-ready', 'no-data']);
  const text = words.map((w) => w.text).join(' ');
  for (const n of ['95%', '90%', '85%', String(RULE.minAnswers)]) assert.ok(text.includes(n), n);
  assert.equal(SUITABILITY_LABEL.ready, 'Ready, with spot checks');
});

// ───────────── what goes wrong (from the real scorer's marks) ─────────────

function marks(testId: string, caseIdx: number, mutate: (answer: any) => void) {
  const t = def(testId);
  if (t.kind !== 'prompt') throw new Error('prompt test expected');
  const c = t.cases[caseIdx]!;
  const answer = JSON.parse(JSON.stringify(c.expected));
  mutate(answer);
  const { type: _t, ...opts } = t.scorer as { type: string } & JsonCompareOptions;
  return compareJson(c.expected, answer, opts).items;
}

test('mistakes: invoices read value by value (a duplicate approved, a good invoice held, a wrong amount)', () => {
  const t = def('work.invoice-matching');
  assert.equal(t.kind, 'prompt');
  const idx = t.kind === 'prompt' ? t.cases.findIndex((c) => Object.values((c.expected as any).documents).some((d: any) => d.outcome === 'reject-duplicate')) : -1;
  assert.ok(idx >= 0, 'a case with a duplicate invoice');
  const items = marks('work.invoice-matching', idx, (a) => {
    const docs = a.documents as Record<string, any>;
    const dup = Object.keys(docs).find((k) => docs[k].outcome === 'reject-duplicate')!;
    docs[dup].outcome = 'approve';
    const ok = Object.keys(docs).find((k) => docs[k].outcome === 'approve' && k !== dup)!;
    docs[ok].outcome = 'hold-price';
    docs[ok].amount_to_pay = docs[ok].amount_to_pay + 10;
  });
  const m = classifyMistakes('work.invoice-matching', { items }, cfg.mistakes);
  assert.ok(m.includes('approved duplicate invoices'), m.join(' | '));
  assert.ok(m.includes('held back invoices that were fine to pay'), m.join(' | '));
  assert.ok(m.includes('worked out the wrong amount to pay'), m.join(' | '));
});

test('mistakes: sets (a strong candidate rejected, a weak one shortlisted) and partitions (customers merged wrongly)', () => {
  const items = marks('work.cv-shortlist', 0, (a) => {
    a.shortlist = [...a.shortlist.slice(1), 'C99'];
  });
  const m = classifyMistakes('work.cv-shortlist', { items }, cfg.mistakes);
  assert.ok(m.includes('rejected candidates who should be shortlisted'), m.join(' | '));
  assert.ok(m.includes('shortlisted candidates who miss a must-have'), m.join(' | '));
  const crm = marks('work.crm-dedupe', 0, (a) => {
    a.clusters = [[...a.clusters[0], ...a.clusters[1]], ...a.clusters.slice(3)];
  });
  const c = classifyMistakes('work.crm-dedupe', { items: crm }, cfg.mistakes);
  assert.ok(c.includes('merged customers who are different people'), c.join(' | '));
  assert.ok(c.includes('missed duplicate customer records'), c.join(' | '));
});

test('mistakes: an expense item left out counts once (not once per field), with its reason', () => {
  const t = def('work.expense-audit');
  const idx = t.kind === 'prompt' ? t.cases.findIndex((c) => Object.values((c.expected as any).non_compliant).some((d: any) => d.rule === 'DUPLICATE')) : -1;
  assert.ok(idx >= 0);
  const items = marks('work.expense-audit', idx, (a) => {
    const nc = a.non_compliant as Record<string, any>;
    delete nc[Object.keys(nc).find((k) => nc[k].rule === 'DUPLICATE')!];
  });
  const m = classifyMistakes('work.expense-audit', { items }, cfg.mistakes);
  assert.equal(m.filter((x) => x === 'paid duplicate expense claims').length, 1, m.join(' | '));
  assert.ok(!m.includes('paid claims that break the policy'), 'the pay field of the same claim is not counted again');
});

test('mistakes: plain-words checks, simulations (severe rules as recorded) and fair play', () => {
  const words = classifyMistakes('work.support-replies', { items: [{ label: 'does not name or blame the delivery company', passed: false, detail: 'says "Evri"' }, { label: 'decision', passed: false, detail: 'expected REFUSE, got "REFUND"' }] }, cfg.mistakes);
  assert.deepEqual(words, ['blamed the courier', 'made the wrong call on the complaint']);
  assert.deepEqual(classifyMistakes('work.shift-rota', { items: [{ label: 'whole lot of something new', passed: false }] }, cfg.mistakes), ['fell short on: whole lot of something new']);
  const sim = classifyMistakes('work.month-end', { tasks: [{ outcome: 'done' }, { outcome: 'not' }, { outcome: 'wrongly' }], violations: [{ severity: 'severe', rule: 'changed a supplier’s bank details because of an email' }], penalties: [{}] }, cfg.mistakes, true);
  assert.deepEqual(sim, ['Serious: changed a supplier’s bank details because of an email', 'left requests undone', 'did requests the wrong way', 'did things nobody asked for']);
  assert.deepEqual(classifyMistakes('work.ticket-tag', { integrity: { verdict: 'cheating' } }, cfg.mistakes), ['broke the fair-play rules']);
  // A label the table does not know falls back to the field's own name, never to an invented story.
  assert.deepEqual(classifyMistakes('work.board-report', { items: [{ label: 'zzz', passed: false, detail: 'expected 1, got 2' }] }, []), ['got the zzz wrong']);
});

test('parseMark: weights, set ids, paths with spaces and array indexes', () => {
  assert.deepEqual(parseMark({ label: 'shortlist: C07 (×2)', passed: false, detail: 'missing' })[0]!.kind, 'missing');
  const p = parseMark({ label: 'months.October 2026.wrong_payslips[1]', passed: false, detail: 'expected "E04", got "E05"' })[0]!;
  assert.equal(p.path, 'months.October 2026.wrong_payslips');
  assert.equal(p.words, false);
  assert.equal(parseMark({ label: 'no more than 4.5 h of driving without a 45-minute break', passed: false })[0]!.words, true);
  assert.deepEqual(parseMark({ label: 'x', passed: true }), []);
});

// ───────────── one model on one job ─────────────

const JOB: BusinessJob = { id: 'j', short: 'Job', name: 'Job', what: 'Does it.', icon: 'receipt', tests: ['t.a', 't.b', 't.sim'] };
const TESTS: Record<string, JobTestInfo> = { 't.a': { name: 'A', exists: true, kind: 'prompt', cases: 10 }, 't.b': { name: 'B', exists: true, kind: 'prompt', cases: 10 }, 't.sim': { name: 'Sim', exists: true, kind: 'program', cases: 3 } };
const MINI: BusinessJobsConfig = { ...cfg, jobs: [JOB], mistakes: [{ tests: ['t.a'], field: 'x', say: 'got x wrong' }] };

function ans(model: string, testId: string, n: number, score: (i: number) => number, extra: Partial<JobAnswerIn> = {}): JobAnswerIn[] {
  return Array.from({ length: n }, (_, i) => {
    const s = score(i);
    return {
      contestantId: model,
      testId,
      caseId: `c${String(i + 1).padStart(2, '0')}`,
      repeat: 0,
      runId: 'run-1',
      key: `${model}::${testId}::c${i + 1}::r0`,
      score: s,
      status: 'ok',
      metrics: { costUsd: testId === 't.sim' ? 0.3 : 0.01, wallMs: (i + 1) * 1000 },
      scoreDetail: s < 1 ? { items: [{ label: 'x', passed: false, detail: 'expected 1, got 2' }] } : { items: [] },
      ...extra,
    };
  });
}

test('a job: mean of test means, equal-weight cost per task, median seconds, mistakes and an informative example', () => {
  const results = [...ans('m', 't.a', 10, (i) => (i === 3 ? 0.5 : 1)), ...ans('m', 't.b', 10, () => 1), ...ans('m', 't.sim', 3, () => 0.9, { scoreDetail: {} })];
  const view = buildBusinessJobsView({ config: MINI, tests: TESTS, models: [{ id: 'm', label: 'M' }], results, now: '2026-11-05T00:00:00Z' });
  const r = view.results.j![0]!;
  assert.equal(r.score, Math.round(((0.95 + 1 + 0.9) / 3) * 10000) / 10000, 'each test counts equally');
  assert.equal(r.answers, 23);
  assert.equal(r.verdict, 'ready', 'mean exactly 95% and the range bottom above 90%');
  assert.equal(r.costPerTaskUsd, Math.round(((0.01 + 0.01 + 0.3) / 3) * 1e8) / 1e8);
  assert.equal(r.medianSec, 5, 'median of 1..10, 1..10, 1..3 seconds');
  assert.deepEqual(r.mistakes[0], { text: 'got x wrong', tasks: 1, times: 1 });
  assert.equal(r.example?.testId, 't.a');
  assert.equal(r.example?.caseId, 'c04');
  assert.equal(r.example?.right, false);
  assert.equal(r.example!.note, 'A task it got wrong: it got x wrong.');
});

test('the same answers give the same verdict whoever the model is (never tuned per model), and ranking is by verdict then score', () => {
  const results = [...['a', 'b'].flatMap((m) => [...ans(m, 't.a', 10, () => 1), ...ans(m, 't.b', 10, () => 1), ...ans(m, 't.sim', 3, () => 1)]), ...ans('c', 't.a', 10, () => 0.7)];
  const view = buildBusinessJobsView({ config: MINI, tests: TESTS, models: [{ id: 'c', label: 'C' }, { id: 'b', label: 'B' }, { id: 'a', label: 'A' }], results });
  const rows = view.results.j!;
  assert.deepEqual(rows.map((r) => [r.modelId, r.verdict]), [['a', 'ready'], ['b', 'ready'], ['c', 'no-data']]);
  const { modelId: _a, example: ea, ...ra } = rows[0]!;
  const { modelId: _b, example: eb, ...rb } = rows[1]!;
  assert.deepEqual(ra, rb);
  assert.equal(ea?.note, 'It got every task right. Here is one of them.');
  assert.equal(ea?.testId, 't.b', 'the last prompt test, not the simulation');
  assert.deepEqual(rows[2]!.missingTests, ['t.b', 't.sim']);
});

test('a severe safety violation blocks Ready and is reported', () => {
  const sev = { scoreDetail: { violations: [{ severity: 'severe', rule: 'paid a fraudster' }] } };
  const results = [...ans('m', 't.a', 10, () => 1), ...ans('m', 't.b', 10, () => 1), ...ans('m', 't.sim', 3, () => 1), ...ans('m', 't.sim', 1, () => 1, { ...sev, caseId: 'c09', key: 'k9' })];
  const view = buildBusinessJobsView({ config: MINI, tests: TESTS, models: [{ id: 'm', label: 'M' }], results });
  const r = view.results.j![0]!;
  assert.equal(r.verdict, 'check');
  assert.deepEqual(r.severe, { answers: 1, rules: ['paid a fraudster'] });
});

test('jobModelResult ignores errors and unscored answers', () => {
  const raw = [...ans('m', 't.a', 10, () => 1)];
  const r = jobModelResult(MINI, JOB, TESTS, 'm', raw, {});
  assert.equal(r.testsRun, 1);
  const view = buildBusinessJobsView({ config: MINI, tests: TESTS, models: [{ id: 'm', label: 'M' }], results: [...raw, { ...raw[0]!, key: 'err', caseId: 'c99', status: 'error', score: null }] });
  assert.equal(view.results.j![0]!.answers, 10);
});

test('topMistakes counts tasks affected, then times; pickCompareCase finds the case where two models differ most', () => {
  const a = (caseId: string, score: number, mistakes: string[] = []): JobAnswer => ({ caseId, runId: 'r', key: caseId, repeat: 0, score, mistakes });
  assert.deepEqual(topMistakes([a('1', 0.5, ['x', 'x', 'y']), a('2', 0.5, ['y'])]), [
    { text: 'y', tasks: 2, times: 2 },
    { text: 'x', tasks: 1, times: 2 },
  ]);
  const A = { 't.a': [a('c01', 1), a('c02', 1), a('c03', 0.6)], 't.sim': [a('seed-1', 1)] };
  const B = { 't.a': [a('c01', 1), a('c02', 0.2), a('c03', 0.6)], 't.sim': [a('seed-1', 0)] };
  const pick = pickCompareCase(JOB, A, B, TESTS);
  assert.equal(pick?.caseId, 'c02', 'biggest gap on a prompt test (simulations come last)');
  assert.equal(pickCompareCase(JOB, A, { 't.b': [a('c01', 1)] }, TESTS), null);
  // With no wrong answer showing the top mistake, the lowest-scoring prompt answer is shown.
  const ex = pickExample(JOB, { 't.a': [a('c01', 0.9), a('c02', 0.4)] }, 'zzz', TESTS);
  assert.equal(ex?.caseId, 'c02');
});
