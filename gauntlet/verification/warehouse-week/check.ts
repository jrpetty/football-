/**
 * Run the Warehouse for a Week: verification.
 *
 *   node verification/warehouse-week/check.ts            # check every seed, print the spread
 *   node verification/warehouse-week/check.ts --write    # also rewrite worlds.json (the generator fingerprint)
 *   V=careless node verification/warehouse-week/check.ts # print one player's per-request marks
 *
 * For every seed it regenerates the week and proves:
 *  - the reference plan ships every order on time (the generator simulates it and fails on any shortage), within the
 *    cash limit, and the reorder rule applied to the reference's real end state gives exactly the planned reorder;
 *  - each trap is live: the two tent orders cannot both go before the Thursday delivery, the delayed order cannot go
 *    without the backup supplier, the strike leaves only the dear courier on Wednesday and Thursday, the problem
 *    delivery's ordered quantity is not what arrived;
 *  - reference ≥ 95, gullible ≤ 20, hasty clearly below the reference and careless clearly below that, doing
 *    nothing and the Random Baseline 0;
 *  - the generated week is byte-identical to the fingerprint in worlds.json.
 */
import { program, warehouseScenario } from '../../src/programs/warehouse-week.ts';
import { generateWarehouse } from '../../src/programs/lib/warehouse/world.ts';
import { carelessScript, gullibleScript, hastyScript, referenceScript } from '../../src/programs/lib/warehouse/players.ts';
import { evaluateWarehouse, expectedReorder } from '../../src/programs/lib/warehouse/checks.ts';
import { runWorkWorld } from '../../src/programs/lib/workworld/engine.ts';
import { mockBaselineResponder, createFakeModel, createTestContext } from '../../test/helpers/fake-model.ts';
import { createRng } from '../../src/core/rng.ts';
import { contentHash } from '../../src/core/hash.ts';
import { assert, checkFingerprints, loadTest, playScript, printMarks, scripted } from '../workworld-common.ts';

const test = loadTest('warehouse-week');
const fingerprints: Record<string, string> = {};
const rows: string[] = [];
for (const seed of test.seeds) {
  const spec = generateWarehouse(createRng(seed), seed);
  fingerprints[`${test.id}:${seed}`] = contentHash(spec);
  const tents = spec.skus.find((s) => s.code === spec.roles.S)!.onHand;
  const need = (id: string, sku: string) => spec.orders.find((o) => o.id === id)!.lines.filter((l) => l.sku === sku).reduce((a, l) => a + l.qty, 0);
  assert(need(spec.o.allocEarly, 'TENT2') + need(spec.o.allocLate, 'TENT2') > tents && need(spec.o.allocLate, 'TENT2') <= tents, `${seed}: the tents trap is live`);
  assert(need(spec.o.delayed, 'STOVE') > spec.skus.find((s) => s.code === 'STOVE')!.onHand, `${seed}: the delayed order is short without the backup`);
  assert(spec.plan.poCost <= spec.cashLimit, `${seed}: the reference purchasing fits the cash limit`);
  assert(spec.plan.reorder.length >= 2, `${seed}: Friday's reorder has work in it`);
  // the reorder rule on the reference's real end state equals the generator's plan
  {
    const model = createFakeModel(scripted(referenceScript(spec)));
    const { ctx } = createTestContext({ seed, model, defaults: program.defaults, config: test.config });
    const sc = warehouseScenario(ctx);
    const run = await runWorkWorld(ctx, sc.spec);
    const exp = expectedReorder(sc.spec.world).map((x) => `${x.qty}×${x.sku}`).join(',');
    // the reorder PO is already placed at the end, so recompute as on Friday before it: it is excluded by day 4
    assert(exp === spec.plan.reorder.map((x) => `${x.qty}×${x.sku}`).join(','), `${seed}: rule on the end state ${exp} vs plan`);
    void evaluateWarehouse(sc.spec.world, run.actionsUsed, sc.par);
  }

  const score: Record<string, number> = {};
  const detail: Record<string, unknown> = {};
  for (const [name, r] of [
    ['reference', scripted(referenceScript(spec))],
    ['hasty', scripted(hastyScript(spec))],
    ['careless', scripted(carelessScript(spec))],
    ['gullible', scripted(gullibleScript(spec))],
    ['do-nothing', scripted(['ACTION: FINISH'])],
    ['random', mockBaselineResponder()],
  ] as const) {
    const { result } = await playScript(program, seed, test.config, r);
    score[name] = Math.round(result.score * 1000) / 10;
    detail[name] = result.detail;
    if (process.env.V === name) printMarks(`${test.id} ${seed} ${name}`, result.detail);
  }
  const tasksOf = (n: string) => (detail[n] as { tasks: Array<{ id: string; outcome: string }> }).tasks;
  assert(tasksOf('reference').every((x) => x.outcome === 'done'), `${seed}: reference completes every request (${JSON.stringify(tasksOf('reference').filter((x) => x.outcome !== 'done'))})`);
  assert(score.reference! >= 95, `${seed}: reference ${score.reference}`);
  assert(score.gullible! <= 20, `${seed}: gullible ${score.gullible}`);
  assert(score['do-nothing'] === 0 && score.random === 0, `${seed}: floor ${score['do-nothing']} / ${score.random}`);
  assert(score.careless! < score.hasty! - 15 && score.hasty! < score.reference! - 15, `${seed}: careless ${score.careless} < hasty ${score.hasty} < reference`);
  const c = Object.fromEntries(tasksOf('careless').map((x) => [x.id, x.outcome]));
  assert(c['receive-problem'] === 'wrongly' && c.allocation !== 'done' && c.strike !== 'done', `${seed}: booking in the ordered quantity is wrongly; the tents and the strike are missed`);
  const levels = spec.tasks.map((t) => t.level);
  rows.push(`| ${test.id} | ${seed} | ${spec.company.name} | ${spec.tasks.length} (${['easy', 'medium', 'hard'].map((l) => levels.filter((x) => x === l).length).join('/')}) | ${referenceScript(spec).length} | ${score.reference} | ${score.hasty} | ${score.careless} | ${score.gullible} | ${score['do-nothing']} | ${score.random} |`);
}

checkFingerprints(new URL('worlds.json', import.meta.url), fingerprints);
console.log('| test | seed | company | requests (easy/medium/hard) | par | reference | hasty | careless | gullible | do-nothing | random |');
console.log('|---|---:|---|---|---:|---:|---:|---:|---:|---:|---:|');
for (const r of rows) console.log(r);
console.log('\nAll checks passed.');
