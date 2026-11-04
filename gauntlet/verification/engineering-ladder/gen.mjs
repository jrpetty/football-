// Builds tests/coding/engineering-ladder.json (Engineering: Ladder) from the specification (spec.mjs), the tests
// (cases.mjs) and the reference (ref.js). Byte-identical on every run.
//
//   node gen.mjs              # write the test file (expected results from ref.js); --out FILE for a dry run
//   node verify.mjs           # every hidden test and example re-checked by brute.py (small) and brute.c (all)
//   node fuzz.mjs 3000        # ref.js (two block sizes), brute.py and brute.c agree on 30,000 random inputs
//   node spread.mjs           # real-sandbox timings and the spread of scripted solutions -> measurements.json
//
// The levels are FROZEN once published: never regenerate a published version (add L11+ in a new version instead).
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
import { LEVELS, FN, prompt, opsUpTo, QUERIES } from './spec.mjs';
import { EXAMPLES, GEN_EXAMPLE, levelTests } from './cases.mjs';
import { load } from './lib.mjs';

const ref = load('./ref.js');
const outArg = process.argv.indexOf('--out');
const OUT = outArg > 0 ? pathToFileURL(process.argv[outArg + 1]) : new URL('../../tests/coding/engineering-ladder.json', import.meta.url);
const MEAS = new URL('./measurements.json', import.meta.url);
const measurements = fs.existsSync(MEAS) ? JSON.parse(fs.readFileSync(MEAS, 'utf8')) : {};
const TAKES_VALUE = new Set(['add', 'assign', 'chmin', 'chmax', 'div']);

/** The values and operations a generator description stands for (the prompt's algorithm, written out). */
export function expand(g) {
  let state = g.seed;
  const rand = (k) => (state = (state * 48271) % 2147483647) % k;
  const values = Array.from({ length: g.n }, () => g.lo + rand(g.hi - g.lo + 1));
  const W = g.mix.reduce((s, e) => s + e[1], 0);
  const ops = [];
  for (let j = 0; j < g.q; j++) {
    let w = rand(W), e = g.mix[0];
    for (const c of g.mix) { if (w < c[1]) { e = c; break; } w -= c[1]; }
    if (e[0] === 'set') { const i = rand(g.n); ops.push(['set', i, e[2] + rand(e[3] - e[2] + 1)]); continue; }
    const u = rand(g.n), t = rand(g.n);
    ops.push([e[0], Math.min(u, t), Math.max(u, t), ...(e.length > 2 ? [e[2] + rand(e[3] - e[2] + 1)] : [])]);
  }
  return { values, ops };
}

/** Every input is well-formed and uses only the level's operations (the prompt promises this). */
export function checkWellFormed(level, input) {
  const L = LEVELS[level - 1];
  const allowed = new Set(opsUpTo(level));
  const fail = (why) => { throw new Error(`L${level}: ${why}`); };
  const int = (x) => Number.isSafeInteger(x);
  if (input.generate) {
    const g = input.generate;
    if (level < 2) fail('generated input below level 2');
    if (!(int(g.seed) && g.seed >= 1 && g.seed <= 2147483646)) fail('bad seed');
    if (!(int(g.n) && g.n >= 1 && g.n <= L.maxN && int(g.q) && g.q >= 0 && g.q <= L.maxQ)) fail('bad size');
    if (!(int(g.lo) && int(g.hi) && g.lo <= g.hi && g.lo >= -1e9 && g.hi <= 1e9)) fail('bad lo/hi');
    for (const e of g.mix) {
      if (!allowed.has(e[0])) fail(`mix entry ${e[0]} not in this level`);
      if (!(int(e[1]) && e[1] >= 1)) fail('bad weight');
      const wantValue = e[0] === 'set' || TAKES_VALUE.has(e[0]);
      if (wantValue !== (e.length === 4)) fail(`mix entry ${e[0]}: value range ${wantValue ? 'missing' : 'not allowed'}`);
      if (wantValue && !(int(e[2]) && int(e[3]) && e[2] <= e[3] && (e[0] !== 'div' || e[2] >= 2))) fail(`mix entry ${e[0]}: bad range`);
    }
    return;
  }
  const n = input.values.length;
  if (!(n >= 1 && n <= L.maxN && input.ops.length <= L.maxQ)) fail('bad explicit size');
  if (!input.values.every((v) => int(v) && Math.abs(v) <= 1e9)) fail('bad value');
  for (const o of input.ops) {
    if (!allowed.has(o[0])) fail(`op ${o[0]} not in this level`);
    if (o[0] === 'set') { if (!(o.length === 3 && int(o[1]) && o[1] >= 0 && o[1] < n && int(o[2]))) fail(`bad ${JSON.stringify(o)}`); continue; }
    const wantValue = TAKES_VALUE.has(o[0]);
    if (o.length !== (wantValue ? 4 : 3) || !(int(o[1]) && int(o[2]) && 0 <= o[1] && o[1] <= o[2] && o[2] < n)) fail(`bad ${JSON.stringify(o)}`);
    if (wantValue && !int(o[3])) fail(`bad value ${JSON.stringify(o)}`);
    if (o[0] === 'div' && o[3] < 2) fail('div by less than 2');
  }
}

/** The individual query answers of a (small) input, by direct simulation, for the prompt's examples. */
export function answersOf(input) {
  const { values, ops } = input.generate ? expand(input.generate) : input;
  const a = values.slice(), H = values.slice(), G = values.slice(), out = [];
  for (const o of ops) {
    const [op, l, r, x] = o[0] === 'set' ? ['set', o[1], o[1], o[2]] : o;
    if (QUERIES.has(op)) {
      const seg = (op === 'hmax' ? H : op === 'hmin' ? G : a).slice(l, r + 1);
      out.push(op === 'sum' ? seg.reduce((s, v) => s + v, 0) : op === 'min' || op === 'hmin' ? Math.min(...seg) : Math.max(...seg));
      continue;
    }
    for (let i = l; i <= r; i++) {
      if (op === 'hreset') { H[i] = G[i] = a[i]; continue; }
      a[i] = op === 'set' || op === 'assign' ? x : op === 'add' ? a[i] + x : op === 'chmin' ? Math.min(a[i], x) : op === 'chmax' ? Math.max(a[i], x) : Math.floor(a[i] / x);
      H[i] = Math.max(H[i], a[i]); G[i] = Math.min(G[i], a[i]);
    }
  }
  return out;
}

export function buildLevel(level) {
  const exInputs = [...EXAMPLES[level], ...(level >= 2 ? [[{ generate: GEN_EXAMPLE }]] : [])];
  const examples = exInputs.map((args) => { checkWellFormed(level, args[0]); return [args, ref(...args), answersOf(args[0])]; });
  const tests = levelTests(level).map((t) => { checkWellFormed(level, t.input); return { label: t.label, args: [t.input], expected: ref(t.input) }; });
  return { examples, tests };
}

function main() {
  const genExample = { input: GEN_EXAMPLE, ...expand(GEN_EXAMPLE) };
  const cases = [];
  const labels = {};
  for (const L of LEVELS) {
    const { examples, tests } = buildLevel(L.level);
    const id = `L${String(L.level).padStart(2, '0')}`;
    labels[id] = tests.map((t) => t.label);
    const m = measurements[id];
    const gens = tests.filter((t) => t.args[0].generate && t.args[0].generate.n >= 100000).length;
    const notes =
      `[level ${L.level}] Adds ${L.adds.join(', ')} (${opsUpTo(L.level).length} operations in all, ${opsUpTo(L.level).filter((o) => !QUERIES.has(o)).length} kinds of update). ` +
      `${L.level === 1 ? `Explicit inputs only (n and operations up to ${L.maxN.toLocaleString('en-GB')})` : `Explicit inputs and generated ones up to n = ${L.maxN.toLocaleString('en-GB')} with ${L.maxQ.toLocaleString('en-GB')} operations`}; ${tests.length} hidden tests (hand-made rule tests, random inputs over narrow value bands, ${gens} large generated). ` +
      'Expected results from verification/engineering-ladder/ref.js (segment tree beats with three element classes and historic tags; one reference for every level, as each level only adds operations). ' +
      'Every test and example re-checked by the C brute force brute.c (64-bit integers, one loop per operation), and every one small enough by the Python brute force brute.py; ' +
      'ref.js (with blocks of 64 and of 2), brute.py and brute.c agree on 30,000 random inputs (fuzz.mjs). ' +
      (m ? `Real sandbox (${m.platform}): reference slowest test ${m.ref.maxMs} ms vs ${L.timeoutMs} ms limit; ${m.summary}.` : 'Sandbox timings: run spread.mjs.') +
      (L.level === 1 ? " Ladder: ten frozen levels (L01 easiest, L10 hardest); each level's specification contains every rule of the levels below plus a new operation. Every level is worth the same, so the test score is the share of the ladder climbed; the headline is the highest level passed reliably." : '');
    cases.push({
      id,
      prompt: prompt(L.level, examples, genExample),
      expected: { functionName: FN, tests: tests.map(({ args, expected }) => ({ args, expected })) },
      scorer: { type: 'code-js', timeoutMs: L.timeoutMs },
      notes,
    });
  }
  const tok = (s) => Math.ceil(s.length / 3.6) + 20;
  const test = {
    kind: 'prompt', id: 'coding.engineering-ladder', version: '1.0.0', name: 'Engineering: Ladder', category: 'coding',
    description:
      'Ten levels of one engineering job: an array engine that applies range operations and answers range queries, checked by hidden unit tests on up to a million elements. ' +
      'Level 1 is point updates and range sums; every level adds one operation on top of all the earlier ones: min/max queries, range add, range assign, range chmin, ' +
      'range chmax, range floor-division, then the historic maximum and minimum of every element and a reset of those histories. A plain segment tree handles levels 2-4; from level 5 ' +
      'the large hidden inputs are built so that only the specialist design (segment tree beats with per-class tags) finishes in time, and from level 8 it must also carry ' +
      'historic values through every kind of update. Expected results come from a reference solution and are re-checked by two independent brute-force programs.',
    difficulty: 'extreme',
    tags: ['coding', 'javascript', 'ladder', 'specification', 'data-structures', 'segment-tree', 'hidden-tests'],
    hook: 'One array, one more operation per level: how far up does its code still pass every hidden test in time?',
    maxOutputTokens: 'model-max', timeLimitSec: 3600, maxRetries: 1,
    estimate: { inputTokens: Math.round(cases.reduce((s, c) => s + tok(c.prompt), 0) / cases.length), outputTokens: 30000 },
    author: 'Gauntlet Ladders', createdAt: '2026-11-04',
    scorer: { type: 'code-js', timeoutMs: 10000 },
    cases,
  };
  const placeholders = [];
  for (const c of test.cases) { placeholders.push(JSON.stringify(c.expected)); c.expected = `@@EXPECTED_${placeholders.length - 1}@@`; }
  let text = JSON.stringify(test, null, 2);
  placeholders.forEach((p, i) => { text = text.replace(JSON.stringify(`@@EXPECTED_${i}@@`), () => p); });
  JSON.parse(text);
  fs.writeFileSync(OUT, text + '\n');
  if (outArg < 0) fs.writeFileSync(new URL('./labels.json', import.meta.url), JSON.stringify(labels, null, 1) + '\n');
  console.log(`wrote ${OUT.pathname.split('/').slice(-2).join('/')} (${(text.length / 1024).toFixed(0)} KB); per-level prompt tokens: ${cases.map((c) => tok(c.prompt)).join(', ')}`);
}

if (import.meta.url === `file://${process.argv[1]}`) main();
