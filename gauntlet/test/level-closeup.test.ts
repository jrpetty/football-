/**
 * The Level close-up (ui/src/components/viz/LevelCloseUp.tsx): its pure parsers and summaries in
 * src/presenter/visuals/ (closeup.ts, business.ts and the ladder additions to grid, planning, maths and code) must
 * read every level of the real ladder tests, never read the answer key for the question side, and agree with the
 * real scorers.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import type { PromptTest } from '../src/core/types.ts';
import { engineQuestion, engineSentence, fractionCompare, gridQuestion, gridSentence, islandSentence, levelVerdict, pegsSentence, questionInput, snakesBoard, snakesSentence } from '../src/presenter/visuals/closeup.ts';
import { businessFromItems, businessQuestion, businessSentence, parseBizItem, sqlFromItems } from '../src/presenter/visuals/business.ts';
import { gridVisual } from '../src/presenter/visuals/grid.ts';
import { islandVisual } from '../src/presenter/visuals/truth.ts';
import { hanoiFramesFromNotes, parsePlanPuzzle, planVisual } from '../src/presenter/visuals/planning.ts';
import { mathsVisual } from '../src/presenter/visuals/maths.ts';
import { withTestNames, type CodeVisual } from '../src/presenter/visuals/code.ts';
import { compareJson } from '../src/scoring/json-compare.ts';
import { scoreLadder } from '../src/scoring/ladder.ts';
import type { CaseVisualInput } from '../src/presenter/visuals/common.ts';

const load = (p: string): PromptTest => JSON.parse(readFileSync(new URL(`../tests/${p}.json`, import.meta.url), 'utf8')) as PromptTest;
const q = (t: PromptTest, i: number): CaseVisualInput => {
  const c = t.cases[i]!;
  return questionInput(t.id, c.id, { turns: [`${t.preamble ? `${t.preamble}\n\n` : ''}${c.prompt}`], expected: c.expected, notes: c.notes })!;
};
const answered = (input: CaseVisualInput, reply: string, passed: boolean): CaseVisualInput => ({ ...input, replies: [reply], passed, score: passed ? 1 : 0, status: 'ok' });

test('verdict: passed, failed, near miss of a 90% rung, not scored', () => {
  assert.equal(levelVerdict(1, 1).tone, 'pass');
  assert.equal(levelVerdict(16 / 17, 1).tone, 'fail');
  assert.equal(levelVerdict(16 / 17, 1).scoreText, '94% of the marks');
  const near = levelVerdict(0.88, 0.9);
  assert.equal(near.tone, 'partial');
  assert.equal(near.title, '88% — needs 90%');
  assert.equal(levelVerdict(0.95, 0.9).tone, 'pass');
  assert.equal(levelVerdict(null, 1, 'error').tone, 'none');
});

test('Deduction Grid ladder: every level drawn (houses, categories, numbered clues, asked rows) and a swap described', () => {
  const t = load('reasoning/grid-ladder');
  t.cases.forEach((c, i) => {
    const g = gridQuestion(q(t, i).turns[0]!);
    assert.ok(g, c.id);
    const clues = Number((c.notes!.match(/(\d+) clues/) ?? [])[1]);
    assert.equal(g.clues.length, clues, `${c.id} clue count`);
    assert.deepEqual(
      g.clues.map((x) => x.n),
      g.clues.map((_, k) => k + 1),
    );
    assert.equal(g.asked.length, 2, `${c.id} asks two categories`);
    assert.equal(g.positions.length, g.categories[0]!.values.length);
    const compound = Number((c.notes!.match(/\((\d+) compound\)/) ?? [])[1] ?? NaN);
    if (Number.isFinite(compound)) assert.equal(g.clues.filter((x) => x.compound).length, compound, `${c.id} compound clues marked`);
    assert.ok(gridVisual(q(t, i)), `${c.id} key drawn`);
  });
  // A swapped pair in the first group: named in the sentence, the level failed.
  const input = q(t, 5);
  const key = String((t.cases[5]!.expected as string[])[0]);
  const groups = key.split(';').map((g) => g.split(',').map((x) => x.trim()));
  [groups[0]![7], groups[0]![8]] = [groups[0]![8]!, groups[0]![7]!];
  const v = gridVisual(answered(input, `FINAL ANSWER: ${groups.map((g) => g.join(', ')).join('; ')}`, false))!;
  assert.equal(v.total - v.right, 2);
  const s = gridSentence(v, 0, 1);
  assert.match(s, /^Got 16 of 18 cells right, but swapped (Ivo and Finn|Finn and Ivo), so the level counts as failed \(full marks needed\)\.$/);
});

test('Knights & Knaves ladder: every level parsed; a misjudged islander named with the true type', () => {
  const t = load('reasoning/truth-ladder');
  t.cases.forEach((_, i) => assert.ok(islandVisual(q(t, i)), t.cases[i]!.id));
  const key = String((t.cases[3]!.expected as string[])[0]);
  const wrong = key.replace('Fitz: spy', 'Fitz: knave');
  const v = islandVisual(answered(q(t, 3), `FINAL ANSWER: ${wrong}`, false))!;
  assert.equal(islandSentence(v, 0, 1), 'Named 8 of 9 islanders correctly, but called Fitz a knave (Fitz is a spy), so the level counts as failed (every islander needed).');
});

test('Shortest Plans ladder: the four-peg towers replay from the notes at every level, matching the key', () => {
  const t = load('reasoning/plans-ladder');
  t.cases.forEach((c, i) => {
    const z = parsePlanPuzzle(c.prompt!);
    assert.equal(z?.kind, 'hanoi', c.id);
    const frames = hanoiFramesFromNotes(z as Extract<typeof z, { kind: 'hanoi' }> & object, c.notes);
    assert.equal(frames?.length, Number(c.expected) + 1, `${c.id} plan length`);
    const started = Date.now();
    assert.equal(planVisual(q(t, i))?.frames?.length, Number(c.expected) + 1);
    assert.ok(Date.now() - started < 500, `${c.id} no search needed`);
  });
  // A tampered plan (an illegal move) is refused, never drawn.
  const c = t.cases[5]!;
  const z = parsePlanPuzzle(c.prompt!) as Extract<ReturnType<typeof parsePlanPuzzle>, { kind: 'hanoi' }>;
  assert.equal(hanoiFramesFromNotes(z, c.notes!.replace('disk 2 C to A', 'disk 6 C to A')), null);
  assert.equal(pegsSentence(33, 32, 0, 1), 'Said 33 moves, but the shortest plan takes 32, so the level counts as failed (only the exact minimum scores).');
});

test('Exact Maths ladder: board parsed at every level; fractions compared exactly, like the scorer', () => {
  const t = load('math/exact-ladder');
  t.cases.forEach((c, i) => {
    const b = snakesBoard(c.prompt!);
    assert.ok(b, c.id);
    assert.equal(b.ladders.length, (c.notes!.match(/(\d+) ladders?/) ?? [0, 0])[1] ? Number(c.notes!.match(/(\d+) ladders?/)![1]) : b.ladders.length);
    const right = answered(q(t, i), `FINAL ANSWER: ${c.expected}`, true);
    assert.equal(fractionCompare(right)?.equal, true);
    // Not in lowest terms: still equal (the scorer agrees).
    const [p, qq] = String(c.expected).split('/');
    const doubled = `FINAL ANSWER: ${BigInt(p!) * 2n}/${BigInt(qq!) * 2n}`;
    assert.equal(fractionCompare(answered(q(t, i), doubled, true))?.equal, true);
    assert.equal(scoreLadder('fraction', c.expected, doubled).passed, true);
    // The maths card keys the fraction, never its numerator.
    assert.equal(mathsVisual(right)?.keyText, String(c.expected));
  });
  const c = t.cases[7]!;
  const off = fractionCompare(answered(q(t, 7), 'FINAL ANSWER: 1179673713753860517/5193464296601540290', false))!;
  assert.equal(off.equal, false);
  assert.ok(off.sameDecimals >= 3 && off.sameDecimals < 8);
  assert.match(snakesSentence(off, 0, 1), /right to \d decimal places, but not the exact value, so the level counts as failed/);
  assert.equal(String(c.expected), '117256149806403965/516248936043890688');
});

test('Engineering ladder: operations unlocked per level, hidden-test names line up with the tests', () => {
  const t = load('coding/engineering-ladder');
  const labels = JSON.parse(readFileSync(new URL('../verification/engineering-ladder/labels.json', import.meta.url), 'utf8')) as Record<string, string[]>;
  // The demo (ui/src/mock/ladderCloseUpMock.ts) scores failed attempts out of these counts.
  const mock = readFileSync(new URL('../ui/src/mock/ladderCloseUpMock.ts', import.meta.url), 'utf8');
  const perLevel = JSON.parse((mock.match(/ENGINE_TESTS_PER_LEVEL = (\[[\d, ]+\])/) ?? [])[1] ?? '[]') as number[];
  t.cases.forEach((c, i) => {
    const e = engineQuestion(c.prompt!, c.notes);
    assert.ok(e, c.id);
    assert.equal(e.level, i + 1);
    assert.ok(e.ops.length >= 2);
    const tests = (c.expected as { tests: unknown[] }).tests.length;
    assert.equal(labels[c.id]?.length, tests, `${c.id} labels`);
    assert.equal(perLevel[i], tests, `${c.id} demo count`);
    assert.equal(e.hiddenTests, tests);
  });
  assert.deepEqual(
    engineQuestion(t.cases[5]!.prompt!)!.ops.filter((o) => o.isNew).map((o) => o.name),
    ['chmax'],
  );
  // Names land on tiles only when the counts match.
  const v: CodeVisual = { tiles: [1, 2].map((index) => ({ index, status: index === 2 ? 'fail' : 'pass', label: `test ${index}`, detail: '', argChars: 0 })), groups: [], passed: 1, total: 2, code: '', loadError: null, functionName: 'f', firstFail: null };
  v.firstFail = v.tiles[1]!;
  assert.equal(withTestNames(v, ['a', 'b']).firstFail?.name, 'b');
  assert.equal(withTestNames(v, ['a']).tiles[0]!.name, undefined);
  assert.equal(
    engineSentence([{ index: 1, name: 'a', status: 'pass' }, { index: 2, name: 'chmax on two-valued ranges', status: 'fail' }], 0.5, 1),
    'Passed 1 of 2 hidden tests, but failed ‘chmax on two-valued ranges’, so the level counts as failed (every test needed).',
  );
});

test('Business ladders: the question reads the prompt only; the answer is summarised from the scorer’s items', () => {
  const t = load('work/invoices-ladder');
  const c = t.cases[6]!;
  const bq = businessQuestion(t.id, c.prompt!);
  const docs = Object.keys((c.expected as { documents: object }).documents);
  assert.equal(bq.piles[0]!.count, docs.length);
  assert.equal(bq.piles[0]!.cards.length, 4);
  assert.match(bq.subject ?? '', /Payment run/);
  assert.ok(bq.answerFields.includes('outcome'));
  // Wrong outcomes on three documents, through the real comparer.
  const answer = structuredClone(c.expected) as { documents: Record<string, { outcome: string; amount_to_pay: number }> };
  for (const id of docs.slice(-3)) answer.documents[id]!.outcome = answer.documents[id]!.outcome === 'approve' ? 'hold-price' : 'approve';
  const sc = (c.scorer ?? t.scorer) as { weights?: Record<string, number>; numberTolerance?: number; lenient?: boolean };
  const r = compareJson(c.expected, answer, { weights: sc.weights, numberTolerance: sc.numberTolerance, lenient: sc.lenient });
  const v = businessFromItems(r.items)!;
  assert.equal(v.wrong.length, 3);
  assert.deepEqual(
    v.wrong.map((g) => g.entity),
    docs.slice(-3),
  );
  assert.equal(v.wrong[0]!.fields[0]!.field, 'outcome');
  assert.ok(Math.abs(v.earned / v.points - r.score) < 1e-9, 'section bars add up to the score');
  assert.equal(v.entities.filter((e) => e.section === 'documents').length, docs.length);
  assert.match(businessSentence(v, r.score, 0.9, 'documents'), /3 of 67 documents had a mistake/);
  // Every Business ladder's question side finds its items.
  for (const id of ['payroll', 'expenses', 'reconciliation', 'crm', 'contract', 'support']) {
    const b = load(`work/${id}-ladder`);
    const p = businessQuestion(b.id, b.cases[4]!.prompt!);
    assert.ok(p.piles.length && p.piles.every((x) => x.count > 0 && x.cards.length > 0), id);
  }
});

test('Business: scorer items parse (weights, sets, extras, groupings) and SQL is per database', () => {
  assert.deepEqual(parseBizItem({ label: 'documents.DOC-1.outcome (×4)', passed: false, detail: 'expected "approve", got "hold-vat"' }), {
    path: 'documents.DOC-1.outcome',
    section: 'documents',
    entity: 'DOC-1',
    field: 'outcome',
    kind: 'value',
    points: 4,
    earned: 0,
    ok: false,
    expected: 'approve',
    got: 'hold-vat',
    note: null,
  });
  assert.equal(parseBizItem({ label: 'months.July 2026.corrected.E03.gross (×2)', passed: true }).entity, 'July 2026 · E03');
  assert.equal(parseBizItem({ label: 'months.July 2026.wrong_payslips: E03', passed: false, detail: 'missing' }).kind, 'listed');
  assert.equal(parseBizItem({ label: 'non_compliant: extra item "L007" (not in the answer key)', passed: false, detail: 'costs 6 points' }).points, 6);
  const pairs = parseBizItem({ label: 'clusters: 40 of 50 same-group pairs found, 5 wrong pairs', passed: false, detail: 'pairwise F1 84%' });
  assert.equal(pairs.points, 50);
  assert.ok(Math.abs(pairs.earned - 42) < 1e-9);
  const sql = sqlFromItems(
    [
      { label: 'live database (sampled in the prompt)', passed: true, detail: 'exact match' },
      { label: 'hidden copy A', passed: false, detail: '37 of 42 rows right (88%)' },
    ],
    'SELECT 1',
  )!;
  assert.equal(sql.sections[1]!.earned, 0.88);
  assert.match(businessSentence(sql, 0.94, 0.9), /exactly right on 1 of 2 databases, scoring 94%: above the 90% pass mark/);
});
