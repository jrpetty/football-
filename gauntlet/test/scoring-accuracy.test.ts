// Regression tests for the scorers accuracy audit: realistic model reply formats must be marked right,
// hedges, stubs and gaming must be marked wrong.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { extractFinalAnswer, parseAnswerNumber, parseJsonLoose, parseNumber, stripLatex, stripReasoning } from '../src/core/extract.ts';
import { exactMatches, isHedged, readChoice, scoreResponse, solutionBlock } from '../src/scoring/index.ts';
import { compareJson } from '../src/scoring/json-compare.ts';
import { jsonEqual, promptExampleArgs, runCodeTests } from '../src/scoring/code-sandbox.ts';
import type { ScorerSpec } from '../src/core/types.ts';

const judges = { ids: [], ask: async () => [] };
async function score(scorer: ScorerSpec, expected: unknown, response: string, taskText = ''): Promise<number | null> {
  const r = await scoreResponse({ scorer, expected, response, stopReason: 'end', taskText, judges, saveArtifact: (name: string) => ({ name, kind: 'text' as const, file: name, bytes: 0 }), signal: new AbortController().signal });
  return r.score;
}

test('LaTeX around a final answer is unwrapped: \\boxed, \\text, $…$, {,} and \\, separators, \\frac', async () => {
  assert.equal(extractFinalAnswer('FINAL ANSWER: $\\boxed{\\text{Eris}}$').answer, 'Eris');
  assert.equal(stripLatex('6{,}589{,}440'), '6,589,440');
  assert.equal(stripLatex('6\\,589\\,440'), '6589440');
  assert.equal(stripLatex('\\frac{3}{4}'), '3/4');
  assert.equal(stripLatex('$1,234'), '$1,234', 'a lone dollar sign is currency, not LaTeX');
  const NUM: ScorerSpec = { type: 'number', tolerance: 0 };
  assert.equal(await score(NUM, 6589440, 'FINAL ANSWER: $6{,}589{,}440$'), 1);
  assert.equal(await score(NUM, 6589440, 'FINAL ANSWER: 6\\,589\\,440'), 1);
  assert.equal(await score(NUM, 6589440, 'FINAL ANSWER: 6 589 440'), 1);
  assert.equal(await score({ type: 'exact', normalize: 'alnum' }, ['1911'], 'FINAL ANSWER: \\boxed{1911}'), 1);
  assert.equal(await score({ type: 'number', tolerance: 0.001 }, 0.75, 'FINAL ANSWER: $\\frac{3}{4}$'), 1);
});

test('number answers: a fraction counts only as the first number; stated results; number words', async () => {
  assert.equal(parseNumber('50 (i.e. 1/2 of the time)'), 50);
  assert.equal(parseNumber('3/4'), 0.75);
  assert.equal(parseAnswerNumber('2π ≈ 6.28'), 6.28);
  assert.equal(parseAnswerNumber('x = 16'), 16);
  assert.equal(parseAnswerNumber('3/8 = 37.5%'), 37.5);
  assert.equal(parseAnswerNumber('1835 with m = 1234 and n = 601'), 1835, 'an explanation after the answer is not the answer');
  assert.equal(parseAnswerNumber('16 (15 × 1 + 5 = 20 m)'), 16);
  assert.equal(parseAnswerNumber('1024 = 2^10'), 1024);
  assert.equal(parseAnswerNumber('twenty-one'), 21);
  assert.equal(parseAnswerNumber('no one'), null);
  assert.equal(await score({ type: 'number', tolerance: 0.006 }, 6.28, 'FINAL ANSWER: 2π ≈ 6.28'), 1);
  assert.equal(await score({ type: 'number', tolerance: 0 }, 50, 'FINAL ANSWER: 50 (i.e. 1/2 of the time)'), 1);
  assert.equal(await score({ type: 'number', tolerance: 0 }, 21, 'FINAL ANSWER: 21 = 22'), 0);
});

test('number hedges: a bare list of different numbers is several answers; thousands separators are not', async () => {
  assert.equal(isHedged('21, 22', 'number'), true);
  assert.equal(isHedged('21; 22', 'number'), true);
  assert.equal(isHedged('21 and 22', 'number'), true);
  assert.equal(isHedged('6,589,440', 'number'), false);
  assert.equal(isHedged('1,234,567', 'number'), false);
  assert.equal(await score({ type: 'number', tolerance: 0 }, 21, 'FINAL ANSWER: 21, 22'), 0);
  assert.equal(await score({ type: 'number', tolerance: 0 }, 1234567, 'FINAL ANSWER: 1,234,567'), 1);
});

test('choice: the chosen letter followed by its own option text is not a hedge, even with capital letters in it', async () => {
  const m35 = 'Which pattern is more likely to appear first?\n(A) H, T, H\n(B) H, H, T\n(C) They are equally likely\n\nAnswer with the letter of the correct option.';
  const m34 = 'Which is true now?\n(A) There is more wine in glass A than water in glass B\n(B) There is more water in glass B than wine in glass A\n(C) The amount of wine in A equals the amount of water in B';
  const CH: ScorerSpec = { type: 'choice' };
  assert.equal(await score(CH, 'B', 'FINAL ANSWER: B) H, H, T', m35), 1);
  assert.equal(await score(CH, 'C', 'FINAL ANSWER: C. The amount of wine in A equals the amount of water in B', m34), 1);
  assert.equal(await score(CH, 'B', "FINAL ANSWER: I'd say B", m35), 1);
  assert.equal(await score(CH, 'C', 'FINAL ANSWER: Option C', m34), 1);
  assert.equal(await score(CH, 'C', 'FINAL ANSWER: $\\boxed{C}$', m34), 1);
  assert.equal(await score(CH, 'C', 'FINAL ANSWER: The amount of wine in A equals the amount of water in B', m34), 1, 'the exact text of one option');
  // Hedges and contradictions stay wrong.
  assert.equal(await score(CH, 'C', 'FINAL ANSWER: A or C', m34), 0);
  assert.equal(await score(CH, 'C', 'FINAL ANSWER: A, B, C', m34), 0);
  assert.equal(await score(CH, 'B', 'FINAL ANSWER: B/C', m35), 0);
  assert.equal(await score(CH, 'C', 'FINAL ANSWER: C) There is more wine in glass A than water in glass B', m34), 0, 'letter C with the text of option A');
  assert.deepEqual(readChoice('B', ''), { letter: 'B', hedged: false });
});

test('exact: list answers forgive a closing "and" and item articles; value keys forgive words around the one value', async () => {
  const EX: ScorerSpec = { type: 'exact', normalize: 'alnum' };
  assert.equal(await score(EX, ['Maeve, Hana, Diego, Tobias'], 'FINAL ANSWER: Maeve, Hana, Diego, and Tobias'), 1);
  assert.equal(await score(EX, ['Maeve, Hana, Diego, Tobias'], 'FINAL ANSWER: Maeve, Hana, Diego and Tobias'), 1);
  assert.equal(await score(EX, ['Maeve, Hana, Diego, Tobias'], 'FINAL ANSWER: Hana, Maeve, Diego, Tobias'), 0);
  const grid = ['Norway, Latvia, Peru, Chile, Egypt, Kenya, Japan; comic, thriller, memoir, atlas, biography, poetry, cookbook'];
  assert.equal(await score(EX, grid, 'FINAL ANSWER: Norway, Latvia, Peru, Chile, Egypt, Kenya, Japan; a comic, thriller, the memoir, an atlas, biography, poetry, cookbook'), 1);
  assert.equal(await score(EX, grid, 'FINAL ANSWER: Norway, Latvia, Peru, Chile, Egypt, Kenya, Japan; an atlas, thriller, memoir, a comic, biography, poetry, cookbook'), 0);
  assert.equal(await score(EX, ['11:00'], 'FINAL ANSWER: 11:00 local time'), 1);
  assert.equal(await score(EX, ['2027-11-23'], 'FINAL ANSWER: 2027-11-23 (a Tuesday)'), 1);
  assert.equal(await score(EX, ['11:00'], 'FINAL ANSWER: 11:00 or 11:30'), 0);
  assert.equal(await score(EX, ['11:00'], 'FINAL ANSWER: 11:00 pm'), 0);
  assert.equal(await score(EX, ['1911'], 'FINAL ANSWER: 1911, not 1903'), 0);
  assert.equal(exactMatches('FALSE PREMISE', '1911', 'alnum'), false);
});

test('a reasoning block printed into the reply (<think>…</think>) is not scored', async () => {
  const lightning: ScorerSpec = { type: 'regex', fullText: true, flags: 'i' };
  const pattern = "^[\\s*_#>]*final\\s+answer[\\s*_]*[:：][\\s*_\\\"'`]*(?:31(?:\\s*posts?)?)[\\s*_\\\"'`.!]*$";
  assert.equal(await score(lightning, pattern, '<think>\n41 posts, 10 broken.\n</think>\n\nFINAL ANSWER: 31'), 1);
  assert.equal(await score(lightning, pattern, '41 posts minus 10.\nFINAL ANSWER: 31'), 0, 'visible working still breaks the one-line rule');
  assert.equal(await score({ type: 'number', tolerance: 0 }, 21, '<think>\nI guess FINAL ANSWER: 21\n</think>\n'), 0, 'an answer only inside the reasoning is no answer');
  assert.equal(stripReasoning('<think>unclosed'), '');
  assert.equal(stripReasoning('no tags'), 'no tags');
});

test('JSON at the end of a reply is found even when the prose echoes the format or contains brackets', async () => {
  const exp = { cells: ['B2', 'D3'] };
  const reply = 'The format is {"cells": ["A1", "C4"]}. Comparing [row 1] and [row 2]...\n\n{"cells": ["D3", "B2"]}';
  assert.deepEqual(parseJsonLoose(reply, exp), { cells: ['D3', 'B2'] });
  assert.equal(await score({ type: 'json', unorderedArrays: true, allOrNothing: true }, exp, reply), 1);
  assert.deepEqual(parseJsonLoose('Line 2 is wrong.\n{"line": 2, "x": 13}\n[1] see above', { line: 2, x: 13 }), { line: 2, x: 13 });
  assert.deepEqual(parseJsonLoose('Draft: {"line": 3, "x": 12}\nFinal: {"line": 2, "x": 13}', { line: 2, x: 13 }), { line: 2, x: 13 });
});

test('JSON compare: a missing key counts as null only inside an object the answer gave', () => {
  assert.equal(compareJson({ a: null, b: 1 }, {}).matched, 0, '{} earns nothing');
  assert.equal(compareJson({ a: null, b: 1 }, { b: 1 }).matched, 2, 'an omitted null key in a real object still counts');
  const r = compareJson({ items: [{ id: 1, due: null }, { id: 2, due: null }] }, { items: [{ id: 1, due: null }] });
  // length ✗, items[0].id ✓, items[0].due ✓, items[1].id ✗, items[1].due ✗ (the item is missing)
  assert.equal(r.matched, 2);
  assert.equal(r.total, 5);
});

test('Messy Text to Exact JSON: a number off by less than 0.5% is wrong', () => {
  const t = JSON.parse(readFileSync(new URL('../tests/extraction/structured-json.json', import.meta.url), 'utf8'));
  const e01 = t.cases.find((c: { id: string }) => c.id === 'e01');
  const wrongTotal = { ...e01.expected, total: 1127.2, tax_amount: 188 };
  const r = compareJson(e01.expected, wrongTotal, { numberTolerance: t.scorer.numberTolerance });
  assert.equal(r.matched, r.total - 2);
});

test('code sandbox: exact numbers; Infinity, NaN and undefined never pass as null', { timeout: 60_000 }, async () => {
  assert.ok(!jsonEqual(0.1 + 0.2, 0.3), 'floating-point results are compared exactly');
  assert.ok(jsonEqual(0.1 + 0.2, 0.3, 1e-6), 'an explicit tolerance still works');
  assert.ok(!jsonEqual(-2147483647, -2147483648));
  assert.ok(!jsonEqual(1000000000001, 1000000000000));
  assert.ok(jsonEqual(-0, 0));
  const tests = [{ args: [1, 0], expected: null }, { args: [0, 0], expected: null }, { args: [], expected: [null] }];
  const inf = await runCodeTests('function f(a, b) { return b === undefined ? [undefined] : a / b; }', 'f', tests);
  assert.equal(inf.passed, 0);
  assert.match(inf.items[0]!.detail ?? '', /Infinity/);
});

test('code sandbox: host-realm Function is unreachable (no escape to process, network or stdout)', { timeout: 60_000 }, async () => {
  for (const code of [
    "function f(){ return typeof console.log.constructor('return process')(); }",
    "function f(){ return typeof module.constructor.constructor('return process')(); }",
    "function f(){ return typeof this.constructor.constructor('return process')(); }",
  ]) {
    const r = await runCodeTests(code, 'f', [{ args: [], expected: 'object' }]);
    assert.equal(r.passed, 0, code);
  }
  // Faking the worker's result line would need process.stdout.
  const fake = await runCodeTests("function f(){ const p = console.log.constructor('return process')(); p.stdout.write(JSON.stringify({loadError:null,results:[{ok:true,output:'42'}]})); p.exit(0); }", 'f', [{ args: [], expected: 42 }]);
  assert.equal(fake.passed, 0);
});

test('code-js: a usage example after the solution does not replace it', () => {
  const reply = 'Solution:\n```javascript\nfunction add(a, b) { return a + b; }\n```\n\nExample usage:\n```javascript\nconsole.log(add(1, 2));\n```';
  assert.match(solutionBlock(reply, 'add')!.code, /return a \+ b/);
  assert.match(solutionBlock('```js\nconst add = (a, b) => a + b;\n```', 'add')!.code, /=>/);
});

test('code-js: stubs, echoes and tables of the visible examples score 0; real partial attempts keep their credit', { timeout: 120_000 }, async () => {
  const prompt = 'Write a JavaScript function `toRoman(n)`.\n\nExamples:\n```javascript\ntoRoman(4)  // returns "IV"\ntoRoman(0)  // returns null\n```';
  const expected = {
    functionName: 'toRoman',
    tests: [
      { args: [4], expected: 'IV' },
      { args: [0], expected: null },
      { args: [-1], expected: null },
      { args: [5000], expected: null },
      { args: [9], expected: 'IX' },
      { args: [3], expected: 'III' },
      { args: [1994], expected: 'MCMXCIV' },
    ],
  };
  assert.deepEqual([...promptExampleArgs(prompt, 'toRoman')].sort(), ['[0]', '[4]']);
  const scorer: ScorerSpec = { type: 'code-js', timeoutMs: 2000 };
  const run = (code: string) => score(scorer, expected, '```javascript\n' + code + '\n```', `[User]\n${prompt}`);
  assert.equal(await run('function toRoman() { return null; }'), 0, 'constant stub (would pass 3/7)');
  assert.equal(await run('function toRoman(n) { return n; }'), 0, 'echo');
  assert.equal(await run('function toRoman(n) { return n === 4 ? "IV" : null; }'), 0, 'table of the visible examples');
  // A genuine but buggy attempt (no validation, no subtractive forms beyond IV) keeps its fraction.
  const partial = await run('function toRoman(n) { if (n === 4) return "IV"; let s = ""; for (const [v, c] of [[1000, "M"], [500, "D"], [100, "C"], [50, "L"], [10, "X"], [5, "V"], [1, "I"]]) while (n >= v) { s += c; n -= v; } return s || null; }');
  assert.equal(partial, 0.5714);
});
