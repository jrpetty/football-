/**
 * Difficulty ladders: the blind calibration in config/ladders.json (`calibration` per test) — the validator, the
 * verdict worked out from the tiers, and the plain-English lines the ladder view, the test pages, the Presenter and
 * the public site show.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { caseIds, loadTests } from '../src/core/registry.ts';
import { loadLadders } from '../src/core/ladders-file.ts';
import {
  calibrationAt,
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
