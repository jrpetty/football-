// Shared helpers for the Business Ladder Level-1 ("Assist") generators.
// Plain JavaScript (no dependencies) so `node build.mjs` and the unit tests can regenerate every test
// byte-identically from fixed seeds on any machine.

export function hashString(input) {
  let h = 0x811c9dc5;
  for (let i = 0; i < input.length; i++) {
    h ^= input.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

/** mulberry32: the same PRNG as src/core/rng.ts. */
export function rngFor(label) {
  let state = hashString(label) >>> 0;
  const next = () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  const r = {
    next,
    int: (min, max) => min + Math.floor(next() * (max - min + 1)),
    pick: (items) => items[Math.floor(next() * items.length)],
    chance: (p) => next() < p,
    shuffle(items) {
      const out = items.slice();
      for (let i = out.length - 1; i > 0; i--) {
        const j = Math.floor(next() * (i + 1));
        [out[i], out[j]] = [out[j], out[i]];
      }
      return out;
    },
    /** k distinct items. */
    sample(items, k) {
      return r.shuffle(items).slice(0, k);
    },
    digits(n) {
      let s = '';
      for (let i = 0; i < n; i++) s += String(i === 0 ? r.int(1, 9) : r.int(0, 9));
      return s;
    },
  };
  return r;
}

// ───────────── Money (integer pence, never floats) ─────────────

export const pounds = (p) => p / 100;
export function money(p, sym = '£') {
  const neg = p < 0;
  const a = Math.abs(p);
  const whole = Math.floor(a / 100)
    .toString()
    .replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return `${neg ? '-' : ''}${sym}${whole}.${String(a % 100).padStart(2, '0')}`;
}
export function plain(p) {
  const a = Math.abs(p);
  return `${p < 0 ? '-' : ''}${Math.floor(a / 100)}.${String(a % 100).padStart(2, '0')}`;
}
/** VAT at 20% on a net amount in pence, rounded half-up to the penny. */
export const vat20 = (netP) => Math.floor((netP * 20 + 50) / 100);

// ───────────── Dates (UTC, no time zones) ─────────────

export const D = (y, m, d) => new Date(Date.UTC(y, m - 1, d));
export const iso = (dt) => dt.toISOString().slice(0, 10);
export const addDays = (dt, n) => new Date(dt.getTime() + n * 86400000);
export const dow = (dt) => dt.getUTCDay(); // 0 Sun … 6 Sat
const DOW = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const DOW_LONG = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const MON_LONG = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
export const dowName = (dt) => DOW_LONG[dow(dt)];
export const monthName = (m) => MON_LONG[m - 1];
/** "Tue 3 Mar 2026" */
export const ukShort = (dt) => `${DOW[dow(dt)]} ${dt.getUTCDate()} ${MON[dt.getUTCMonth()]} ${dt.getUTCFullYear()}`;
/** "3 March 2026" */
export const ukLong = (dt) => `${dt.getUTCDate()} ${MON_LONG[dt.getUTCMonth()]} ${dt.getUTCFullYear()}`;
/** "03/03/2026" (day/month/year) */
export const ukNum = (dt) => `${String(dt.getUTCDate()).padStart(2, '0')}/${String(dt.getUTCMonth() + 1).padStart(2, '0')}/${dt.getUTCFullYear()}`;
/** "03/04/2026" (month/day/year) */
export const usNum = (dt) => `${String(dt.getUTCMonth() + 1).padStart(2, '0')}/${String(dt.getUTCDate()).padStart(2, '0')}/${dt.getUTCFullYear()}`;
export const monYear = (y, m) => `${MON[m - 1]} ${y}`;
export function lastDayOfMonth(y, m) {
  return new Date(Date.UTC(y, m, 0));
}

/** England & Wales bank holidays used by the generators (stated in every prompt that needs them). */
export const BANK_HOLIDAYS = [
  '2026-01-01', '2026-04-03', '2026-04-06', '2026-05-04', '2026-05-25', '2026-08-31', '2026-12-25', '2026-12-28',
  '2027-01-01', '2027-03-26', '2027-03-29', '2027-05-03', '2027-05-31', '2027-08-30', '2027-12-27', '2027-12-28',
];
export const isWorkingDay = (dt) => dow(dt) !== 0 && dow(dt) !== 6 && !BANK_HOLIDAYS.includes(iso(dt));

// ───────────── People and companies (invented) ─────────────

export const FIRST = [
  'Priya', 'Tom', 'Aisha', 'Callum', 'Mei', 'Oluwaseun', 'Grace', 'Rhys', 'Fatima', 'Daniel', 'Siobhan', 'Arjun', 'Hannah',
  'Kwame', 'Zofia', 'Imran', 'Eleanor', 'Tariq', 'Niamh', 'Ravi', 'Chloe', 'Bilal', 'Freya', 'Jamal', 'Lucy', 'Ewan', 'Amara',
  'Hiroshi', 'Sofia', 'Declan', 'Yusuf', 'Bethan', 'Adebayo', 'Ines', 'Marcus', 'Leila', 'Owen', 'Anya', 'Kofi', 'Rosa',
];
export const LAST = [
  'Shah', 'Okafor', 'Brennan', 'Nowak', 'Chen', 'Hughes', 'Patel', 'MacLeod', 'Adeyemi', 'Kowalski', 'Ahmed', 'Fletcher', 'Osei',
  'Morgan', 'Haddad', 'Lindqvist', 'Murphy', 'Begum', 'Taylor', 'Ferreira', 'Kaur', 'Doyle', 'Mensah', 'Novak', 'Price', 'Yilmaz',
  'Rahman', 'Walsh', 'Tanaka', 'Evans', 'Gallagher', 'Iqbal', 'Barker', 'Silva', 'Clarke', 'Mahmood', 'Jones', 'Kim', 'Reid', 'Duarte',
];
const CO_A = ['Halden', 'Brookfield', 'Kestrel', 'Northgate', 'Ashby', 'Calder', 'Thornbury', 'Marlow', 'Pennine', 'Severn', 'Larch', 'Wren', 'Harbour', 'Fenwick', 'Orchard', 'Saltmarsh', 'Copperleaf', 'Blackwater', 'Elmstead', 'Tamar'];
const CO_B = ['Supplies', 'Trade Supplies', 'Packaging', 'Office Solutions', 'Catering Supplies', 'Print & Signs', 'Facilities', 'Hygiene', 'Electrical', 'Logistics', 'Coffee Co', 'Wholesale', 'Timber', 'IT Services', 'Workwear'];
export function companyName(rng, suffix = 'Ltd') {
  return `${rng.pick(CO_A)} ${rng.pick(CO_B)} ${suffix}`;
}
export function person(rng, used) {
  for (;;) {
    const n = `${rng.pick(FIRST)} ${rng.pick(LAST)}`;
    if (!used || !used.has(n)) {
      used?.add(n);
      return n;
    }
  }
}

export const STREETS = ['Mill Lane', 'Station Road', 'Canal Street', 'Victoria Way', 'Hope Street', 'Foundry Close', 'Bridge Street', 'Wharf Road', 'Market Place', 'Albion Park'];
export const TOWNS = [
  ['Leeds', 'LS11 5QR'], ['Bristol', 'BS3 4HG'], ['Glasgow', 'G40 2QH'], ['Cardiff', 'CF10 4LJ'], ['Norwich', 'NR3 1RU'],
  ['Leicester', 'LE1 3PQ'], ['Sheffield', 'S3 8EN'], ['Belfast', 'BT3 9DT'], ['Plymouth', 'PL4 0RA'], ['Manchester', 'M4 6BF'],
];
export function address(rng) {
  const [town, pc] = rng.pick(TOWNS);
  return `Unit ${rng.int(1, 24)}, ${rng.pick(STREETS)}, ${town} ${pc}`;
}

/** A UK VAT number "GB 123 4567 89" style (9 digits). */
export function vatNumber(rng) {
  const d = rng.digits(9);
  return `GB${d}`;
}
export const spacedVat = (v) => `${v.slice(0, 2)} ${v.slice(2, 5)} ${v.slice(5, 9)} ${v.slice(9)}`;

// ───────────── Output ─────────────

export const HEDGE_RULE = 'Give exactly one value per field: a field holding two values, a range or an "X or Y" answer is marked wrong.';

export function tok(s) {
  return Math.ceil(s.length / 3.6) + 20;
}

/** The test JSON text exactly as committed (2-space indent, trailing newline). */
export function testJson(meta, cases) {
  const obj = {
    kind: 'prompt',
    id: meta.id,
    version: meta.version ?? '1.0.0',
    name: meta.name,
    category: 'work',
    description: meta.description,
    difficulty: meta.difficulty,
    tags: meta.tags,
    hook: meta.hook,
    maxOutputTokens: meta.maxOutputTokens ?? 8000,
    estimate: {
      inputTokens: Math.round(cases.reduce((s, c) => s + tok(c.prompt), 0) / cases.length) + 20,
      outputTokens: meta.outputTokens,
    },
    author: 'Gauntlet Core',
    createdAt: '2026-11-04',
    scorer: meta.scorer,
    cases,
  };
  return JSON.stringify(obj, null, 2) + '\n';
}

/** Every key path whose expected value is in `exp`, for uniqueness assertions in generators. */
export function assert(cond, msg) {
  if (!cond) throw new Error(`generator check failed: ${msg}`);
}
