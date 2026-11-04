/**
 * Difficulty ladders: the config (config/ladders.json), the height function every screen uses
 * (src/core/ladders.ts), the ladder suites and the leaderboard heights.
 *
 * The config check is tolerant on purpose: the ladder tests are built in parallel, so most start as
 * "planned": true. When one lands, this test fails and says exactly what to do: delete its "planned": true
 * and add its ten rung labels in config/ladders.json, and delete "planned": true in its suite.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { caseIds, getSuite, loadSuites, loadTests, resolveTests, suiteProblems } from '../src/core/registry.ts';
import { loadLadders } from '../src/core/ladders-file.ts';
import {
  attachLadderHeights,
  climbLadder,
  compareHeights,
  heightsNote,
  isLadderSuite,
  ladderCaseIds,
  ladderClimbs,
  ladderRules,
  passesRung,
  validateLadders,
  type LaddersConfig,
  type LibraryTest,
} from '../src/core/ladders.ts';
import { ladderClimbs as horizonClimbs } from '../src/presenter/visuals/horizon.ts';
import { rungCaption } from '../src/presenter/visuals/horizon.ts';
import { areaOfSuite, scoreNameForSuite, BUSINESS_LADDERS_SUITE_ID, FRONTIER_LADDERS_SUITE_ID } from '../src/core/areas.ts';
import type { PromptTest } from '../src/core/types.ts';

const cfg = loadLadders();
const all = loadTests();
const library = new Map<string, LibraryTest>(all.map((t) => [t.definition.id, { category: t.definition.category, caseIds: caseIds(t.definition) }]));

/** The ladder tests being built in parallel (ids fixed by the brief). */
const BENCHMARK_LADDERS = ['reasoning.grid-ladder', 'reasoning.truth-ladder', 'reasoning.plans-ladder', 'math.exact-ladder', 'coding.engineering-ladder'];
const BUSINESS_LADDERS = ['work.invoices-ladder', 'work.payroll-ladder', 'work.expenses-ladder', 'work.reconciliation-ladder', 'work.crm-ladder', 'work.contract-ladder', 'work.sql-ladder', 'work.support-ladder'];
const HORIZON = ['horizon.mind-runner', 'horizon.modpow-ladder', 'horizon.sliding-ladder', 'horizon.nonogram-ladder', 'horizon.tiling-count'];

// ─── the config ─────────────────────────────────────────────────────────────────────────────────────

test('config/ladders.json: every ladder listed once, built ones exist and are real ladders, planned ones do not exist yet', () => {
  assert.equal(cfg.levels, 10);
  assert.deepEqual(cfg.tests.map((t) => t.id).sort(), [...HORIZON, ...BENCHMARK_LADDERS, ...BUSINESS_LADDERS].sort());
  const problems = validateLadders(cfg, library);
  assert.deepEqual(problems, [], `\n${problems.join('\n')}\n(When a ladder test lands: delete its "planned": true, add its "rungs", and flip it in suites/.)`);
  for (const id of HORIZON) assert.ok(!cfg.tests.find((t) => t.id === id)!.planned, `${id} is built`);
});

test('pass marks: exact-answer ladders (Horizon and the five benchmark ladders) need full marks; the eight work.* ladders 90%', () => {
  for (const t of cfg.tests) {
    if (t.id.startsWith('work.')) {
      assert.equal(t.passMark, 0.9, t.id);
      assert.equal(t.area, 'business', t.id);
    } else {
      assert.equal(t.passMark, 1, t.id);
      assert.equal(t.area, 'benchmark', t.id);
    }
    assert.ok(t.grows.length > 20 && t.grows.length < 200, `${t.id}: "grows" is one line`);
  }
});

test('Horizon rung labels in the config match what the test files say', () => {
  for (const id of HORIZON) {
    const d = all.find((t) => t.definition.id === id)!.definition as PromptTest;
    const want = d.cases.map((c) => rungCaption(id, c.prompt ?? '', c.notes));
    assert.deepEqual(cfg.tests.find((t) => t.id === id)!.rungs, want, id);
  }
});

test('the validator: a planned test that now exists, a missing built test, bad cases, missing rungs, wrong area and pass mark', () => {
  const bad: LaddersConfig = JSON.parse(JSON.stringify(cfg));
  const fake = new Map(library);
  bad.tests.push({ id: 'reasoning.landed-ladder', name: 'Landed', area: 'benchmark', category: 'reasoning', passMark: 1, grows: 'A planned test that has landed, just a test of the validator.', planned: true });
  fake.set('reasoning.landed-ladder', { category: 'reasoning', caseIds: ladderCaseIds(10) }); // it landed
  bad.tests.find((t) => t.id === 'horizon.mind-runner')!.planned = undefined;
  bad.tests.push({ id: 'reasoning.not-built', name: 'Not built', area: 'benchmark', category: 'reasoning', passMark: 1, grows: 'Nothing yet, just a test of the validator.' });
  fake.set('math.odd-cases', { category: 'math', caseIds: ['L01', 'L02', 'c03', ...ladderCaseIds(10).slice(3)] });
  bad.tests.push({ id: 'math.odd-cases', name: 'Odd', area: 'benchmark', category: 'math', passMark: 1, grows: 'Odd case ids, just a test of the validator.', rungs: Array(10).fill('x') });
  bad.tests.find((t) => t.id === 'horizon.tiling-count')!.rungs = ['only one'];
  bad.tests.find((t) => t.id === 'work.sql-ladder')!.area = 'benchmark';
  bad.tests.find((t) => t.id === 'work.crm-ladder')!.passMark = 90;
  const errs = validateLadders(bad, fake).join('\n');
  assert.match(errs, /reasoning\.landed-ladder now exists in the test library: delete "planned": true and add its 10 "rungs"/);
  assert.match(errs, /reasoning\.not-built is not in the test library: mark it "planned": true/);
  assert.match(errs, /math\.odd-cases: cases must be exactly L01…L10/);
  assert.match(errs, /horizon\.tiling-count: needs "rungs"/);
  assert.match(errs, /work\.sql-ladder: work\.\* tests are area "business"/);
  assert.match(errs, /work\.crm-ladder: passMark must be in \(0, 1\]/);
});

// ─── the suites ─────────────────────────────────────────────────────────────────────────────────────

test('ladder suites: Frontier Ladders (benchmark) and Business Ladders (Business Score) list their ladders, planned until built', () => {
  const fl = getSuite(FRONTIER_LADDERS_SUITE_ID)!;
  const bl = getSuite(BUSINESS_LADDERS_SUITE_ID)!;
  assert.equal(fl.name, 'Frontier Ladders');
  assert.equal(bl.name, 'Business Ladders');
  assert.deepEqual(fl.tests.map((t) => t.id), BENCHMARK_LADDERS);
  assert.deepEqual(bl.tests.map((t) => t.id), BUSINESS_LADDERS);
  assert.equal(fl.repeats, 1);
  assert.equal(bl.repeats, 1);
  assert.equal(areaOfSuite(fl), 'benchmark');
  assert.equal(areaOfSuite(bl), 'business');
  assert.equal(scoreNameForSuite(bl), 'Business Score');
  assert.ok(isLadderSuite(cfg, fl) && isLadderSuite(cfg, bl) && isLadderSuite(cfg, getSuite('horizon')!));
  // A suite entry is planned exactly when the ladder is planned in the config, so both flips happen together.
  for (const s of [fl, bl]) for (const e of s.tests) assert.equal(!!e.planned, !!cfg.tests.find((t) => t.id === e.id)!.planned, `${s.id} → ${e.id}: planned in the suite and in config/ladders.json must agree`);
  // Planned entries are never run; built ones are.
  const runnable = resolveTests({ suiteId: fl.id }, all).map((t) => t.definition.id);
  assert.deepEqual(runnable, fl.tests.filter((t) => !t.planned).map((t) => t.id));
  // No ladder suite leaks into the Indexes.
  for (const s of loadSuites()) {
    if (s.id === fl.id || s.id === bl.id || s.id === 'all') continue;
    for (const e of s.tests) assert.ok(![...BENCHMARK_LADDERS, ...BUSINESS_LADDERS].includes(e.id) || s.id === 'horizon', `${s.id} must not include ${e.id}`);
  }
});

test('suite validation: a planned entry may be missing, but must be flipped once the test exists', () => {
  const lib = all.slice(0, 1);
  const id = lib[0]!.definition.id;
  const suites = [{ id: 'x', version: '1.0.0', name: 'X', description: 'x', tests: [{ id: 'never.built', planned: true }, { id, planned: true }, { id: 'gone.test' }] }];
  const errs = suiteProblems(suites, lib).join('\n');
  assert.doesNotMatch(errs, /never\.built/);
  assert.match(errs, new RegExp(`"${id.replace('.', '\\.')}" now exists, delete its "planned": true`));
  assert.match(errs, /unknown test "gone\.test"/);
});

// ─── the height function ────────────────────────────────────────────────────────────────────────────

const r = (contestantId: string, testId: string, level: number, ...scores: number[]) =>
  scores.map((score) => ({ contestantId, testId, caseId: `L${String(level).padStart(2, '0')}`, score, status: 'ok' }));

test('height = every level up to it passed reliably (2 of 3, or the single attempt); flukes above are "once solved"', () => {
  const h = climbLadder({ 1: [1, 1, 1], 2: [1, 1, 0], 3: [1, 0, 0], 4: [0, 0, 0], 6: [1, 0, 0] }, { passMark: 1 });
  assert.equal(h.height, 2);
  assert.equal(h.best, 6);
  assert.deepEqual(h.onceSolved, [3, 6]);
  assert.equal(h.levels, 10);
  assert.equal(h.levelsRun, 5);
  assert.deepEqual(h.rungs.map((x) => x.state).slice(0, 6), ['passed', 'passed', 'sometimes', 'failed', 'not-run', 'sometimes']);
  assert.deepEqual(h.rungs[1]!.passed, [true, true, false]);
  assert.equal(h.borderline, true, 'level 2 was passed 2 of 3: the height rests on a shaky rung');
  assert.equal(h.justShort, true, 'level 3 was passed once');
  const one = climbLadder({ 1: [1], 2: [1], 3: [0.4] }, { passMark: 1 });
  assert.equal(one.height, 2, 'one repeat: the single attempt decides');
  assert.equal(one.borderline, false);
  assert.equal(one.justShort, false);
  assert.equal(one.rungs[2]!.state, 'partial');
  assert.equal(climbLadder({}, { passMark: 1 }).score, null);
  assert.equal(climbLadder({ 2: [1] }, { passMark: 1 }).height, 0, 'a missing level 1 is not climbed');
});

test('partial-credit ladders pass a rung at the pass mark (0.9), with a 5-point noise band either side', () => {
  const h = climbLadder({ 1: [1], 2: [0.93], 3: [0.9], 4: [0.87] }, { passMark: 0.9 });
  assert.equal(h.height, 3);
  assert.equal(h.borderline, true, 'level 2 (93) and 3 (90) are within 5 points of 90');
  assert.equal(h.justShort, true, 'level 4 missed by 3 points');
  const clear = climbLadder({ 1: [1], 2: [0.97], 3: [0.6] }, { passMark: 0.9 });
  assert.equal(clear.height, 2);
  assert.equal(clear.borderline, false);
  assert.equal(clear.justShort, false);
  assert.ok(passesRung(0.9, 0.9) && !passesRung(0.8999, 0.9));
  assert.ok(passesRung(0.9995, 1) && !passesRung(0.99, 1), 'full marks: the Horizon rounding hair');
});

test('ladderClimbs: per-test results, errors are not attempts, every climber gets the same rungs; Horizon wrapper is unchanged', () => {
  const results = [
    ...r('a', 't', 1, 1, 1, 1), ...r('a', 't', 2, 1, 1, 0), ...r('a', 't', 3, 1, 0, 0), ...r('a', 't', 4, 0, 0, 0),
    ...r('b', 't', 1, 0.2), ...r('b', 't', 12, 1),
    { contestantId: 'a', testId: 'other', caseId: 'L09', score: 1, status: 'ok' },
    { contestantId: 'a', testId: 't', caseId: 'L09', score: null, status: 'error' },
    { contestantId: 'a', testId: 't', caseId: 'L08', score: 1, status: 'cancelled' },
  ];
  const [a, b] = ladderClimbs(results, 't', { passMark: 1 });
  assert.equal(a!.height, 2);
  assert.equal(a!.rungs.length, 12);
  assert.equal(b!.rungs.length, 12);
  assert.equal(a!.rungs[7]!.attempts, 0);
  assert.equal(a!.rungs[8]!.attempts, 0);
  const old = horizonClimbs(results, 't');
  assert.deepEqual(old.map((c) => [c.contestantId, c.height, c.best, c.score]), [['a', a!.height, a!.best, a!.score], ['b', b!.height, b!.best, b!.score]]);
  assert.equal(old[0]!.rungs[1]!.full, 2);
});

test('plain-English comparisons', () => {
  assert.equal(compareHeights({ label: 'Atlas', height: 5 }, { label: 'Nova', height: 3 }), 'Atlas climbs 2 rungs higher than Nova (level 5 vs 3).');
  assert.equal(compareHeights({ label: 'Atlas', height: 4 }, { label: 'Nova', height: 5, borderline: true }), 'Nova climbs 1 rung higher than Atlas (level 5 vs 4), but one rung is within the noise.');
  assert.equal(compareHeights({ label: 'A', height: 3 }, { label: 'B', height: 3 }), 'A and B reach the same height: level 3.');
  assert.equal(heightsNote([{ label: 'A', height: 6 }, { label: 'B', height: 6 }, { label: 'C', height: 2 }, { label: 'Random', height: 9, baseline: true }]), 'A and B share the top at level 6, 4 rungs above C.');
  assert.equal(heightsNote([{ label: 'A', height: 0 }, { label: 'B', height: 0 }]), 'No model passed level 1 reliably.');
  assert.equal(heightsNote([]), null);
});

test('leaderboard heights: per test cell and the mean over a model’s ladder tests; boards without ladders are untouched', () => {
  const rules = ladderRules(cfg);
  const board = {
    tests: [{ id: 'horizon.mind-runner' }, { id: 'horizon.modpow-ladder' }, { id: 'math.olympiad' }],
    rows: [
      { contestantId: 'a', tests: { 'horizon.mind-runner': {}, 'horizon.modpow-ladder': {}, 'math.olympiad': {} } as Record<string, { ladder?: never }> },
      { contestantId: 'b', tests: { 'math.olympiad': {} } as Record<string, { ladder?: never }> },
    ],
  };
  const results = [...r('a', 'horizon.mind-runner', 1, 1), ...r('a', 'horizon.mind-runner', 2, 1), ...r('a', 'horizon.modpow-ladder', 1, 0)];
  attachLadderHeights(board, results, rules);
  const row = board.rows[0] as unknown as { ladderHeight: number; ladderTests: number; tests: Record<string, { ladder?: { height: number; passMark: number } }> };
  assert.equal(row.tests['horizon.mind-runner']!.ladder!.height, 2);
  assert.equal(row.tests['horizon.modpow-ladder']!.ladder!.height, 0);
  assert.equal(row.tests['math.olympiad']!.ladder, undefined);
  assert.equal(row.ladderHeight, 1);
  assert.equal(row.ladderTests, 2);
  assert.equal((board.rows[1] as { ladderHeight?: number | null }).ladderHeight, null);
  const plain = { tests: [{ id: 'math.olympiad' }], rows: [{ contestantId: 'a', tests: {} }] };
  attachLadderHeights(plain, results, rules);
  assert.equal('ladderHeight' in plain.rows[0]!, false);
  for (const t of cfg.tests) assert.equal(rules.has(t.id), !t.planned, `${t.id}: planned ladders have no rule until built`);
});
