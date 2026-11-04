// The hidden tests and prompt examples of every level of Engineering: Ladder. Pure functions of fixed seeds;
// gen.mjs turns them into tests/coding/engineering-ladder.json with every expected result computed by ref.js.
//
// Each level has hand-made tests for the operation it adds and for its interplay with every earlier one (ties,
// one-element ranges, negative division, clipping to equal values, histories through assign and hreset ...), random
// explicit inputs over narrow value bands (where segment-tree tags interact most), small generated inputs, and large
// generated inputs (the generator is part of the specification, so a test of a million elements is a few bytes)
// whose operation mix keeps undoing the shortcuts of simpler designs.
import { rng, randomExplicit } from './lib.mjs';
import { LEVELS, opsUpTo, QUERIES } from './spec.mjs';

const S = (i, x) => ['set', i, x], SUM = (l, r) => ['sum', l, r], MIN = (l, r) => ['min', l, r], MAX = (l, r) => ['max', l, r];
const ADD = (l, r, x) => ['add', l, r, x], ASG = (l, r, x) => ['assign', l, r, x], CMN = (l, r, x) => ['chmin', l, r, x], CMX = (l, r, x) => ['chmax', l, r, x];
const DIV = (l, r, x) => ['div', l, r, x], HMX = (l, r) => ['hmax', l, r], HMN = (l, r) => ['hmin', l, r], HR = (l, r) => ['hreset', l, r];

// ── examples shown in each prompt ───────────────────────────────────────────────────────────────────
export const EXAMPLES = {
  1: [[{ values: [5, -2, 7], ops: [SUM(0, 2), S(1, 10), SUM(1, 2), SUM(0, 0)] }]],
  2: [[{ values: [4, -1, 9, 3], ops: [MIN(0, 3), MAX(1, 2), S(2, -8), MIN(1, 3), SUM(0, 3)] }]],
  3: [[{ values: [1, 2, 3, 4, 5], ops: [ADD(1, 3, 10), SUM(0, 4), MAX(0, 2), ADD(0, 4, -20), MIN(2, 4)] }]],
  4: [[{ values: [1, 2, 3, 4, 5], ops: [ASG(1, 3, 7), ADD(2, 4, 1), SUM(0, 4), S(2, 0), MAX(1, 3)] }]],
  5: [[{ values: [9, 2, 6, 6, 1], ops: [CMN(0, 4, 5), SUM(0, 4), ADD(0, 2, 3), CMN(1, 3, 6), MAX(0, 4), SUM(0, 4)] }]],
  6: [[{ values: [9, 2, 6, 6, 1], ops: [CMX(0, 4, 4), SUM(0, 4), CMN(0, 4, 5), MIN(0, 4), SUM(0, 4)] }]],
  7: [[{ values: [7, -7, 10, -1, 0], ops: [DIV(0, 4, 2), SUM(0, 4), MIN(0, 4), DIV(1, 3, 3), SUM(0, 4)] }]],
  8: [[{ values: [3, 1, 4], ops: [ADD(0, 2, 5), ASG(0, 1, 0), HMX(0, 2), HMX(1, 1), CMX(1, 2, 20), HMX(0, 1)] }]],
  9: [[{ values: [3, 1, 4], ops: [ADD(0, 2, -5), HMN(0, 2), ASG(0, 2, 9), HMN(2, 2), DIV(0, 0, 2), HMN(0, 0), HMX(0, 0)] }]],
  10: [[{ values: [3, 1, 4], ops: [ADD(0, 2, 5), HR(0, 1), HMX(0, 2), HMN(0, 1), ADD(0, 2, -9), HMX(0, 1), HMN(0, 2)] }]],
};
/** The generator example of the prompt (expanded there by gen.mjs). */
export const GEN_EXAMPLE = { seed: 7, n: 5, lo: -3, hi: 3, q: 4, mix: [['sum', 2], ['set', 1, 0, 9]] };

// ── the operation mix of the large generated tests ─────────────────────────────────────────────────────
// Variant 0 ("clipped from above, drifting up"): values start spread over ±10^9; chmin clips into a band near 0 and
// additions push the clipped elements up again, so every chmin meets a large group of equal maxima scattered among
// much smaller values; chmax does the same from below against a deep band around -5·10^8. A node-by-node shortcut
// must touch every one of those elements (in the measurements a plain lazy segment tree needs minutes); a design that
// treats "all elements equal to the maximum" as one class touches a few nodes. No division here: it would collapse
// the spread and blunt the pattern. Variant 1: the mirror image (chmax clips up into the band, additions push down,
// chmin against a deep band near +5·10^8; from level 7 occasional division instead of the deep band). Variant 2: every operation
// mixed, values starting in the band. Levels 2-4 have no chmin/chmax: there the mixes are plain random workloads.
export function largeMix(level, variant = 0) {
  const has = new Set(opsUpTo(level));
  const B = 1000, WIDE = 1000000000, DEEP = [400000000, 600000000];
  const k = variant === 1 && has.has('div') ? 2 : 1; // with division in the mix, everything else twice as often
  const m = [['sum', 4 * k], ['min', 2 * k], ['max', 2 * k]];
  if (has.has('hmax')) m.push(['hmax', 3 * k]);
  if (has.has('hmin')) m.push(['hmin', 3 * k]);
  if (level <= 4) {
    m.push(['set', 3, -WIDE, WIDE]);
    if (has.has('add')) m.push(['add', 5, variant === 1 ? -B : -1000000, variant === 1 ? B : 1000000]);
    if (has.has('assign')) m.push(['assign', 3, variant === 1 ? -B : -WIDE, variant === 1 ? B : WIDE]);
    return m;
  }
  m.push(['set', 1 * k, -WIDE, WIDE]);
  if (variant === 2) {
    m.push(['add', 6, -100, 100]);
    if (has.has('assign')) m.push(['assign', 1, -WIDE, WIDE]);
    m.push(['chmin', 6, -B, B]);
    if (has.has('chmax')) m.push(['chmax', 6, -B, B]);
    if (has.has('div')) m.push(['div', 1, 2, 6]);
  } else if (variant === 0) {
    m.push(['add', 6, 0, 200]);
    m.push(['chmin', 8, -B, B]);
    if (has.has('chmax')) m.push(['chmax', 4, -DEEP[1], -DEEP[0]]);
  } else {
    m.push(['add', 6 * k, -200, 0]);
    // (no deep band together with division: re-injected large values would make every division expensive)
    if (has.has('chmax')) { m.push(['chmax', 8 * k, -B, B]); if (!has.has('div')) m.push(['chmin', 4, DEEP[0], DEEP[1]]); }
    else m.push(['chmin', 8, -B, B]);
    if (has.has('div')) m.push(['div', 1, 2, 6]);
  }
  if (has.has('hreset')) m.push(['hreset', 1]);
  return m;
}

// ── the tests of each level ───────────────────────────────────────────────────────────────────────────
export function levelTests(level) {
  const R = rng(9100 + level * 37);
  const T = [];
  const add = (label, input) => T.push({ label, input });
  const L = LEVELS[level - 1];
  const explicitRandom = (count, opts) => { for (let i = 0; i < count; i++) add(`random explicit input ${i + 1} (n ${opts.n}, ${opts.q} operations, values within ±${opts.band})`, randomExplicit(R, level, opts)); };
  const generated = (label, n, q, mix, lo = -1000000000, hi = 1000000000) => add(label, { generate: { seed: R.int(1, 2147483646), n, lo, hi, q, mix } });

  switch (level) {
    case 1:
      add('one element', { values: [42], ops: [SUM(0, 0), S(0, -7), SUM(0, 0)] });
      add('negative values and big sums', { values: [1000000000, -1000000000, 999999999, 1000000000], ops: [SUM(0, 3), SUM(0, 0), SUM(2, 3), S(1, 1000000000), SUM(0, 3)] });
      add('the checksum takes negative answers mod 1000000007', { values: [-1, -2, -3], ops: [SUM(0, 0), SUM(0, 2), SUM(1, 1), SUM(2, 2)] });
      add('set the same index repeatedly', { values: [0, 0, 0], ops: [S(1, 5), S(1, 6), S(1, -6), SUM(0, 2), S(0, 6), SUM(0, 1)] });
      add('no queries at all', { values: [1, 2], ops: [S(0, 3)] });
      add('only queries, whole and single ranges', { values: [3, 1, 4, 1, 5, 9, 2, 6], ops: [SUM(0, 7), SUM(3, 3), SUM(7, 7), SUM(0, 0), SUM(2, 5)] });
      explicitRandom(6, { n: 50, q: 80, band: 1000 });
      explicitRandom(2, { n: 2000, q: 2000, band: 1000000000 });
      break;
    case 2:
      add('min and max of one element', { values: [5], ops: [MIN(0, 0), MAX(0, 0), S(0, -5), MIN(0, 0), MAX(0, 0)] });
      add('ties and negative extremes', { values: [-3, -3, -9, -9, 0], ops: [MIN(0, 4), MAX(0, 3), MIN(0, 1), MAX(2, 3), S(4, -9), MAX(2, 4)] });
      add('set changes the extremes', { values: [1, 2, 3, 4], ops: [MAX(0, 3), S(3, 0), MAX(0, 3), MIN(0, 3), S(0, 5), MIN(0, 3), SUM(0, 3)] });
      explicitRandom(6, { n: 60, q: 100, band: 30 });
      explicitRandom(2, { n: 2000, q: 2000, band: 1000000000 });
      generated('small generated input', 40, 200, largeMix(level));
      break;
    case 3:
      add('add then every query', { values: [1, 1, 1, 1], ops: [ADD(1, 2, 5), SUM(0, 3), MIN(0, 3), MAX(0, 3), ADD(0, 3, -6), MAX(0, 3), MIN(1, 2)] });
      add('adds that cancel', { values: [0, 0, 0], ops: [ADD(0, 2, 7), ADD(0, 2, -7), SUM(0, 2), MAX(0, 2), ADD(1, 1, 3), ADD(0, 1, -3), MIN(0, 2), SUM(0, 2)] });
      add('set after add (the add does not stay attached)', { values: [5, 5, 5], ops: [ADD(0, 2, 10), S(1, 0), ADD(0, 2, 1), SUM(0, 2), MIN(0, 2), MAX(1, 1)] });
      add('overlapping adds', { values: [0, 0, 0, 0, 0, 0], ops: [ADD(0, 3, 2), ADD(2, 5, 3), ADD(1, 4, -1), SUM(0, 5), MIN(0, 5), MAX(0, 5), SUM(2, 3)] });
      explicitRandom(6, { n: 80, q: 120, band: 30 });
      explicitRandom(2, { n: 2000, q: 2000, band: 1000000 });
      generated('small generated input', 50, 300, largeMix(level));
      break;
    case 4:
      add('assign then add then assign', { values: [1, 2, 3, 4], ops: [ASG(0, 3, 5), ADD(1, 2, 2), SUM(0, 3), ASG(2, 3, -1), SUM(0, 3), MIN(0, 3), MAX(0, 3)] });
      add('add then assign over part of the range', { values: [0, 0, 0, 0, 0], ops: [ADD(0, 4, 3), ASG(1, 3, 1), ADD(2, 4, 2), SUM(0, 4), MAX(0, 4), MIN(0, 4)] });
      add('assign one element, set over an assign', { values: [9, 9, 9], ops: [ASG(1, 1, 0), S(1, 4), ASG(0, 2, 2), S(0, 7), SUM(0, 2), MAX(0, 2)] });
      add('nested assigns', { values: [1, 2, 3, 4, 5, 6], ops: [ASG(0, 5, 1), ASG(1, 4, 2), ASG(2, 3, 3), ADD(0, 5, 1), SUM(0, 5), MIN(1, 4), MAX(0, 1)] });
      explicitRandom(6, { n: 80, q: 120, band: 10 });
      explicitRandom(2, { n: 2000, q: 2000, band: 1000 });
      generated('small generated input', 60, 300, largeMix(level));
      break;
    case 5:
      add('chmin above every value changes nothing', { values: [1, 5, 3], ops: [CMN(0, 2, 5), SUM(0, 2), CMN(0, 2, 100), MAX(0, 2)] });
      add('chmin makes ties, then add moves them together', { values: [9, 8, 7, 1], ops: [CMN(0, 3, 5), SUM(0, 3), ADD(0, 3, 2), CMN(0, 3, 6), SUM(0, 3), MAX(0, 3), MIN(0, 3)] });
      add('chmin then assign then chmin', { values: [4, 8, 2, 6], ops: [CMN(0, 3, 5), ASG(1, 2, 9), CMN(0, 3, 7), SUM(0, 3), MAX(0, 3)] });
      add('chmin with negative values and partial ranges', { values: [-1, -5, 3, -2, 0], ops: [CMN(1, 3, -3), SUM(0, 4), CMN(0, 4, -4), SUM(0, 4), MIN(0, 4), MAX(2, 4)] });
      add('repeated clipping of the same elements', { values: [10, 10, 10, 1, 1], ops: [CMN(0, 4, 8), ADD(0, 4, 1), CMN(0, 4, 8), ADD(0, 2, 1), CMN(0, 4, 8), SUM(0, 4), MAX(0, 4)] });
      explicitRandom(6, { n: 100, q: 150, band: 5 });
      explicitRandom(2, { n: 2000, q: 2000, band: 50 });
      generated('small generated input', 60, 400, largeMix(level), -50, 50);
      break;
    case 6:
      add('chmax below every value changes nothing', { values: [1, 5, 3], ops: [CMX(0, 2, 1), SUM(0, 2), CMX(0, 2, -100), MIN(0, 2)] });
      add('chmax and chmin squeeze to one value', { values: [1, 9, 5, 3], ops: [CMX(0, 3, 4), CMN(0, 3, 4), SUM(0, 3), MIN(0, 3), MAX(0, 3), ADD(1, 2, 1), SUM(0, 3)] });
      add('chmax on two-valued ranges', { values: [1, 5, 1, 5], ops: [CMX(0, 3, 3), SUM(0, 3), CMN(0, 3, 4), SUM(0, 3), CMX(0, 1, 5), SUM(0, 3), MIN(0, 3)] });
      add('chmax after assign and add', { values: [0, 0, 0, 0], ops: [ASG(0, 1, -5), ADD(1, 3, 2), CMX(0, 3, 0), SUM(0, 3), MIN(0, 3), MAX(0, 3)] });
      explicitRandom(6, { n: 100, q: 150, band: 5 });
      explicitRandom(2, { n: 2000, q: 2000, band: 50 });
      generated('small generated input', 60, 400, largeMix(level), -50, 50);
      break;
    case 7:
      add('floor rounds toward negative infinity', { values: [7, -7, -1, 1, 0, -8], ops: [DIV(0, 5, 2), SUM(0, 5), MIN(0, 5), MAX(0, 5), DIV(0, 5, 3), SUM(0, 5)] });
      add('div until everything is 0 or -1', { values: [100, -100, 37, -37], ops: [DIV(0, 3, 3), DIV(0, 3, 3), DIV(0, 3, 3), DIV(0, 3, 3), DIV(0, 3, 3), SUM(0, 3), MIN(0, 3), MAX(0, 3)] });
      add('div after add and chmin', { values: [10, 20, 30], ops: [ADD(0, 2, 1), CMN(0, 2, 25), DIV(0, 2, 4), SUM(0, 2), CMX(0, 2, 5), DIV(1, 2, 2), SUM(0, 2)] });
      add('div of equal values and of a single element', { values: [9, 9, 9, 2], ops: [DIV(0, 2, 2), SUM(0, 3), DIV(3, 3, 5), SUM(0, 3), ASG(0, 3, -9), DIV(1, 2, 4), SUM(0, 3)] });
      explicitRandom(6, { n: 100, q: 150, band: 30 });
      explicitRandom(2, { n: 2000, q: 2000, band: 1000 });
      generated('small generated input', 60, 400, largeMix(level), -200, 200);
      break;
    case 8:
      add('the initial value counts as history', { values: [5, -3], ops: [ADD(0, 1, -10), HMX(0, 0), HMX(1, 1), HMX(0, 1)] });
      add('history through assign, chmin and add', { values: [1, 2, 3], ops: [ASG(0, 2, 10), CMN(0, 2, 4), ADD(0, 2, 1), HMX(0, 2), HMX(1, 1), SUM(0, 2)] });
      add('a peak inside a sequence of adds', { values: [0, 0], ops: [ADD(0, 1, 5), ADD(0, 1, -7), ADD(0, 0, 4), HMX(0, 0), HMX(1, 1), MAX(0, 1)] });
      add('history after div and chmax', { values: [-9, 9], ops: [DIV(0, 1, 2), CMX(0, 1, 6), HMX(0, 0), HMX(1, 1), DIV(0, 1, 3), HMX(0, 1)] });
      explicitRandom(6, { n: 100, q: 150, band: 5 });
      explicitRandom(2, { n: 2000, q: 2000, band: 100 });
      generated('small generated input', 60, 400, largeMix(level), -50, 50);
      break;
    case 9:
      add('historic min of the initial values', { values: [5, -3], ops: [ADD(0, 1, 10), HMN(0, 0), HMN(1, 1), HMN(0, 1)] });
      add('a dip inside a sequence of updates', { values: [4, 4], ops: [ADD(0, 1, -6), ASG(0, 1, 3), CMX(0, 1, 5), HMN(0, 1), HMN(1, 1), HMX(0, 1)] });
      add('historic min through div of negatives', { values: [-7, 7], ops: [DIV(0, 1, 2), DIV(0, 1, 2), HMN(0, 0), HMN(1, 1), ADD(0, 1, -1), HMN(0, 1)] });
      explicitRandom(6, { n: 100, q: 150, band: 5 });
      explicitRandom(2, { n: 2000, q: 2000, band: 100 });
      generated('small generated input', 60, 400, largeMix(level), -50, 50);
      break;
    case 10:
      add('hreset forgets the past peak and dip', { values: [0, 0], ops: [ADD(0, 1, 5), ADD(0, 1, -10), HR(0, 1), HMX(0, 1), HMN(0, 1), ADD(0, 0, 2), HMX(0, 1), HMN(0, 1)] });
      add('hreset on part of a range', { values: [1, 2, 3, 4], ops: [ADD(0, 3, 10), ADD(0, 3, -10), HR(1, 2), HMX(0, 3), HMX(1, 2), HMN(1, 2), HMN(0, 0)] });
      add('updates before and after hreset', { values: [5, 5, 5], ops: [CMN(0, 2, 1), HR(0, 1), ASG(1, 2, 3), HMX(0, 2), HMX(0, 1), HMN(0, 1), CMX(0, 2, 9), HMX(0, 0)] });
      add('hreset twice', { values: [0, 0, 0], ops: [ADD(0, 2, 4), HR(0, 2), ADD(0, 2, -4), HR(1, 1), ADD(0, 2, 1), HMX(0, 2), HMN(0, 2), HMN(1, 1)] });
      explicitRandom(6, { n: 100, q: 150, band: 5 });
      explicitRandom(2, { n: 2000, q: 2000, band: 100 });
      generated('small generated input', 60, 400, largeMix(level), -50, 50);
      break;
  }
  if (level >= 2) {
    generated(`medium generated input (n 3,000, 3,000 operations)`, 3000, 3000, largeMix(level));
    const size = `n ${L.maxN.toLocaleString('en-GB')}, ${L.maxQ.toLocaleString('en-GB')} operations`;
    const W0 = 1000000000;
    const [n0, n1] = level <= 4 ? ['wide random values', 'values in a narrow band'] : ['clipped from above, drifting up', 'clipped from below, drifting down'];
    generated(`large generated input, ${n0} (${size})`, L.maxN, L.maxQ, largeMix(level, 0), -W0, W0);
    // with division in the mix (level 7 on) the values start within ±10^6, still far outside the band: division then
    // needs fewer passes to bring them down
    const W1 = level >= 7 ? 1000000 : W0;
    generated(`large generated input, ${n1} (${size})`, L.maxN, L.maxQ, largeMix(level, 1), -W1, W1);
    generated(`large generated input, every operation mixed, values starting near 0 (${size})`, L.maxN, L.maxQ, largeMix(level, 2), -1000, 1000);
  }
  return T;
}
