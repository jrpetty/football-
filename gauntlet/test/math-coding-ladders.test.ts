// The maths and coding difficulty ladders: Exact Maths: Ladder (math.exact-ladder) and Engineering: Ladder
// (coding.engineering-ladder). Ten frozen levels each; keys/expected replies regenerate byte for byte, every key
// scores 100, each level strictly grows along its documented parameter, scripted wrong answers and do-nothing
// replies score as intended, and the fraction parser forgives presentation but never content.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { loadTests, renderCase, caseScorer, validateTest } from '../src/core/registry.ts';
import { scoreResponse } from '../src/scoring/index.ts';
import { parseFraction, scoreLadder } from '../src/scoring/ladder.ts';
import { checkReply } from '../src/scoring/integrity.ts';
import { createRng } from '../src/core/rng.ts';
import type { PromptTest, ScorerSpec } from '../src/core/types.ts';
// @ts-expect-error: plain JavaScript verification module
import { parseBoard, winProbability } from '../verification/exact-ladder/solver.mjs';
// @ts-expect-error: plain JavaScript verification module
import { LEVELS, opsUpTo } from '../verification/engineering-ladder/spec.mjs';
// @ts-expect-error: plain JavaScript verification module
import { checkWellFormed, expand } from '../verification/engineering-ladder/gen.mjs';

const ALL = loadTests();
const def = (id: string) => ALL.find((t) => t.definition.id === id)!;
const MATH = def('math.exact-ladder').definition as PromptTest;
const CODE = def('coding.engineering-ladder').definition as PromptTest;
const HERE = import.meta.dirname;
const LEVEL_IDS = ['L01', 'L02', 'L03', 'L04', 'L05', 'L06', 'L07', 'L08', 'L09', 'L10'];

const noJudges = { ids: [], ask: async () => [] };
const art = (name: string) => ({ name, kind: 'text' as const, file: name, bytes: 0 });
async function score(scorer: ScorerSpec, expected: unknown, response: string, taskText = '') {
  return scoreResponse({ scorer, expected, response, stopReason: 'end', taskText, judges: noJudges, saveArtifact: art, signal: new AbortController().signal });
}
const js = (file: string) => '```javascript\n' + readFileSync(join(HERE, '..', 'verification', 'engineering-ladder', file), 'utf8') + '\n```';

// ─────────────────────────────── shared ladder contract ───────────────────────────────

test('both are valid ten-level ladders L01..L10 with model-max output', () => {
  for (const id of ['math.exact-ladder', 'coding.engineering-ladder']) {
    const t = def(id);
    const d = t.definition as PromptTest;
    assert.deepEqual(validateTest(d, ALL, { selfFile: t.file }), [], id);
    assert.deepEqual(d.cases.map((c) => c.id), LEVEL_IDS, id);
    assert.equal(d.maxOutputTokens, 'model-max', id);
    assert.ok((d.estimate?.outputTokens ?? 0) >= 20000, `${id}: realistic output estimate`);
  }
  assert.equal(MATH.category, 'math');
  assert.equal(CODE.category, 'coding');
});

// ─────────────────────────────── Exact Maths: Ladder ───────────────────────────────

test('fraction answers: presentation is forgiven, content is not', () => {
  const k = '15/128';
  for (const a of ['15/128', '15 / 128', '30/256', '$\\frac{15}{128}$', '\\boxed{\\dfrac{15}{128}}', 'P = 15/128', '15/128 = 0.1171875', '15/128 ≈ 0.117', '15/128 (about 11.7%)', '(15/128)', '**15/128**.', 'P(0) = 15/128', '15,000/128,000'])
    assert.equal(scoreLadder('fraction', k, `Working...\nFINAL ANSWER: ${a}`).score, 1, a);
  for (const a of ['0.1171875', '15/129', '16/128', '15/128 or 15/127', '15/0', '-15/128', '1/2/3', '15/128 = 16/128', '', 'I cannot do this exactly'])
    assert.equal(scoreLadder('fraction', k, `FINAL ANSWER: ${a}`).score, 0, a);
  assert.equal(scoreLadder('fraction', '1/1', 'FINAL ANSWER: 1').score, 1, 'a whole number is n/1');
  assert.deepEqual(parseFraction('12 / 34'), { p: 12n, q: 34n });
  // big keys are compared exactly (a float comparison would call these equal)
  const big = MATH.cases[9]!.expected as string;
  const [p, q] = big.split('/');
  assert.equal(scoreLadder('fraction', big, `FINAL ANSWER: ${BigInt(p!) + 1n}/${q}`).score, 0);
  assert.equal(scoreLadder('fraction', big, `FINAL ANSWER: ${BigInt(p!) * 7n}/${BigInt(q!) * 7n}`).score, 1);
});

test('every Exact Maths key scores 100%, and the scripted wrong answers score 0', async () => {
  const lures = JSON.parse(readFileSync(join(HERE, '..', 'verification', 'exact-ladder', 'snakes_cases.json'), 'utf8')) as Array<{ answer: string; lures: Record<string, string> }>;
  for (const [i, c] of MATH.cases.entries()) {
    const sc = caseScorer(MATH, c);
    const task = renderCase(MATH, c).turns.join('\n');
    assert.match(task, /FINAL ANSWER: <answer>$/, c.id);
    assert.equal((await score(sc, c.expected, `FINAL ANSWER: ${c.expected}`, task)).score, 1, c.id);
    assert.equal(checkReply(`FINAL ANSWER: ${c.expected}`, { taskText: task, manual: true }), null, `${c.id}: the honest answer passes fair play`);
    const [p, q] = String(c.expected).split('/');
    const wrong = [`${BigInt(p!) + 1n}/${q}`, `${p}/${BigInt(q!) + 1n}`, (Number(p) / Number(q)).toPrecision(12), ...Object.values(lures[i]!.lures), `${c.expected} or ${p}/${BigInt(q!) * 2n}`];
    for (const w of wrong) assert.equal((await score(sc, c.expected, `FINAL ANSWER: ${w}`, task)).score, 0, `${c.id}: ${w}`);
  }
});

test('every Exact Maths key is re-derived from the prompt text by the independent BigInt solver', () => {
  for (const c of MATH.cases) {
    const r = winProbability(parseBoard(c.prompt!)) as { p: bigint; q: bigint };
    assert.equal(`${r.p}/${r.q}`, c.expected, c.id);
  }
});

test('Exact Maths: every level is a longer board with more snakes, ladders and traps, and a longer exact answer', () => {
  const boards = MATH.cases.map((c) => parseBoard(c.prompt!) as { N: number; d: number; ladders: Map<number, number>; snakes: Map<number, number>; traps: Set<number> });
  const specials = boards.map((b) => b.ladders.size + b.snakes.size + b.traps.size);
  const digits = MATH.cases.map((c) => String(c.expected).split('/')[1]!.length);
  for (let i = 1; i < 10; i++) {
    assert.ok(boards[i]!.N > boards[i - 1]!.N, `L${i + 1}: longer track`);
    assert.ok(specials[i]! > specials[i - 1]!, `L${i + 1}: more special squares`);
    assert.ok(boards[i]!.snakes.size >= boards[i - 1]!.snakes.size, `L${i + 1}: no fewer snake loops`);
    assert.ok(boards[i]!.d >= boards[i - 1]!.d, `L${i + 1}: no smaller spinner`);
    assert.ok(digits[i]! > digits[i - 1]!, `L${i + 1}: longer denominator`);
  }
  assert.equal(boards[0]!.snakes.size, 0, 'level 1 has no loop');
  assert.ok(boards[2]!.snakes.size >= 1, 'loops (simultaneous equations) from level 3');
});

test('Exact Maths: random guessing and filler score 0', async () => {
  const words = 'the probability is about one third after careful thought'.split(' ');
  let sum = 0;
  let n = 0;
  for (const c of MATH.cases) {
    for (let k = 0; k < 6; k++) {
      const rng = createRng(31 + k * 7 + n);
      const filler = Array.from({ length: 30 + rng.int(0, 40) }, () => rng.pick(words)).join(' ');
      const guesses = [`FINAL ANSWER: ${rng.int(0, 100)}`, `FINAL ANSWER: ${rng.int(1, 9)}/${rng.int(10, 99)}`, `${filler}\nFINAL ANSWER: 1/3`, filler, '', `FINAL ANSWER: ${rng.next().toFixed(6)}`];
      sum += (await score(caseScorer(MATH, c), c.expected, guesses[k]!)).score ?? 0;
      n++;
    }
  }
  assert.equal(sum, 0);
});

test('Exact Maths: the generator rebuilds the boards and the test file byte for byte', { timeout: 120_000 }, () => {
  if (spawnSync('python3', ['--version']).status !== 0) return; // no Python on this machine
  const dir = join(HERE, '..', 'verification', 'exact-ladder');
  const out = mkdtempSync(join(tmpdir(), 'gauntlet-exact-'));
  try {
    let r = spawnSync('python3', ['snakes.py', '--out', out], { cwd: dir, encoding: 'utf8' });
    assert.equal(r.status, 0, r.stderr);
    r = spawnSync('python3', ['build.py', '--cases', join(out, 'snakes_cases.json'), '--out', join(out, 't.json')], { cwd: dir, encoding: 'utf8' });
    assert.equal(r.status, 0, r.stderr);
    assert.equal(readFileSync(join(out, 'snakes_cases.json'), 'utf8'), readFileSync(join(dir, 'snakes_cases.json'), 'utf8'));
    assert.equal(readFileSync(join(out, 't.json'), 'utf8'), readFileSync(join(HERE, '..', 'tests', 'math', 'exact-ladder.json'), 'utf8'));
  } finally {
    rmSync(out, { recursive: true, force: true });
  }
});

// ─────────────────────────────── Engineering: Ladder ───────────────────────────────

type CodeKey = { functionName: string; tests: Array<{ args: [Record<string, unknown>]; expected: unknown }> };
const QUERY_OPS = new Set(['sum', 'min', 'max', 'hmax', 'hmin']);

test('Engineering: each level adds an operation and its rules on top of the level below', () => {
  for (let i = 0; i < 10; i++) {
    const c = CODE.cases[i]!;
    const L = LEVELS[i];
    const sc = caseScorer(CODE, c) as Extract<ScorerSpec, { type: 'code-js' }>;
    assert.equal(sc.type, 'code-js');
    assert.equal(sc.timeoutMs, L.timeoutMs, c.id);
    assert.match(c.prompt!, new RegExp(`time limit of about ${L.timeoutMs / 1000} seconds per test`), `${c.id}: the prompt states its time limit`);
    assert.equal((c.expected as CodeKey).functionName, 'runEngine');
    for (const op of L.adds) assert.ok(c.prompt!.includes(`["${op}"`), `${c.id} specifies ${op}`);
    if (i === 0) continue;
    const prev = CODE.cases[i - 1]!;
    for (let l = 1; l <= i; l++) {
      const head = `**Level ${l}**`;
      const sec = prev.prompt!.slice(prev.prompt!.indexOf(head)).split('\n\n')[0]!;
      assert.ok(c.prompt!.includes(sec), `${c.id} keeps the level ${l} section word for word`);
    }
    assert.ok(opsUpTo(i + 1).length > opsUpTo(i).length, `${c.id}: more operations`);
    assert.ok(L.maxN >= LEVELS[i - 1].maxN && L.maxQ >= LEVELS[i - 1].maxQ, `${c.id}: no smaller inputs`);
    assert.ok(L.timeoutMs >= LEVELS[i - 1].timeoutMs, `${c.id}: no tighter time limit`);
  }
});

test('Engineering: every hidden test is well-formed, uses only its level\'s operations, and the new ones appear', () => {
  for (const [i, c] of CODE.cases.entries()) {
    const key = c.expected as CodeKey;
    assert.ok(key.tests.length >= 10 && key.tests.length <= 30, `${c.id}: ${key.tests.length} hidden tests`);
    const used = new Set<string>();
    for (const t of key.tests) {
      checkWellFormed(i + 1, t.args[0]);
      const inp = t.args[0] as { ops?: string[][]; generate?: { mix: string[][] } };
      for (const o of inp.ops ?? inp.generate!.mix) used.add(o[0]!);
      const [count, checksum] = t.expected as [number, number];
      assert.ok(Number.isInteger(count) && Number.isInteger(checksum) && checksum >= 0 && checksum < 1000000007);
    }
    for (const op of LEVELS[i].adds) assert.ok(used.has(op), `${c.id} tests its new operation ${op}`);
    assert.ok([...used].some((o) => QUERY_OPS.has(o)));
    if (i >= 1) assert.ok(key.tests.some((t) => (t.args[0] as { generate?: { n: number } }).generate?.n === LEVELS[i].maxN), `${c.id}: the largest size is tested`);
  }
});

test('Engineering: the prompt\'s generator example and checksum example are right', () => {
  const p = CODE.cases[1]!.prompt!;
  const m = /Example: `(\{"generate".*?\})` produces the array (\[.*?\]) and then the operations (\[\[.*?\]\]) /.exec(p)!;
  const ex = expand(JSON.parse(m[1]!).generate);
  assert.deepEqual([ex.values, ex.ops], [JSON.parse(m[2]!), JSON.parse(m[3]!)]);
  let c = 0;
  for (const v of [5, -1, 12]) c = (c * 1000003 + (((v % 1000000007) + 1000000007) % 1000000007)) % 1000000007;
  assert.ok(p.includes(`the result is \`[3, ${c}]\``), 'checksum example');
});

test('Engineering: the reference passes every hidden test of every level through the real scorer', { timeout: 600_000 }, async () => {
  for (const c of CODE.cases) {
    const task = renderCase(CODE, c).turns.join('\n');
    const code = readFileSync(join(HERE, '..', 'verification', 'engineering-ladder', 'ref.js'), 'utf8');
    assert.equal(checkReply('```javascript\n' + code + '\n```', { taskText: task, code: [code] }), null, `${c.id}: fair play`);
    const out = await score(caseScorer(CODE, c), c.expected, js('ref.js'), task);
    assert.equal(out.score, 1, `${c.id}: ${out.summary}`);
  }
});

test('Engineering: do-nothing and stub solutions score 0 on every level (stub gate)', { timeout: 120_000 }, async () => {
  for (const c of CODE.cases) {
    const task = renderCase(CODE, c).turns.join('\n');
    for (const f of ['solutions/stub.js', 'solutions/empty.js']) assert.equal((await score(caseScorer(CODE, c), c.expected, js(f), task)).score, 0, `${c.id} ${f}`);
    // a lookup table of the prompt's own examples passes no hidden test that counts
    const examples = [...c.prompt!.matchAll(/^runEngine\((.*)\)\n\/\/ returns (\[[^\]]*\])/gm)].map((m) => [m[1], m[2]]);
    const table = 'function runEngine(input) {\n  const k = JSON.stringify(input);\n' + examples.map(([a, e]) => `  if (k === JSON.stringify(${a})) return ${e};`).join('\n') + '\n  return [0, 0];\n}';
    assert.equal((await score(caseScorer(CODE, c), c.expected, '```javascript\n' + table + '\n```', task)).score, 0, `${c.id}: examples table`);
    assert.equal((await score(caseScorer(CODE, c), c.expected, 'I would use a segment tree.', task)).score, 0, `${c.id}: no code`);
  }
});

test('Engineering: the scripted solutions behave as described on the cheap levels', { timeout: 300_000 }, async () => {
  const at = async (id: string, file: string) => {
    const c = CODE.cases.find((x) => x.id === id)!;
    return (await score(caseScorer(CODE, c), c.expected, js(file), renderCase(CODE, c).turns.join('\n'))).score ?? 0;
  };
  // the plain lazy segment tree is right and fast enough below the specialist levels, and so is the reference design with slips
  for (const id of ['L01', 'L02', 'L03', 'L04']) {
    assert.equal(await at(id, 'solutions/plain.js'), 1, `${id} plain`);
    assert.equal(await at(id, 'solutions/slips.js'), 1, `${id} slips`);
  }
  assert.ok((await at('L01', 'solutions/careless.js')) < 1, 'careless loses a test with a negative answer on level 1');
  assert.equal(await at('L01', 'solutions/naive.js'), 1, 'a loop per operation is fine on level 1');
});

test('Engineering: the measured spread is recorded, and every limit is at least 16 times the reference\'s slowest test', () => {
  const meas = JSON.parse(readFileSync(join(HERE, '..', 'verification', 'engineering-ladder', 'measurements.json'), 'utf8')) as Record<string, { ref: { maxMs: number }; spread: Record<string, { score: number }> }>;
  for (const [i, c] of CODE.cases.entries()) {
    const m = meas[c.id]!;
    assert.ok(m, c.id);
    assert.equal(m.spread.ref!.score, 1, `${c.id}: reference 100%`);
    assert.ok(m.ref.maxMs * 16 <= LEVELS[i].timeoutMs, `${c.id}: reference ${m.ref.maxMs} ms vs limit ${LEVELS[i].timeoutMs} ms`);
    assert.equal(m.spread.stub!.score, 0);
    assert.equal(m.spread.empty!.score, 0);
    if (i >= 4) assert.ok(m.spread.plain!.score < 1, `${c.id}: the plain lazy segment tree does not pass`);
    if (i >= 4) assert.ok(m.spread.slips!.score < 1, `${c.id}: the slip of this level costs tests`);
    if (i >= 1) assert.ok(m.spread.naive!.score < 1, `${c.id}: a loop per operation does not pass`);
    assert.match(c.notes!, /reference slowest test \d+ ms/, `${c.id}: notes carry the timing`);
  }
});

test('Engineering: the generator rebuilds the test file byte for byte', { timeout: 300_000 }, () => {
  const out = mkdtempSync(join(tmpdir(), 'gauntlet-eng-'));
  try {
    const r = spawnSync(process.execPath, ['gen.mjs', '--out', join(out, 't.json')], { cwd: join(HERE, '..', 'verification', 'engineering-ladder'), encoding: 'utf8' });
    assert.equal(r.status, 0, r.stderr);
    assert.ok(readFileSync(join(out, 't.json'), 'utf8') === readFileSync(join(HERE, '..', 'tests', 'coding', 'engineering-ladder.json'), 'utf8'), 'identical');
  } finally {
    rmSync(out, { recursive: true, force: true });
  }
});
