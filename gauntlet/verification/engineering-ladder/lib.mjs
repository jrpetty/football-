// Shared helpers for Engineering: Ladder: loading a solution into a fresh VM context, the two brute-force oracles
// (brute.py and brute.c), and the random explicit inputs used for fuzzing and for the small hidden tests.
import vm from 'node:vm';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { opsUpTo } from './spec.mjs';

export function rng(seed) {
  let a = seed >>> 0;
  const next = () => { a |= 0; a = (a + 0x6d2b79f5) | 0; let t = Math.imul(a ^ (a >>> 15), 1 | a); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
  const int = (lo, hi) => lo + Math.floor(next() * (hi - lo + 1));
  return { next, int, pick: (arr) => arr[Math.floor(next() * arr.length)], chance: (p) => next() < p };
}

/** A solution file's function in its own VM context; arguments and results are copied through JSON like the sandbox does. */
export function load(file, name = 'runEngine', edit = (src) => src) {
  const ctx = vm.createContext({});
  vm.runInContext(edit(fs.readFileSync(new URL(file, import.meta.url), 'utf8')), ctx);
  return (...args) => JSON.parse(JSON.stringify(ctx[name](...JSON.parse(JSON.stringify(args)))));
}

export const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

export function python(inputs) {
  const out = execFileSync('python3', [new URL('./brute.py', import.meta.url).pathname], { input: JSON.stringify(inputs), maxBuffer: 1 << 28 });
  return JSON.parse(out.toString());
}

let cBinary = null;
function compileC() {
  if (cBinary) return cBinary;
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'eng-ladder-c-'));
  cBinary = path.join(dir, 'brute');
  execFileSync('cc', ['-O2', '-o', cBinary, new URL('./brute.c', import.meta.url).pathname]);
  return cBinary;
}
const TAKES_VALUE = new Set(['add', 'assign', 'chmin', 'chmax', 'div']);
export function cText(input) {
  if (input.generate) {
    const g = input.generate;
    return [`G ${g.seed} ${g.n} ${g.lo} ${g.hi} ${g.q} ${g.mix.length}`, ...g.mix.map((e) => (e[0] === 'set' || TAKES_VALUE.has(e[0]) ? `${e[0]} ${e[1]} ${e[2]} ${e[3]}` : `${e[0]} ${e[1]} 0 -1`))].join('\n') + '\n';
  }
  const ops = input.ops.map((o) => `${o[0]} ${o[1]} ${o[2] ?? 0} ${o[3] ?? 0}`);
  return `E ${input.values.length} ${input.values.join(' ')}\n${input.ops.length}\n${ops.join('\n')}\n`;
}
/** [count, checksum] from the C brute force, or 'OVERFLOW'. */
export function cBrute(input) {
  const out = execFileSync(compileC(), [], { input: cText(input), maxBuffer: 1 << 20 }).toString().trim();
  return out === 'OVERFLOW' ? out : out.split(' ').map(Number);
}

// ── random explicit inputs ─────────────────────────────────────────────────────────────────────────
/** Random values and operations of a level: small n, values in a narrow band so ties, clipping and equal
 * runs are common (where segment-tree tags interact). */
export function randomExplicit(R, level, { n = R.int(1, 12), q = R.int(1, 40), band = R.pick([3, 10, 40, 1000]) } = {}) {
  const ops = opsUpTo(level);
  const values = Array.from({ length: n }, () => R.int(-band, band));
  const out = [];
  for (let j = 0; j < q; j++) {
    const op = R.pick(ops);
    const u = R.int(0, n - 1), t = R.chance(0.2) ? u : R.int(0, n - 1);
    const l = Math.min(u, t), r = Math.max(u, t);
    const x = op === 'div' ? R.int(2, R.pick([2, 3, 5, 10])) : R.int(-band - 2, band + 2);
    if (op === 'set') out.push(['set', u, x]);
    else if (TAKES_VALUE.has(op)) out.push([op, l, r, x]);
    else out.push([op, l, r]);
  }
  return { values, ops: out };
}
