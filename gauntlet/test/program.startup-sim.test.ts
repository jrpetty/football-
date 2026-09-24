import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createRng } from '../src/core/rng.ts';
import { parseDecisions, program, startupObservation, STARTUP_SYSTEM } from '../src/programs/startup-sim.ts';
import {
  autopilot,
  equityOf,
  generateMarket,
  newFirm,
  oracle,
  playMonth,
  simulate,
  type Decisions,
} from '../src/programs/lib/agentic-startup.ts';
import { constantResponder, createFakeModel, createTestContext, randomBacktickResponder, type Responder } from './helpers/fake-model.ts';
import { startupHeuristicResponder, startupOracleResponder } from './helpers/agentic-policies.ts';

const SEEDS = [101, 202, 303];

async function play(seed: number, responder: Responder) {
  const model = createFakeModel(responder);
  const { ctx } = createTestContext({ seed, model, defaults: program.defaults });
  const result = await program.run(ctx);
  return { result, model };
}

const PLAN: Decisions = { price: 30, produce: 400, marketing: 1000, hire: 0, loan: false };

describe('startup-sim: determinism', () => {
  it('same seed and same play ⇒ identical prompts, replay and result', async () => {
    const a = await play(101, startupHeuristicResponder(101));
    const b = await play(101, startupHeuristicResponder(101));
    assert.equal(a.model.calls.length, b.model.calls.length);
    for (let i = 0; i < a.model.calls.length; i++) assert.deepEqual(a.model.calls[i]!.messages, b.model.calls[i]!.messages);
    assert.deepEqual(a.result, b.result);
  });

  it('different seeds ⇒ different hidden markets and different first briefings', async () => {
    const markets = SEEDS.map((s) => JSON.stringify(generateMarket(createRng(s))));
    assert.equal(new Set(markets).size, SEEDS.length);
    const firsts = await Promise.all(SEEDS.map(async (s) => (await play(s, constantResponder('x'))).model.calls[0]!.messages[0]!.content));
    assert.equal(new Set(firsts).size, SEEDS.length);
  });

  it('every market leaves real room above the autopilot and the oracle never goes bankrupt', () => {
    for (let seed = 1; seed <= 25; seed++) {
      const m = generateMarket(createRng(seed));
      const ref = oracle(m);
      const auto = equityOf(m, simulate(m, autopilot));
      assert.equal(ref.firm.bankrupt, false, `seed ${seed}`);
      assert.ok(ref.equity - Math.max(0, auto) >= 20000, `seed ${seed}: oracle ${ref.equity} vs autopilot ${auto}`);
    }
  });
});

describe('startup-sim: scoring rewards competence', () => {
  it('replaying the oracle’s decisions through the harness scores 1', async () => {
    for (const seed of SEEDS) {
      const { result, model } = await play(seed, startupOracleResponder(seed));
      assert.equal(result.score, 1, result.summary);
      assert.equal(result.passed, true);
      assert.equal(model.calls.length, 12);
      assert.match(result.summary, /^Month 12: \$[\d,]+ equity \(oracle \$[\d,]+/);
      assert.equal(result.replay!.series!.length, 3);
      assert.equal(result.replay!.series![0]!.points.length, 13);
    }
  });

  it('an observation-only founder beats the autopilot on average and never goes bankrupt', async () => {
    let total = 0;
    for (const seed of SEEDS) {
      const { result } = await play(seed, startupHeuristicResponder(seed));
      assert.equal(result.detail.bankrupt, false);
      total += result.score;
    }
    assert.ok(total / SEEDS.length > 0.1, `mean ${total / SEEDS.length}`);
  });

  it('garbage output repeats the plan (the autopilot) and scores 0 without crashing', async () => {
    for (const seed of SEEDS) {
      const { result, model } = await play(seed, constantResponder('Let me think about the market dynamics here.'));
      assert.equal(result.score, 0);
      assert.equal(result.detail.invalidTurns, 12);
      assert.equal(model.calls.length, 12);
      assert.equal(result.detail.finalEquity, result.detail.autopilotEquity);
    }
  });

  it('refusals and empty replies count as invalid turns', async () => {
    const { result } = await play(202, () => ({ text: '', stopReason: 'refusal' }));
    assert.equal(result.score, 0);
    assert.equal(result.detail.invalidTurns, 12);
  });

  it('a random-backticked-decision player scores low', async () => {
    for (const seed of SEEDS) {
      const { result } = await play(seed, randomBacktickResponder(seed));
      assert.ok((result.score as number) <= 0.15, `${seed}: ${result.summary}`);
    }
  });

  it('API errors propagate', async () => {
    const model = createFakeModel(() => {
      throw new Error('rate limited');
    });
    const { ctx } = createTestContext({ seed: 101, model, defaults: program.defaults });
    await assert.rejects(program.run(ctx), /rate limited/);
  });
});

describe('startup-sim: rules', () => {
  it('parses decision blocks leniently; omitted lines carry over, HIRE resets, the last line wins', () => {
    const p = parseDecisions('PRICE: $24.99\nPRODUCE: 600 units\nMARKETING: $1,500\nHIRE: −1\nPRICE: 26.50', PLAN);
    assert.deepEqual(p.decisions, { price: 26.5, produce: 600, marketing: 1500, hire: -1, loan: false });
    assert.deepEqual(p.set.sort(), ['HIRE', 'MARKETING', 'PRICE', 'PRODUCE']);
    const partial = parseDecisions('I will only change the price.\nPRICE: 35', { ...PLAN, hire: 3 });
    assert.deepEqual(partial.decisions, { ...PLAN, price: 35, hire: 0 });
    const mockStyle = parseDecisions('I will try this.\nACTION: MARKETING: 3000', PLAN);
    assert.equal(mockStyle.decisions.marketing, 3000);
    const loan = parseDecisions('**LOAN:** ACCEPT\n**HIRE:** 2', PLAN);
    assert.equal(loan.decisions.loan, true);
    assert.equal(loan.decisions.hire, 2);
    const none = parseDecisions('We made a fortune this month!', PLAN);
    assert.equal(none.set.length, 0);
    const bad = parseDecisions('PRICE: cheap', PLAN);
    assert.equal(bad.decisions.price, PLAN.price);
    assert.equal(bad.problems.length, 1);
  });

  it('production is capped by staff capacity and cash; bankruptcy ends the firm', () => {
    const m = generateMarket(createRng(101));
    const f = newFirm(m);
    const r = playMonth(m, f, { price: 30, produce: 100000, marketing: 0, hire: 0, loan: false });
    assert.ok(r.produced <= m.staff0 * m.capacityPerStaff);
    assert.ok(r.adjustments.some((a) => /capacity|cash/.test(a)));
    const g = newFirm(m);
    g.cash = 500;
    playMonth(m, g, { price: 1, produce: 0, marketing: 0, hire: 5, loan: false });
    assert.equal(g.bankrupt, true);
  });

  it('a loan can only be taken in the month it is offered, and is repaid by month 12', () => {
    const m = generateMarket(createRng(202));
    const early = newFirm(m);
    playMonth(m, early, { ...m.opening, loan: true });
    assert.equal(early.loanTaken, m.loan.month === 1);
    const policy = (mk: typeof m, f: ReturnType<typeof newFirm>): Decisions => ({ ...mk.opening, loan: f.month === mk.loan.month });
    const f = simulate(m, policy);
    if (!f.bankrupt) {
      assert.equal(f.loanTaken, true);
      assert.ok(f.loanBalance < 1, `balance left ${f.loanBalance}`);
    }
  });

  it('observations never reveal hidden parameters or unannounced events, and end with the output format', async () => {
    for (const seed of SEEDS) {
      const { model } = await play(seed, startupHeuristicResponder(seed));
      const m = generateMarket(createRng(seed));
      for (const [i, c] of model.calls.entries()) {
        const obs = c.messages[0]!.content;
        assert.doesNotMatch(obs, /elasticity|oracle|autopilot/i);
        assert.ok(!obs.includes(`${m.baseDemand} `), 'base demand leaked');
        const month = i + 1;
        if (month < m.priceWar.start) assert.doesNotMatch(obs, /price war/i);
        if (month < m.supplierSpike.start - 1) assert.doesNotMatch(obs, /Supplier notice/);
        if (month < m.viral.month - 1) assert.doesNotMatch(obs, /reviewer/);
        assert.match(obs.split('\n').pop()!, /PRICE: <dollars>/);
        assert.equal(c.system, STARTUP_SYSTEM);
      }
    }
  });

  it('observation shows backticked example decision lines for the Random Baseline', () => {
    const m = generateMarket(createRng(303));
    const obs = startupObservation(m, newFirm(m), 'Your note: (empty)', null);
    const opts = [...obs.matchAll(/`([^`\n]+)`/g)].map((x) => x[1]!);
    assert.ok(opts.length >= 10);
    for (const o of opts) assert.match(o, /^(PRICE|PRODUCE|MARKETING|HIRE|LOAN): \S+$/);
  });
});
