import vm from 'node:vm';
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
export function rng(seed) {
  let a = seed >>> 0;
  const next = () => { a |= 0; a = (a + 0x6D2B79F5) | 0; let t = Math.imul(a ^ (a >>> 15), 1 | a); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
  const int = (lo, hi) => lo + Math.floor(next() * (hi - lo + 1));
  return { next, int, pick: (arr) => arr[Math.floor(next() * arr.length)], chance: (p) => next() < p,
    shuffle: (arr) => { const a = arr.slice(); for (let i = a.length - 1; i > 0; i--) { const j = int(0, i); [a[i], a[j]] = [a[j], a[i]]; } return a; } };
}
export function load(file, name) {
  const ctx = vm.createContext({});
  vm.runInContext(fs.readFileSync(new URL(file, import.meta.url), 'utf8'), ctx);
  return (...args) => JSON.parse(JSON.stringify(ctx[name](...JSON.parse(JSON.stringify(args)))));
}
export function deepEqual(a, b) {
  if (typeof a === 'number' && typeof b === 'number') return a === b || Math.abs(a - b) <= 1e-6 * Math.max(1, Math.abs(b));
  if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object') return a === b;
  if (Array.isArray(a) !== Array.isArray(b)) return false;
  if (Array.isArray(a)) return a.length === b.length && a.every((v, i) => deepEqual(v, b[i]));
  const ka = Object.keys(a), kb = Object.keys(b);
  return ka.length === kb.length && ka.every((k) => Object.prototype.hasOwnProperty.call(b, k) && deepEqual(a[k], b[k]));
}
// Run a Python oracle: it reads a JSON list of arg-lists on stdin and prints a JSON list of results.
export function python(script, argLists) {
  const out = execFileSync('python3', [new URL(script, import.meta.url).pathname], { input: JSON.stringify(argLists), maxBuffer: 1 << 30 });
  return JSON.parse(out.toString());
}
