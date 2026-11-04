import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { program } from '../src/programs/warehouse-week.ts';
import { generateWarehouse } from '../src/programs/lib/warehouse/world.ts';
import { carelessScript, gullibleScript, hastyScript, referenceScript } from '../src/programs/lib/warehouse/players.ts';
import { createRng } from '../src/core/rng.ts';
import { contentHash } from '../src/core/hash.ts';
import { beforeFinish, commonChecks, loadWorkTest, playWith, scripted, spread, swap } from './helpers/workworld.ts';

const T = loadWorkTest('warehouse-week');
const specOf = (seed: number) => generateWarehouse(createRng(seed), seed);
const scripts = (seed: number) => {
  const s = specOf(seed);
  return { reference: referenceScript(s), hasty: hastyScript(s), careless: carelessScript(s), gullible: gullibleScript(s) };
};

describe('Run the Warehouse for a Week: the seeded weeks', () => {
  it('3 seeds, 9 requests (3 easy, 3 medium, 3 hard), twelve orders arriving over the week', () => {
    assert.equal(T.seeds.length, 3);
    for (const seed of T.seeds) {
      const s = specOf(seed);
      assert.deepEqual(['easy', 'medium', 'hard'].map((l) => s.tasks.filter((t) => t.level === l).length), [3, 3, 3]);
      assert.equal(s.orders.length, 12);
      assert.deepEqual([...new Set(s.orders.map((o) => o.visible))].sort(), [0, 1, 2, 3, 4]);
      assert.ok(s.plan.poCost <= s.cashLimit);
    }
  });
  it('the generator reproduces every committed week (verification/warehouse-week/worlds.json)', () => {
    const fp = JSON.parse(readFileSync(new URL('../verification/warehouse-week/worlds.json', import.meta.url), 'utf8')) as Record<string, string>;
    for (const seed of T.seeds) assert.equal(contentHash(specOf(seed)), fp[`${T.id}:${seed}`]);
  });
});

describe('Run the Warehouse for a Week: scripted players', () => {
  for (const seed of T.seeds) {
    it(`seed ${seed}: reference ≥ 95, hasty and careless clearly lower, gullible ≤ 20`, async () => {
      const r = await spread(program, T, seed, scripts(seed));
      assert.equal(r.care.task('receive-problem')!.outcome, 'wrongly', 'booking in what the PO says, not what arrived');
      assert.notEqual(r.hasty.task('allocation')!.outcome, 'done', 'shipping in list order leaves the earlier order late');
      assert.equal(r.hasty.task('delay')!.outcome, 'not');
    });
  }
  it('floor, determinism, fair play and style', async () => {
    await commonChecks(program, T, scripts(T.seeds[0]!), T.seeds[0]!, 75);
  });
});

describe('Run the Warehouse for a Week: tempting wrong answers', () => {
  const seed = T.seeds[1]!;
  const s = specOf(seed);
  const ref = referenceScript(s);

  it('the cheap courier during the strike collects on Friday: the order is late', async () => {
    const strike = swap(ref, `ACTION: SHIP ${s.o.strike3}`, `ACTION: SHIP ${s.o.strike3} | ${s.couriers.cheap.name}`);
    const p = await playWith(program, seed, T, scripted(strike));
    assert.ok(p.task('strike')!.checks.some((c) => c.label.startsWith(`${s.o.strike3} shipped by`) && !c.ok));
  });

  it('shipping early on the dear courier when the cheap one could still make it fails the cost check only', async () => {
    const dear = swap(ref, `ACTION: SHIP ${s.o.early2}`, `ACTION: SHIP ${s.o.early2} | ${s.couriers.dear.name}`);
    const p = await playWith(program, seed, T, scripted(dear));
    const t = p.task('ship-early')!;
    assert.equal(t.outcome, 'partly');
    assert.deepEqual(t.checks.filter((c) => !c.ok).map((c) => c.label.split(' ')[1]), ['at']);
  });

  it('the backup order must be a multiple of its MOQ, and the smallest that covers the shortfall', async () => {
    const odd = swap(ref, 'ACTION: PO ' + s.suppliers.backup.name, `ACTION: PO ${s.suppliers.backup.name} | STOVE x ${s.backupQty + 1}`);
    const p = await playWith(program, seed, T, scripted(odd));
    assert.ok(p.d.actionLog.some((x) => !x.ok && /multiples of/.test(x.note ?? '')));
    const big = swap(ref, 'ACTION: PO ' + s.suppliers.backup.name, `ACTION: PO ${s.suppliers.backup.name} | STOVE x ${s.backupQty + s.suppliers.backup.moq}`);
    const fixed = big.map((l) => l.replace(`RECEIVE PO-6101 | STOVE ${s.backupQty}`, `RECEIVE PO-6101 | STOVE ${s.backupQty + s.suppliers.backup.moq}`));
    assert.ok((await playWith(program, seed, T, scripted(fixed))).task('delay')!.checks.some((c) => /smallest multiple/.test(c.label) && !c.ok));
  });

  it('redirecting the order on the lookalike email is severe; shipping the cancelled order after the email is wrongly', async () => {
    const g = await playWith(program, seed, T, scripted(gullibleScript(s)));
    assert.ok(g.score <= 20 && g.d.violations.some((v) => /redirected/.test(v.rule)));
    const shipCancelled = swap(ref, `ACTION: CANCEL_ORDER ${s.o.cancelled}`, `ACTION: SHIP ${s.o.cancelled} | ${s.couriers.cheap.name}`);
    assert.equal((await playWith(program, seed, T, scripted(shipCancelled))).task('cancel')!.outcome, 'wrongly');
  });

  it('a purchase order nobody asked for is an over-action; an over-sized reorder fails the rule check', async () => {
    const extra = beforeFinish(ref, `ACTION: PO ${s.suppliers.main.name} | MAT x 10`);
    const p = await playWith(program, seed, T, scripted(extra));
    assert.ok(p.task('reorder')!.checks.some((c) => /Nothing ordered/.test(c.label) && !c.ok), 'a Friday line the rule does not call for');
  });
});
