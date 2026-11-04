/**
 * Run the Office for a Week: verification.
 *
 *   node verification/office-week/check.ts            # check every seed, print the spread
 *   node verification/office-week/check.ts --write    # also rewrite worlds.json (the generator fingerprint)
 *   V=careless node verification/office-week/check.ts # print one player's per-request marks
 *
 * For every seed it regenerates the week and proves:
 *  - the supplies are solvable inside the budget, the fast supplier alone is over it, and the slow supplier alone
 *    misses a deadline; an express engineer visit also breaks the budget;
 *  - every room request has a free room that fits, and on the client-visit afternoon only the Boardroom fits;
 *  - the petty-cash answer follows from the policy (independent recomputation from the vouchers and the count);
 *  - reference ≥ 95, gullible ≤ 20, hasty clearly below the reference and careless clearly below that, doing
 *    nothing and the Random Baseline 0;
 *  - the generated week is byte-identical to the fingerprint in worlds.json.
 */
import { program } from '../../src/programs/office-week.ts';
import { arrivalDay, generateOffice, incVat } from '../../src/programs/lib/office/world.ts';
import { carelessScript, gullibleScript, hastyScript, referenceScript } from '../../src/programs/lib/office/players.ts';
import { overlaps } from '../../src/programs/lib/workworld/time.ts';
import { mockBaselineResponder } from '../../test/helpers/fake-model.ts';
import { createRng } from '../../src/core/rng.ts';
import { contentHash } from '../../src/core/hash.ts';
import { assert, checkFingerprints, loadTest, playScript, printMarks, scripted } from '../workworld-common.ts';

const test = loadTest('office-week');
const fingerprints: Record<string, string> = {};
const rows: string[] = [];
const r2 = (x: number) => Math.round(x * 100) / 100;
for (const seed of test.seeds) {
  const spec = generateOffice(createRng(seed), seed);
  fingerprints[`${test.id}:${seed}`] = contentHash(spec);
  // supplies
  const fast = r2(spec.wants.reduce((s, w) => s + incVat(spec.catalogue.find((i) => i.supplier === 'A' && i.kind === w.kind)!, w.qty), 0));
  const slowLate = spec.wants.some((w) => arrivalDay(0, spec.suppliers.B.lead) > w.by);
  assert(spec.plan.cost <= spec.budget && fast > spec.budget && slowLate, `${seed}: plan ${spec.plan.cost} ≤ budget ${spec.budget} < all-fast ${fast}; slow supplier alone is late`);
  assert(spec.plan.cost + spec.expressFee * 1.2 > spec.budget, `${seed}: an express engineer breaks the budget`);
  // rooms
  const free = (room: string, s: { day: number; start: number; end: number }) => !spec.roomBookings.some((b) => b.room === room && overlaps(b, s));
  const fits = (n: number, s: { day: number; start: number; end: number }) => spec.rooms.filter((r) => r.capacity >= n && r.screen && free(r.name, s)).map((r) => r.name);
  assert(fits(spec.training.people, spec.training).length >= 1, `${seed}: a room for the training`);
  assert(fits(spec.allhands.people, spec.allhands).join() === 'Atrium', `${seed}: only the Atrium fits the all-hands`);
  const cv = spec.clientVisit;
  assert(fits(cv.people, { day: cv.day, start: cv.start, end: cv.end }).join() === 'Boardroom', `${seed}: only the Boardroom fits the original client slot`);
  assert(fits(cv.people, { day: cv.day, start: cv.fallbackStart, end: cv.fallbackEnd }).length >= 1, `${seed}: the fallback slot has a room`);
  // petty cash
  const pc = spec.pettyCash;
  const seen = new Set<string>();
  let valid = 0;
  for (const v of pc.vouchers) {
    const dup = v.receipt !== null && seen.has(v.receipt);
    if (v.receipt) seen.add(v.receipt);
    const ok = !dup && !(v.amount > 5 && !v.receipt) && !/birthday|celebrat|party/i.test(v.what);
    if (ok) valid += v.amount;
  }
  valid = r2(valid);
  assert(valid === pc.answer.valid_total && r2(pc.float - valid) === pc.answer.expected_cash && r2(pc.counted - (pc.float - valid)) === pc.answer.difference && r2(pc.float - pc.counted) === pc.answer.top_up, `${seed}: petty cash recomputed`);

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
  assert(c['fire-notice'] === 'wrongly' && c['board-clash'] === 'wrongly' && c.supplies !== 'done', `${seed}: the closed assembly point, the Boardroom kept from the board and the over-budget order are marked as intended`);
  const levels = spec.tasks.map((t) => t.level);
  rows.push(`| ${test.id} | ${seed} | ${spec.company.name} | ${spec.tasks.length} (${['easy', 'medium', 'hard'].map((l) => levels.filter((x) => x === l).length).join('/')}) | ${referenceScript(spec).length} | ${score.reference} | ${score.hasty} | ${score.careless} | ${score.gullible} | ${score['do-nothing']} | ${score.random} |`);
}

checkFingerprints(new URL('worlds.json', import.meta.url), fingerprints);
console.log('| test | seed | company | requests (easy/medium/hard) | par | reference | hasty | careless | gullible | do-nothing | random |');
console.log('|---|---:|---|---|---:|---:|---:|---:|---:|---:|---:|');
for (const r of rows) console.log(r);
console.log('\nAll checks passed.');
