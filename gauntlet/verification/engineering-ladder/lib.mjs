// Shared helpers for Engineering: Ladder: a seeded RNG, loading a solution file into a fresh VM context, running the
// Python oracle, and the random command-stream generator used for hidden tests and for fuzzing.
import vm from 'node:vm';
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
import { commandsUpTo } from './spec.mjs';

export function rng(seed) {
  let a = seed >>> 0;
  const next = () => { a |= 0; a = (a + 0x6d2b79f5) | 0; let t = Math.imul(a ^ (a >>> 15), 1 | a); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
  const int = (lo, hi) => lo + Math.floor(next() * (hi - lo + 1));
  return { next, int, pick: (arr) => arr[Math.floor(next() * arr.length)], chance: (p) => next() < p };
}

/** A solution file's function, run in its own VM context; arguments and results are copied through JSON like the sandbox does. */
export function load(file, name = 'runStore') {
  const ctx = vm.createContext({});
  vm.runInContext(fs.readFileSync(new URL(file, import.meta.url), 'utf8'), ctx);
  return (...args) => JSON.parse(JSON.stringify(ctx[name](...JSON.parse(JSON.stringify(args)))));
}

export function oracle(tests) {
  const out = execFileSync('python3', [new URL('./oracle.py', import.meta.url).pathname], { input: JSON.stringify(tests), maxBuffer: 1 << 30 });
  return JSON.parse(out.toString());
}

export const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

// ── random streams ────────────────────────────────────────────────────────────────────────────────
// A small key pool full of near-collisions ("a", "a:1", "a_1", "a1", "ab", ...) so ordering, prefixes and overwrites
// are exercised; a value pool with canonical and NON-canonical integers and look-alike strings.
export const KEY_POOL = ['a', 'b', 'ab', 'a1', 'a:1', 'a_1', 'a:10', 'a:2', 'b:1', 'ba', 'user:1', 'user:10', 'user:2', 'u:0', 'u:1', 'u:10', 'z', 'z_', 'z:', '0', '9z'];
export const VALUE_POOL = ['x', 'X', 'x ', '', '0', '1', '7', '-3', '42', '007', '-0', '+5', ' 5', '1e3', '9007199254740993', '-9007199254740993', 'OK', '(nil)', 'NULL', 'a b', 'red', 'Red'];
const PREFIXES = ['', 'a', 'a:', 'a_', 'u:', 'u:1', 'user:', 'b', 'z', 'zz', ':', 'user:1'];

/**
 * A random command stream that uses only the commands of `level` (and below).
 * opts: n (commands), keys (pool), values (pool), deep (bias towards nested transactions), big (allow big POPULATE)
 */
export function randomStream(R, level, opts = {}) {
  const allowed = commandsUpTo(level);
  const n = opts.n ?? 60;
  const keys = opts.keys ?? KEY_POOL;
  const values = opts.values ?? VALUE_POOL;
  const out = [];
  let clock = 0;
  let depth = 0;
  let snaps = 0;
  const key = () => R.pick(keys);
  const value = () => R.pick(values);
  const weights = {
    SET: 6, GET: 5, DEL: 2, SETNX: 2, COUNT: 3, SIZE: 2, POPULATE: 0.6, INCR: 4, BEGIN: 2.2, ROLLBACK: 1.2, COMMIT: 1.2,
    SCAN: 3, TIME: 2, EXPIRE: 2.5, TTL: 2, PERSIST: 1, RENAME: 2.5, SNAPSHOT: 1.5, GETAT: 3, DELPREFIX: 1, COUNTRANGE: 3,
  };
  const ops = allowed.map((op) => [op, weights[op]]);
  const total = ops.reduce((s, [, w]) => s + w, 0);
  const pickOp = () => { let x = R.next() * total; for (const [op, w] of ops) { x -= w; if (x <= 0) return op; } return ops[ops.length - 1][0]; };
  for (let i = 0; i < n; i++) {
    const op = pickOp();
    switch (op) {
      case 'SET': out.push(['SET', key(), value()]); break;
      case 'GET': out.push(['GET', key()]); break;
      case 'DEL': out.push(['DEL', key()]); break;
      case 'SETNX': out.push(['SETNX', key(), value()]); break;
      case 'COUNT': out.push(['COUNT', value()]); break;
      case 'SIZE': out.push(['SIZE']); break;
      case 'POPULATE': out.push(['POPULATE', R.pick(['u:', 'a:', 'a', 'user:', 'p']), String(R.int(0, opts.big ? 40 : 12)), value()]); break;
      case 'INCR': out.push(['INCR', key(), R.pick(['1', '-1', '5', '0', '-7', '1000000000000000000', '-1000000000000000000', String(R.int(-99, 99))])]); break;
      case 'BEGIN': out.push(['BEGIN']); depth++; break;
      case 'ROLLBACK': out.push(['ROLLBACK']); if (depth) depth--; break;
      case 'COMMIT': out.push(['COMMIT']); if (depth) depth--; break;
      case 'SCAN': out.push(['SCAN', R.pick(PREFIXES), String(R.pick([1, 2, 3, 5, 1000]))]); break;
      case 'TIME': clock += R.pick([0, 1, 1, 2, 3, 5, 10]); out.push(['TIME', String(clock)]); break;
      case 'EXPIRE': out.push(['EXPIRE', key(), String(R.pick([0, 1, 2, 3, 5, 8, 1000000000]))]); break;
      case 'TTL': out.push(['TTL', key()]); break;
      case 'PERSIST': out.push(['PERSIST', key()]); break;
      case 'RENAME': out.push(['RENAME', key(), R.chance(0.15) ? keys[0] : key()]); break;
      case 'SNAPSHOT': out.push(['SNAPSHOT']); snaps++; break;
      case 'GETAT': out.push(['GETAT', key(), String(R.chance(0.1) ? R.pick([0, snaps + 1, 99]) : R.int(1, Math.max(1, snaps)))]); break;
      case 'DELPREFIX': out.push(['DELPREFIX', R.pick(PREFIXES.filter((p) => p !== '' || R.chance(0.2)))]); break;
      case 'COUNTRANGE': { const a = R.pick([...keys, ...PREFIXES]); const b = R.pick([...keys, ...PREFIXES]); out.push(['COUNTRANGE', a, b]); break; }
    }
  }
  return out;
}
