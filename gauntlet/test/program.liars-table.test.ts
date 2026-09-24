import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRng } from '../src/core/rng.ts';
import { program, buildWorld, readConfig, buildMenu } from '../src/programs/liars-table.ts';
import {
  candidateTheftSlots,
  claimedCompanions,
  evidenceText,
  generateWorld,
  LIE_VARIANTS,
  SLOTS,
  viableCulprits,
} from '../src/programs/lib/liars-table-world.ts';
import { examinerPolicy, oraclePolicy, sweepPolicy } from './helpers/liars-table-policies.ts';
import type { LtWorld } from '../src/programs/lib/liars-table-world.ts';
import { checkReason, parseCommand, parseSlot, mentionsSlot } from '../src/programs/lib/liars-table-parse.ts';
import {
  constantResponder,
  createFakeModel,
  createTestContext,
  mockBaselineResponder,
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
  return (_s, _u, _h, info) => script[Math.min(info.index, script.length - 1)]!;
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
    a.model.calls.map((c) => c.messages),
    b.model.calls.map((c) => c.messages),
  );
  assert.deepEqual(a.result, b.result);
  // Random policy with the same RNG seed also replays identically.
  const r1 = await play(202, randomBacktickResponder(7));
  const r2 = await play(202, randomBacktickResponder(7));
  assert.deepEqual(r1.model.calls.map((c) => c.messages), r2.model.calls.map((c) => c.messages));
  assert.deepEqual(r1.result, r2.result);
});

test('liars-table: different seeds produce different worlds', async () => {
  const prompts = new Set<string>();
  for (const seed of [101, 202, 303, 404]) {
    const { model } = await play(seed, constantResponder('ACTION: ACCUSE Nobody'));
    prompts.add(model.calls[0]!.messages[0]!.content);
  }
  assert.equal(prompts.size, 4);
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

test('liars-table: the real Random Baseline (mock provider behaviour) scores low', async () => {
  let total = 0;
  let accused = 0;
  const n = 30;
  for (let seed = 1; seed <= n; seed++) {
    const { result } = await play(seed, mockBaselineResponder(`baseline-${seed}`));
    total += result.score;
    if ((result.detail as { accused: string | null }).accused) accused++;
  }
  assert.ok(total / n < 0.3, `baseline mean ${total / n}`);
  assert.equal(accused, n, 'the baseline can always play to an accusation');
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
  const { result } = await play(101, (_s, _u, _h, info) => script[info.index]!);
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
  const last = model.calls[12]!.messages[0]!.content;
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

test('liars-table: every call is one self-contained message with the full log and a format reminder', async () => {
  const w = worldFor(303);
  const script = ['ACTION: CHECK DOOR LOG', 'ACTION: CHECK CCTV', `ACTION: ACCUSE ${w.culprit} BECAUSE x`];
  const { model } = await play(303, (_s, _u, _h, info) => script[info.index]!);
  for (const call of model.calls) {
    assert.equal(call.system, undefined);
    assert.equal(call.messages.length, 1);
    assert.equal(call.messages[0]!.role, 'user');
    assert.match(call.messages[0]!.content, /end with exactly one line:\nACTION: <command>\nExactly one command per reply\. If you write more than one ACCUSE, only the first counts\.$/);
  }
  const third = model.calls[2]!.messages[0]!.content;
  assert.ok(third.includes('Q1 · CHECK DOOR LOG') && third.includes('keypad log'));
  assert.ok(third.includes('Q2 · CHECK CCTV'));
  assert.ok(third.includes('Questions left: 10 of 12.'));
  assert.ok(!third.includes('`CHECK DOOR LOG`'), 'already-asked commands leave the menu');
  // The case file never reveals the answer key.
  assert.ok(!third.includes('thief is') && !new RegExp(`${w.culprit} (is|was) the thief`).test(third));
});

test('liars-table: only the first ACCUSE in a reply counts', () => {
  const w = worldFor(101);
  const innocent = w.suspects.find((s) => s.name !== w.culprit)!.name;
  const hedge = parseCommand(`ACTION: ACCUSE ${innocent} BECAUSE maybe\nACTION: ACCUSE ${w.culprit} BECAUSE or maybe them`, w);
  assert.deepEqual(hedge, { kind: 'accuse', suspect: innocent, reason: 'maybe' });
  const mixed = parseCommand(`ACTION: CHECK CCTV\nACTION: ACCUSE ${innocent} BECAUSE x`, w);
  assert.equal(mixed.kind, 'accuse');
  const lastAsk = parseCommand('ACTION: CHECK CCTV\nOn reflection:\nACTION: CHECK DOOR LOG', w);
  assert.deepEqual(lastAsk, { kind: 'check', evidence: 'DOOR LOG' });
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

// ─── hard tier ──────────────────────────────────────────────────────────────

const HARD = {
  questionBudget: 8,
  suspects: 7,
  slots: 4,
  mistakenWitnesses: 2,
  doorLogGap: true,
  variant: 'auto',
  variants: ['companion', 'receipt'],
};

async function mean(seeds: number[], policy: (w: LtWorld) => Responder, config: Record<string, unknown>) {
  let total = 0;
  for (const seed of seeds) total += (await play(seed, policy(worldFor(seed, config)), config)).result.score;
  return total / seeds.length;
}

test('liars-table: standard worlds are unchanged by the hard-tier options (golden check)', () => {
  const golden: Array<[number, string, string, number, string]> = [
    [101, 'Gideon', 'cctv', 0, 'Orangery'],
    [202, 'Felicity', 'cctv', 1, 'Conservatory'],
    [303, 'Marguerite', 'companion', 1, 'Drawing Room'],
  ];
  for (const [seed, culprit, variant, slot, room] of golden) {
    const w = worldFor(seed);
    assert.deepEqual([w.culprit, w.variant, w.theftSlot, w.claimedRoom, w.suspects.length, w.slotCount, w.doorGap], [culprit, variant, slot, room, 5, 3, null]);
  }
});

test('liars-table hard: every world has one viable thief, a door-log gap and a decoy in the other candidate slot', () => {
  for (let seed = 1; seed <= 150; seed++) {
    for (const variant of LIE_VARIANTS) {
      const w = worldFor(seed, { ...HARD, variant });
      assert.deepEqual(viableCulprits(w), [w.culprit], `seed ${seed} ${variant}`);
      assert.equal(w.suspects.length, 7);
      assert.equal(w.slotCount, 4);
      const cands = candidateTheftSlots(w);
      assert.equal(cands.length, 2);
      assert.ok(cands.includes(w.theftSlot));
      assert.equal(w.mistakes.length, 2);
      const decoy = w.mistakes[0]!;
      assert.deepEqual(cands.filter((c) => c !== w.theftSlot), [decoy.slot], 'decoy sits in the other candidate slot');
      assert.equal(claimedCompanions(w, decoy.name, decoy.slot), null, 'decoy is hedged');
      assert.ok(w.cctvGap.slot === cands[0] || w.cctvGap.slot === cands[1]);
      const log = evidenceText(w, 'DOOR LOG');
      assert.match(log, /LOG OFFLINE from .* pm to .* pm/);
      assert.ok(!/pm opened · [\d:]+ pm closed/.test(log), 'the exact opening time is not revealed');
    }
  }
});

test('liars-table hard: the hard pool only uses cross-examination lies', () => {
  const seen = new Set<string>();
  for (let seed = 1; seed <= 40; seed++) seen.add(worldFor(seed, HARD).variant);
  assert.deepEqual([...seen].sort(), ['companion', 'receipt']);
});

test('liars-table hard: prompt shows 7 suspects, 4 slots, the offline log after CHECK, and an 8-question budget', async () => {
  const w = worldFor(101, HARD);
  const { model } = await play(101, (_s, _u, _h, info) => (info.index === 0 ? 'ACTION: CHECK DOOR LOG' : `ACTION: ACCUSE ${w.culprit} BECAUSE x`), HARD);
  const first = model.calls[0]!.messages[0]!.content;
  assert.ok(first.includes('four half-hour slots: 8:30, 9:00, 9:30 and 10:00 pm'));
  assert.ok(first.includes('At 10:30 pm'));
  assert.ok(first.includes('Questions left: 8 of 8.'));
  assert.equal([...first.matchAll(/`ACCUSE (\w+) BECAUSE/g)].length, 7);
  assert.ok(first.includes(`\`ASK ${w.suspects[0]!.name} ABOUT 10:00\``));
  assert.match(first, /ACTION: <command>\nExactly one command per reply\. If you write more than one ACCUSE, only the first counts\.$/);
  assert.ok(model.calls[1]!.messages[0]!.content.includes('LOG OFFLINE'));
});

test('liars-table hard: 10:00 is a valid slot only in four-slot worlds', () => {
  const hard = worldFor(101, HARD);
  const std = worldFor(101);
  const name = hard.suspects[0]!.name;
  assert.deepEqual(parseCommand(`ACTION: ASK ${name} ABOUT 10:00 pm`, hard), { kind: 'ask', suspect: name, topic: { kind: 'time', slot: 3 } });
  assert.equal(parseCommand(`ACTION: ASK ${std.suspects[0]!.name} ABOUT 10:00`, std).kind, 'invalid');
  assert.equal(parseSlot('ten o\'clock', 4), 3);
  assert.equal(parseSlot('ten o\'clock', 3), null);
  assert.ok(mentionsSlot('at about 10:05 pm', 3));
});

test('liars-table hard: oracle ≥ 0.9; scripted detectives score far lower than on the standard tier', async () => {
  const seeds = Array.from({ length: 40 }, (_, i) => i + 1);
  for (const seed of seeds.slice(0, 12)) {
    const { result } = await play(seed, oraclePolicy(worldFor(seed, HARD)), HARD);
    assert.ok(result.score >= 0.9, `seed ${seed}: ${result.score}`);
  }
  const stdExaminer = await mean(seeds, (w) => examinerPolicy(w, 12), {});
  const hardExaminer = await mean(seeds, (w) => examinerPolicy(w, 8), HARD);
  const hardSweep = await mean(seeds, (w) => sweepPolicy(w, 8), HARD);
  assert.ok(stdExaminer >= 0.85, `standard examiner ${stdExaminer}`);
  assert.ok(hardExaminer >= 0.3 && hardExaminer <= 0.7, `hard examiner ${hardExaminer}`);
  assert.ok(hardSweep < hardExaminer, `rigid sweep ${hardSweep} vs adaptive ${hardExaminer}`);
});

test('liars-table hard: garbage and the Random Baseline stay near zero', async () => {
  const garbage = await play(404, constantResponder('no idea'), HARD);
  assert.equal(garbage.result.score, 0);
  assert.equal(garbage.model.calls.length, 8 + 2);
  let total = 0;
  for (let seed = 1; seed <= 30; seed++) total += (await play(seed, mockBaselineResponder(`h${seed}`), HARD)).result.score;
  assert.ok(total / 30 < 0.2, `baseline ${total / 30}`);
});
