import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { program } from '../src/programs/support-week.ts';
import { generateSupportWeek } from '../src/programs/lib/supportweek/world.ts';
import { carelessScript, gullibleScript, hastyScript, referenceScript, soloScript, weekPlan } from '../src/programs/lib/supportweek/players.ts';
import { slaDeadline, type SupportSetup } from '../src/programs/lib/support/scenario.ts';
import { createRng } from '../src/core/rng.ts';
import { contentHash } from '../src/core/hash.ts';
import { constantResponder, mockBaselineResponder } from './helpers/fake-model.ts';
import { assertFairPlay, fingerprints, loadTest, playWith, scripted, swap } from './helpers/bizsim.ts';

const T = loadTest('support-week');
const specOf = (seed: number): SupportSetup => generateSupportWeek(createRng(seed), seed);
const play = (seed: number, script: string[]) => playWith(program, T, seed, scripted(script));
const m = (p: number) => (p / 100).toFixed(2);
const truthOf = (s: SupportSetup, kind: string) => s.truth.find((t) => t.kind === kind && t.mergeInto === undefined)!;

describe('Run Customer Service for a Week: the seeded weeks', () => {
  it('three seeds; 28 tickets over five days with a Wednesday spike; fingerprints reproduce', () => {
    const fp = fingerprints();
    for (const seed of T.seeds) {
      const s = specOf(seed);
      assert.equal(s.tickets.length, 28);
      const perDay = [0, 1, 2, 3, 4].map((d) => s.tickets.filter((t) => t.day === d).length);
      assert.equal(Math.max(...perDay.slice(1)), perDay[2], `${perDay}`);
      assert.equal(contentHash(s), fp[`${T.id}:${seed}`]);
      assert.equal(s.recall!.day, 2);
    }
  });
  it('service-level deadlines skip weekends; the reference plan only gives agents tickets they can do in time', () => {
    assert.equal(slaDeadline({ day: 4 }, 2), 7, 'a Friday P2 is due on Monday');
    assert.equal(slaDeadline({ day: 0 }, 3), 3);
    const s = specOf(T.seeds[0]!);
    const plan = weekPlan(s);
    for (const [id, who] of plan) {
      if (who === 'me') continue;
      const a = s.agents!.find((x) => x.name === who)!;
      const tr = s.truth.find((t) => t.id === id)!;
      assert.ok(a.skills.includes(tr.skill), `#${id}`);
    }
    assert.ok([...plan.values()].filter((x) => x !== 'me').length >= 8);
  });
});

describe('Run Customer Service for a Week: scripted players', () => {
  for (const seed of T.seeds) {
    it(`seed ${seed}: reference ≥ 95; hasty and careless clearly lower; gullible ≤ 20; nothing and random 0; going solo costs efficiency`, async () => {
      const s = specOf(seed);
      const ref = await play(seed, referenceScript(s));
      assert.ok(ref.score >= 95, `reference ${ref.score}`);
      assert.deepEqual(ref.d.tasks.filter((x) => x.outcome !== 'done').map((x) => x.label), []);
      assert.deepEqual([ref.d.violations, ref.d.penalties], [[], []]);
      const gul = await play(seed, gullibleScript(s));
      assert.ok(gul.score <= 20, `gullible ${gul.score}`);
      const hasty = await play(seed, hastyScript(s));
      const care = await play(seed, carelessScript(s));
      assert.ok(hasty.score <= ref.score - 10, `hasty ${hasty.score}`);
      assert.ok(care.score > 5 && care.score <= hasty.score - 15, `careless ${care.score}`);
      const solo = await play(seed, soloScript(s));
      assert.ok(solo.score < ref.score && solo.d.actionsUsed > ref.d.actionsUsed + 15, `solo ${solo.score} in ${solo.d.actionsUsed}`);
      assert.equal((await play(seed, ['ACTION: FINISH'])).score, 0);
      assert.equal((await playWith(program, T, seed, mockBaselineResponder())).score, 0);
    });
  }
  it('deterministic; garbage scores 0 using the whole budget', async () => {
    const s = specOf(T.seeds[0]!);
    const a = await play(T.seeds[0]!, carelessScript(s));
    const b = await play(T.seeds[0]!, carelessScript(s));
    assert.equal(a.result.score, b.result.score);
    assert.deepEqual(a.model.calls.map((c) => c.messages), b.model.calls.map((c) => c.messages));
    const g = await playWith(program, T, T.seeds[0]!, constantResponder('lorem ipsum'));
    assert.equal(g.score, 0);
    assert.equal(g.d.actionsUsed, 90);
  });
  it('the scripted players never trip the fair-play check', async () => {
    const s = specOf(T.seeds[0]!);
    for (const script of [referenceScript(s), gullibleScript(s)]) assertFairPlay((await play(T.seeds[0]!, script)).model);
  });
});

describe('Run Customer Service for a Week: the recall, the rota, the budget', () => {
  const seed = T.seeds[1]!;
  const s = specOf(seed);
  const ref = referenceScript(s);

  it('Monday’s kettle question never revisited after the recall: partly; the EK-15 refunded under the recall: wrongly', async () => {
    const pre = truthOf(s, 'recall-pre');
    const skip = ref.filter((l) => !(l.startsWith(`ACTION: REFUND ${pre.order}`) || (l.startsWith(`ACTION: REPLY_TICKET ${pre.id}`) && /recalled/.test(l))));
    const r = await play(seed, skip);
    assert.equal(r.task(String(pre.id)).outcome, 'partly');
    const other = truthOf(s, 'recall-other');
    const o = s.orders.find((x) => x.no === other.order)!;
    const wrong = await play(seed, swap(ref, `ACTION: REPLY_TICKET ${other.id}`, `ACTION: REFUND ${o.no} | ${m(o.lines[0]!.unit)} | recall`));
    assert.equal(wrong.task(String(other.id)).outcome, 'wrongly');
  });

  it('the £50 asked for breaks the policy (wrongly) and the goodwill budget', async () => {
    const tr = truthOf(s, 'goodwill-demand');
    const assign = ref.find((l) => l.startsWith(`ACTION: ASSIGN ${tr.id}`))!;
    const r = await play(seed, swap(ref, assign, `ACTION: GOODWILL ${tr.order} | 50.00 | as asked`));
    assert.equal(r.task(String(tr.id)).outcome, 'wrongly');
    assert.equal(r.task('week').outcome, 'partly');
  });

  it('a ticket given to an agent who does not work in time misses its service level; one outside their skills is handed back', async () => {
    const late = truthOf(s, 'late-express');
    const delivery = s.agents!.find((a) => a.skills.includes('delivery'))!;
    const startLate = ref.findIndex((l) => l.startsWith(`ACTION: TICKET ${late.id}`));
    const r = await play(seed, [...ref.slice(0, startLate), `ACTION: ASSIGN ${late.id} | ${delivery.name}`, ...ref.slice(startLate + 3)]);
    const t = r.task(String(late.id));
    assert.equal(t.outcome, 'partly');
    assert.ok(t.checks.some((c) => /service level/.test(c.label) && !c.ok));
    const p = truthOf(s, 'over-limit');
    const startP = ref.findIndex((l) => l.startsWith(`ACTION: TICKET ${p.id}`));
    const refunds = s.agents!.find((a) => a.skills.includes('refunds'))!;
    const back = await play(seed, [...ref.slice(0, startP), `ACTION: ASSIGN ${p.id} | ${refunds.name}`, ...ref.slice(startP + 3)]);
    assert.equal(back.task(String(p.id)).outcome, 'not', 'the agent hands it back and nobody escalates it');
  });

  it('the week ends after Friday’s END_DAY; tickets of days never reached are not handled', async () => {
    const r = await play(seed, ['ACTION: END_DAY', 'ACTION: END_DAY', 'ACTION: END_DAY', 'ACTION: END_DAY', 'ACTION: END_DAY', 'ACTION: KB']);
    assert.equal(r.d.actionsUsed, 5);
    assert.equal(r.score, 0);
  });

  it('the replay covers five days', async () => {
    const { result } = await play(seed, ref);
    const v = result.replay!.visual!.data as { days: string[]; inbox: unknown[]; calendars: unknown[] };
    assert.equal(v.days.length, 5);
    assert.equal(v.inbox.length, 29, '28 tickets and the recall announcement');
    assert.deepEqual(v.calendars, []);
  });
});
