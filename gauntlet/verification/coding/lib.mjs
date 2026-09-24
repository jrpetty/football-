import vm from 'node:vm';
import fs from 'node:fs';
import { runTests } from './harness.mjs';
export function rng(seed) {
  let a = seed >>> 0;
  const next = () => { a |= 0; a = (a + 0x6D2B79F5) | 0; let t = Math.imul(a ^ (a >>> 15), 1 | a); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
  return { next, int: (lo, hi) => lo + Math.floor(next() * (hi - lo + 1)), pick: (arr) => arr[Math.floor(next() * arr.length)] };
}
// Load a function from a source file in a fresh VM context (same way the harness does).
export function load(file, name) {
  const ctx = vm.createContext({});
  vm.runInContext(fs.readFileSync(file, 'utf8'), ctx);
  return (...args) => JSON.parse(JSON.stringify(ctx[name](...JSON.parse(JSON.stringify(args)))));
}
export function check(label, code, name, tests, timeoutMs = 2000) {
  const r = runTests(code, name, tests, timeoutMs, true);
  console.log(`${label}: ${r.passed}/${r.total}  times(ms): ${r.results.map((x) => (x.ok ? '' : 'X') + x.ms).join(' ')}`);
  return r;
}
export function sizeKB(tests) { return (JSON.stringify(tests).length / 1024).toFixed(1); }
