/**
 * Difficulty ladders: the blind calibration in config/ladders.json (`calibration` per test) — the validator, the
 * verdict worked out from the tiers, and the plain-English lines the ladder view, the test pages, the Presenter and
 * the public site show.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { caseIds, loadTests } from '../src/core/registry.ts';
import { loadLadders } from '../src/core/ladders-file.ts';
import { climbBlock, climbSvg, miniStairsSvg } from '../src/channel/site-ladder.ts';
import {
  calibrationAt,
  calibrationClimb,
  calibrationClimbText,
  calibrationSummitText,
  rungSizes,
  calibrationMonth,
  calibrationPendingText,
  calibrationTierText,
  calibrationVerdict,
  calibrationVerdictText,
  levelsText,
  validateCalibration,
  validateLadders,
  type LadderCalibration,
  type LaddersConfig,
  type LibraryTest,
} from '../src/core/ladders.ts';

const cfg = loadLadders();
const library = new Map<string, LibraryTest>(loadTests().map((t) => [t.definition.id, { category: t.definition.category, caseIds: caseIds(t.definition) }]));
const cal = (tiers: LadderCalibration['tiers'], extra: Partial<LadderCalibration> = {}): LadderCalibration => ({ date: '2026-11', tiers, ...extra });

test('config: the 13 blind-tested ladders carry a calibration, Horizon keeps its design targets, and it validates', () => {
  const withCal = cfg.tests.filter((t) => t.calibration).map((t) => t.id);
  assert.equal(withCal.length, 13);
  for (const t of cfg.tests) assert.equal(!!t.calibration, t.category !== 'horizon', t.id);
  assert.deepEqual(validateLadders(cfg, library), []);
  assert.ok(cfg.calibrationMethod && /one attempt per level/.test(cfg.calibrationMethod));
});

test('config: the measured verdicts (Engineering and the eight Business ladders are cleared by the top model)', () => {
  const v = (id: string) => calibrationVerdict(cfg.tests.find((t) => t.id === id)!.calibration!);
  assert.equal(v('reasoning.grid-ladder'), 'separates');
  assert.equal(v('reasoning.truth-ladder'), 'separates');
  assert.equal(v('math.exact-ladder'), 'partly');
  assert.equal(v('reasoning.plans-ladder'), 'separates');
  assert.equal(v('coding.engineering-ladder'), 'top-clears');
  for (const t of cfg.tests.filter((x) => x.area === 'business')) assert.equal(v(t.id), 'top-clears', t.id);
});

test('validator: level numbers 1..10, no level both passed and failed, tiers small/mid/top only, the date and verdict', () => {
  const errs = (c: unknown) => validateCalibration('x.ladder', c, 10).join('\n');
  assert.equal(errs(cal({ small: { passed: [1, 2], failed: [4] }, mid: { passed: [2, 4], failed: [6], pending: [8] }, top: { passed: [], failed: [] } })), '');
  assert.match(errs(cal({ small: { passed: [0], failed: [] } })), /tiers\.small\.passed: 0 is not a level \(1\.\.10\)/);
  assert.match(errs(cal({ small: { passed: [11], failed: [] } })), /11 is not a level/);
  assert.match(errs(cal({ small: { passed: [2.5], failed: [] } })), /2\.5 is not a level/);
  assert.match(errs(cal({ mid: { passed: [4], failed: [4] } })), /tiers\.mid: L4 is both passed and failed/);
  assert.match(errs(cal({ top: { passed: [9], failed: [], pending: [9] } })), /L9 is both passed and pending/);
  assert.match(errs(cal({ top: { passed: [3, 3], failed: [] } })), /L3 is listed twice/);
  assert.match(errs(cal({ huge: { passed: [], failed: [] } } as never)), /tiers\.huge: the tiers are small, mid and top only/);
  assert.match(errs(cal({ small: { passed: [1] } } as never)), /tiers\.small\.failed must be a list/);
  assert.match(errs(cal({ small: { passed: [1], failed: [], tried: [3] } } as never)), /unknown field "tried"/);
  assert.match(errs(cal({})), /needs at least one of small, mid, top/);
  assert.match(errs({ date: 'November', tiers: { small: { passed: [], failed: [] } } }), /date must be "YYYY-MM"/);
  assert.match(errs(cal({ small: { passed: [], failed: [] } }, { verdict: 'wins' as never })), /verdict must be "separates", "top-clears", "partly"/);
  assert.match(errs({ date: '2026-11' }), /tiers must be an object/);
  assert.match(errs([]), /calibration must be an object/);
  // Wired into validateLadders, with the test's own number of rungs.
  const bad: LaddersConfig = JSON.parse(JSON.stringify(cfg));
  bad.tests.find((t) => t.id === 'math.exact-ladder')!.calibration!.tiers.top!.passed.push(9);
  bad.tests.find((t) => t.id === 'horizon.mind-runner')!.calibration = cal({ small: { passed: [12], failed: [] } });
  const all = validateLadders(bad, library).join('\n');
  assert.match(all, /math\.exact-ladder: calibration\.tiers\.top: L9 is both passed and failed/);
  assert.match(all, /horizon\.mind-runner: calibration\.tiers\.small\.passed: 12 is not a level \(1\.\.10\)/);
});

test('verdict: top-clears when the top model failed nothing; separates when each size got further; partly otherwise; explicit wins', () => {
  const grid = cal({ small: { passed: [2], failed: [4, 6, 8] }, mid: { passed: [2, 4], failed: [6, 8] }, top: { passed: [2, 4, 6], failed: [8] } });
  assert.equal(calibrationVerdict(grid), 'separates');
  // A top-model slip low down does not undo the separation (highest level passed decides).
  assert.equal(calibrationVerdict(cal({ small: { passed: [2], failed: [4] }, mid: { passed: [2, 4], failed: [6] }, top: { passed: [4, 6], failed: [2, 8] } })), 'separates');
  assert.equal(calibrationVerdict(cal({ small: { passed: [3, 5], failed: [] }, mid: { passed: [5, 7], failed: [] }, top: { passed: [7, 9], failed: [] } })), 'top-clears');
  assert.equal(calibrationVerdict(cal({ small: { passed: [2, 4, 6], failed: [8] }, mid: { passed: [2, 4, 6], failed: [8] }, top: { passed: [8], failed: [9, 10] } })), 'partly');
  assert.equal(calibrationVerdict(cal({ small: { passed: [2], failed: [4] }, top: { passed: [6], failed: [8] } })), 'partly', 'needs all three sizes to say "separates"');
  assert.equal(calibrationVerdict(cal({ top: { passed: [], failed: [], pending: [9] } })), 'partly', 'nothing passed yet is not "clears"');
  assert.equal(calibrationVerdict({ ...grid, verdict: 'partly' }), 'partly');
});

test('plain English: months, level lists, one line per size, pending levels and the verdict line', () => {
  assert.equal(calibrationMonth('2026-11'), 'Nov 2026');
  assert.equal(calibrationMonth('2027-01'), 'Jan 2027');
  assert.equal(levelsText([4]), 'L4');
  assert.equal(levelsText([8, 4, 6]), 'L4, L6 and L8');
  assert.equal(levelsText([9, 10]), 'L9–L10');
  assert.equal(levelsText([2, 4, 9, 10]), 'L2, L4 and L9–L10');
  const plans = cal({ small: { passed: [], failed: [2, 4, 6, 8] }, mid: { passed: [4], failed: [2, 6, 8] }, top: { passed: [2, 4, 6, 8], failed: [], pending: [9, 10] } });
  assert.equal(calibrationTierText(plans, 'small'), 'Small model (blind test, Nov 2026): failed L2, L4, L6 and L8');
  assert.equal(calibrationTierText(plans, 'top'), 'Top model (blind test, Nov 2026): passed L2, L4, L6 and L8; L9–L10 still being tested');
  assert.equal(calibrationAt(plans, 'top', 9), 'pending');
  assert.equal(calibrationAt(plans, 'mid', 4), 'passed');
  assert.equal(calibrationAt(plans, 'mid', 2), 'failed');
  assert.equal(calibrationAt(plans, 'mid', 3), null);
  assert.equal(calibrationPendingText(plans), 'L9–L10 still being tested for the top model');
  assert.equal(calibrationVerdictText(plans), 'Blind test, Nov 2026: today’s top models clear every level tested so far (up to L8); it separates small and mid models (L9–L10 still being tested for the top model)');
  const grid = cfg.tests.find((t) => t.id === 'reasoning.grid-ladder')!;
  assert.equal(calibrationVerdictText(grid.calibration!, grid.area), 'Blind test, Nov 2026: separates small, mid and top models');
  const maths = cfg.tests.find((t) => t.id === 'math.exact-ladder')!;
  assert.equal(calibrationVerdictText(maths.calibration!, maths.area), 'Blind test, Nov 2026: partly separates — small and mid models came out level; top models got further');
  const crm = cfg.tests.find((t) => t.id === 'work.crm-ladder')!;
  assert.equal(calibrationVerdictText(crm.calibration!, crm.area), 'Blind test, Nov 2026: today’s top models clear every level tested (up to L9) — this ladder measures reliability at scale and separates small models');
  const sql = cfg.tests.find((t) => t.id === 'work.sql-ladder')!;
  assert.equal(calibrationVerdictText(sql.calibration!, sql.area), 'Blind test, Nov 2026: today’s top models clear every level tested (up to L9) — this ladder measures reliability at scale; smaller models passed every level they were tested on too');
  // When the pending numbers arrive (one config edit: move the levels, delete "pending"), the line updates itself.
  const done = cal({ ...plans.tiers, top: { passed: [2, 4, 6, 8], failed: [9, 10] } });
  assert.equal(calibrationPendingText(done), null);
  assert.equal(calibrationVerdict(done), 'separates');
  // The final numbers are in the config: no ladder has pending levels left.
  for (const t of cfg.tests) assert.equal(t.calibration ? calibrationPendingText(t.calibration) : null, null, t.id);
  const plansCfg = cfg.tests.find((t) => t.id === 'reasoning.plans-ladder')!;
  assert.equal(calibrationVerdictText(plansCfg.calibration!, plansCfg.area), 'Blind test, Nov 2026: separates small, mid and top models');
  const eng = cfg.tests.find((t) => t.id === 'coding.engineering-ladder')!;
  assert.equal(calibrationVerdictText(eng.calibration!, eng.area), 'Blind test, Nov 2026: today’s mid and top models clear every level tested (up to L10); it separates small models only');
});

test('the blind test as climbers: where each size got to and where it fell, in one line', () => {
  const grid = cfg.tests.find((t) => t.id === 'reasoning.grid-ladder')!.calibration!;
  assert.deepEqual(calibrationClimb(grid, 'small'), { tier: 'small', best: 2, fell: 4, passed: [2], failed: [4, 6, 8], pending: [], clearedAll: false });
  assert.equal(calibrationClimbText(grid), 'Blind test, Nov 2026: small fell at L4, mid at L6, top at L8');
  assert.equal(calibrationSummitText(grid), null);
  // A miss below the best level (a copying slip) is drawn, but the climber falls at the first miss above it.
  const truth = cfg.tests.find((t) => t.id === 'reasoning.truth-ladder')!.calibration!;
  assert.equal(calibrationClimb(truth, 'top')!.fell, 8);
  assert.equal(calibrationClimb(truth, 'top')!.best, 6);
  // Nothing passed: on the ground, fell at the first level tried.
  const plans = cfg.tests.find((t) => t.id === 'reasoning.plans-ladder')!.calibration!;
  assert.deepEqual([calibrationClimb(plans, 'small')!.best, calibrationClimb(plans, 'small')!.fell], [0, 2]);
  // A Business ladder the top model cleared: "reached", and the summit flag.
  const inv = cfg.tests.find((t) => t.id === 'work.invoices-ladder')!.calibration!;
  assert.equal(calibrationClimbText(inv), 'Blind test, Nov 2026: small reached L5, mid L7, top L9');
  assert.equal(calibrationSummitText(inv), 'today’s top model reached L9 — every level tested');
  const crm = cfg.tests.find((t) => t.id === 'work.crm-ladder')!.calibration!;
  assert.equal(calibrationClimbText(crm), 'Blind test, Nov 2026: small fell at L5, mid reached L7, top L9');
  assert.equal(calibrationClimb(cal({ mid: { passed: [2] } as never }), 'top'), null);
  assert.match(calibrationClimbText(cal({ top: { passed: [], failed: [], pending: [9, 10] } })), /top not marked yet \(L9–L10 still being tested for the top model\)$/);
});

test('rung sizes: one growing number read from the labels, or no bar at all', () => {
  const sizes = (id: string) => rungSizes(cfg.tests.find((t) => t.id === id)!.rungs ?? []);
  assert.deepEqual(sizes('reasoning.grid-ladder'), { values: [10, 16, 23, 26, 29, 46, 52, 71, 104, 172], unit: 'clue' });
  assert.deepEqual(sizes('horizon.sliding-ladder')?.unit, 'move');
  assert.deepEqual(sizes('horizon.nonogram-ladder')?.values.slice(0, 2), [64, 100]);
  assert.deepEqual(sizes('horizon.mind-runner')?.values.at(-1), 73119);
  assert.deepEqual(sizes('work.crm-ladder')?.unit, 'record');
  // The labels do not carry one growing number: no bar rather than an invented one.
  assert.equal(sizes('work.invoices-ladder'), null);
  assert.equal(sizes('coding.engineering-ladder'), null);
  assert.equal(rungSizes(['3 steps', null, '9 steps']), null);
  assert.equal(rungSizes(['9 steps', '3 steps']), null);
});

test('public site: the climb is a static picture with every height and the wall', () => {
  const svg = climbSvg(
    [
      { label: 'Kite 2.5 Reasoner', color: '#199e70', height: 7 },
      { label: 'Atlas-4 Ultra', color: '#3987e5', height: 6 },
      { label: 'Random Baseline', color: '#7f8b9d', height: 0, baseline: true },
    ],
    10,
  );
  assert.match(svg, /aria-label="The climb: Kite 2.5 Reasoner reached level 7 of 10; Atlas-4 Ultra reached level 6 of 10"/);
  assert.match(svg, />K2\.5</);
  assert.equal((svg.match(/class="wall"/g) ?? []).length, 1);
  assert.match(svg, /Random guessing: level 0 of 10/);
  assert.match(miniStairsSvg(4.2), /aria-label="height 4.2 of 10"/);
  assert.match(climbBlock([{ label: 'Kite 2.5 Reasoner', color: '#199e70', height: 10 }]), /flag reached/);
});
