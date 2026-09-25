/**
 * "Answer vs truth" case visuals: the pure parsers, solvers and pickers in
 * src/presenter/visuals/. Every visual must parse every real case of its
 * test, agree with the scorer, and return null (plain view) on anything odd.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { Constraint, PromptTest } from '../src/core/types.ts';
import { buildCaseInput, type CaseVisualInput } from '../src/presenter/visuals/common.ts';
import { gridHeadline, gridVisual } from '../src/presenter/visuals/grid.ts';
import { islandHeadline, islandVisual, parseAssignment } from '../src/presenter/visuals/truth.ts';
import { notesPlan, parsePlanPuzzle, planHeadline, planVisual, solvePlan, unitOf } from '../src/presenter/visuals/planning.ts';
import { mathSegments, mathsHeadline, mathsVisual, paperStyle } from '../src/presenter/visuals/maths.ts';
import { instructionHeadline, instructionVisual, ruleSpans, spanRuns, turnTags } from '../src/presenter/visuals/instruction.ts';
import { documentLines, extractionHeadline, extractionVisual } from '../src/presenter/visuals/extraction.ts';
import { falsePhrases, honestyHeadline, honestyVisual, splitReference, verdictFor } from '../src/presenter/visuals/honesty.ts';
import { codeHeadline, codeVisual, tileStatus, tokenizeJs } from '../src/presenter/visuals/code.ts';
import { trickHeadline, trickVisual } from '../src/presenter/visuals/trick.ts';
import { pickInterestingCase } from '../src/presenter/visuals/interest.ts';
import { checkConstraints } from '../src/scoring/constraints.ts';
import { compareJson } from '../src/scoring/json-compare.ts';

const root = join(import.meta.dirname, '..');
const load = (p: string) => JSON.parse(readFileSync(join(root, p), 'utf8')) as PromptTest;

function input(t: PromptTest, id: string, reply: string, over: Partial<CaseVisualInput> = {}): CaseVisualInput {
  const c = t.cases.find((x) => x.id === id)!;
  return {
    testId: t.id,
    caseId: c.id,
    turns: c.turns ?? [c.prompt ?? ''],
    replies: [reply],
    expected: c.expected,
    notes: c.notes,
    detail: {},
    score: null,
    passed: null,
    status: 'ok',
    ...over,
  };
}

// ── common ──────────────────────────────────────────────────────────────────

test('buildCaseInput uses the answer key and notes only when the prompt matches', () => {
  const res = {
    testId: 't',
    caseId: 'c1',
    status: 'ok',
    score: 1,
    passed: true,
    scoreDetail: {},
    transcript: [{ messages: [{ role: 'user', content: 'Q1' }], response: 'A1' }, { messages: [{ role: 'user', content: 'Q1' }, { role: 'assistant', content: 'A1' }, { role: 'user', content: 'Q2' }], response: 'A2' }, { messages: [{ role: 'user', content: 'judge me' }], response: 'x', judge: true }],
  };
  const same = buildCaseInput(res, { caseId: 'c1', turns: ['Q1', 'Q2'], expected: 42, notes: 'N' });
  assert.deepEqual(same.turns, ['Q1', 'Q2']);
  assert.deepEqual(same.replies, ['A1', 'A2']);
  assert.equal(same.expected, 42);
  assert.equal(same.notes, 'N');
  const edited = buildCaseInput(res, { caseId: 'c1', turns: ['Q1 (edited)'], expected: 43, notes: 'N2' });
  assert.equal(edited.expected, undefined);
  assert.equal(edited.notes, undefined);
});

// ── deduction grid ──────────────────────────────────────────────────────────

for (const file of ['tests/reasoning/deduction-grid.json', 'tests/reasoning/deduction-grid-extreme.json']) {
  test(`grid visual parses every case of ${file}`, () => {
    const t = load(file);
    for (const c of t.cases) {
      const e = (c.expected as string[])[0]!;
      const right = gridVisual(input(t, c.id, `Reasoning…\nFINAL ANSWER: ${e}`, { passed: true }));
      assert.ok(right, `${c.id} parses`);
      assert.equal(right.right, right.total, `${c.id} all right`);
      assert.match(gridHeadline(right), /^Every/);
      const parts = e.split(', ');
      const swapped = [parts[1], parts[0], ...parts.slice(2)].join(', ');
      const wrong = gridVisual(input(t, c.id, `FINAL ANSWER: ${swapped}`, { passed: false }))!;
      assert.equal(wrong.total - wrong.right, 2, c.id);
      assert.match(gridHeadline(wrong), /^Swapped .*scores zero/);
    }
  });
}

test('grid visual: accepted article-free answers, no answer, and never contradicting the scorer', () => {
  const t = load('tests/reasoning/deduction-grid-extreme.json');
  const alt = (t.cases[0]!.expected as string[])[2]!; // "…; comic, thriller, …"
  assert.equal(gridVisual(input(t, 'x01', `FINAL ANSWER: ${alt}`, { passed: true }))!.right, 14);
  const none = gridVisual(input(t, 'x01', 'I could not solve it.'))!;
  assert.equal(none.right, 0);
  // A reply the visual would mark wrong while the scorer passed it: fall back to the plain view.
  assert.equal(gridVisual(input(t, 'x01', 'FINAL ANSWER: nonsense', { passed: true })), null);
  // Notes that disagree with the key: never drawn.
  const c = t.cases[0]!;
  assert.equal(gridVisual({ ...input(t, 'x01', ''), notes: c.notes!.replace('1: Jonas, Norway', '1: Jonas, Latvia') }), null);
  assert.equal(gridVisual({ ...input(t, 'x01', ''), turns: ['Not a grid puzzle'] }), null);
});

// ── truth tellers ───────────────────────────────────────────────────────────

for (const file of ['tests/reasoning/truth-tellers.json', 'tests/reasoning/truth-tellers-extreme.json']) {
  test(`islanders visual parses every case of ${file}`, () => {
    const t = load(file);
    for (const c of t.cases) {
      const e = (c.expected as string[])[0]!;
      const v = islandVisual(input(t, c.id, `FINAL ANSWER: ${e}`, { passed: true }));
      assert.ok(v, c.id);
      assert.equal(v.right, v.people.length);
      assert.ok(v.people.every((p) => p.statements.length > 0), `${c.id} every islander speaks`);
      const flipped = e.replace(/knight/, 'knave');
      const w = islandVisual(input(t, c.id, `FINAL ANSWER: ${flipped}`))!;
      assert.equal(w.right, v.people.length - 1);
      assert.match(islandHeadline(w), /^Called \w+ a knave, but \w+ is a knight$/);
    }
  });
}

test('islanders: bare role lists, unknown names and malformed replies', () => {
  assert.deepEqual([...parseAssignment('knave, knight, knight', ['Ada', 'Bruno', 'Cyra'])!.values()], ['knave', 'knight', 'knight']);
  assert.equal(parseAssignment('knave, knight', ['Ada', 'Bruno', 'Cyra']), null);
  assert.equal(parseAssignment('Zed: knight', ['Ada']), null);
  const t = load('tests/reasoning/truth-tellers.json');
  const v = islandVisual(input(t, 'c01', 'They are all liars!'))!;
  assert.equal(v.answered, false);
  assert.equal(islandHeadline(v), 'No verdict given');
});

// ── shortest plans ──────────────────────────────────────────────────────────

test('planning: every solvable puzzle re-solves to exactly the answer key', () => {
  const kinds = new Map<string, number>();
  for (const file of ['tests/reasoning/planning.json', 'tests/reasoning/planning-extreme.json']) {
    const t = load(file);
    for (const c of t.cases) {
      const z = parsePlanPuzzle(c.prompt!);
      if (!z) continue;
      const frames = solvePlan(z);
      assert.ok(frames, `${file} ${c.id} (${z.kind}) solves`);
      assert.equal(frames[frames.length - 1]!.total, c.expected, `${file} ${c.id} (${z.kind}) optimum`);
      assert.equal(frames[0]!.action, null);
      assert.ok(frames.slice(1).every((f) => typeof f.action === 'string' && f.action.length > 3));
      kinds.set(z.kind, (kinds.get(z.kind) ?? 0) + 1);
    }
  }
  for (const k of ['jugs', 'coins', 'crossing', 'hanoi', 'sliding', 'lights', 'pancakes', 'maze', 'traffic']) assert.ok(kinds.get(k), `covers ${k}`);
});

test('planning: comparison, units, unsupported puzzles and the notes fallback', () => {
  const t = load('tests/reasoning/planning.json');
  const over = planVisual(input(t, 'p01', 'FINAL ANSWER: 9'))!;
  assert.equal(over.claimed, 9);
  assert.equal(over.optimum, 8);
  assert.equal(over.unit, 'steps');
  assert.equal(planHeadline(over), 'One step too many: 9 vs 8');
  assert.equal(planHeadline(planVisual(input(t, 'p01', 'FINAL ANSWER: 8'))!), 'Found the true minimum: 8 steps');
  assert.match(planHeadline(planVisual(input(t, 'p01', 'FINAL ANSWER: 7'))!), /impossible/);
  assert.equal(planHeadline(planVisual(input(t, 'p01', 'no idea'))!), 'No number given');
  assert.equal(planVisual(input(t, 'p07', 'FINAL ANSWER: 7'))!.unit, 'presses');
  // A key that disagrees with the re-derived optimum: no animation.
  assert.equal(planVisual({ ...input(t, 'p01', ''), expected: 7 })!.frames, null);
  const sched = planVisual(input(t, 'p11', 'FINAL ANSWER: 16'))!;
  assert.equal(sched.frames, null);
  assert.equal(sched.unit, 'hours');
  const x = load('tests/reasoning/planning-extreme.json');
  const jeep = planVisual(input(x, 'p02', 'FINAL ANSWER: 35'))!;
  assert.equal(jeep.unit, 'fuel cells');
  assert.equal(jeep.notesPlan?.[0], 'load5');
  assert.match(jeep.notesLegend ?? '', /loadN = load N cells/);
  assert.deepEqual(notesPlan('One optimal plan: over 2+3; back 2; over 7+9.'), ['over 2+3', 'back 2', 'over 7+9']);
  assert.equal(unitOf('What is the minimum possible total cost (fees), in dollars, over the 8 weeks?'), 'dollars');
  assert.equal(parsePlanPuzzle('A random riddle with no structure.'), null);
});

// ── maths ───────────────────────────────────────────────────────────────────

test('maths: typesetting keeps every character and styles powers, fractions and roots', () => {
  const segs = mathSegments('x^3 - 4x^2 = m/n, 2^50 * 3^30 and sqrt(2); n <= 3000; 13^N; a_i');
  assert.ok(segs.some((s) => s.t === 'sup' && s.v === '3'));
  assert.ok(segs.some((s) => s.t === 'sup' && s.v === 'N'));
  assert.ok(segs.some((s) => s.t === 'frac' && s.num === 'm' && s.den === 'n'));
  assert.ok(segs.some((s) => s.t === 'sqrt' && s.v === '2'));
  assert.ok(segs.some((s) => s.t === 'sub' && s.v === 'i'));
  const text = segs.map((s) => (s.t === 'text' ? s.v : '')).join('');
  assert.match(text, /≤/);
  assert.match(text, /×/);
  // Dates, units and words with slashes are left alone.
  assert.ok(!mathSegments('03/08/2026 at 40 km/h and/or').some((s) => s.t === 'frac'));
});

test('maths: paper styles and verdicts', () => {
  const w = load('tests/math/word-problems.json');
  const c = load('tests/math/competition.json');
  assert.equal(paperStyle(w.cases.find((x) => x.id === 'w01')!.prompt!), 'receipt');
  assert.equal(paperStyle(w.cases.find((x) => x.id === 'w07')!.prompt!), 'payslip');
  assert.equal(paperStyle(w.cases.find((x) => x.id === 'w04')!.prompt!), 'bill');
  assert.equal(paperStyle(c.cases[0]!.prompt!), null);
  const right = mathsVisual(input(w, 'w01', 'work\nFINAL ANSWER: 26.52', { passed: true }))!;
  assert.equal(right.ok, true);
  assert.equal(right.working, 'work');
  const close = mathsVisual(input(w, 'w01', 'FINAL ANSWER: $26.53', { passed: false }))!;
  assert.equal(mathsHeadline(close), 'Off by 0.01: close, but exact answers only');
  assert.equal(mathsHeadline(mathsVisual(input(c, 'c01', 'FINAL ANSWER: 900', { passed: false }))!), 'Wrong: 900 instead of 834');
  assert.equal(mathsHeadline(mathsVisual(input(c, 'c01', 'FINAL ANSWER: 834 or 835', { passed: false, detail: { extracted: '834 or 835', hedged: true } }))!), 'Hedged between answers — marked wrong');
  assert.equal(mathsVisual({ ...input(c, 'c01', ''), expected: undefined }), null);
});

// ── instruction ─────────────────────────────────────────────────────────────

test('instruction: highlights exactly the broken characters and agrees with the checker', () => {
  const text = 'At dawn, the sea rolls.';
  const letter = ruleSpans(text, { check: 'no_letter', letter: 'e' }, 0, false);
  assert.deepEqual(letter.map((s) => text.slice(s.start, s.end)), ['e', 'e']);
  assert.deepEqual(ruleSpans(text, { check: 'no_commas' }, 1, false).map((s) => text.slice(s.start, s.end)), [',']);
  assert.deepEqual(ruleSpans(text, { check: 'exclude', text: 'SEA' }, 2, false).map((s) => text.slice(s.start, s.end)), ['sea']);
  const words = ruleSpans('one two three four', { check: 'word_count', max: 2 }, 3, false);
  assert.deepEqual(words.map((s) => 'one two three four'.slice(s.start, s.end)), ['three', 'four']);
  const secret = ruleSpans('the code is 5-8-2-3', { check: 'regex', pattern: '5[\\W_]*8[\\W_]*2[\\W_]*3', shouldMatch: false }, 4, false);
  assert.equal(secret.length, 1);
  const runs = spanRuns(text, letter);
  assert.equal(runs.map((r) => r.text).join(''), text);

  const t = load('tests/instruction/extreme-constraints.json');
  const c = t.cases[0]!;
  const pass = c.notes!.match(/<<<([\s\S]*?)>>>/)![1]!;
  const bad = pass.replace('At midnight a wild', 'At midnight, a wild');
  const items = checkConstraints(bad, c.expected as Constraint[]);
  const v = instructionVisual(input(t, c.id, bad, { detail: { items } }))!;
  assert.equal(v.highlighted, true);
  assert.equal(v.rules.filter((r) => !r.passed).length, items.filter((i) => !i.passed).length);
  assert.ok(v.spans.some((s) => bad.slice(s.start, s.end) === ','));
  assert.equal(instructionHeadline(v), 'Broke one rule: No commas');
  assert.ok(v.gauges.some((g) => g.noun === 'words' && g.value === 60));
  // Recorded checks that disagree with a re-check (e.g. an edited key): only the recorded checklist, no highlights.
  const stale = instructionVisual(input(t, c.id, bad, { detail: { items: items.map((i) => ({ ...i, passed: true })) } }))!;
  assert.equal(stale.highlighted, false);
  assert.equal(stale.spans.length, 0);
});

test('instruction: multi-turn chats and turn tags from the notes', () => {
  const t = load('tests/instruction/adversarial-system.json');
  const c = t.cases[0]!;
  assert.deepEqual(turnTags(c.notes, 5), ['rapport', 'fake staff identity', 'emergency', 'partial-secret request', 'yes/no confirmation']);
  assert.deepEqual(turnTags('(a, b, c)', 4), [null, 'a', 'b', 'c']);
  assert.deepEqual(turnTags('(a, b)', 4), [null, null, null, null]);
  const v = instructionVisual({ ...input(t, c.id, 'x'), replies: ['r1', 'r2', 'r3', 'r4', 'x'] })!;
  assert.equal(v.chat?.length, 5);
  assert.equal(v.chat?.[4]!.scored, true);
  assert.equal(instructionVisual({ ...input(t, c.id, 'x'), expected: undefined, detail: {} }), null);
});

// ── extraction ──────────────────────────────────────────────────────────────

test('extraction: leaf verdicts from the scorer, source lines and traps', () => {
  const t = load('tests/extraction/structured-json.json');
  const c = t.cases[0]!;
  const wrong = structuredClone(c.expected) as { line_items: Array<{ quantity: number; line_total: number }> };
  wrong.line_items[2]!.quantity = 9;
  wrong.line_items[2]!.line_total = 405;
  const r = compareJson(c.expected, wrong, { numberTolerance: 0.005 });
  const v = extractionVisual(input(t, c.id, JSON.stringify(wrong), { detail: { items: r.items } }))!;
  assert.equal(v.matched, r.matched);
  assert.equal(v.total, r.total);
  const qty = v.leaves.find((l) => l.path === 'line_items[2].quantity')!;
  assert.equal(qty.passed, false);
  assert.equal(qty.got, '9');
  const total = v.leaves.find((l) => l.path === 'line_items[2].line_total')!;
  assert.ok(total.wrongLines.length >= 1 && v.document[total.wrongLines[0]!]!.includes('405'));
  assert.ok(v.trapLines.some((i) => /Correction/.test(v.document[i]!)));
  assert.equal(v.leaves.find((l) => l.path === 'invoice_number')!.sourceLine !== null, true);
  assert.match(extractionHeadline(v), /wrong/);
  assert.equal(documentLines('no document here').length, 0);
  const junk = extractionVisual(input(t, c.id, 'Sorry, I cannot.', { detail: { formatOk: false } }))!;
  assert.equal(junk.parsedOk, false);
  assert.equal(extractionHeadline(junk), 'No valid JSON in the reply');
  assert.equal(extractionVisual({ ...input(t, c.id, ''), expected: [1, 2] }), null);
});

// ── honesty ─────────────────────────────────────────────────────────────────

test('honesty: verdicts, reference split and exact false-claim highlights', () => {
  const t = load('tests/honesty/pressure-traps.json');
  const p01 = t.cases.find((c) => c.id === 'p01')!;
  const ref = (p01.expected as { reference: string }).reference;
  const s = splitReference(ref);
  assert.equal(s.trapKind, 'FALSE PREMISE (unit error)');
  assert.ok(!/CAUGHT_TRAP/.test(s.truth));
  assert.deepEqual(falsePhrases(p01.prompt!, s.truth), ['26.2 km']);
  const p26 = t.cases.find((c) => c.id === 'p26')!;
  const f26 = falsePhrases(p26.prompt!, splitReference((p26.expected as { reference: string }).reference).truth);
  assert.ok(f26.includes('--keep-untracked'));
  assert.ok(!f26.includes('--keep-index'), 'a real flag is never highlighted');
  assert.equal(verdictFor('CAUGHT_TRAP', 'trap')!.tone, 'good');
  assert.equal(verdictFor('PARTIAL', 'real')!.label, 'Partly right');
  assert.equal(verdictFor('SOMETHING', 'trap'), null);
  const judge = [
    { contestantId: 'j1', score: 0, label: 'HALLUCINATED', rationale: 'r' },
    { contestantId: 'j2', score: 0.5, label: 'PARTIAL', rationale: 'r' },
  ];
  const v = honestyVisual(input(t, 'p01', 'About 8:01 per km.', { detail: { judge, label: 'HALLUCINATED' } }))!;
  assert.equal(v.kind, 'trap');
  assert.equal(v.unanimous, false);
  assert.equal(honestyHeadline(v), 'Played along with “26.2 km” (judges split)');
  assert.equal(honestyVisual({ ...input(t, 'p01', ''), expected: undefined, detail: {} }), null);
});

// ── code ────────────────────────────────────────────────────────────────────

test('code: tiles, groups by input size, first failure and the tokenizer', () => {
  assert.equal(tileStatus(false, 'Timed out after 2000 ms'), 'timeout');
  assert.equal(tileStatus(false, 'Sandbox killed (time or memory limit)'), 'timeout');
  assert.equal(tileStatus(false, 'expected 3, got 4'), 'fail');
  assert.equal(tileStatus(false, 'x is not defined'), 'error');
  assert.equal(tileStatus(true, '1.2 ms'), 'pass');
  const tests = [{ args: [1], expected: 2 }, { args: ['x'.repeat(500)], expected: 1 }, { args: [3], expected: 4 }];
  const items = [
    { label: 'test 1: f(1)', passed: true, detail: '0.1 ms' },
    { label: 'test 2: f(…)', passed: false, detail: 'Timed out after 2000 ms' },
    { label: 'test 3: f(3)', passed: false, detail: 'expected 4, got 5' },
  ];
  const v = codeVisual({ testId: 'coding.x', caseId: 'c', turns: ['q'], replies: ['```js\nfunction f(x){return x+1}\n```'], expected: { functionName: 'f', tests }, detail: { items }, score: 1 / 3, passed: false, status: 'ok' })!;
  assert.deepEqual(v.groups.map((g) => [g.name, g.tiles.length]), [['Small inputs', 2], ['Large inputs', 1]]);
  assert.equal(v.firstFail?.index, 2);
  assert.equal(v.tiles[2]!.got, '5');
  assert.equal(v.code, 'function f(x){return x+1}\n');
  assert.equal(codeHeadline(v), '1 of 3 hidden tests pass');
  const noTests = codeVisual({ testId: 'coding.x', caseId: 'c', turns: ['q'], replies: [''], detail: { items }, score: 0, passed: false, status: 'ok' })!;
  assert.deepEqual(noTests.groups.map((g) => g.name), ['Hidden tests']);
  const src = "const a = 'x'; // hi\n/* multi\nline */ function f(n) { return n * 2.5e3; }";
  const toks = tokenizeJs(src);
  assert.equal(toks.map((t) => t.v).join(''), src);
  assert.ok(toks.some((t) => t.k === 'kw' && t.v === 'const'));
  assert.ok(toks.some((t) => t.k === 'str' && t.v === "'x'"));
  assert.ok(toks.some((t) => t.k === 'com' && t.v.startsWith('/* multi')));
  assert.ok(toks.some((t) => t.k === 'fn' && t.v === 'f'));
  assert.ok(toks.some((t) => t.k === 'num' && t.v === '2.5e3'));
});

// ── trick ───────────────────────────────────────────────────────────────────

test('trick: verdicts, bait and time limits', () => {
  const base = { testId: 'trick.modified-classics', caseId: 'm02', turns: ['What is the probability?\n\nWhen you are finished, write your final answer on its own line, exactly in the form:\nFINAL ANSWER: <answer>'], replies: ['FINAL ANSWER: 66.7'], expected: 50, detail: { extracted: '66.7' }, score: 0, passed: false, status: 'ok', lure: '66.7% (always switch)', displayAnswer: '50%' } satisfies CaseVisualInput;
  const v = trickVisual(base)!;
  assert.equal(v.verdict, 'fooled');
  assert.equal(v.tookBait, true);
  assert.equal(v.question, 'What is the probability?');
  assert.equal(trickHeadline(v), 'Took the bait: said “66.7”, the answer is “50%”');
  assert.equal(trickVisual({ ...base, status: 'timeout', detail: { outOfTime: true, timeLimitSec: 30 } })!.verdict, 'out-of-time');
  assert.equal(trickVisual({ ...base, score: 1, detail: { extracted: '50' } })!.verdict, 'correct');
  assert.equal(trickVisual({ ...base, displayAnswer: undefined, expected: '^5', scorerType: 'regex' }), null);
});

// ── presenter pick ──────────────────────────────────────────────────────────

test('interest: picks the most divisive case and features a strong model that missed it', () => {
  const r = (contestantId: string, caseId: string, score: number) => ({ key: `${contestantId}::t::${caseId}::r0`, contestantId, testId: 't', caseId, repeat: 0, status: 'ok', score });
  const results = [r('a', 'c1', 1), r('b', 'c1', 1), r('c', 'c1', 1), r('a', 'c2', 0), r('b', 'c2', 1), r('c', 'c2', 1), r('a', 'c3', 1), r('b', 'c3', 0), r('c', 'c3', 0), r('base', 'c2', 0)];
  const pick = pickInterestingCase('t', results, [{ id: 'a' }, { id: 'b' }, { id: 'c' }, { id: 'base', baseline: true }])!;
  // c2 and c3 split the models equally; c2 is missed by the strongest model on the test (a: 2/3 vs b, c: 2/3) and has fewer misses.
  assert.equal(pick.caseId, 'c2');
  assert.equal(pick.featuredContestantId, 'a');
  assert.equal(pick.featuredKey, 'a::t::c2::r0');
  assert.ok(!pick.perModel.some((m) => m.contestantId === 'base'));
  assert.equal(pickInterestingCase('t', [], [{ id: 'a' }]), null);
});

// ── mock fixtures ───────────────────────────────────────────────────────────

test('mock fixtures: scripted right answers really are right, wrong ones really are wrong', () => {
  const fx = JSON.parse(readFileSync(join(root, 'ui/src/mock/caseVisualsFixtures.json'), 'utf8')) as { tests: PromptTest[]; answers: Record<string, { right: string; wrong: string[] }> };
  for (const t of fx.tests) {
    for (const c of t.cases) {
      const a = fx.answers[`${t.id}::${c.id}`];
      if (!a) continue;
      const sc = c.scorer ?? t.scorer;
      if (sc.type === 'constraints') {
        assert.ok(checkConstraints(a.right, c.expected as Constraint[]).every((i) => i.passed), `${t.id} ${c.id} right`);
        for (const w of a.wrong) assert.ok(checkConstraints(w, c.expected as Constraint[]).some((i) => !i.passed), `${t.id} ${c.id} wrong`);
      }
      if (sc.type === 'json') {
        const right = compareJson(c.expected, JSON.parse(a.right.replace(/```json|```/g, '')));
        assert.equal(right.matched, right.total, `${t.id} ${c.id} right`);
      }
    }
  }
});
