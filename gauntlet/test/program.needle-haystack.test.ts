import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRng } from '../src/core/rng.ts';
import {
  program,
  buildHaystack,
  buildPrompt,
  gradeAnswer,
  parseAnswers,
  readConfig,
} from '../src/programs/needle-haystack.ts';
import type { Haystack, PlantedNeedle } from '../src/programs/needle-haystack.ts';
import { containsPhrase, extractNumbers, normalizeText, wordsToDigits } from '../src/programs/lib/needle-haystack-normalize.ts';
import { nearMissNumber } from '../src/programs/lib/needle-haystack-needles.ts';
import type { NeedleSpec } from '../src/programs/lib/needle-haystack-needles.ts';
import {
  constantResponder,
  createFakeModel,
  createTestContext,
  mockBaselineResponder,
  randomBacktickResponder,
} from './helpers/fake-model.ts';
import type { Responder } from './helpers/fake-model.ts';

const LITE = { targetWords: 12_000 };

function haystackFor(seed: number, config: Record<string, unknown> = {}): Haystack {
  return buildHaystack(createRng(seed).fork('needle-haystack'), readConfig({ ...program.defaults, ...config }));
}

async function play(seed: number, responder: Responder, config: Record<string, unknown> = LITE) {
  const model = createFakeModel(responder);
  const { ctx, artifacts } = createTestContext({ seed, model, config, defaults: program.defaults });
  const result = await program.run(ctx);
  return { result, model, artifacts };
}

function oracle(h: Haystack, skip: (n: PlantedNeedle) => boolean = () => false): Responder {
  return () => h.needles.map((n) => `A${n.n}: ${skip(n) ? 'unknown' : n.expected}`).join('\n');
}

type Detail = { needles: Array<{ id: string; kind: string; depth: number; depths: number[]; correct: boolean; trap: string | null; hedged: boolean }> };

test('needle-haystack: determinism — same seed, same bytes and result; different seeds differ', async () => {
  const a = await play(101, constantResponder('A1: 42'));
  const b = await play(101, constantResponder('A1: 42'));
  assert.equal(a.model.calls[0]!.messages[0]!.content, b.model.calls[0]!.messages[0]!.content);
  assert.deepEqual(a.result, b.result);
  const c = await play(202, constantResponder('A1: 42'));
  assert.notEqual(a.model.calls[0]!.messages[0]!.content, c.model.calls[0]!.messages[0]!.content);
});

test('needle-haystack: default document is ~45k words (~60k tokens) and generates fast', () => {
  const t0 = performance.now();
  const h = haystackFor(303);
  const ms = performance.now() - t0;
  assert.ok(h.words >= 44_000 && h.words <= 46_500, `words ${h.words}`);
  const approxTokens = buildPrompt(h).length / 3.8;
  assert.ok(approxTokens < 72_000, `~${Math.round(approxTokens)} tokens`);
  assert.ok(ms < 2000, `generation took ${ms}ms`);
  assert.equal(h.needles.length, 10);
});

test('needle-haystack: oracle scores 1.0; missing one 2-hop gives the broadcast summary', async () => {
  for (const seed of [101, 202, 303]) {
    const h = haystackFor(seed, LITE);
    const { result, artifacts } = await play(seed, oracle(h));
    assert.equal(result.score, 1, JSON.stringify(result.detail));
    assert.equal(result.passed, true);
    assert.match(result.summary, /^10\/10 needles · perfect recall/);
    assert.equal(artifacts[0]!.name, 'chronicle.txt');
    assert.equal(artifacts[0]!.content, h.text);

    const hop = h.needles.find((n) => n.kind === 'multi-hop')!;
    const partial = await play(seed, oracle(h, (n) => n === hop));
    assert.equal(partial.result.score, 0.9);
    assert.equal(partial.result.summary, `9/10 needles · missed the 2-hop at ${Math.round(hop.depth)}% depth`);
  }
});

test('needle-haystack: garbage, empty, refusal and random-backtick policies score 0 without crashing', async () => {
  for (const r of [
    constantResponder('I refuse to read all that. The answer is 42.'),
    constantResponder(''),
    constantResponder('A1: x', 'refusal'),
    randomBacktickResponder(5),
  ]) {
    const { result } = await play(202, r);
    assert.equal(result.score, 0);
    assert.equal(result.passed, false);
  }
  const { result } = await play(202, constantResponder(''));
  assert.match(result.summary, /no answers given/);
});

test('needle-haystack: the real Random Baseline (all "unknown") scores 0', async () => {
  const { result } = await play(101, mockBaselineResponder());
  assert.equal(result.score, 0);
  assert.equal((result.detail as { answered: number }).answered, 10);
});

test('needle-haystack: model errors propagate', async () => {
  await assert.rejects(play(101, () => Promise.reject(new Error('rate limited'))), /rate limited/);
});

test('needle-haystack: needles are planted at spread depths and measured accurately', () => {
  for (const seed of [1, 2, 3, 101, 202, 303]) {
    const h = haystackFor(seed);
    const words = h.text.split(/\s+/).filter(Boolean);
    const anchors = h.needles.map((n) => n.depth);
    assert.ok(Math.min(...anchors) < 20 && Math.max(...anchors) > 80, `anchors ${anchors}`);
    for (const n of h.needles) {
      for (const [j, part] of n.parts.entries()) {
        // Planted exactly once, at the measured depth.
        const idx = h.text.indexOf(part);
        assert.ok(idx >= 0 && h.text.indexOf(part, idx + 1) === -1, `part planted once: ${part}`);
        const before = h.text.slice(0, idx).split(/\s+/).filter(Boolean).length;
        assert.ok(Math.abs((before / words.length) * 100 - n.depths[j]!) < 0.2, 'measured depth matches');
      }
      assert.equal(n.depth, Math.max(...n.depths));
      if (n.kind === 'multi-hop') assert.ok(Math.abs(n.depths[0]! - n.depths[1]!) >= 15, `2-hop halves far apart: ${n.depths}`);
      if (n.kind === 'superseded') assert.ok(n.depths[0]! < n.depths[1]!, 'original precedes correction');
      if (n.kind === 'aggregate') assert.equal(n.parts.length, 3);
      for (const d of n.distractors) assert.ok(h.text.includes(d), 'distractor planted');
    }
  }
});

test('needle-haystack: text answers occur only in their needle; distractor names only in distractors', () => {
  for (const seed of [11, 12, 13, 14, 15]) {
    const h = haystackFor(seed, LITE);
    const sentences = h.text.split(/(?<=[.!?"])\s+/).map((s) => normalizeText(s));
    for (const n of h.needles.filter((x) => x.numeric === null)) {
      const answerIn = sentences.filter((s) => containsPhrase(s, n.accept[0]!));
      const planted = [...n.parts].map((p) => normalizeText(p)).filter((p) => containsPhrase(p, n.accept[0]!));
      assert.equal(answerIn.length, planted.length, `seed ${seed}: "${n.accept[0]}" appears only in its needle`);
      for (const r of n.reject) {
        const hits = sentences.filter((s) => containsPhrase(s, r));
        assert.ok(hits.length >= 1 && hits.length <= n.distractors.length, `seed ${seed}: distractor "${r}"`);
      }
    }
  }
});

test('needle-haystack: every seed has 2 multi-hop, 1 aggregate, 1 superseded and 6 single needles', () => {
  const kinds = new Set<string>();
  for (let seed = 1; seed <= 30; seed++) {
    const h = haystackFor(seed, { targetWords: 4000 });
    const count = (k: string) => h.needles.filter((n) => n.kind === k).length;
    assert.deepEqual([count('single'), count('multi-hop'), count('aggregate'), count('superseded')], [6, 2, 1, 1]);
    h.needles.forEach((n) => kinds.add(n.topic));
  }
  assert.ok(kinds.size >= 16, `needle variety ${kinds.size}`);
});

test('needle-haystack: prompt ends with the answer format and never leaks the key after the document', () => {
  const h = haystackFor(101, LITE);
  const prompt = buildPrompt(h);
  assert.match(prompt, /Reply with exactly 10 lines and nothing else, one per question, in this form:\nA1: <answer>\n(?:A\d+: <answer>\n){8}A10: <answer>$/);
  assert.ok(prompt.includes('marked wrong'));
  const tail = prompt.slice(prompt.indexOf('</chronicle>'));
  for (const n of h.needles) assert.ok(!tail.includes(n.expected) || n.question.includes(n.expected), `key leaked: ${n.expected}`);
  assert.ok(!prompt.includes('`'), 'no backticks (random baseline falls back to filler)');
});

test('needle-haystack: detail exposes per-needle depth and a depth-bucket series', async () => {
  const h = haystackFor(303, LITE);
  const { result } = await play(303, oracle(h, (n) => n.depth > 50));
  const d = result.detail as unknown as Detail;
  assert.equal(d.needles.length, 10);
  for (const n of d.needles) {
    assert.equal(typeof n.depth, 'number');
    assert.equal(n.correct, n.depth <= 50);
  }
  const series = result.replay!.series![0]!;
  assert.equal(series.name, 'Accuracy by depth (%)');
  assert.ok(series.points.length >= 4);
  assert.ok(series.points.every((p) => p.y >= 0 && p.y <= 100 && [10, 30, 50, 70, 90].includes(p.x)));
  assert.equal(result.replay!.frames.length, 10);
});

test('needle-haystack: answer parsing handles tags, markdown and numbered fallbacks', () => {
  assert.deepEqual(parseAnswers('A1: Copper Lane\n**A2:** 317\nA10: heron', 10).slice(0, 3), ['Copper Lane', '317', null]);
  assert.equal(parseAnswers('A1: Copper Lane\nA10: heron', 10)[9], 'heron');
  assert.deepEqual(parseAnswers('1. Tallow Row\n2) 44', 2), ['Tallow Row', '44']);
  assert.deepEqual(parseAnswers('A1: draft\nfinal answers:\nA1: final', 1), ['final']);
});

const textNeedle: NeedleSpec = {
  kind: 'single',
  topic: 't',
  question: 'q',
  expected: 'Orsolya Venn',
  numeric: null,
  accept: ['Orsolya Venn', 'Venn'],
  reject: ['Fenn'],
  rejectNumbers: [],
  parts: [],
  distractors: [],
};
const numNeedle: NeedleSpec = { ...textNeedle, kind: 'superseded', expected: '67', numeric: 67, accept: [], reject: [], rejectNumbers: [61] };
const sumNeedle: NeedleSpec = { ...numNeedle, kind: 'aggregate', expected: '122', numeric: 122, rejectNumbers: [160] };

test('needle-haystack: grading normalises, rejects distractors and gives no credit for hedging', () => {
  assert.equal(gradeAnswer(textNeedle, 'Orsolya Venn').correct, true);
  assert.equal(gradeAnswer(textNeedle, '**venn.**').correct, true);
  assert.equal(gradeAnswer(textNeedle, 'Orsolya Fenn').correct, false);
  assert.equal(gradeAnswer(textNeedle, 'Orsolya Fenn').trap, 'distractor');
  assert.equal(gradeAnswer(textNeedle, 'Orsolya').correct, false);
  assert.equal(gradeAnswer(textNeedle, 'Venner').correct, false);
  assert.deepEqual(gradeAnswer(textNeedle, 'Orsolya Venn or Idris Holt'), { correct: false, trap: null, hedged: true });
  assert.equal(gradeAnswer(textNeedle, 'Venn / Fenn').hedged, true);
  assert.equal(gradeAnswer(textNeedle, 'unknown').correct, false);
  assert.equal(gradeAnswer(textNeedle, null).correct, false);

  assert.equal(gradeAnswer(numNeedle, '67').correct, true);
  assert.equal(gradeAnswer(numNeedle, 'sixty-seven cubits').correct, true);
  assert.deepEqual(gradeAnswer(numNeedle, '61'), { correct: false, trap: 'superseded', hedged: false });
  assert.deepEqual(gradeAnswer(numNeedle, '67 (previously 61)'), { correct: false, trap: 'superseded', hedged: true });
  assert.equal(gradeAnswer(numNeedle, '66 or 67').correct, false);
  assert.equal(gradeAnswer(sumNeedle, '37 + 41 + 44 = 122').correct, true);
  assert.equal(gradeAnswer(sumNeedle, 'one hundred and twenty-two').correct, true);
  assert.equal(gradeAnswer(sumNeedle, '1,22').correct, false);
});

test('needle-haystack: number normalisation helpers', () => {
  assert.equal(wordsToDigits('three hundred and seventeen arches'), '317 arches');
  assert.equal(normalizeText('the Twenty-First of March'), 'the 21 of march');
  assert.deepEqual(extractNumbers('4,812 households'), [4812]);
  assert.deepEqual(extractNumbers('a hundred and six'), [106]);
  assert.equal(containsPhrase(normalizeText("St. Ilse's great bell"), 'st ilse'), true);
  const rng = createRng(9);
  for (let i = 0; i < 200; i++) {
    const n = rng.int(10, 999);
    assert.notEqual(nearMissNumber(rng, n), n);
  }
});

test('needle-haystack: config clamps and a lite variant stays cheap', () => {
  assert.deepEqual(readConfig({}), { targetWords: 44_000, needles: 10 });
  assert.deepEqual(readConfig({ targetWords: 50, needles: 99 }), { targetWords: 3000, needles: 10 });
  const lite = haystackFor(7, { targetWords: 15_000, needles: 8 });
  assert.equal(lite.needles.length, 8);
  assert.ok(lite.words < 17_000);
});
