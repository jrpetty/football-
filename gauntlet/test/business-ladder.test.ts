/**
 * The Business Ladder: the config (config/business-ladder.json), the climbing rules, the paired comparison and
 * the three-area split (benchmark / business / everyday life).
 *
 * The config check is tolerant on purpose: as tests land, the lead flips "planned": true → false. It fails
 * when a planned test now exists (telling you to flip it) and when a non-planned one does not.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { getSuite, loadTests } from '../src/core/registry.ts';
import { EXPLAIN_ICONS } from '../src/core/explainers.ts';
import { loadBusinessLadder } from '../src/core/business-ladder-file.ts';
import {
  LADDER_LEVELS,
  bandOf,
  buildBusinessLadderView,
  compareLadders,
  ladderTestIds,
  multiTestScore,
  scenarioLadder,
  validateBusinessLadder,
  type LadderDefinition,
  type ModelClusters,
} from '../src/core/business-ladder.ts';
import { MIN_PAIRED_CASES, pairedDifference, pairedWords } from '../src/core/paired.ts';
import { AREAS, BUSINESS_SUITE_ID, LIFE_SUITE_ID, areaOfCategory, areaOfSuite, areaOfTest } from '../src/core/areas.ts';

const def = loadBusinessLadder();
const library = new Set(loadTests().map((t) => t.definition.id));

test('the ladder config: levels 1–5, six scenarios, unique ids, built tests exist and planned ones do not', () => {
  assert.deepEqual(def.levels.map((l) => l.level), [...LADDER_LEVELS]);
  assert.deepEqual(def.levels.map((l) => l.name), ['Assist', 'Automate a step', 'Run a workflow', 'Agent', 'Autonomous department']);
  assert.equal(def.scenarios.length, 6);
  assert.equal(def.passMark, 70);
  const problems = validateBusinessLadder(def, library);
  assert.deepEqual(problems, [], problems.join('\n'));
  for (const s of def.scenarios) {
    assert.ok((EXPLAIN_ICONS as readonly string[]).includes(s.icon), `${s.id}: icon ${s.icon} is an EXPLAIN_ICONS name`);
    assert.ok(s.tests.some((t) => t.level === 1 && !t.planned), `${s.id}: has a built Level 1 test`);
  }
  for (const id of ladderTestIds(def)) assert.match(id, /^work\./, `${id}: business tests are work.*`);
});

test('the validator catches a planned test that now exists, a missing built test, bad levels and duplicates', () => {
  const bad: LadderDefinition = JSON.parse(JSON.stringify(def));
  bad.scenarios[0]!.tests[0]!.planned = true; // exists, so must be flipped
  const notBuilt = bad.scenarios.flatMap((s, i) => (i === 0 ? s.tests.slice(1) : s.tests)).find((t) => t.planned)!;
  notBuilt.planned = false; // does not exist
  bad.scenarios[1]!.tests.push({ id: 'work.cv-read', level: 1 });
  bad.scenarios[2]!.tests.push({ id: 'work.x', level: 6, planned: true });
  bad.levels = bad.levels.slice(0, 4);
  const errs = validateBusinessLadder(bad, library).join('\n');
  assert.match(errs, /work\.invoice-read now exists .* set "planned": false/);
  assert.match(errs, new RegExp(`${notBuilt.id.replace('.', '\\.')} is not in the test library: mark it "planned": true`));
  assert.match(errs, /work\.cv-read is listed twice/);
  assert.match(errs, /level must be 1–5/);
  assert.match(errs, /levels must be exactly 1–5/);
});

// ─── climbing rules on a small synthetic ladder ───

const MINI: LadderDefinition = {
  ...def,
  scenarios: [
    {
      id: 'demo',
      name: 'Demo',
      color: '#123456',
      icon: 'chart',
      tests: [
        { id: 'a1', level: 1 },
        { id: 'a2', level: 2 },
        { id: 'b2', level: 2 },
        { id: 'p3', level: 3, planned: true },
        { id: 'a4', level: 4 },
      ],
    },
  ],
};
const BUILT = new Set(['a1', 'a2', 'b2', 'a4']);
/** n cases all scoring `s` (one repeat each). */
const flat = (s: number, n = 10) => Object.fromEntries(Array.from({ length: n }, (_, i) => [`c${i}`, [s]]));
/** n cases, k of them 1 and the rest 0. */
const mix = (k: number, n = 10) => Object.fromEntries(Array.from({ length: n }, (_, i) => [`c${i}`, [i < k ? 1 : 0]]));

test('height: every level up to L passed (all its built tests ≥ 70); coming-soon levels are skipped, never reached', () => {
  const r = scenarioLadder(MINI, MINI.scenarios[0]!, BUILT, { a1: flat(0.9), a2: flat(0.8), b2: flat(0.75), a4: flat(0.95) });
  assert.equal(r.levels[2]!.status, 'coming-soon');
  assert.equal(r.levels[4]!.status, 'coming-soon', 'no test at level 5');
  assert.equal(r.height, 4, 'level 3 has no built test, so the climb goes on to level 4');
  assert.equal(r.topBuilt, 4);
  assert.equal(r.levels[1]!.score, 0.775, 'level score = mean of its tests');
  // One level-2 test below 70 stops the climb at 1, even if level 4 is aced.
  const s = scenarioLadder(MINI, MINI.scenarios[0]!, BUILT, { a1: flat(0.9), a2: flat(0.8), b2: flat(0.6), a4: flat(1) });
  assert.equal(s.height, 1);
  assert.equal(s.stoppedAt, 2);
  assert.equal(s.stoppedBecause, 'failed');
  // A built test that was not run blocks the climb ("partial"), so missing data never counts as a pass.
  const p = scenarioLadder(MINI, MINI.scenarios[0]!, BUILT, { a1: flat(0.9), a2: flat(0.8) });
  assert.equal(p.height, 1);
  assert.equal(p.stoppedBecause, 'partial');
  assert.equal(scenarioLadder(MINI, MINI.scenarios[0]!, BUILT, {}).height, 0);
});

test('within the noise: a pass whose range dips under 70 is borderline; a fail whose range reaches 70 is just short', () => {
  // 7 of 10 cases right: exactly 70, range well below and above → borderline pass.
  const r = scenarioLadder(MINI, MINI.scenarios[0]!, BUILT, { a1: mix(7), a2: flat(1), b2: flat(1), a4: flat(1) });
  assert.equal(r.levels[0]!.tests[0]!.band, 'borderline-pass');
  assert.equal(r.height, 4);
  assert.equal(r.borderline, true);
  // 6 of 10: below 70, but the range reaches it → just short.
  const s = scenarioLadder(MINI, MINI.scenarios[0]!, BUILT, { a1: mix(6), a2: flat(1), b2: flat(1), a4: flat(1) });
  assert.equal(s.height, 0);
  assert.equal(s.justShort, true);
  // 1 of 10: a clear fail.
  assert.equal(scenarioLadder(MINI, MINI.scenarios[0]!, BUILT, { a1: mix(1) }).justShort, false);
  assert.equal(bandOf(0.95, [0.9, 1], 0.7), 'clear-pass');
  assert.equal(bandOf(0.3, [0.1, 0.5], 0.7), 'clear-fail');
});

test('ranges: a cluster bootstrap over cases, repeats kept together; one case gives a zero-width range', () => {
  const m = multiTestScore([[[1, 1], [0, 0], [1, 0]]])!;
  assert.equal(Math.round(m.score * 1000), 500);
  assert.ok(m.ci95[0] < 0.5 && m.ci95[1] > 0.5);
  assert.deepEqual(multiTestScore([[[0.8]]])!.ci95, [0.8, 0.8]);
  assert.equal(multiTestScore([]), null);
  assert.deepEqual(multiTestScore([[[1], [0]]]), multiTestScore([[[1], [0]]]), 'deterministic (fixed seed)');
});

// ─── paired comparison ───

test('paired difference: same cases, gap with a 95% range; "ahead" only when the range excludes zero', () => {
  const a = { a: mix(9), b: mix(3) };
  const r = pairedDifference([a]);
  assert.equal(r.verdict, 'a-ahead');
  assert.equal(Math.round(r.diff! * 100), 60);
  assert.ok(r.ci95![0] > 0);
  assert.equal(pairedWords(r, 'Model A', 'Model B'), 'Model A ahead by 60 points');
  // Reversed.
  assert.equal(pairedDifference([{ a: mix(3), b: mix(9) }]).verdict, 'b-ahead');
  // A small gap on few cases is a tie, and it is never worded as a win.
  const tie = pairedDifference([{ a: mix(6), b: mix(5) }]);
  assert.equal(tie.verdict, 'tied');
  assert.match(pairedWords(tie, 'A', 'B'), /^Statistically tied \(gap 10 points, within the noise\)$/);
  // Only shared cases count; unmatched cases are ignored.
  const partial = pairedDifference([{ a: { c1: [1], c2: [1], c3: [1], c9: [0] }, b: { c1: [0], c2: [0], c3: [0] } }]);
  assert.equal(partial.cases, 3);
  assert.equal(partial.diff, 1);
  // Too few shared cases: never "ahead".
  const few = pairedDifference([{ a: { c1: [1], c2: [1] }, b: { c1: [0], c2: [0] } }]);
  assert.ok(few.cases < MIN_PAIRED_CASES);
  assert.equal(few.verdict, 'tied');
  assert.match(pairedWords(few, 'A', 'B'), /Too few shared questions/);
  assert.equal(pairedDifference([{ a: {}, b: mix(5) }]).verdict, 'no-data');
  // Identical answers: tied with no gap.
  assert.equal(pairedWords(pairedDifference([{ a: mix(5), b: mix(5) }]), 'A', 'B'), 'Statistically tied (no gap)');
});

test('ladder comparison per scenario and level, and the API view (coverage, pairs of the requested models)', () => {
  const A: ModelClusters = { a1: mix(10), a2: mix(9), b2: mix(9), a4: mix(8) };
  const B: ModelClusters = { a1: mix(9), a2: mix(2), b2: mix(3), a4: mix(8) };
  const c = compareLadders(MINI, BUILT, A, B);
  const lv = (n: number) => c.scenarios[0]!.levels.find((l) => l.level === n)!.result;
  assert.equal(lv(1)!.verdict, 'tied', 'a 10-point gap on 10 cases is within the noise');
  assert.equal(lv(2)!.verdict, 'a-ahead');
  assert.equal(lv(3), null, 'no built test at level 3');
  assert.equal(lv(4)!.verdict, 'tied');
  const tests = { a1: { name: 'A1', exists: true }, a2: { name: 'A2', exists: true }, b2: { name: 'B2', exists: true }, p3: { name: 'P3', exists: false }, a4: { name: 'A4', exists: true } };
  const v = buildBusinessLadderView({ definition: MINI, suiteId: 'x', tests, models: [{ id: 'm1', label: 'M1', clusters: A }, { id: 'm2', label: 'M2', clusters: { a1: mix(9) } }, { id: 'm3', label: 'M3', clusters: B }], compare: ['m1', 'm3', 'm2', 'm1', 'nobody'], now: 't' });
  assert.deepEqual(v.models.map((m) => m.coverage), [{ run: 4, built: 4 }, { run: 1, built: 4 }, { run: 4, built: 4 }]);
  assert.deepEqual(v.comparisons.map((p) => `${p.a}-${p.b}`), ['m1-m3', 'm1-m2', 'm3-m2']);
  assert.equal(v.models[0]!.scenarios[0]!.height, 4);
  assert.equal(v.models[2]!.scenarios[0]!.height, 1);
});

// ─── areas ───

test('three areas: work → business, life → everyday life, everything else → benchmark', () => {
  assert.deepEqual(AREAS.map((a) => a.id), ['benchmark', 'business', 'life']);
  assert.equal(areaOfCategory('work'), 'business');
  assert.equal(areaOfCategory('life'), 'life');
  for (const c of ['reasoning', 'math', 'horizon', 'trick', 'vision', 'art', 'agentic', undefined, null, '']) assert.equal(areaOfCategory(c as string), 'benchmark');
  assert.equal(areaOfTest({ id: 'work.invoice-read', category: 'work' }), 'business');
  assert.equal(areaOfTest({ id: 'life.split-bill' }), 'life');
  assert.equal(areaOfTest({ id: 'math.word-problems' }), 'benchmark');
  assert.equal(areaOfSuite({ id: BUSINESS_SUITE_ID }), 'business');
  assert.equal(areaOfSuite({ id: LIFE_SUITE_ID }), 'life');
  assert.equal(areaOfSuite({ id: 'core', tests: [{ id: 'math.a' }, { id: 'work.b' }] }), 'benchmark');
  assert.equal(areaOfSuite({ id: 'mine', tests: [{ id: 'work.a' }, { id: 'work.b' }] }), 'business');
  assert.equal(areaOfSuite({ id: 'all', tests: [{ id: '*' }] }), 'benchmark');
  // Every built test on the ladder is in the business area.
  for (const t of loadTests()) if (ladderTestIds(def).includes(t.definition.id)) assert.equal(areaOfTest({ id: t.definition.id, category: t.definition.category }), 'business');
  // No benchmark suite (the Gauntlet Index) contains business or everyday-life tests.
  for (const s of ['core', 'frontier', 'quick', 'quick-check']) {
    const suite = getSuite(s);
    if (!suite) continue;
    for (const e of suite.tests) assert.notEqual(areaOfTest({ id: e.id }), 'business', `${s} contains ${e.id}`);
  }
});
