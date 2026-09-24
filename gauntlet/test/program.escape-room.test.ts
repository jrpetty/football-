import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRng } from '../src/core/rng.ts';
import { program } from '../src/programs/escape-room.ts';
import {
  ESCAPE_DEFAULTS,
  escapeActions,
  escapeObservation,
  generateEscape,
  newEscState,
  normAnswer,
  parseEscCommand,
  stepEscape,
  type EscapeConfig,
} from '../src/programs/lib/agentic-escape.ts';
import { constantResponder, createFakeModel, createTestContext, randomBacktickResponder, type Responder } from './helpers/fake-model.ts';
import { escapeResponder } from './helpers/agentic-policies.ts';

function testDef(file: string): { seeds: number[]; config: Record<string, unknown> } {
  return JSON.parse(readFileSync(new URL(`../tests/agentic/${file}.json`, import.meta.url), 'utf8'));
}
const STD = testDef('escape-room');
const HARD = testDef('escape-room-hard');
const SEEDS = STD.seeds;
const CFG: EscapeConfig = { ...ESCAPE_DEFAULTS, ...(STD.config as Partial<EscapeConfig>) };
const HARD_CFG: EscapeConfig = { ...ESCAPE_DEFAULTS, ...(HARD.config as Partial<EscapeConfig>) };

async function play(seed: number, responder: Responder, config: Record<string, unknown> = STD.config) {
  const model = createFakeModel(responder);
  const { ctx } = createTestContext({ seed, model, defaults: program.defaults, config });
  const result = await program.run(ctx);
  return { result, model };
}

/** A structural fingerprint of the puzzle chain: lock kinds, lock order, clue objects and answers. */
function fingerprint(seed: number): string {
  const w = generateEscape(createRng(seed), CFG);
  return JSON.stringify(w.locks.map((id) => [w.objects[id]!.name, w.objects[id]!.lock!.kind, w.objects[id]!.lock!.answer, w.objects[id]!.lock!.needs]));
}

describe('escape-room: determinism', () => {
  it('same seed and same play ⇒ identical prompts, replay and result', async () => {
    const a = await play(303, escapeResponder(303, CFG));
    const b = await play(303, escapeResponder(303, CFG));
    assert.equal(a.model.calls.length, b.model.calls.length);
    for (let i = 0; i < a.model.calls.length; i++) assert.deepEqual(a.model.calls[i]!.messages, b.model.calls[i]!.messages);
    assert.deepEqual(a.result, b.result);
  });

  it('different seeds ⇒ different puzzle chains, not just different numbers', () => {
    const prints = new Set<string>();
    const kindOrders = new Set<string>();
    for (let seed = 1; seed <= 30; seed++) {
      prints.add(fingerprint(seed));
      const w = generateEscape(createRng(seed), CFG);
      kindOrders.add(w.locks.map((id) => w.objects[id]!.lock!.kind).join(','));
    }
    assert.equal(prints.size, 30);
    assert.ok(kindOrders.size >= 20, `only ${kindOrders.size} distinct lock-kind chains in 30 seeds`);
  });

  it('every world is solvable and its plan is exactly the optimal move count', () => {
    for (let seed = 1; seed <= 150; seed++) {
      const w = generateEscape(createRng(seed), CFG);
      assert.equal(w.plan.length, w.optimal);
      assert.ok(w.optimal < CFG.moveBudget, `seed ${seed}: optimal ${w.optimal} exceeds the budget`);
      const s = newEscState(w);
      for (const cmd of w.plan) {
        const step = stepEscape(w, s, cmd);
        assert.equal(step.valid, true, `seed ${seed}: ${cmd} → ${step.outcome}`);
      }
      assert.equal(s.escaped, true, `seed ${seed} plan does not escape`);
      assert.equal(s.unlocked.length, w.locks.length, `seed ${seed}: every lock is on the critical path`);
    }
  });
});

describe('escape-room: scoring rewards competence', () => {
  it('the optimal player escapes at exactly the optimal move count and scores 1', async () => {
    for (const seed of SEEDS) {
      const { result } = await play(seed, escapeResponder(seed, CFG));
      assert.equal(result.score, 1);
      assert.equal(result.passed, true);
      assert.equal(result.detail.movesUsed, result.detail.optimalMoves);
      assert.equal(result.summary, `Escaped in ${result.detail.movesUsed} moves (optimal ${result.detail.optimalMoves})`);
    }
  });

  it('a slower solver who wastes moves still escapes but scores less', async () => {
    const seed = 202;
    const w = generateEscape(createRng(seed), CFG);
    let i = 0;
    // Ten wasted LOOKs first, then the optimal plan.
    const { result } = await play(seed, () => (i++ < 10 ? 'Let me look around again.\nACTION: LOOK' : `ACTION: ${w.plan[i - 11]}`));
    assert.equal(result.detail.escaped, true);
    assert.equal(result.detail.movesUsed, w.optimal + 10);
    assert.ok((result.score as number) < 1 && (result.score as number) > 0.9);
  });

  it('garbage output scores 0 without crashing', async () => {
    for (const seed of SEEDS) {
      const { result, model } = await play(seed, constantResponder('I need to think about this room carefully...\n\nMaybe the painting?'));
      assert.equal(result.score, 0);
      assert.equal(model.calls.length, CFG.moveBudget);
      assert.equal(result.detail.invalidActions, CFG.moveBudget);
    }
  });

  it('refusals and empty replies waste moves', async () => {
    const { result } = await play(101, () => ({ text: '', stopReason: 'refusal' }));
    assert.equal(result.score, 0);
    assert.equal(result.detail.invalidActions, CFG.moveBudget);
  });

  it('claiming to have escaped earns nothing — only world state counts', async () => {
    const { result } = await play(101, constantResponder('I opened every lock and walked out.\nACTION: I HAVE ESCAPED'));
    assert.equal(result.score, 0);
    assert.equal(result.detail.escaped, false);
  });

  it('a random-backticked-action player scores low', async () => {
    for (const seed of SEEDS) {
      const { result } = await play(seed, randomBacktickResponder(seed));
      assert.ok((result.score as number) <= 0.15, `${seed}: ${result.summary}`);
      assert.equal(result.detail.invalidActions, 0, 'every listed action must parse');
    }
  });

  it('API errors propagate', async () => {
    const model = createFakeModel(() => {
      throw new Error('socket hang up');
    });
    const { ctx } = createTestContext({ seed: 101, model, defaults: program.defaults });
    await assert.rejects(program.run(ctx), /socket hang up/);
  });
});

describe('escape-room: hard variant', () => {
  it('every hard world is solvable, has a genuine cross-room dependency and a budget of optimal + 25%', () => {
    for (let seed = 1; seed <= 80; seed++) {
      const w = generateEscape(createRng(seed), HARD_CFG);
      assert.equal(w.locks.length, 10);
      assert.equal(w.moveBudget, Math.ceil(w.optimal * 1.25), `seed ${seed}`);
      // A lock in the last room needs a clue that lives in the first room.
      const crossClue = w.locks.some(
        (id) => w.objects[id]!.rooms.includes(2) && w.objects[id]!.lock!.needs.some((n) => w.objects[n]!.kind !== 'door' && w.objects[n]!.rooms[0] === 0),
      );
      assert.ok(crossClue, `seed ${seed}: no room-1 clue needed in room 3`);
      // The plan picks something up in room 1 and uses it in room 3.
      const s = newEscState(w);
      for (const cmd of w.plan) assert.equal(stepEscape(w, s, cmd).valid, true, `seed ${seed}: ${cmd}`);
      assert.equal(s.escaped, true);
      assert.equal(s.unlocked.length, w.locks.length);
    }
  });

  it('hard ciphers take two steps: written backwards, with the shift given elsewhere', () => {
    let seen = 0;
    for (let seed = 1; seed <= 20; seed++) {
      const w = generateEscape(createRng(seed), HARD_CFG);
      for (const o of Object.values(w.objects)) if (/back to front/.test(o.desc)) seen++;
    }
    assert.ok(seen >= 20, `${seen}`);
  });

  it('the optimal player scores 1; a player 30% over optimal runs out of moves; garbage and random score ~0', async () => {
    for (const seed of HARD.seeds) {
      const w = generateEscape(createRng(seed), HARD_CFG);
      const { result } = await play(seed, escapeResponder(seed, HARD_CFG), HARD.config);
      assert.equal(result.score, 1, result.summary);
      assert.equal(result.detail.moveBudget, Math.ceil(w.optimal * 1.25));

      const waste = Math.ceil(w.optimal * 0.3);
      let i = 0;
      const slow = await play(seed, () => (i++ < waste ? 'ACTION: LOOK' : `ACTION: ${w.plan[i - waste - 1]}`), HARD.config);
      assert.equal(slow.result.detail.escaped, false);
      assert.ok((slow.result.score as number) <= 0.5, `${slow.result.score}`);

      const garbage = await play(seed, constantResponder('Hmm, let me think about the painting.'), HARD.config);
      assert.equal(garbage.result.score, 0);
      const random = await play(seed, randomBacktickResponder(seed), HARD.config);
      assert.ok((random.result.score as number) <= 0.1, random.result.summary);
    }
  });
});

describe('escape-room: rules', () => {
  it('parses the command language', () => {
    assert.deepEqual(parseEscCommand('USE BRASS KEY ON CABINET'), { kind: 'use', item: 'BRASS KEY', target: 'CABINET' });
    assert.deepEqual(parseEscCommand('USE MAGNET LINE ON DRAIN'), { kind: 'use', item: 'MAGNET LINE', target: 'DRAIN' });
    assert.deepEqual(parseEscCommand('ENTER RED BLUE GREEN ON DISPLAY CASE'), { kind: 'enter', value: 'RED BLUE GREEN', target: 'DISPLAY CASE' });
    assert.deepEqual(parseEscCommand('go oak door'), { kind: 'go', target: 'OAK DOOR' });
    assert.deepEqual(parseEscCommand('EXAMINE THE RUG'), { kind: 'examine', target: 'THE RUG' });
    assert.equal(parseEscCommand('DANCE'), null);
    assert.equal(normAnswer('code', '1,847'), '1847');
    assert.equal(normAnswer('colour', 'red, violet - Green'), 'RED PURPLE GREEN');
    assert.equal(normAnswer('word', 'an-chor'), 'ANCHOR');
  });

  it('wrong codes cost a move and are counted', () => {
    const w = generateEscape(createRng(101), CFG);
    const s = newEscState(w);
    const codeLock = w.locks.map((id) => w.objects[id]!).find((o) => o.lock!.kind === 'code' && o.rooms.includes(0));
    if (!codeLock) return;
    const wrong = codeLock.lock!.answer === '0000' ? '1111' : '0000';
    const step = stepEscape(w, s, `ENTER ${wrong} ON ${codeLock.name}`);
    assert.equal(s.moves, 1);
    assert.equal(s.wrongEntries, 1);
    assert.equal(step.tone, 'bad');
    assert.equal(s.unlocked.length, 0);
  });

  it('every listed action parses', () => {
    for (const seed of SEEDS) {
      const w = generateEscape(createRng(seed), CFG);
      const s = newEscState(w);
      for (const cmd of w.plan) {
        for (const a of escapeActions(w, s)) assert.ok(parseEscCommand(a), a);
        stepEscape(w, s, cmd);
      }
    }
  });

  it('room listings never reveal lock answers, and observations end with the output format', () => {
    for (let seed = 1; seed <= 40; seed++) {
      const w = generateEscape(createRng(seed), CFG);
      const s = newEscState(w);
      const obs = escapeObservation(w, s, 'Your note: (empty)', []);
      for (const id of w.locks) {
        const ans = w.objects[id]!.lock!.answer;
        if (ans && ans.length >= 3) assert.ok(!obs.includes(ans), `seed ${seed}: answer ${ans} leaked`);
      }
      assert.match(obs.split('\n').pop()!, /ACTION: <command>/);
    }
  });
});
