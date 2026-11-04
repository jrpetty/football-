// Builds tests/coding/engineering-ladder.json (Engineering: Ladder) from the specification (spec.mjs), the tests
// (cases.mjs) and the reference (ref.js). Byte-identical on every run.
//
//   node gen.mjs            # write the test file (expected replies from ref.js); --out FILE for a dry run
//   node verify.mjs         # every hidden test and example re-checked: Python oracle (small/medium), alt.js (large)
//   node fuzz.mjs 3000      # ref.js, alt.js and the oracle agree on 30,000 random streams
//   node spread.mjs         # real-sandbox timings and the spread of scripted solutions -> measurements.json
//
// The levels are FROZEN once published: never regenerate a published version (add L11+ in a new version instead).
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
import { LEVELS, FN, prompt, commandsUpTo } from './spec.mjs';
import { EXAMPLES, levelTests } from './cases.mjs';
import { load } from './lib.mjs';

const ref = load('./ref.js');
const outArg = process.argv.indexOf('--out');
const OUT = outArg > 0 ? pathToFileURL(process.argv[outArg + 1]) : new URL('../../tests/coding/engineering-ladder.json', import.meta.url);
const MEAS = new URL('./measurements.json', import.meta.url);
const measurements = fs.existsSync(MEAS) ? JSON.parse(fs.readFileSync(MEAS, 'utf8')) : {};

const KEY = /^[a-z0-9:_]{1,24}$/;
const KEYCHARS = /^[a-z0-9:_]{0,24}$/;
const VALUE = /^[\x20-\x7e]{0,24}$/;
const CANON = /^(?:0|-?[1-9][0-9]*)$/;

/** Every command is well-formed and belongs to the level (the prompt promises this). */
export function checkWellFormed(level, commands) {
  const allowed = new Set(commandsUpTo(level));
  let clock = 0;
  for (const c of commands) {
    const [op, a, b, d] = c;
    const fail = (why) => { throw new Error(`L${level}: ${why}: ${JSON.stringify(c)}`); };
    if (!allowed.has(op)) fail('command not in this level');
    if (!c.every((x) => typeof x === 'string')) fail('non-string argument');
    const arity = { SET: 3, GET: 2, DEL: 2, SETNX: 3, COUNT: 2, SIZE: 1, POPULATE: 4, INCR: 3, BEGIN: 1, ROLLBACK: 1, COMMIT: 1, SCAN: 3, TIME: 2, EXPIRE: 3, TTL: 2, PERSIST: 2, RENAME: 3, SNAPSHOT: 1, GETAT: 3, DELPREFIX: 2, COUNTRANGE: 3 }[op];
    if (c.length !== arity) fail('wrong number of arguments');
    if (['SET', 'GET', 'DEL', 'SETNX', 'INCR', 'TTL', 'EXPIRE', 'PERSIST', 'GETAT'].includes(op) && !KEY.test(a)) fail('bad key');
    if (['SET', 'SETNX'].includes(op) && !VALUE.test(b)) fail('bad value');
    if (op === 'COUNT' && !VALUE.test(a)) fail('bad value');
    if (op === 'RENAME' && !(KEY.test(a) && KEY.test(b))) fail('bad key');
    if (['SCAN', 'DELPREFIX', 'POPULATE'].includes(op) && !KEYCHARS.test(a)) fail('bad prefix');
    if (op === 'COUNTRANGE' && !(KEYCHARS.test(a) && KEYCHARS.test(b))) fail('bad bound');
    if (op === 'POPULATE') { if (!CANON.test(b) || +b < 0 || +b > 200000 || !VALUE.test(d)) fail('bad POPULATE'); if (+b > 0 && !KEY.test(a + (+b - 1))) fail('POPULATE key too long'); }
    if (op === 'INCR' && !(CANON.test(b) && BigInt(b) <= 10n ** 18n && BigInt(b) >= -(10n ** 18n))) fail('bad delta');
    if (op === 'SCAN' && !(CANON.test(b) && +b >= 1 && +b <= 1000)) fail('bad limit');
    if (op === 'TIME') { if (!(CANON.test(a) && +a >= clock && +a <= 1e9)) fail('bad time'); clock = +a; }
    if (op === 'EXPIRE' && !(CANON.test(b) && +b >= 0 && +b <= 1e9)) fail('bad seconds');
    if (op === 'GETAT' && !CANON.test(b)) fail('bad snapshot id');
  }
}

/** Largest number of keys that exist at once (a SIZE after every command; SIZE changes nothing), for the stated limit. */
function peakKeys(commands) {
  const probe = ref(commands.flatMap((c) => [c, ['SIZE']]));
  let peak = 0;
  for (let i = 1; i < probe.length; i += 2) peak = Math.max(peak, +probe[i]);
  return peak;
}

export function buildLevel(level) {
  const L = LEVELS[level - 1];
  const examples = EXAMPLES[level].map((args) => { checkWellFormed(level, args[0]); return [args, ref(...args)]; });
  const tests = levelTests(level);
  const out = [];
  for (const t of tests) {
    checkWellFormed(level, t.commands);
    if (t.commands.length > L.maxCommands) throw new Error(`L${level} ${t.label}: ${t.commands.length} commands > ${L.maxCommands}`);
    const peak = peakKeys(t.commands);
    if (peak > L.maxKeys) throw new Error(`L${level} ${t.label}: ${peak} keys at once > ${L.maxKeys}`);
    out.push({ label: t.label, args: [t.commands], expected: ref(t.commands) });
  }
  return { L, examples, tests: out };
}

function main() {
  const cases = [];
  const labels = {};
  for (const L of LEVELS) {
    const { examples, tests } = buildLevel(L.level);
    const id = `L${String(L.level).padStart(2, '0')}`;
    labels[id] = tests.map((t) => t.label);
    const m = measurements[id];
    const big = tests.filter((t) => t.args[0].length >= 1000).length;
    const notes =
      `[level ${L.level}] Adds ${L.adds.join(', ')} (${commandsUpTo(L.level).length} commands in all); tests of up to ${L.maxCommands.toLocaleString('en-GB')} commands and ${L.maxKeys.toLocaleString('en-GB')} keys; ${tests.length} hidden tests ` +
      `(hand-made rule tests, random streams over near-collision keys, ${big} large). Expected replies from verification/engineering-ladder/ref.js (one reference for every level: ` +
      'the level-10 specification is a superset and changes nothing below it). Every test and example re-checked by the Python oracle oracle.py (literal model: copies the store at every BEGIN and SNAPSHOT, ' +
      'lazy expiry, full scans) or, where too large for it, by the independent second JavaScript implementation alt.js; ref, alt and the oracle agree on 30,000 random streams (fuzz.mjs). ' +
      (m ? `Real sandbox (${m.platform}): reference max ${m.ref.maxMs} ms vs ${L.timeoutMs} ms limit; scripted solutions: ${m.summary}.` : 'Sandbox timings: run spread.mjs.') +
      (L.level === 1 ? ' Ladder: ten frozen levels (L01 easiest, L10 hardest); each level\'s specification contains every rule of the levels below plus new commands, edge cases and larger inputs. Every level is worth the same, so the test score is the share of the ladder climbed; the headline is the highest level passed reliably.' : '');
    cases.push({
      id,
      prompt: prompt(L.level, examples),
      expected: { functionName: FN, tests: tests.map(({ args, expected }) => ({ args, expected })) },
      scorer: { type: 'code-js', timeoutMs: L.timeoutMs },
      notes,
    });
  }
  const tok = (s) => Math.ceil(s.length / 3.6) + 20;
  const test = {
    kind: 'prompt', id: 'coding.engineering-ladder', version: '1.0.0', name: 'Engineering: Ladder', category: 'coding',
    description:
      'Ten levels of one engineering job: build a small in-memory database (a mini Redis) from a written specification, checked by hidden unit tests. ' +
      'Level 1 is three commands (SET, GET, DEL); every level adds commands and rules on top of all the earlier ones (counting and bulk loading, exact big-integer ' +
      'arithmetic, nested transactions, ordered scans, expiry with a clock, renames, snapshots, bulk deletes, range counts) and larger hidden inputs, up to 200,000 keys. ' +
      'The top levels need every rule right in every combination AND the efficient design: copying the store, scanning every key or re-sorting on each command runs out of time. ' +
      'Expected replies come from a reference solution and are re-checked by two independent implementations.',
    difficulty: 'extreme',
    tags: ['coding', 'javascript', 'ladder', 'specification', 'data-structures', 'hidden-tests', 'database'],
    hook: 'Build a database one feature at a time: how far up does it get before something breaks?',
    maxOutputTokens: 'model-max', timeLimitSec: 3600, maxRetries: 1,
    estimate: { inputTokens: Math.round(cases.reduce((s, c) => s + tok(c.prompt), 0) / cases.length), outputTokens: 30000 },
    author: 'Gauntlet Ladders', createdAt: '2026-11-04',
    scorer: { type: 'code-js', timeoutMs: 6000 },
    cases,
  };
  // compact hidden tests (one line per case), everything else indented
  const placeholders = [];
  for (const c of test.cases) { placeholders.push(JSON.stringify(c.expected)); c.expected = `@@EXPECTED_${placeholders.length - 1}@@`; }
  let text = JSON.stringify(test, null, 2);
  placeholders.forEach((p, i) => { text = text.replace(JSON.stringify(`@@EXPECTED_${i}@@`), () => p); });
  JSON.parse(text);
  fs.writeFileSync(OUT, text + '\n');
  if (outArg < 0) fs.writeFileSync(new URL('./labels.json', import.meta.url), JSON.stringify(labels, null, 1) + '\n');
  console.log(`wrote tests/coding/engineering-ladder.json (${(text.length / 1024).toFixed(0)} KB); per-level prompt tokens: ${cases.map((c) => tok(c.prompt)).join(', ')}`);
}

if (import.meta.url === `file://${process.argv[1]}`) main();
