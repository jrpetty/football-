// Shared helpers for the Real-World Work business pack generators (verification/realworld-business/).
// Pure JavaScript, no dependencies: every generator is deterministic for a given seed.
// Money is always handled in integer pence; dates are UTC-midnight Date objects for calendar days.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const HERE = path.dirname(fileURLToPath(import.meta.url));
export const TESTS_DIR = path.join(HERE, '..', '..', 'tests');

// ───────────── RNG (mulberry32) ─────────────
export function rng(seed) {
  let a = seed >>> 0;
  const next = () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  const r = {
    next,
    int: (lo, hi) => lo + Math.floor(next() * (hi - lo + 1)),
    pick: (xs) => xs[Math.floor(next() * xs.length)],
    chance: (p) => next() < p,
    shuffle: (xs) => {
      const a2 = xs.slice();
      for (let i = a2.length - 1; i > 0; i--) {
        const j = Math.floor(next() * (i + 1));
        [a2[i], a2[j]] = [a2[j], a2[i]];
      }
      return a2;
    },
    sample: (xs, n) => r.shuffle(xs).slice(0, n),
  };
  return r;
}

// ───────────── Money (integer pence) ─────────────
/** Round a rational number of pence num/den to the nearest penny, half up (for non-negative num). */
export function divHalfUp(num, den) {
  if (num < 0) return -divHalfUp(-num, den);
  return Math.floor((2 * num + den) / (2 * den));
}
/** Half a penny rounds DOWN. */
export function divHalfDown(num, den) {
  if (num < 0) return -divHalfDown(-num, den);
  return Math.ceil((2 * num - den) / (2 * den));
}
/** pence × pct% rounded half up, pct given in basis points (2% = 200). */
export const pctBp = (pence, bp) => divHalfUp(pence * bp, 10000);
export function gbp(p, { sign = true, comma = true } = {}) {
  const neg = p < 0;
  const a = Math.abs(p);
  let s = String(Math.floor(a / 100));
  if (comma) s = s.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  s = `${s}.${String(a % 100).padStart(2, '0')}`;
  return (neg ? '-' : '') + (sign ? '£' : '') + s;
}
export const num2 = (p) => gbp(p, { sign: false, comma: false });
export const toPounds = (p) => Math.round(p) / 100;

// ───────────── Dates ─────────────
export const D = (y, m, d) => new Date(Date.UTC(y, m - 1, d));
export const addDays = (dt, n) => new Date(dt.getTime() + n * 86400000);
export const diffDays = (a, b) => Math.round((a.getTime() - b.getTime()) / 86400000);
export const iso = (dt) => dt.toISOString().slice(0, 10);
const DOW = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const DOW_LONG = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const MON_LONG = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
export const ukShort = (dt) => `${DOW[dt.getUTCDay()]} ${dt.getUTCDate()} ${MON[dt.getUTCMonth()]} ${dt.getUTCFullYear()}`;
export const ukLong = (dt) => `${DOW_LONG[dt.getUTCDay()]} ${dt.getUTCDate()} ${MON_LONG[dt.getUTCMonth()]} ${dt.getUTCFullYear()}`;
export const dmy = (dt) => `${String(dt.getUTCDate()).padStart(2, '0')}/${String(dt.getUTCMonth() + 1).padStart(2, '0')}/${dt.getUTCFullYear()}`;
export const dMonY = (dt) => `${dt.getUTCDate()} ${MON[dt.getUTCMonth()]} ${dt.getUTCFullYear()}`;
export const monY = (y, m) => `${MON[m - 1]} ${y}`;
export const monthLong = (m) => MON_LONG[m - 1];
export const dow = (dt) => dt.getUTCDay();
export const lastOfMonth = (dt) => new Date(Date.UTC(dt.getUTCFullYear(), dt.getUTCMonth() + 1, 0));
export const isWeekend = (dt) => dt.getUTCDay() === 0 || dt.getUTCDay() === 6;

/** England & Wales bank holidays used by the pack (stated in every prompt that needs them). */
export const BANK_HOLIDAYS = [
  '2026-01-01', '2026-04-03', '2026-04-06', '2026-05-04', '2026-05-25', '2026-08-31', '2026-12-25', '2026-12-28',
  '2027-01-01', '2027-03-26', '2027-03-29', '2027-05-03', '2027-05-31', '2027-08-30', '2027-12-27', '2027-12-28',
];
export const isBankHoliday = (dt) => BANK_HOLIDAYS.includes(iso(dt));

/** UK clock offset (hours ahead of UTC) at a UTC instant: BST from 01:00 UTC on the last Sunday of March to 01:00 UTC on the last Sunday of October. */
export function ukOffsetHours(utcMs) {
  const y = new Date(utcMs).getUTCFullYear();
  const lastSun = (m) => {
    const d = new Date(Date.UTC(y, m + 1, 0));
    return Date.UTC(y, m, d.getUTCDate() - d.getUTCDay(), 1, 0);
  };
  return utcMs >= lastSun(2) && utcMs < lastSun(9) ? 1 : 0;
}

// ───────────── Text helpers ─────────────
export const pad = (s, n) => String(s).padEnd(n);
export const lpad = (s, n) => String(s).padStart(n);
export function wrap(text, width = 96) {
  return text
    .split('\n')
    .map((line) => {
      if (line.length <= width) return line;
      const words = line.split(' ');
      const out = [];
      let cur = '';
      for (const w of words) {
        if ((cur + ' ' + w).trim().length > width) {
          out.push(cur);
          cur = w;
        } else cur = (cur ? cur + ' ' : '') + w;
      }
      if (cur) out.push(cur);
      return out.join('\n');
    })
    .join('\n');
}

export const ONE_ANSWER = 'Give exactly one value for every field: alternatives, ranges or "either/or" answers are marked wrong.';
export const tok = (s) => Math.ceil(s.length / 3.6) + 20;

// ───────────── Writing test files ─────────────
/** Serialise a test exactly as the other generators do (indent 2, large keys compact on one line). */
export function serializeTest(meta, cases) {
  const obj = {
    kind: 'prompt',
    id: meta.id,
    version: meta.version,
    name: meta.name,
    category: meta.category,
    description: meta.description,
    difficulty: meta.difficulty,
    tags: meta.tags,
    hook: meta.hook,
    maxOutputTokens: meta.maxOutputTokens,
    estimate: meta.estimate,
    author: 'Gauntlet Core',
    createdAt: '2026-11-04',
    scorer: meta.scorer,
    cases: cases.map((c) => ({ ...c })),
  };
  const placeholders = new Map();
  obj.cases.forEach((c, i) => {
    const compact = JSON.stringify(c.expected);
    if (compact.length > 4000) {
      const key = `@@COMPACT_${i}@@`;
      placeholders.set(key, compact);
      c.expected = key;
    }
  });
  let text = JSON.stringify(obj, null, 2);
  for (const [key, compact] of placeholders) text = text.replace(JSON.stringify(key), compact);
  JSON.parse(text);
  return text + '\n';
}

export function testPath(id) {
  const [cat, slug] = id.split('.');
  return path.join(TESTS_DIR, cat, `${slug}.json`);
}

export function writeOrCheck(file, text, check) {
  if (check) {
    const cur = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : null;
    return cur === text;
  }
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, text);
  return true;
}

export function assert(cond, msg) {
  if (!cond) throw new Error(`Assertion failed: ${msg}`);
}
