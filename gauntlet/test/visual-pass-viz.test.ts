/**
 * Visual pass (long-context, drawing and picture tests): the pure helpers
 * behind the UI views (ui/src/components/viz/vizModel.ts and
 * svgRequirements.ts) and the vision overlay geometry.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import * as vm from '../ui/src/components/viz/vizModel.ts';
import * as sr from '../ui/src/components/viz/svgRequirements.ts';
import { demoSvg } from '../verification/visual-pass/demo-svgs.mts';

test('viz model: needle outcomes, zones and frame lookup (also for old results without passages)', () => {
  const detail = {
    words: 45000,
    needles: [
      { id: 'Q1', kind: 'single', question: 'q', expected: 'Anna', answer: 'Anna', correct: true, trap: null, hedged: false, depth: 10, depths: [10], distractorDepths: [50] },
      { id: 'Q2', kind: 'multi-hop', question: 'q', expected: 'Bo', answer: 'Bob', correct: false, trap: 'distractor', hedged: false, depth: 50, depths: [20, 50], distractorDepths: [70] },
      { id: 'Q3', kind: 'superseded', question: 'q', expected: '12', answer: '11', correct: false, trap: 'superseded', hedged: false, depth: 80, depths: [30, 80], distractorDepths: [] },
      { id: 'Q4', kind: 'single', question: 'q', expected: 'X', answer: 'unknown', correct: false, trap: null, hedged: false, depth: 95, depths: [95], distractorDepths: [] },
      { id: 'Q5', kind: 'aggregate', question: 'q', expected: '9', answer: '9 or 10', correct: false, trap: null, hedged: true, depth: 90, depths: [5, 40, 90], distractorDepths: [] },
    ],
  };
  const m = vm.needleModel(detail, null)!;
  assert.deepEqual(m.needles.map((n) => n.outcome), ['found', 'decoy', 'old-value', 'blank', 'hedged']);
  assert.equal(m.hasPassages, false);
  assert.deepEqual(m.zones.map((z) => [z.found, z.total]), [[1, 1], [0, 1], [0, 3]]);
  assert.equal(vm.needleForFrame(m, '80% · correction · Q3')?.id, 'Q3');
  assert.equal(vm.needleForFrame(m, 'Step 4'), undefined);
  assert.equal(vm.needleHeadline(m.needles[1]!).tone, 'warn');
  assert.equal(vm.wordAt(50, 45000), 'word 22,500 of 45,000');
  const withText = vm.needleModel(detail, { needles: [{ id: 'Q1', parts: [{ text: 'Anna ruled.', before: 'a', after: 'b', depth: 10 }], decoys: [] }] })!;
  assert.equal(withText.hasPassages, true);
  assert.equal(withText.needles[0]!.parts![0]!.text, 'Anna ruled.');
  assert.equal(vm.needleModel({ needles: 'nope' }, null), null);
  assert.equal(vm.needleModel(null, null), null);
});

test('viz model: whispers lanes from the trace, and rebuilt from "lost" lists for old results', () => {
  const detail = {
    total: 3,
    survived: 1,
    story: { words: 470 },
    facts: [
      { id: 'a', label: 'A', canonical: 'A is 1', survived: true },
      { id: 'b', label: 'B', canonical: 'B is 2', survived: false },
      { id: 'c', label: 'C', canonical: 'C is 3', survived: false },
    ],
    rounds: [
      { round: 1, cycle: 1, kind: 'summary', words: 90, limit: 100, truncated: false, failed: false, lost: ['b'] },
      { round: 2, cycle: 1, kind: 'story', words: 400, limit: 500, truncated: false, failed: false, lost: ['c'] },
    ],
  };
  const old = vm.whispersModel(detail, null)!;
  assert.equal(old.hasTrace, false);
  assert.deepEqual(old.status, [
    ['kept', 'kept', 'kept'],
    ['kept', 'lost', 'lost'],
    ['kept', 'kept', 'lost'],
  ]);
  assert.equal(vm.whisperHeadline(old, 1).tone, 'bad');
  assert.match(vm.whisperHeadline(old, 1).title, /lost the B/);
  assert.equal(vm.whisperHeadline(old, 0).tone, 'neutral');
  const text = 'A is 1. Bee is 7. Weather.';
  const fresh = vm.whispersModel(detail, {
    source: { text: 'x', trace: { a: { status: 'kept' }, b: { status: 'kept' }, c: { status: 'kept' } } },
    rounds: [
      { round: 1, kind: 'summary', text, trace: { a: { status: 'kept', sentence: 'A is 1.' }, b: { status: 'changed', sentence: 'Bee is 7.' }, c: { status: 'kept', sentence: 'A is 1.' } } },
      { round: 2, kind: 'story', text, trace: { a: { status: 'kept', sentence: 'A is 1.' }, b: { status: 'lost' }, c: { status: 'lost' } } },
    ],
  })!;
  assert.equal(fresh.hasTrace, true);
  assert.deepEqual(fresh.status[1], ['kept', 'changed', 'lost']);
  assert.match(vm.whisperHeadline(fresh, 1).title, /B started to drift/);
  assert.match(vm.whisperHeadline(fresh, 2).title, /lost the B and C/);
  const segs = vm.highlightFacts(text, fresh.columns[1]!.trace);
  assert.deepEqual(
    segs.map((s) => [s.text, s.status ?? null, s.factIds ?? null]),
    [
      ['A is 1.', 'kept', ['a', 'c']],
      [' ', null, null],
      ['Bee is 7.', 'changed', ['b']],
      [' Weather.', null, null],
    ],
  );
  assert.deepEqual(vm.highlightFacts('plain', null), [{ text: 'plain' }]);
  const all = vm.whispersModel({ ...detail, survived: 3, rounds: [{ ...detail.rounds[0], lost: [] }] }, null)!;
  assert.match(vm.whisperHeadline(all, 1).title, /3 of 3 facts survived/);
  assert.equal(vm.whisperHeadline(all, 1).tone, 'good');
});

test('viz model: Draw It Blind phrases link to the shapes they describe', () => {
  const shapes = [
    { i: 0, type: 'circle', color: 'red', cx: 50, cy: 50, w: 40, h: 40, angle: null },
    { i: 1, type: 'rectangle', color: 'blue', cx: 200, cy: 200, w: 80, h: 40, angle: null },
    { i: 2, type: 'circle', color: 'red', cx: 300, cy: 300, w: 40, h: 40, angle: null },
    { i: 3, type: 'star', color: 'green', cx: 300, cy: 50, w: 40, h: 40, angle: null },
  ];
  const text = 'A big red circle top left. A long blue bar in the middle. Another small red circle bottom right.';
  const links = vm.phraseLinks(text, shapes);
  assert.deepEqual(
    links.map((l) => [text.slice(l.start, l.end), l.shape]),
    [
      ['red circle', 0],
      ['blue bar', 1],
      ['red circle', 2],
    ],
  );
  assert.equal(vm.pairTone(0.8), 'good');
  assert.equal(vm.pairTone(0.6), 'warn');
  assert.equal(vm.pairTone(0.3), 'bad');
  const replay = { title: 'Draw It Blind — 2 shapes', frames: [], svgCompare: { left: { title: 'o', svg: '<svg/>' }, right: { title: 'r', svg: '<svg/>' } }, visual: { kind: 'draw-it-blind', data: { canvas: 400, limit: 120, angles: [null, 45], extras: [{ kind: 'circle', color: 'red', cx: 1, cy: 2, w: 3, h: 4 }] } } };
  const d = vm.drawModel({ scene: [{ type: 'circle', color: 'red', cx: 1, cy: 1, w: 2, h: 2 }, { type: 'triangle', color: 'blue', cx: 1, cy: 1, w: 2, h: 2 }], matches: [{ drawn: null, score: 0 }, { drawn: { kind: 'triangle', color: 'blue', cx: 2, cy: 2, w: 2, h: 2 }, score: 0.9, distance: 1.4 }], descriptionWords: 97, match: 0.45 }, replay)!;
  assert.equal(d.limit, 120);
  assert.equal(d.shapes[1]!.angle, 45);
  assert.equal(d.pairs[0]!.drawn, null);
  assert.equal(d.extras.length, 1);
  assert.equal(d.targetSvg, '<svg/>');
  // Old result: no visual data, so no limit (the view says "limit not recorded").
  assert.equal(vm.drawModel({ scene: [], matches: [] }, { title: 'x', frames: [] })!.limit, null);
});

test('viz model: vision answers vs truth (cells, counts, lines)', () => {
  const truth = vm.parseCells({ cells: ['B2', 'B4', 'D3'] })!;
  const said = vm.parseCells('{"cells":["b2","D3","C3"]}');
  assert.deepEqual(
    vm.cellMarks(said, truth).map((m) => [m.cell, m.status]),
    [
      ['B2', 'correct'],
      ['B4', 'missed'],
      ['D3', 'correct'],
      ['C3', 'wrong'],
    ],
  );
  const typedTruth = vm.parseCells({ differences: [{ cell: 'A1', change: 'colour' }] })!;
  assert.equal(vm.cellMarks(vm.parseCells('{"differences":[{"cell":"A1","change":"shape"}]}'), typedTruth)[0]!.status, 'wrong');
  assert.equal(vm.cellMarks(vm.parseCells('{"differences":[{"cell":"A1","change":"color"}]}'), typedTruth)[0]!.status, 'correct');
  assert.equal(vm.parseCells('not json'), null);
  assert.equal(vm.parseCells('{"cells": [}'), null);
  assert.equal(vm.cellMarks(null, truth).every((m) => m.status === 'missed'), true);
  assert.deepEqual(vm.cellRect({ id: 'L', x0: 110, y0: 100, size: 560, n: 4 }, 'B3'), { x: 250, y: 380, w: 140, h: 140 });
  assert.equal(vm.cellRect({ id: 'L', x0: 0, y0: 0, size: 400, n: 4 }, 'E1'), null);
  assert.equal(vm.cellRect({ id: 'L', x0: 0, y0: 0, size: 400, n: 4 }, 'nonsense'), null);
  assert.equal(vm.parseNumber('I count 7 red triangles'), 7);
  assert.equal(vm.parseNumber('1,250'), 1250);
  assert.equal(vm.parseNumber(undefined), null);
  assert.equal(vm.parseLine('{"line": 3, "x": 12}'), 3);
  assert.equal(vm.parseLine('3'), null);
  assert.equal(vm.showAnswer('{"cells":["A1","C4"]}'), 'A1, C4');
  assert.equal(vm.showAnswer(['SEP 72', 'September 72']), 'SEP 72');
  assert.equal(vm.showAnswer({ line: 2, x: 13 }), 'line 2 · x 13');
  assert.equal(vm.showAnswer(undefined), '—');
});

test('vision layouts: every overlay matches its test file (grid size, target count, chart marks)', () => {
  const layouts = JSON.parse(readFileSync(new URL('../ui/src/components/viz/vision-layouts.json', import.meta.url), 'utf8')) as Record<string, { width: number; grids?: Array<{ n: number }>; targets?: unknown[]; lines?: unknown[]; highlights?: unknown[] }>;
  for (const slug of ['spot-the-difference', 'count-and-locate', 'read-the-chart', 'handwritten-maths']) {
    const def = JSON.parse(readFileSync(new URL(`../tests/vision/${slug}.json`, import.meta.url), 'utf8')) as { cases: Array<{ id: string; prompt: string; images: string[]; expected: unknown }> };
    for (const c of def.cases) {
      const l = layouts[`vision/${c.images[0]}`];
      if (slug === 'spot-the-difference') {
        const n = Number(/(\d)×\1/.exec(c.prompt)![1]);
        assert.deepEqual(l!.grids!.map((g) => g.n), [n, n], c.id);
      }
      if (slug === 'count-and-locate') assert.ok(typeof c.expected === 'number' ? l!.targets!.length === c.expected : l!.grids!.length === 1, c.id);
      if (slug === 'read-the-chart') assert.ok(l!.highlights!.length > 0, c.id);
      if (slug === 'handwritten-maths' && c.expected && typeof c.expected === 'object') assert.equal(l!.lines!.length, 5, c.id);
    }
  }
});

test('svg requirements: measurements read the model’s SVG code (transforms included)', () => {
  const good = sr.SVG_CASES.v02!.measure(sr.scanSvg(demoSvg('v02', 'none')));
  assert.deepEqual(good.map((m) => m.ok), [true, true, true]);
  const bad = sr.SVG_CASES.v02!.measure(sr.scanSvg(demoSvg('v02', 'major')));
  assert.equal(bad[0]!.measured, '300°');
  assert.equal(bad[0]!.ok, false);
  // Hands drawn straight up and rotated into place (on the element or a parent group) still measure right.
  const rotated =
    '<svg viewBox="0 0 400 400"><g transform="rotate(51 200 200)"><line x1="200" y1="200" x2="200" y2="50" stroke="#000"/></g><line x1="200" y1="200" x2="200" y2="130" stroke="#000" transform="rotate(304.25, 200, 200)"/><line x1="200" y1="200" x2="200" y2="370" stroke="red"/></svg>';
  assert.deepEqual(
    sr.SVG_CASES.v02!.measure(sr.scanSvg(rotated)).map((m) => [m.measured, m.ok]),
    [
      ['304.25°', true],
      ['51°', true],
      ['180°', true],
    ],
  );
  assert.deepEqual(sr.SVG_CASES.v03!.measure(sr.scanSvg(demoSvg('v03', 'none'))).map((m) => m.ok), [true, true, true, true, true]);
  assert.equal(sr.SVG_CASES.v03!.measure(sr.scanSvg(demoSvg('v03', 'minor')))[3]!.ok, false);
  const chess = sr.SVG_CASES.v04!.measure(sr.scanSvg(demoSvg('v04', 'minor')));
  assert.equal(chess.find((m) => m.label.startsWith('White pawn'))!.measured, 'd5');
  assert.equal(chess.filter((m) => m.ok === false).length, 1);
  assert.equal(sr.SVG_CASES.v04!.measure(sr.scanSvg(demoSvg('v04', 'major'))).at(-1)!.ok, false, 'a1 must be dark');
  assert.equal(sr.svgCaseSpec('visual.svg-illustration', 'v09'), null);
  assert.equal(sr.svgCaseSpec('coding.x', 'v02'), null);
  assert.equal(sr.SVG_CASES.v02!.measure(sr.scanSvg('<svg></svg>'))[0]!.ok, false);
  assert.equal(sr.SVG_CASES.v02!.measure(sr.scanSvg('not even svg <<<'))[0]!.ok, false);
  assert.equal(Math.round(sr.clockAngle(0, -1)), 0);
  assert.equal(Math.round(sr.clockAngle(1, 0)), 90);
});
