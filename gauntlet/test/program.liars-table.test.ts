import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRng } from '../src/core/rng.ts';
import { program, buildWorld, readConfig, buildMenu } from '../src/programs/liars-table.ts';
import {
  claimedCompanions,
  generateWorld,
  LIE_VARIANTS,
  SLOTS,
  viableCulprits,
} from '../src/programs/lib/liars-table-world.ts';
import type { LtWorld } from '../src/programs/lib/liars-table-world.ts';
import { checkReason, parseCommand, parseSlot, mentionsSlot } from '../src/programs/lib/liars-table-parse.ts';
import {
  constantResponder,
  createFakeModel,
  createTestContext,
  randomBacktickResponder,
} from './helpers/fake-model.ts';
import type { Responder } from './helpers/fake-model.ts';

function worldFor(seed: number, config: Record<string, unknown> = {}): LtWorld {
  return buildWorld({ rng: createRng(seed) }, readConfig({ ...program.defaults, ...config }));
}

async function play(seed: number, responder: Responder, config: Record<string, unknown> = {}) {
  const model = createFakeModel(responder);
  const { ctx } = createTestContext({ seed, model, config, defaults: program.defaults });
  const result = await program.run(ctx);
  return { result, model };
}

/** Plays with full knowledge of the ground truth: expose the lie in two questions, then accuse. */
function oracle(w: LtWorld): Responder {
  const t = SLOTS[w.theftSlot]!;
  const src = w.contradiction.sources[0]!;
  const evidence = ['CCTV', 'WITNESS', 'RECEIPT'].includes(src);
  const script = [
    `ACTION: ASK ${w.culprit} ABOUT ${t}`,
    evidence ? `ACTION: CHECK ${src}` : `ACTION: ASK ${src} ABOUT ${t}`,
    `The claim is contradicted.\nACTION: ACCUSE ${w.culprit} BECAUSE at ${t} ${w.culprit} claimed to be in the ${w.claimedRoom}, but ${evidence ? `the ${src}` : src} shows ${w.culprit} was not there.`,
  ];
  return (_s, _u, history) => script[Math.min(history.length / 2, script.length - 1)]!;
}

test('liars-table: every generated world has exactly one viable culprit (the thief)', () => {
  for (let seed = 1; seed <= 150; seed++) {
    for (const variant of LIE_VARIANTS) {
      for (const mistakenWitness of [true, false]) {
        const w = generateWorld(createRng(seed), { variant, mistakenWitness });
        assert.deepEqual(viableCulprits(w), [w.culprit], `seed ${seed} ${variant}`);
        assert.ok(w.contradiction.sources.length >= 1, `seed ${seed} ${variant} has a contradiction source`);
        assert.equal(w.truth[w.culprit]![w.theftSlot], w.objectRoom);
        assert.notEqual(w.claims[w.culprit]![w.theftSlot], w.objectRoom);
        // Innocents are never in the object room during the window.
        for (const s of w.suspects) {
          if (s.name !== w.culprit) assert.ok(!w.truth[s.name]!.includes(w.objectRoom));
        }
        if (mistakenWitness) {
          assert.ok(w.mistaken && w.mistaken.slot !== w.theftSlot && w.mistaken.name !== w.culprit);
          assert.equal(claimedCompanions(w, w.mistaken.name, w.mistaken.slot), null);
        }
      }
    }
  }
});

test('liars-table: the seeded variant distribution covers every lie type', () => {
  const seen = new Set<string>();
  for (let seed = 1; seed <= 60; seed++) seen.add(worldFor(seed).variant);
  assert.deepEqual([...seen].sort(), [...LIE_VARIANTS].sort());
});

test('liars-table: determinism — same seed gives identical prompts and results', async () => {
  const w = worldFor(101);
  const a = await play(101, oracle(w));
  const b = await play(101, oracle(w));
  assert.deepEqual(
    a.model.calls.map((c) => [c.system, c.messages]),
    b.model.calls.map((c) => [c.system, c.messages]),
  );
  assert.deepEqual(a.result, b.result);
  // Random policy with the same RNG seed also replays identically.
  const r1 = await play(202, randomBacktickResponder(7));
  const r2 = await play(202, randomBacktickResponder(7));
  assert.deepEqual(r1.model.calls.map((c) => c.messages), r2.model.calls.map((c) => c.messages));
  assert.deepEqual(r1.result, r2.result);
});

test('liars-table: different seeds produce different worlds', async () => {
  const systems = new Set<string>();
  for (const seed of [101, 202, 303, 404]) {
    const { model } = await play(seed, constantResponder('ACTION: ACCUSE Nobody'));
    systems.add(model.calls[0]!.system!);
  }
  assert.equal(systems.size, 4);
});

test('liars-table: oracle policy scores >= 0.9 on many seeds and variants', async () => {
  for (let seed = 1; seed <= 24; seed++) {
    const variant = LIE_VARIANTS[seed % 4]!;
    const w = worldFor(seed, { variant });
    const { result } = await play(seed, oracle(w), { variant });
    assert.ok(result.score >= 0.9, `seed ${seed} (${variant}) scored ${result.score}: ${JSON.stringify(result.detail)}`);
    assert.equal(result.passed, true);
    assert.match(result.summary, new RegExp(`^Caught ${w.culprit} after 2 questions$`));
    assert.equal((result.detail as { informed: boolean }).informed, true);
  }
});

test('liars-table: garbage output scores 0 without crashing and stays within the call cap', async () => {
  const { result, model } = await play(101, constantResponder('lorem ipsum dolor sit amet'));
  assert.equal(result.score, 0);
  assert.equal(result.passed, false);
  assert.match(result.summary, /Never accused anyone/);
  assert.equal(model.calls.length, 12 + 2);
  assert.equal((result.detail as { invalidActions: number }).invalidActions, 12);
});

test('liars-table: refusals and empty replies are failed steps', async () => {
  const { result } = await play(303, constantResponder('ACTION: ASK Ada ABOUT ALIBI', 'refusal'));
  assert.equal(result.score, 0);
  const empty = await play(303, constantResponder(''));
  assert.equal(empty.result.score, 0);
});

test('liars-table: model errors propagate', async () => {
  await assert.rejects(
    play(101, () => {
      throw new Error('API down');
    }),
    /API down/,
  );
});

test('liars-table: random-backtick policy scores low on average', async () => {
  let total = 0;
  const n = 30;
  for (let seed = 1; seed <= n; seed++) {
    const { result } = await play(seed, randomBacktickResponder(seed * 7919));
    total += result.score;
  }
  const mean = total / n;
  assert.ok(mean < 0.3, `random policy mean ${mean}`);
});

test('liars-table: a correct blind guess earns no efficiency credit; a wrong one scores 0', async () => {
  const w = worldFor(202);
  const guess = await play(202, constantResponder(`ACTION: ACCUSE ${w.culprit} BECAUSE a hunch`));
  assert.equal(guess.result.score, 0.6);
  assert.equal((guess.result.detail as { informed: boolean }).informed, false);
  const wrong = w.suspects.find((s) => s.name !== w.culprit)!.name;
  const bad = await play(202, constantResponder(`ACTION: ACCUSE ${wrong} BECAUSE a hunch`));
  assert.equal(bad.result.score, 0);
  assert.equal(bad.result.summary, `Accused the wrong person (${wrong})`);
});

test('liars-table: efficiency decays with questions used', async () => {
  const w = worldFor(101);
  const t = SLOTS[w.theftSlot]!;
  const src = w.contradiction.sources[0]!;
  const reveal = ['CCTV', 'WITNESS', 'RECEIPT'].includes(src) ? `CHECK ${src}` : `ASK ${src} ABOUT ${t}`;
  const filler = w.suspects.map((s) => `ASK ${s.name} ABOUT ALIBI`);
  const script = [...filler, `ASK ${w.culprit} ABOUT ${t}`, reveal, `ACCUSE ${w.culprit} BECAUSE lied`].map((c) => `ACTION: ${c}`);
  const { result } = await play(101, (_s, _u, h) => script[h.length / 2]!);
  const d = result.detail as { questionsUsed: number; efficiency: number };
  assert.equal(d.questionsUsed, 7);
  assert.equal(d.efficiency, Math.round(((12 - 7) / 9) * 1000) / 1000);
  assert.ok(result.score > 0.6 && result.score < 0.9);
});

test('liars-table: forced accusation after the budget is spent', async () => {
  const w = worldFor(101);
  const { result, model } = await play(101, (_s, userText) =>
    userText.includes('you must ACCUSE') ? `ACTION: ACCUSE ${w.culprit} BECAUSE of the timeline` : 'ACTION: CHECK DOOR LOG',
  );
  assert.equal(model.calls.length, 13);
  assert.equal(result.passed, true);
  const last = model.calls[12]!.messages.at(-1)!.content;
  assert.ok(!last.includes('`ASK '), 'forced turn only offers ACCUSE commands');
  assert.ok(last.includes('`ACCUSE '));
});

test('liars-table: every prompt lists complete backticked commands, minus ones already asked', () => {
  const w = worldFor(101);
  const menu = buildMenu(w, new Set([`ASK ${w.suspects[0]!.name} ABOUT ALIBI`]), false);
  const options = [...menu.matchAll(/`([^`]+)`/g)].map((m) => m[1]!);
  assert.equal(options.length, 5 * 9 + 4 + 5 - 1);
  for (const o of options.filter((x) => !x.startsWith('ACCUSE'))) {
    assert.notEqual(parseCommand(o, w).kind, 'invalid', o);
  }
});

test('liars-table: history carries earlier Q&A but only the latest turn carries the menu', async () => {
  const w = worldFor(303);
  const script = ['ACTION: CHECK DOOR LOG', 'ACTION: CHECK CCTV', `ACTION: ACCUSE ${w.culprit} BECAUSE x`];
  const { model } = await play(303, (_s, _u, h) => script[h.length / 2]!);
  const third = model.calls[2]!.messages;
  assert.equal(third.length, 5);
  assert.ok(third[2]!.content.includes('keypad log'));
  assert.ok(!third[0]!.content.includes('`ASK'));
  assert.ok(third[4]!.content.includes('`ASK'));
});

test('liars-table: command parser is lenient about formatting', () => {
  const w = worldFor(101);
  const [a, b] = w.suspects.map((s) => s.name) as [string, string];
  assert.deepEqual(parseCommand(`Let me think.\n**ACTION:** \`ASK ${a} ABOUT 9:00 pm\``, w), {
    kind: 'ask',
    suspect: a,
    topic: { kind: 'time', slot: 1 },
  });
  assert.deepEqual(parseCommand(`ASK ${a.toUpperCase()} ABOUT ${b.toLowerCase()}`, w), {
    kind: 'ask',
    suspect: a,
    topic: { kind: 'suspect', name: b },
  });
  assert.deepEqual(parseCommand(`ACTION: ASK ${a} ABOUT THE ${w.objectKey}`, w), { kind: 'ask', suspect: a, topic: { kind: 'object' } });
  assert.deepEqual(parseCommand(`ACTION: ASK ${a} ABOUT alibi.`, w), { kind: 'ask', suspect: a, topic: { kind: 'alibi' } });
  assert.deepEqual(parseCommand('ACTION: CHECK the CCTV footage', w), { kind: 'check', evidence: 'CCTV' });
  assert.deepEqual(parseCommand('ACTION: CHECK DOOR LOG', w), { kind: 'check', evidence: 'DOOR LOG' });
  const acc = parseCommand(`ACTION: ACCUSE ${b} BECAUSE she lied about 9:00\nThe CCTV shows the room empty.`, w);
  assert.equal(acc.kind, 'accuse');
  assert.ok(acc.kind === 'accuse' && acc.reason.includes('CCTV shows the room empty'));
  assert.equal(parseCommand('ACTION: ASK Zebedee ABOUT ALIBI', w).kind, 'invalid');
  assert.equal(parseCommand(`ACTION: ASK ${a} ABOUT 11:15`, w).kind, 'invalid');
  assert.equal(parseCommand('I have no idea.', w).kind, 'invalid');
  assert.equal(parseCommand('ACTION: DANCE', w).kind, 'invalid');
});

test('liars-table: time parsing and slot mentions', () => {
  assert.equal(parseSlot('8:30'), 0);
  assert.equal(parseSlot('9.00 PM'), 1);
  assert.equal(parseSlot('21:30'), 2);
  assert.equal(parseSlot('9pm'), 1);
  assert.equal(parseSlot('half past eight'), 0);
  assert.equal(parseSlot('10:00'), null);
  assert.ok(mentionsSlot('the door opened at 9:07 pm', 1));
  assert.ok(mentionsSlot('around half past nine', 2));
  assert.ok(!mentionsSlot('at 9:30', 1));
});

test('liars-table: reason check needs time, claimed room and a contradicting source', () => {
  const w = worldFor(303, { variant: 'cctv' });
  const t = SLOTS[w.theftSlot]!;
  const full = checkReason(`At ${t} they said they were in the ${w.claimedRoom} but the camera shows it empty`, w);
  assert.deepEqual(full, { time: true, place: true, source: true, score: 1 });
  const partial = checkReason(`They lied about the ${w.claimedRoom}`, w);
  assert.equal(partial.score, 1 / 3);
  assert.equal(checkReason('gut feeling', w).score, 0);
});
