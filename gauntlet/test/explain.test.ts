import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadTests, computeTestHash } from '../src/core/registry.ts';
import { EXPLAINERS, EXPLAIN_ICONS, arenaExplainerId, explainerFor, explainerForDefinition } from '../src/core/explainers.ts';
import { answerForViewer, buildArenaSample, buildSample, collapseRepeats, trimForViewer, viewerExcerpt } from '../src/core/test-sample.ts';
import { PROGRAMS } from '../src/programs/index.ts';
import { GAMES } from '../src/arena/games/index.ts';
import { testBaseDir } from '../src/core/vision.ts';
import type { PromptTest, TestDefinition } from '../src/core/types.ts';

/**
 * Plain-English explainers ("What this test is") and the viewer sample question.
 *
 * The first test is the guard rail: every built-in test and every Arena game must
 * have a hand-written explainer in src/core/explainers.ts, so a new test can't
 * reach a video without one.
 */

const builtin = loadTests().filter((t) => t.source === 'builtin');
const ICONS = new Set<string>(EXPLAIN_ICONS);

test('every built-in test and every Arena game has a hand-written explainer (add one to src/core/explainers.ts)', () => {
  const missing = builtin.map((t) => t.definition.id).filter((id) => !explainerFor(id));
  const missingGames = Object.keys(GAMES)
    .map(arenaExplainerId)
    .filter((id) => !explainerFor(id));
  assert.deepEqual([...missing, ...missingGames], [], 'Write an explainer for each of these (see docs/ADDING_TESTS.md → "Write an explainer")');
});

test('no explainer points at a test or game that does not exist', () => {
  const known = new Set([...loadTests().map((t) => t.definition.id), ...Object.keys(GAMES).map(arenaExplainerId)]);
  assert.deepEqual(
    Object.keys(EXPLAINERS).filter((id) => !known.has(id)),
    [],
  );
});

test('every explainer is complete, short enough for a slide and uses known icons', () => {
  for (const [id, x] of Object.entries(EXPLAINERS)) {
    assert.ok(x.hook.trim() && x.hook.length <= 100, `${id}: hook must be one line (≤ 100 chars), got ${x.hook.length}`);
    assert.ok(!/\n/.test(x.hook), `${id}: hook is one line`);
    assert.ok(x.whatItTests.trim().endsWith('.'), `${id}: whatItTests is one sentence`);
    assert.ok(x.whyHard.trim().endsWith('.'), `${id}: whyHard is one sentence`);
    assert.ok(x.goodScore.trim(), `${id}: goodScore`);
    assert.ok(x.howScored.length >= 2 && x.howScored.length <= 4, `${id}: 2–4 howScored steps`);
    for (const s of x.howScored) {
      assert.ok(s.text.length <= 64, `${id}: step "${s.text}" is too long for the slide strip (${s.text.length} > 64)`);
      assert.ok(ICONS.has(s.icon), `${id}: unknown step icon ${s.icon}`);
    }
    assert.ok(ICONS.has(x.icon), `${id}: unknown icon ${x.icon}`);
    assert.ok(!x.generated, `${id}: hand-written explainers are not "generated"`);
  }
});

test('programs and Arena games describe their opening situation', () => {
  for (const t of builtin) if (t.definition.kind === 'program') assert.ok(explainerFor(t.definition.id)?.opening, `${t.definition.id}: opening`);
  for (const g of Object.keys(GAMES)) assert.ok(explainerFor(arenaExplainerId(g))?.opening, `arena.${g}: opening`);
});

test('explainers are display-only: test hashes do not depend on them', () => {
  for (const t of builtin) assert.equal(computeTestHash(t.definition, testBaseDir(t.file)), t.hash, t.definition.id);
});

test('a test without a hand-written explainer gets a plain one built from its own definition', () => {
  const def: TestDefinition = {
    kind: 'prompt',
    id: 'custom.my-quiz',
    version: '1.0.0',
    name: 'My quiz',
    category: 'reasoning',
    description: 'Three riddles about boats. Written for my channel.',
    difficulty: 'easy',
    scorer: { type: 'constraints', allOrNothing: true },
    cases: [{ id: 'c1', prompt: 'Hi', expected: [{ check: 'word_count', max: 5 }] }],
  };
  const x = explainerForDefinition(def);
  assert.equal(x.generated, true);
  assert.equal(x.hook, 'My quiz');
  assert.equal(x.whatItTests, 'Three riddles about boats.');
  assert.deepEqual(
    x.howScored.map((s) => s.icon),
    ['rules', 'zero'],
  );
  // A built-in test keeps its hand-written explainer.
  const needle = builtin.find((t) => t.definition.id === 'long-context.needle-haystack')!.definition;
  assert.equal(explainerForDefinition(needle), EXPLAINERS['long-context.needle-haystack']);
});

test('trimForViewer cuts long text at a boundary and marks it', () => {
  assert.deepEqual(trimForViewer('Short question?'), { text: 'Short question?', truncated: false });
  const long = `${'First paragraph with some words. '.repeat(12)}\n\n${'Second paragraph goes on and on. '.repeat(20)}`;
  const r = trimForViewer(long, 500);
  assert.equal(r.truncated, true);
  assert.ok(r.text.length <= 502, `${r.text.length}`);
  assert.ok(r.text.endsWith(' …'));
  // Leading indentation (board diagrams) survives.
  assert.equal(trimForViewer('\n\n  a b c\n1 . . .').text, '  a b c\n1 . . .');
});

test('viewerExcerpt keeps the head and the questions at the end of a book-length prompt', () => {
  const book = `Read the book below.\n\n${'Lorem ipsum dolor sit amet. '.repeat(2000)}\n\nQ1. Who?\nQ2. Where?\n\nReply in this form:\nA1: <answer>\nA2: <answer>\nA3: <answer>\nA4: <answer>\nA5: <answer>`;
  const x = viewerExcerpt(book, 600);
  assert.equal(x.truncated, true);
  assert.ok(x.text.startsWith('Read the book below.'));
  assert.ok(x.tail?.includes('Q1. Who?'));
  assert.ok(x.tail?.includes('A1: <answer>\n…\nA5: <answer>'), x.tail);
  assert.ok((x.skippedWords ?? 0) > 8000);
  assert.equal(collapseRepeats('a\nA1: x\nA2: x\nA3: x\nb'), 'a\nA1: x\nA2: x\nA3: x\nb');
});

test('the sample never includes the answer key unless reveal is asked for', async () => {
  for (const t of builtin) {
    const d = t.definition;
    if (d.kind !== 'prompt') continue;
    const hidden = await buildSample(d, { baseDir: testBaseDir(t.file) });
    assert.equal(hidden.answer, undefined, d.id);
    assert.equal(hidden.answerNote, undefined, d.id);
    assert.ok(hidden.text.length > 0, `${d.id}: sample text`);
    assert.ok(hidden.text.length <= 720 || hidden.tail, `${d.id}: sample is trimmed (${hidden.text.length})`);
    const exp = JSON.stringify(d.cases[0]!.expected ?? null);
    if (exp.length > 12 && !d.cases[0]!.prompt?.includes(exp)) assert.ok(!JSON.stringify(hidden).includes(exp), `${d.id}: answer key leaked`);
    const shown = await buildSample(d, { reveal: true, baseDir: testBaseDir(t.file) });
    assert.equal(shown.hasAnswer, !!shown.answer, d.id);
  }
});

test('vision samples carry their pictures', async () => {
  const t = builtin.find((x) => x.definition.id === 'vision.count-and-locate')!;
  const s = await buildSample(t.definition, { baseDir: testBaseDir(t.file) });
  assert.equal(s.images.length, 1);
  assert.match(s.images[0]!.path ?? '', /^vision\/images\/.+\.png$/);
});

test('answers are shown in viewer words', () => {
  const byId = (id: string) => builtin.find((t) => t.definition.id === id)!.definition as PromptTest;
  const math = byId('math.competition');
  assert.deepEqual(answerForViewer(math, math.cases[0]!), { answer: '834', note: 'Must match exactly.' });
  const trap = byId('honesty.pressure-traps');
  const a = answerForViewer(trap, trap.cases[0]!)!;
  assert.ok(a.answer.startsWith('FALSE PREMISE'));
  assert.ok(!/CAUGHT_TRAP/.test(a.answer), 'judge grading notes are cut');
  const code = byId('coding.algorithms');
  assert.match(answerForViewer(code, code.cases[0]!)!.answer, /^\d+ hidden unit tests call coverageProfile\(\)$/);
  const games = byId('creative.one-shot-games');
  assert.equal(answerForViewer(games, games.cases[0]!), null);
});

test('program samples show the real opening message without calling any model', async () => {
  for (const id of ['agentic.escape-room', 'social.liars-table', 'visual.draw-it-blind', 'long-context.needle-haystack']) {
    const t = builtin.find((x) => x.definition.id === id)!;
    const d = t.definition;
    assert.equal(d.kind, 'program');
    if (d.kind !== 'program') continue;
    const s = await buildSample(d, { program: PROGRAMS[d.program], hash: t.hash, reveal: true });
    assert.equal(s.kind, 'program');
    assert.equal(s.caseId, `seed-${d.seeds[0]}`);
    assert.ok(s.text.length > 40, `${id}: opening text`);
    assert.equal(s.hasAnswer, false);
    assert.equal(s.answer, undefined);
    assert.equal(s.situation, explainerFor(id)!.opening);
  }
  const needle = builtin.find((x) => x.definition.id === 'long-context.needle-haystack')!;
  const s = await buildSample(needle.definition, { program: PROGRAMS['needle-haystack'], hash: needle.hash });
  assert.ok(s.tail?.includes('Q1.'), 'the questions at the end of the book are shown');
  assert.ok((s.skippedWords ?? 0) > 40000);
});

test('Arena samples show the opening position', () => {
  const c4 = buildArenaSample('arena.connect4', GAMES.connect4!, 'x');
  assert.match(c4.text, /1 2 3 4 5 6 7/);
  assert.equal(c4.hasAnswer, false);
  const chess = buildArenaSample('arena.chess', GAMES.chess!, null);
  assert.match(chess.text, /r n b q k b n r/);
  assert.ok(buildArenaSample('arena.debate', GAMES.debate!, null).text.includes('MOTION'));
});
