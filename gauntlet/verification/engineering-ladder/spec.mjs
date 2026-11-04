// The specification of Engineering: Ladder, assembled level by level. Level L's prompt is the introduction, the
// operation sections of levels 1..L (in order), the input-generator section (from level 2), the limits, examples and
// the trailer. Each level only ADDS operations; nothing a later section says changes an earlier operation.

export const FN = 'runEngine';

/** Per level: the operations it adds, the largest n and number of operations, the time limit per hidden test (ms). */
export const LEVELS = [
  { level: 1, adds: ['set', 'sum'], maxN: 2000, maxQ: 2000, timeoutMs: 2000 },
  { level: 2, adds: ['min', 'max'], maxN: 1000000, maxQ: 40000, timeoutMs: 4000 },
  { level: 3, adds: ['add'], maxN: 1000000, maxQ: 40000, timeoutMs: 7000 },
  { level: 4, adds: ['assign'], maxN: 1000000, maxQ: 40000, timeoutMs: 11000 },
  { level: 5, adds: ['chmin'], maxN: 1000000, maxQ: 40000, timeoutMs: 11000 },
  { level: 6, adds: ['chmax'], maxN: 1000000, maxQ: 40000, timeoutMs: 12000 },
  { level: 7, adds: ['div'], maxN: 1000000, maxQ: 40000, timeoutMs: 18000 },
  { level: 8, adds: ['hmax'], maxN: 1000000, maxQ: 40000, timeoutMs: 27000 },
  { level: 9, adds: ['hmin'], maxN: 1000000, maxQ: 40000, timeoutMs: 27000 },
  { level: 10, adds: ['hreset'], maxN: 1000000, maxQ: 40000, timeoutMs: 27000 },
];

export const opsUpTo = (level) => LEVELS.filter((l) => l.level <= level).flatMap((l) => l.adds);
export const QUERIES = new Set(['sum', 'min', 'max', 'hmax', 'hmin']);

const INTRO = `Write a JavaScript function \`runEngine(input)\` that keeps an array of integers a[0], a[1], ..., a[n - 1], applies a list of operations to it in order, and answers the queries among them.

**Input.** INPUT_FORMS

Indices are 0-based. In every operation with \`l\` and \`r\`, 0 <= l <= r <= n - 1 and the operation applies to every index i with l <= i <= r. All numbers in the input are integers.

**Output.** Return an array \`[count, checksum]\`: \`count\` is the number of queries, and \`checksum\` combines their answers in order: start with c = 0 and, for each answer v, set c = (c * 1000003 + m) mod 1000000007, where m = v mod 1000000007 taken in the range 0 to 1000000006 (for a negative v, m = ((v % 1000000007) + 1000000007) % 1000000007). With no queries, return \`[0, 0]\`. For example, answers 5, -1 and 12 give c = 5, then (5 * 1000003 + 1000000006) mod 1000000007 = 5000014, then (5000014 * 1000003 + 12) mod 1000000007 = 28965054, so the result is \`[3, 28965054]\`. (All intermediate products stay below 2^53, so ordinary JavaScript numbers compute this exactly.)`;

const SECTIONS = {
  1: `**Level 1**
- \`["set", i, x]\`: a[i] = x.
- \`["sum", l, r]\` (query): the answer is a[l] + a[l + 1] + ... + a[r].`,
  2: `**Level 2**
- \`["min", l, r]\` (query): the smallest of a[l..r].
- \`["max", l, r]\` (query): the largest of a[l..r].`,
  3: `**Level 3**
- \`["add", l, r, x]\`: a[i] = a[i] + x for every i in l..r.`,
  4: `**Level 4**
- \`["assign", l, r, x]\`: a[i] = x for every i in l..r.`,
  5: `**Level 5**
- \`["chmin", l, r, x]\`: a[i] = min(a[i], x) for every i in l..r.`,
  6: `**Level 6**
- \`["chmax", l, r, x]\`: a[i] = max(a[i], x) for every i in l..r.`,
  7: `**Level 7**
- \`["div", l, r, x]\` (x >= 2): a[i] = floor(a[i] / x) for every i in l..r, rounding toward negative infinity: floor(7 / 2) = 3, floor(-7 / 2) = -4, floor(-1 / 3) = -1.`,
  8: `**Level 8**
- Every index has a **historic maximum** H[i]: the largest value a[i] has ever had, counting its initial value and its value after every operation so far. (An operation that leaves a[i] unchanged changes nothing.)
- \`["hmax", l, r]\` (query): the largest of H[l..r].`,
  9: `**Level 9**
- Every index also has a **historic minimum** G[i]: the smallest value a[i] has ever had, in the same sense.
- \`["hmin", l, r]\` (query): the smallest of G[l..r].`,
  10: `**Level 10**
- \`["hreset", l, r]\`: H[i] = a[i] and G[i] = a[i] for every i in l..r (their histories start again from the current value; a[i] does not change). Later operations update H and G as usual.`,
};

const GENERATOR = `**Generated inputs.** \`input.generate\` is \`{ "seed": s, "n": n, "lo": lo, "hi": hi, "q": q, "mix": [entry, entry, ...] }\`. The generator keeps one integer \`state\`, starting at \`seed\` (1 <= seed <= 2147483646). \`rand(k)\` first sets state = (state * 48271) mod 2147483647 and then returns state mod k (a number from 0 to k - 1). The steps, in exactly this order:
1. For i = 0, 1, ..., n - 1: a[i] = lo + rand(hi - lo + 1).
2. Each mix entry is \`[name, weight]\` (for a query or hreset) or \`[name, weight, xlo, xhi]\` (for an operation that takes a value x). Let W be the sum of all weights. Then q times: let w = rand(W); go through the entries in order, and pick the first entry whose weight is greater than w, subtracting each skipped entry's weight from w. For the picked entry:
   - \`set\`: i = rand(n), then x = xlo + rand(xhi - xlo + 1); the operation is \`["set", i, x]\`.
   - anything else: u = rand(n), then t = rand(n); l = min(u, t) and r = max(u, t); then, only if the entry has xlo and xhi, x = xlo + rand(xhi - xlo + 1). The operation is \`[name, l, r]\` or \`[name, l, r, x]\`.
   Each operation is applied (or answered) before the next one is generated.

Example: \`GEN_INPUT\` produces the array GEN_VALUES and then the operations GEN_OPS (the answers and result are in the examples below).`;

const TRAILER = (sec) =>
  'Respond with a single ```javascript code block containing the complete function (plain JavaScript, no imports, no TypeScript). ' +
  'The function must have exactly the name and parameters given above; you may define helper functions inside the same code block. ' +
  'Do not read input or print output: the function is called directly with the argument and its return value is checked against hidden tests, ' +
  `including large inputs with a time limit of about ${sec} seconds per test. Give exactly one solution: if your answer contains several code blocks, only the last one is run.`;

function limits(L) {
  const fmt = (x) => x.toLocaleString('en-GB');
  return `**Size of the tests.** n is at most ${fmt(L.maxN)} and there are at most ${fmt(L.maxQ)} operations. Initial values are between -10^9 and 10^9, and the tests are chosen so that every value, and every answer, stays between -2^50 and 2^50 (safe JavaScript integers). ${L.level === 1 ? 'Simple loops are fast enough at this size.' : 'The hidden tests include the largest sizes, so each operation must take time that grows much more slowly than n (a loop over the whole range is far too slow), and the tests deliberately include long runs of operations that undo the shortcuts of simpler designs. Recursion depth is not a problem (about 20 levels for a balanced tree over the array). Memory: ordinary JavaScript objects and arrays are limited to about 256 MB in all, which a tree of millions of objects exceeds; typed arrays (Float64Array, Int32Array, ...) do not count towards that limit.'}`;
}

/** examples: [args, expected, answers] (the individual query answers are shown in the comment). */
export function fmtExamples(examples) {
  return '```javascript\n' + examples.map(([args, exp, answers]) => `${FN}(${args.map((a) => JSON.stringify(a)).join(', ')})\n// returns ${JSON.stringify(exp)}${answers ? `  (the query answers are ${answers.join(', ')})` : ''}`).join('\n') + '\n```';
}

/** The full prompt of one level. genExample = { values, ops } expanded from the generator example. */
export function prompt(level, examples, genExample) {
  const L = LEVELS[level - 1];
  const sections = [];
  for (let l = 1; l <= level; l++) sections.push(SECTIONS[l]);
  const forms = level === 1
    ? '`input` is `{ "values": [v0, v1, ...], "ops": [op, op, ...] }`: the initial array (n = the number of values, n >= 1) and the operations, each an array such as `["sum", 2, 5]`.'
    : '`input` is an object of one of two forms:\n- `{ "values": [v0, v1, ...], "ops": [op, op, ...] }`: the initial array (n = the number of values, n >= 1) and the operations, each an array such as `["sum", 2, 5]`.\n- `{ "generate": { ... } }`: the array and the operations are produced by the generator described below (so that a test of a million elements stays small).';
  return [
    INTRO.replace('INPUT_FORMS', forms),
    `The operations, level by level (this task is level ${level}; ${level === 1 ? 'only level 1 applies' : `levels 1 to ${level} all apply`}):`,
    ...sections,
    ...(level >= 2 ? [GENERATOR.replace('GEN_INPUT', JSON.stringify({ generate: genExample.input })).replace('GEN_VALUES', JSON.stringify(genExample.values)).replace('GEN_OPS', JSON.stringify(genExample.ops))] : []),
    limits(L),
    'Examples:\n' + fmtExamples(examples),
    TRAILER(L.timeoutMs / 1000),
  ].join('\n\n');
}
