import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createRng } from '../src/core/rng.ts';
import { program } from '../src/programs/survival-island.ts';
import {
  availableActions,
  generateIsland,
  idleNights,
  islandObservation,
  newIslandState,
  parseIslandCommand,
  stepIsland,
  type IslandConfig,
} from '../src/programs/lib/agentic-island.ts';
import { parseActionLine } from '../src/programs/lib/agentic-common.ts';
import { constantResponder, createFakeModel, createTestContext, randomBacktickResponder, type Responder } from './helpers/fake-model.ts';
import { islandResponder } from './helpers/agentic-policies.ts';

const SEEDS = [101, 202, 303];
const CFG = program.defaults as unknown as IslandConfig;

async function play(seed: number, responder: Responder) {
  const model = createFakeModel(responder);
  const { ctx } = createTestContext({ seed, model, defaults: program.defaults });
  const result = await program.run(ctx);
  return { result, model };
}

describe('survival-island: determinism', () => {
  it('same seed and same play ⇒ identical prompts, replay and result', async () => {
    const a = await play(202, islandResponder(202, CFG));
    const b = await play(202, islandResponder(202, CFG));
    assert.equal(a.model.calls.length, b.model.calls.length);
    for (let i = 0; i < a.model.calls.length; i++) {
      assert.equal(a.model.calls[i]!.system, b.model.calls[i]!.system);
      assert.deepEqual(a.model.calls[i]!.messages, b.model.calls[i]!.messages);
    }
    assert.deepEqual(a.result, b.result);
  });

  it('different seeds ⇒ different islands', () => {
    const maps = SEEDS.map((seed) => {
      const w = generateIsland(createRng(seed), CFG);
      return JSON.stringify([w.tiles.map((r) => r.map((t) => t.terrain)), w.start, w.shipDays, w.safeBerry]);
    });
    assert.equal(new Set(maps).size, SEEDS.length);
  });

  it('every generated island is well formed', () => {
    for (let seed = 1; seed <= 60; seed++) {
      const w = generateIsland(createRng(seed), CFG);
      const kinds = new Set(w.tiles.flat().map((t) => t.terrain));
      for (const k of ['sea', 'beach', 'forest', 'grass', 'rocks', 'summit', 'spring', 'cave', 'palm', 'bush']) assert.ok(kinds.has(k as never), `seed ${seed} lacks ${k}`);
      assert.notEqual(w.safeBerry, w.poisonBerry);
      assert.ok(w.shipDays.length >= 2, `seed ${seed} has too few ship passings`);
      assert.ok(w.shipDays[0]! <= 4);
      const idle = idleNights(w);
      assert.ok(idle >= 3 && idle <= 7, `idle baseline ${idle}`);
    }
  });
});

describe('survival-island: scoring rewards competence', () => {
  it('a full-knowledge survivor plays through the harness and scores high', async () => {
    const scores: number[] = [];
    let rescued = 0;
    for (const seed of SEEDS) {
      const { result, model } = await play(seed, islandResponder(seed, CFG));
      scores.push(result.score);
      if (result.detail.rescued) rescued++;
      assert.ok(result.score >= 0.7, `seed ${seed}: ${result.summary} (${result.score})`);
      assert.equal(result.passed, true);
      assert.equal(result.detail.invalidActions, 0);
      assert.ok(model.calls.length <= CFG.maxDays * 3);
      assert.match(result.summary, /Rescued by ship on day \d+|Survived all \d+ days/);
    }
    assert.ok(rescued >= 1, 'the rescue path must be reachable');
    assert.ok(Math.max(...scores) >= 0.9);
  });

  it('the survival-only strategy survives every test seed without rescue', async () => {
    for (const seed of SEEDS) {
      const { result } = await play(seed, islandResponder(seed, CFG, false));
      assert.equal(result.detail.alive, true, result.summary);
      assert.ok(result.score >= 0.7 && result.score < 0.9, `${result.score}`);
    }
  });

  it('garbage output scores ~0 without crashing and every turn counts as invalid', async () => {
    for (const seed of SEEDS) {
      const { result, model } = await play(seed, constantResponder('I think I should look around a bit first.'));
      assert.ok(result.score <= 0.05, `${result.score}`);
      assert.equal(result.detail.invalidActions, model.calls.length);
      assert.equal(result.passed, false);
      assert.match(result.summary, /^Day \d+: died of /);
    }
  });

  it('refusals and empty replies are wasted turns, not crashes', async () => {
    const { result: r1 } = await play(101, constantResponder('', 'end'));
    const { result: r2 } = await play(101, () => ({ text: 'I cannot help with that.', stopReason: 'refusal' }));
    for (const r of [r1, r2]) {
      assert.ok(r.score <= 0.05);
      assert.ok((r.detail.invalidActions as number) > 0);
    }
  });

  it('a random-backticked-action player scores low', async () => {
    for (const seed of SEEDS) {
      const { result } = await play(seed, randomBacktickResponder(seed));
      assert.ok(result.score <= 0.15, `${seed}: ${result.summary} ${result.score}`);
      assert.equal(result.detail.invalidActions, 0, 'every listed action must be a valid command');
    }
  });

  it('API errors propagate instead of being swallowed', async () => {
    const model = createFakeModel(() => {
      throw new Error('HTTP 500');
    });
    const { ctx } = createTestContext({ seed: 101, model, defaults: program.defaults });
    await assert.rejects(program.run(ctx), /HTTP 500/);
  });
});

describe('survival-island: rules', () => {
  it('parses commands leniently but exactly', () => {
    assert.deepEqual(parseIslandCommand('MOVE N'), { kind: 'move', dir: 'N', steps: 1 });
    assert.deepEqual(parseIslandCommand('move east 3'), { kind: 'move', dir: 'E', steps: 3 });
    assert.deepEqual(parseIslandCommand('EAT BLUE BERRIES'), { kind: 'eat', item: 'blueberry' });
    assert.deepEqual(parseIslandCommand('EAT COOKED FISH'), { kind: 'eat', item: 'cookedfish' });
    assert.deepEqual(parseIslandCommand('BUILD SIGNAL FIRE'), { kind: 'build', what: 'signal' });
    assert.equal(parseIslandCommand('MOVE N 4'), null);
    assert.equal(parseIslandCommand('FLY AWAY'), null);
  });

  it('the last ACTION line counts and prose is ignored', () => {
    assert.equal(parseActionLine('ACTION: MOVE N\nOn second thought...\nACTION: GATHER'), 'GATHER');
    // A self-report is not a command and earns nothing.
    assert.equal(parseIslandCommand(parseActionLine('I have been rescued!') ?? ''), null);
    assert.equal(parseActionLine('**ACTION:** `MOVE W 2`'), 'MOVE W 2');
  });

  it('every listed action is valid and accepted by the engine', () => {
    for (const seed of SEEDS) {
      const w = generateIsland(createRng(seed), CFG);
      const s = newIslandState(w);
      const rng = createRng(seed);
      for (let t = 0; t < 30 && !s.over; t++) {
        const acts = availableActions(w, s);
        for (const a of acts) assert.ok(parseIslandCommand(a), `unparseable listed action ${a}`);
        const step = stepIsland(w, s, rng.pick(acts));
        assert.equal(step.valid, true);
        assert.doesNotMatch(step.outcome, /Unrecognised|too exhausted|blocks the way/);
      }
    }
  });

  it('poisonous berries hurt; the spring fills water to 100', () => {
    const w = generateIsland(createRng(101), CFG);
    const s = newIslandState(w);
    s.inv[`${w.poisonBerry}berry`] = 2;
    const before = s.health;
    stepIsland(w, s, `EAT ${w.poisonBerry.toUpperCase()} BERRIES`);
    assert.ok(s.health <= before - 20);
    assert.equal(s.poisonEaten, 1);
    s.pos = { ...w.spring };
    s.water = 10;
    stepIsland(w, s, 'DRINK');
    assert.ok(s.water >= 90, 'drinking fills water (minus one turn of thirst)');
  });

  it('a lit signal fire on a ship day means rescue; otherwise the ship sails by', () => {
    const w = generateIsland(createRng(202), CFG);
    const shipDay = w.shipDays[0]!;
    const setup = () => {
      const s = newIslandState(w);
      s.day = shipDay;
      s.phase = 0;
      s.pos = { ...w.summit };
      s.signal = 'built';
      s.inv.fibre = 1;
      return s;
    };
    const lit = setup();
    stepIsland(w, lit, 'LIGHT SIGNAL');
    stepIsland(w, lit, 'REST');
    assert.equal(lit.rescued, true);
    assert.equal(lit.over, true);
    const unlit = setup();
    stepIsland(w, unlit, 'REST');
    const step = stepIsland(w, unlit, 'REST');
    assert.equal(unlit.rescued, false);
    assert.ok(step.events.some((e) => /ship/i.test(e)));
  });

  it('observations never reveal the ship schedule or the poisonous colour up front, and end with the output format', () => {
    for (const seed of SEEDS) {
      const w = generateIsland(createRng(seed), CFG);
      const s = newIslandState(w);
      const obs = islandObservation(w, s, 'Your note: (empty)', []);
      assert.doesNotMatch(obs, /every \d+ days/);
      assert.doesNotMatch(obs, /poison/i);
      assert.match(obs.split('\n').pop()!, /ACTION: <command>/);
    }
  });

  it('each turn is a fresh, bounded prompt (no growing history)', async () => {
    const { model } = await play(101, islandResponder(101, CFG));
    for (const c of model.calls) {
      const chars = (c.system?.length ?? 0) + c.messages.reduce((a, m) => a + m.content.length, 0);
      assert.ok(chars / 3.8 < 2500, `turn prompt ≈ ${Math.round(chars / 3.8)} tokens`);
      assert.equal(c.messages.length, 1, 'no growing chat history');
    }
  });
});
