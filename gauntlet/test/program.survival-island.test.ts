import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRng } from '../src/core/rng.ts';
import { program } from '../src/programs/survival-island.ts';
import {
  ISLAND_DEFAULTS,
  availableActions,
  bottleMessage,
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

function testDef(file: string): { seeds: number[]; config: Record<string, unknown> } {
  return JSON.parse(readFileSync(new URL(`../tests/agentic/${file}.json`, import.meta.url), 'utf8'));
}
const STD = testDef('survival-island');
const HARD = testDef('survival-island-hard');
const CFG: IslandConfig = { ...ISLAND_DEFAULTS, ...(STD.config as Partial<IslandConfig>) };
const HARD_CFG: IslandConfig = { ...ISLAND_DEFAULTS, ...(HARD.config as Partial<IslandConfig>) };

async function play(seed: number, responder: Responder, config: Record<string, unknown> = STD.config) {
  const model = createFakeModel(responder);
  const { ctx } = createTestContext({ seed, model, defaults: program.defaults, config });
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
    const maps = STD.seeds.map((seed) => {
      const w = generateIsland(createRng(seed), CFG);
      return JSON.stringify([w.tiles.map((r) => r.map((t) => t.terrain)), w.start, w.shipDays, w.safeBerry]);
    });
    assert.equal(new Set(maps).size, STD.seeds.length);
  });

  it('every generated island is well formed, in both variants', () => {
    for (const [cfg, first] of [
      [CFG, CFG.shipFirstDay],
      [HARD_CFG, HARD_CFG.shipFirstDay],
    ] as const) {
      for (let seed = 1; seed <= 40; seed++) {
        const w = generateIsland(createRng(seed), cfg);
        const kinds = new Set(w.tiles.flat().map((t) => t.terrain));
        for (const k of ['sea', 'beach', 'forest', 'grass', 'rocks', 'summit', 'spring', 'cave', 'palm', 'bush']) assert.ok(kinds.has(k as never), `seed ${seed} lacks ${k}`);
        const bushes = w.tiles.flat().filter((t) => t.terrain === 'bush');
        assert.equal(bushes.length, cfg.berryBushes);
        assert.equal(bushes.filter((t) => t.berry === w.poisonBerry).length, cfg.poisonBushes);
        assert.ok(w.shipDays.length >= cfg.signalsNeeded, `seed ${seed} has too few ship passings`);
        assert.ok(w.shipDays[0]! >= first[0] && w.shipDays[0]! <= first[1]);
        // The bottle states the full schedule: the first day and the interval.
        assert.match(bottleMessage(w), new RegExp(`day ${w.shipFirst} of the month and then every ${w.shipPeriod} days`));
        const idle = idleNights(w);
        assert.ok(idle >= 3 && idle <= 7, `idle baseline ${idle}`);
      }
    }
  });
});

describe('survival-island: scoring rewards competence', () => {
  it('a full-knowledge player survives every test seed of both variants and scores high', async () => {
    for (const [def, cfg] of [
      [STD, CFG],
      [HARD, HARD_CFG],
    ] as const) {
      for (const seed of def.seeds) {
        const { result, model } = await play(seed, islandResponder(seed, cfg), def.config);
        assert.ok(result.score >= 0.7, `seed ${seed}: ${result.summary} (${result.score})`);
        assert.equal(result.passed, true);
        assert.equal(result.detail.invalidActions, 0);
        assert.ok(model.calls.length <= cfg.maxDays * 3);
        assert.match(result.summary, /Rescued by ship on day \d+|Survived all \d+ days/);
      }
    }
  });

  it('rescue is reachable by skill: signal at midday after a night with a campfire', async () => {
    // Seeds where the scripted player completes the full rescue chain (found by search; see the helper).
    for (const [seed, config, cfg] of [
      [1, STD.config, CFG],
      [4, STD.config, CFG],
      [25, HARD.config, HARD_CFG],
    ] as const) {
      const { result } = await play(seed, islandResponder(seed, cfg), config);
      assert.equal(result.detail.rescued, true, `seed ${seed}: ${result.summary}`);
      assert.ok(result.score >= 0.9, `${result.score}`);
    }
  });

  it('the survival-only strategy survives every test seed without rescue', async () => {
    for (const seed of STD.seeds) {
      const { result } = await play(seed, islandResponder(seed, CFG, false));
      assert.equal(result.detail.alive, true, result.summary);
      assert.ok(result.score >= 0.7 && result.score < 0.9, `${result.score}`);
    }
  });

  it('garbage output scores ~0 without crashing and every turn counts as invalid', async () => {
    for (const config of [STD.config, HARD.config]) {
      for (const seed of STD.seeds) {
        const { result, model } = await play(seed, constantResponder('I think I should look around a bit first.'), config);
        assert.ok(result.score <= 0.05, `${result.score}`);
        assert.equal(result.detail.invalidActions, model.calls.length);
        assert.equal(result.passed, false);
        assert.match(result.summary, /^Day \d+: died of /);
      }
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
    for (const config of [STD.config, HARD.config]) {
      for (const seed of STD.seeds) {
        const { result } = await play(seed, randomBacktickResponder(seed), config);
        assert.ok(result.score <= 0.15, `${seed}: ${result.summary} ${result.score}`);
        assert.equal(result.detail.invalidActions, 0, 'every listed action must be a valid command');
      }
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
    for (const cfg of [CFG, HARD_CFG]) {
      for (const seed of STD.seeds) {
        const w = generateIsland(createRng(seed), cfg);
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
    }
  });

  it('poisonous berries hurt (harder in the hard variant); the spring refills water', () => {
    for (const [cfg, dmg, drink] of [
      [CFG, 20, 100],
      [HARD_CFG, 30, 60],
    ] as const) {
      const w = generateIsland(createRng(101), cfg);
      const s = newIslandState(w);
      s.inv[`${w.poisonBerry}berry`] = 2;
      stepIsland(w, s, `EAT ${w.poisonBerry.toUpperCase()} BERRIES`);
      assert.equal(s.health, 100 - dmg);
      assert.equal(s.poisonEaten, 1);
      s.pos = { ...w.spring };
      s.water = 10;
      stepIsland(w, s, 'DRINK');
      assert.ok(s.water >= Math.min(100, 10 + drink) - 7, `water ${s.water}`);
    }
  });

  it('a signal counts only when it blazes at midday after a night with a campfire', () => {
    // Find a pass whose previous night is calm enough for a fire on the summit.
    let seed = 1;
    let w = generateIsland(createRng(seed), CFG);
    while (w.weather[w.shipDays[0]! - 1] === 'storm') w = generateIsland(createRng(++seed), CFG);
    const pass = w.shipDays[0]!;
    const setup = (withFire: boolean) => {
      const s = newIslandState(w);
      s.day = pass - 1;
      s.phase = 2;
      s.pos = { ...w.summit };
      s.signal = 'built';
      s.inv.fibre = 1;
      if (withFire) s.fires[`${w.summit.x},${w.summit.y}`] = 2;
      stepIsland(w, s, 'REST'); // evening; the night resolves
      return s;
    };
    const ok = setup(true);
    stepIsland(w, ok, 'LIGHT SIGNAL');
    const okPass = stepIsland(w, ok, 'REST');
    assert.equal(ok.rescued, true, okPass.events.join(' '));
    assert.equal(ok.over, true);

    const noFire = setup(false);
    stepIsland(w, noFire, 'LIGHT SIGNAL');
    const noFirePass = stepIsland(w, noFire, 'REST');
    assert.equal(noFire.rescued, false);
    assert.ok(noFirePass.events.some((e) => /no campfire burned/.test(e)));

    const unlit = setup(true);
    stepIsland(w, unlit, 'REST');
    const unlitPass = stepIsland(w, unlit, 'REST');
    assert.equal(unlit.rescued, false);
    // Every pass is reported, wherever the castaway stands.
    assert.ok(unlitPass.events.some((e) => /supply ship Albatross sails past/.test(e)));
  });

  it('a lit pile burns out after the next turn and must be rebuilt', () => {
    const w = generateIsland(createRng(202), CFG);
    const s = newIslandState(w);
    const notPass = [1, 2, 3].find((d) => !w.shipDays.includes(d) && w.weather[d] !== 'storm')!;
    s.day = notPass;
    s.phase = 0;
    s.pos = { ...w.summit };
    s.signal = 'built';
    s.inv.fibre = 1;
    stepIsland(w, s, 'LIGHT SIGNAL');
    assert.equal(s.signal, 'lit');
    stepIsland(w, s, 'REST');
    assert.equal(s.signal, 'none');
  });

  it('observations never reveal the ship schedule or the poisonous colour up front, and end with the output format', () => {
    for (const cfg of [CFG, HARD_CFG]) {
      for (const seed of STD.seeds) {
        const w = generateIsland(createRng(seed), cfg);
        const s = newIslandState(w);
        const obs = islandObservation(w, s, 'Your note: (empty)', []);
        assert.doesNotMatch(obs, /every \d+ days|of the month/);
        assert.doesNotMatch(obs, /poison/i);
        assert.match(obs.split('\n').pop()!, /ACTION: <command>/);
      }
    }
  });

  it('the rules define midday, pile burn-out, storms and proof of life; each turn is a fresh, bounded prompt', async () => {
    const { model } = await play(101, islandResponder(101, CFG));
    const sys = model.calls[0]!.system!;
    assert.match(sys, /Midday is the end of the Afternoon turn/);
    assert.match(sys, /then burns out and the pile is gone/);
    assert.match(sys, /Impossible during a storm \(rain is fine\)/);
    assert.match(sys, /campfire you built was burning somewhere on the island during the previous night/);
    for (const c of model.calls) {
      const chars = (c.system?.length ?? 0) + c.messages.reduce((a, m) => a + m.content.length, 0);
      assert.ok(chars / 3.8 < 2500, `turn prompt ≈ ${Math.round(chars / 3.8)} tokens`);
      assert.equal(c.messages.length, 1, 'no growing chat history');
    }
  });
});
